# Copyright 2026 The Buildish Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Provider fixtures and the independently published documentation trigger."""

import importlib.util
import io
import json
import os
import subprocess
import urllib.error
import urllib.request
import zipfile
from argparse import Namespace
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "registry_fixture", ROOT / "tests/rehearsal/registry_fixture.py"
)
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


def test_central_upload_readback_partial_conflict_and_outage(tmp_path, monkeypatch):
    monkeypatch.setenv(fixture.ROOT_ENV, str(tmp_path))
    content = io.BytesIO()
    with zipfile.ZipFile(content, "w") as archive:
        archive.writestr("org/example/1.2.3/plugin.jar", b"approved JAR")
    boundary = "fixture-boundary"
    body = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="bundle.zip"\r\nContent-Type: application/zip\r\n\r\n'.encode()
        + content.getvalue()
        + f"\r\n--{boundary}--\r\n".encode()
    )
    request = urllib.request.Request(
        "https://central.sonatype.com/api/v1/publisher/upload?publishingType=AUTOMATIC",
        data=body,
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Authorization": "Bearer fixture-only",
        },
    )
    assert fixture.urlopen(request).read() == b"fixture-central-deployment"
    url = "https://repo.maven.apache.org/maven2/org/example/1.2.3/plugin.jar"
    assert fixture.urlopen(url).read() == b"approved JAR"
    policy = tmp_path / "policy.json"
    monkeypatch.setenv("BUILDISH_HARNESS_JOB_ID", "inspect-central")
    policy.write_text(json.dumps({"partial_preflight": "central"}))
    with pytest.raises(urllib.error.HTTPError) as error:
        fixture.urlopen(url)
    assert error.value.code == 404
    monkeypatch.setenv("BUILDISH_HARNESS_JOB_ID", "verify-central")
    assert fixture.urlopen(url).read() == b"approved JAR"
    policy.write_text(json.dumps({"conflict": "central"}))
    assert fixture.urlopen(url).read() != b"approved JAR"
    policy.write_text(json.dumps({"fail_verification": "central"}))
    with pytest.raises(urllib.error.HTTPError) as error:
        fixture.urlopen(url)
    assert error.value.code == 503


def test_docs_branch_trigger_uses_only_the_aggregate_token(tmp_path):
    template = ROOT / "tools/versioned-docs/publish-site.yml"
    workflow = yaml.safe_load(template.read_text())
    trigger = workflow.get("on", workflow.get(True))
    assert trigger == {"push": {"branches": ["versioned-docs"]}}
    assert workflow["permissions"] == {}
    job = workflow["jobs"]["publish-site"]
    assert (
        "github.repository == 'buildish-tooling/buildish-atr-maven-bundle'" in job["if"]
    )
    assert "github.event.deleted == false" in job["if"]
    step = job["steps"][0]
    assert step["env"] == {"GH_TOKEN": "${{ secrets.BUILDISH_SITE_PUBLISH_TOKEN }}"}
    fake_gh = tmp_path / "gh"
    fake_gh.write_text('#!/bin/sh\nprintf "%s\\n" "$*" > "$CAPTURE"\n')
    fake_gh.chmod(0o755)
    capture = tmp_path / "arguments"
    env = {
        **os.environ,
        "PATH": str(tmp_path) + os.pathsep + os.environ["PATH"],
        "CAPTURE": str(capture),
        "GH_TOKEN": "fixture-only",
    }
    subprocess.run(["bash", "-e", "-c", step["run"]], env=env, check=True)
    assert (
        capture.read_text().strip()
        == "workflow run site-publish.yml --repo buildish-tooling/buildish --ref main"
    )
    capture.unlink()
    env["GH_TOKEN"] = ""
    result = subprocess.run(
        ["bash", "-e", "-c", step["run"]], env=env, capture_output=True, check=False
    )
    assert result.returncode != 0
    assert not capture.exists()


def test_rehearsal_keeps_production_job_graph_and_installed_package_path(tmp_path):
    driver_spec = importlib.util.spec_from_file_location(
        "rehearsal_driver", ROOT / "tests/rehearse_release_workflows.py"
    )
    driver = importlib.util.module_from_spec(driver_spec)
    driver_spec.loader.exec_module(driver)
    output, frozen = driver.prepare(
        Namespace(
            output=tmp_path / "run",
            java17=tmp_path / "jdk17",
            java21=tmp_path / "jdk21",
            gradle_cache=tmp_path / "gradle",
        )
    )
    for name in ("candidate", "promote"):
        path = Path(f".github/workflows/release-{name}.yml")
        production = yaml.safe_load((ROOT / path).read_text())
        rehearsal = yaml.safe_load((frozen / path).read_text())
        assert production["jobs"].keys() == rehearsal["jobs"].keys()
        for key, job in production["jobs"].items():
            for field in ("needs", "if", "permissions", "environment", "outputs"):
                assert job.get(field) == rehearsal["jobs"][key].get(field)
    installer = yaml.safe_load(
        (frozen / ".github/actions/setup-release-tooling/action.yml").read_text()
    )["runs"]["steps"][0]["run"]
    assert "pip --isolated install --only-binary=:all:" in installer
    assert "requirements.txt" in installer
    assert (frozen / "buildish-release-tooling/run.py").read_bytes() == (
        ROOT / "buildish-release-tooling/run.py"
    ).read_bytes()
    assert not (frozen / "docs/releases").exists()
    assert not (frozen / "src/buildish_release_tooling").exists()
    assert (frozen / "version.txt").read_text() == driver.VERSION + "-SNAPSHOT\n"
    assert json.loads((output / "registries/policy.json").read_text()) == {}
    scenario = driver.scenario(output, frozen, "candidate")
    expected = ["resolve", "build", "sign", "publish-candidate"]
    driver.assert_selected_jobs({"selected_job_ids": expected}, scenario)
    for selected in ([], expected[:-1]):
        with pytest.raises(AssertionError):
            driver.assert_selected_jobs({"selected_job_ids": selected}, scenario)


def test_portal_fixture_requires_real_publisher_outputs(tmp_path, monkeypatch):
    monkeypatch.setenv(fixture.ROOT_ENV, str(tmp_path))
    central = tmp_path / "central"
    publisher = tmp_path / "publisher"
    for index in range(6):
        relative = Path(f"org/example/1.2.3/artifact-{index}.pom")
        path = central / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(f"retained {index}".encode())
        target = publisher / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(path.read_bytes())
    missing = publisher / relative
    missing.unlink()
    with pytest.raises(ValueError, match="publisher output"):
        fixture.publish_portal(publisher, "1.2.3")
    assert not (tmp_path / "portal-registration").exists()
    missing.write_bytes(path.read_bytes())
    fixture.publish_portal(publisher, "1.2.3")
    assert (tmp_path / "portal-registration").read_text() == "1.2.3"
    assert (tmp_path / "portal" / relative).read_bytes() == path.read_bytes()
