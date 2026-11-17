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

"""Release handoff, registry readback and workflow status-policy regressions."""

import hashlib
import io
import json
import os
import subprocess
import sys
import urllib.error
import zipfile
from pathlib import Path
from unittest.mock import patch

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "buildish-release-tooling"))
import registries as r
import workflow as w


@pytest.fixture
def repository(tmp_path):
    repo = tmp_path / "repository"
    for name in w.primary_paths("1.2.3"):
        path = repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(name.encode())
        for suffix in (".asc", ".sha256", ".sha512"):
            Path(str(path) + suffix).write_bytes((name + suffix).encode())
    return repo


def response(data):
    return io.BytesIO(data)


def fetcher(repository, missing=(), corrupt=(), transient=()):
    def fetch(url, timeout):
        if "/plugin/" in url:
            name = "portal-page"
        else:
            name = (
                url.split("/m2/", 1)[-1]
                if "/m2/" in url
                else url.split("/maven2/", 1)[-1]
            )
        if name in missing:
            raise urllib.error.HTTPError(url, 404, "missing", {}, None)
        if name in transient:
            raise urllib.error.HTTPError(url, 503, "busy", {}, None)
        result = response(
            b"different"
            if name in corrupt
            else b"registered"
            if name == "portal-page"
            else (repository / name).read_bytes()
        )
        result.geturl = lambda: url
        return result

    return fetch


def test_registry_absent_partial_complete_and_conflict(repository):
    names = sorted(r.expected_files(repository, "1.2.3", "central"))
    assert (
        r.inspect(repository, "1.2.3", "central", fetcher(repository, missing=names))
        == "absent"
    )
    assert (
        r.inspect(
            repository, "1.2.3", "central", fetcher(repository, missing=names[:1])
        )
        == "partial"
    )
    assert r.inspect(repository, "1.2.3", "central", fetcher(repository)) == "complete"
    with pytest.raises(ValueError, match="Conflicting"):
        r.inspect(
            repository, "1.2.3", "central", fetcher(repository, corrupt=names[:1])
        )
    with pytest.raises(ConnectionError):
        r.inspect(
            repository, "1.2.3", "central", fetcher(repository, transient=names[:1])
        )
    # Central's fallback copies cannot prove Plugin Portal registration.
    assert (
        r.inspect(
            repository, "1.2.3", "portal", fetcher(repository, missing=["portal-page"])
        )
        == "absent"
    )


def test_poll_missing_transient_but_never_conflict(repository, tmp_path):
    result = tmp_path / "verified.json"
    with (
        patch.object(
            r, "inspect", side_effect=["partial", ConnectionError(), "complete"]
        ) as inspect,
        patch.object(r.time, "sleep"),
    ):
        r.verify(repository, "1.2.3", "central", result, attempts=3, retry_delay=0)
        assert inspect.call_count == 3
    assert json.loads(result.read_text())["files"]
    with patch.object(r, "inspect", side_effect=ValueError("conflict")) as inspect:
        with pytest.raises(ValueError, match="conflict"):
            r.verify(repository, "1.2.3", "central", result)
        assert inspect.call_count == 1
    with (
        patch.object(r, "inspect", return_value="partial"),
        pytest.raises(ValueError, match="budget"),
    ):
        r.verify(repository, "1.2.3", "central", result, attempts=1)


def test_central_upload_retains_receipt_without_retrying_uncertain_failure(
    tmp_path, monkeypatch
):
    archive = tmp_path / "bundle.zip"
    archive.write_bytes(b"exact retained ZIP")
    result = tmp_path / "receipt.json"
    monkeypatch.setenv("CENTRAL_USERNAME", "fixture-user")
    monkeypatch.setenv("CENTRAL_PASSWORD", "fixture-token")

    def upload(request, timeout):
        assert request.full_url.endswith("/publisher/upload?publishingType=AUTOMATIC")
        assert request.get_method() == "POST"
        assert request.get_header("Authorization").startswith("Bearer ")
        assert b'name="bundle"' in request.data
        assert archive.read_bytes() in request.data
        return response(b"fixture-deployment-id")

    with patch.object(r.urllib.request, "urlopen", side_effect=upload) as request:
        r.upload_central(archive, result)
        assert request.call_count == 1
    receipt = json.loads(result.read_text())
    assert receipt == {
        "deployment_id": "fixture-deployment-id",
        "bundle_sha256": w.digest(archive),
    }
    with patch.object(
        r.urllib.request, "urlopen", side_effect=TimeoutError("fixture-token")
    ) as request:
        with pytest.raises(ValueError, match="inspect Portal deployments") as error:
            r.upload_central(archive, result)
        assert "fixture-token" not in str(error.value)
        assert request.call_count == 1


def test_transport_rejects_changed_missing_extra_files(tmp_path, monkeypatch):
    monkeypatch.setenv("GITHUB_OUTPUT", str(tmp_path / "output"))
    bundle = tmp_path / "bundle"
    bundle.mkdir()
    (bundle / "state.json").write_text('{"source":"approved"}')
    w.seal(bundle)
    expected = w.digest(bundle / "transport.json")
    w.verify(bundle, expected)
    (bundle / "extra").write_text("extra")
    with pytest.raises(ValueError):
        w.verify(bundle, expected)
    (bundle / "extra").unlink()
    (bundle / "state.json").write_text("substitution")
    with pytest.raises(ValueError):
        w.verify(bundle, expected)
    (bundle / "state.json").unlink()
    with pytest.raises(ValueError):
        w.verify(bundle, expected)


def test_maven_archive_exact_inventory_and_recovery(repository, tmp_path):
    for name in w.primary_paths("1.2.3"):
        path = repository / name
        Path(str(path) + ".asc").write_bytes(b"test signature")
        Path(str(path) + ".sha256").write_text(w.digest(path))
        Path(str(path) + ".sha512").write_text(
            hashlib.sha512(path.read_bytes()).hexdigest()
        )
    archive = tmp_path / w.archive_name("1.2.3")
    w.pack(repository, archive, "1.2.3")
    w.unpack(archive, tmp_path / "unpacked", "1.2.3")
    with zipfile.ZipFile(archive, "a") as contents:
        contents.writestr("docs/releases/1.0.0/private", "extra")
    with pytest.raises(ValueError, match="inventory"):
        w.unpack(archive, tmp_path / "rejected", "1.2.3")


def test_pinned_console_launcher_and_required_commands(tmp_path):
    launcher = ROOT / "buildish-release-tooling/release-tooling.sh"
    env = {**os.environ, "BUILDISH_RELEASE_TOOLING_PYTHON": sys.executable}
    shadow = tmp_path / "buildish_release_tooling"
    shadow.mkdir()
    (shadow / "__init__.py").write_text(
        "raise RuntimeError('source checkout imported')\n"
    )
    env["PYTHONPATH"] = str(tmp_path)
    info = json.loads(
        subprocess.check_output(["bash", str(launcher), "--package-info"], env=env)
    )
    assert info["version"] == "0.1.4"
    assert "site-packages" in info["distribution"]
    for command in (
        "resolve-candidate",
        "resolve-promotion",
        "prepare-release-assets",
        "create-final-tag",
        "create-release-manifest",
        "attach-github-release-manifest",
        "publish-github-final-release",
    ):
        result = subprocess.run(
            ["bash", str(launcher), command, "--help"],
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )
        assert result.returncode == 0, result.stderr
        assert "--component-config" in result.stdout
    result = subprocess.run(
        ["bash", str(launcher), "resolve-candidate", "--component-conf=x"],
        check=False,
        env=env,
        capture_output=True,
    )
    assert result.returncode != 0


def test_release_job_permissions_source_and_status_policy():
    config = yaml.safe_load(
        (ROOT / "buildish-release-tooling/release-config.yaml").read_text()
    )
    assert config["source"]["require_default_branch_ancestor"] is True
    assert config["source"]["checks"] == {
        "platform": "github",
        "trusted_workflow_path": ".github/workflows/ci.yml",
        "trusted_event": "push",
        "trusted_branch": "main",
        "required": ["Required Checks"],
    }
    candidate, promotion = [
        yaml.safe_load((ROOT / f".github/workflows/release-{name}.yml").read_text())
        for name in ("candidate", "promote")
    ]
    for workflow in (candidate, promotion):
        assert workflow["permissions"] == {"contents": "read"}
        assert workflow["concurrency"]["cancel-in-progress"] is False
        for name, job in workflow["jobs"].items():
            assert job["runs-on"] == "ubuntu-26.04"  # Explicit Linux rehearsal mapping.
            for step in job["steps"]:
                if step.get("uses", "").startswith("actions/checkout@"):
                    assert step["with"]["persist-credentials"] is False
                    expected = (
                        "${{ needs.resolve.outputs.source_commit }}"
                        if step["with"].get("path") == "source"
                        else "${{ github.workflow_sha }}"
                    )
                    assert step["with"]["ref"] == expected
            if job.get("permissions", {}).get("contents") == "write":
                assert job["environment"] == "release-candidate"
    assert "secrets" not in json.dumps(candidate["jobs"]["build"])
    resolve_script = "\n".join(
        step.get("run", "") for step in candidate["jobs"]["resolve"]["steps"]
    )
    assert 'MANIFEST_PATH="$RUNNER_TEMP/source-checks.json"' in resolve_script
    for target in ("central", "portal"):
        assert (
            promotion["jobs"]["publish-" + target]["environment"] == "release-candidate"
        )
        assert "!cancelled()" in promotion["jobs"]["inspect-" + target]["if"]
        assert "!cancelled()" in promotion["jobs"]["publish-" + target]["if"]
        verify = promotion["jobs"]["verify-" + target]
        assert "!cancelled()" in verify["if"]
        assert "'skipped'" in verify["if"]
        assert "'success'" in verify["if"]
    final = promotion["jobs"]["publish"]
    for name in ("resolve", "stage", "verify-central", "verify-portal"):
        assert f"needs.{name}.result == 'success'" in final["if"]
    commands = "\n".join(step.get("run", "") for step in final["steps"])
    assert commands.index("fetch-tag") < commands.index("create-release-manifest")
    assert commands.index("registries.py evidence") < commands.index(
        "stage-github-final-release"
    )
    assert commands.index("stage-github-final-release") < commands.index(
        "create-release-manifest"
    )
    early = "\n".join(
        step.get("run", "") for step in promotion["jobs"]["stage"]["steps"]
    )
    assert "create-final-tag" in early
    assert "stage-github-final-release" not in early
    assert commands.index("attach-github-release-manifest") < commands.index(
        "publish-github-final-release"
    )
    assert "--require-manifest-digest" in commands
    assert commands.index("publish-github-final-release") < commands.index(
        "verify-github-final-release"
    )


@pytest.mark.parametrize(
    ("source_version", "accepted"),
    [
        ("0.1.0", True),
        ("0.1.0-SNAPSHOT", True),
        ("0.2.0", False),
        ("0.2.0-SNAPSHOT", False),
    ],
)
def test_candidate_sets_build_version_without_changing_reviewed_source(
    tmp_path, source_version, accepted
):
    candidate = yaml.safe_load(
        (ROOT / ".github/workflows/release-candidate.yml").read_text()
    )
    scripts = {
        job: "\n".join(step.get("run", "") for step in candidate["jobs"][job]["steps"])
        for job in ("resolve", "build")
    }
    resolve = scripts["resolve"]
    guard = resolve[
        resolve.index("source_version=") : resolve.index(
            'MANIFEST_PATH="$RUNNER_TEMP/source-checks.json"'
        )
    ]
    version_step = next(
        line for line in scripts["build"].splitlines() if "> source/version.txt" in line
    )
    assert scripts["build"].index("workflow.py digest") < scripts["build"].index(
        version_step
    )
    assert scripts["build"].index(version_step) < scripts["build"].index("./gradlew")

    git = [
        "git",
        "-C",
        str(tmp_path),
        "-c",
        "user.name=Fixture",
        "-c",
        "user.email=fixture@example.invalid",
        "-c",
        "commit.gpgsign=false",
        "-c",
        "core.hooksPath=/dev/null",
    ]
    subprocess.check_output([*git, "init", "-b", "main"])
    original = source_version + "\n"
    (tmp_path / "version.txt").write_text(original)
    subprocess.check_output([*git, "add", "version.txt"])
    subprocess.check_output([*git, "commit", "-m", "Reviewed source"])
    commit = subprocess.check_output([*git, "rev-parse", "HEAD"], text=True).strip()
    selected = tmp_path / "source/version.txt"
    selected.parent.mkdir()
    selected.write_text(original)
    env = {**os.environ, "source": commit, "RELEASE_VERSION": "0.1.0"}
    result = subprocess.run(
        ["bash", "-euo", "pipefail", "-c", guard],
        cwd=tmp_path,
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )
    assert (result.returncode == 0) is accepted
    if accepted:
        subprocess.run(
            ["bash", "-euo", "pipefail", "-c", version_step],
            cwd=tmp_path,
            env=env,
            check=True,
        )
        assert selected.read_text() == "0.1.0\n"
    else:
        assert "does not match release" in result.stderr
        assert selected.read_text() == original
    assert (tmp_path / "version.txt").read_text() == original
    assert (
        subprocess.check_output([*git, "show", "HEAD:version.txt"], text=True)
        == original
    )
    assert (
        subprocess.check_output([*git, "rev-parse", "HEAD"], text=True).strip()
        == commit
    )


def test_fetch_exact_older_final_tag_keeps_newer_workflow_checkout(tmp_path):
    def git(path, *args):
        return subprocess.check_output(
            [
                "git",
                "-C",
                str(path),
                "-c",
                "user.name=Fixture",
                "-c",
                "user.email=fixture@example.invalid",
                "-c",
                "commit.gpgsign=false",
                "-c",
                "core.hooksPath=/dev/null",
                *args,
            ],
            text=True,
        ).strip()

    origin = tmp_path / "origin"
    origin.mkdir()
    git(origin, "init", "-b", "main")
    (origin / "file").write_text("approved old source")
    git(origin, "add", ".")
    git(origin, "commit", "-m", "approved source")
    approved = git(origin, "rev-parse", "HEAD")
    git(origin, "tag", "v1.2.3")
    (origin / "file").write_text("new trusted workflow")
    git(origin, "commit", "-am", "workflow fix")
    trusted = git(origin, "rev-parse", "HEAD")
    checkout = tmp_path / "shallow"
    subprocess.run(
        ["git", "clone", "--depth=1", "--no-tags", origin.as_uri(), str(checkout)],
        check=True,
    )
    assert not git(checkout, "tag", "--list")
    state = tmp_path / "state.json"
    w.write(
        state, {"final_tag": {"name": "v1.2.3"}, "source": {"commit_sha": approved}}
    )
    subprocess.run(
        [
            sys.executable,
            str(ROOT / "buildish-release-tooling/workflow.py"),
            "fetch-tag",
            str(state),
            w.digest(state),
        ],
        cwd=checkout,
        check=True,
    )
    assert git(checkout, "rev-parse", "HEAD") == trusted
    assert git(checkout, "rev-parse", "v1.2.3^{commit}") == approved


def test_final_job_conditions_reject_cancelled_failed_and_skipped_verifiers():
    promotion = yaml.safe_load(
        (ROOT / ".github/workflows/release-promote.yml").read_text()
    )

    def allowed(job, statuses, cancelled=False):
        expression = job["if"].removeprefix("${{").removesuffix("}}")
        expression = expression.replace("!cancelled()", repr(not cancelled))
        for name, status in statuses.items():
            expression = expression.replace(f"needs.{name}.result", repr(status))
        return eval(
            expression.replace("&&", " and ").replace("||", " or "),
            {"__builtins__": {}},
        )

    final = promotion["jobs"]["publish"]
    successes = {name: "success" for name in final["needs"]}
    assert allowed(final, successes)
    assert not allowed(final, successes, cancelled=True)
    for name in successes:
        for status in ("failure", "cancelled", "skipped"):
            assert not allowed(final, {**successes, name: status})
    for target in ("central", "portal"):
        verifier = promotion["jobs"]["verify-" + target]
        statuses = {name: "success" for name in verifier["needs"]}
        assert allowed(verifier, {**statuses, "publish-" + target: "skipped"})
        assert not allowed(verifier, {**statuses, "publish-" + target: "failure"})
        assert not allowed(verifier, statuses, cancelled=True)


def test_sign_and_consume_maven_zip_with_isolated_fixture_key(
    repository, tmp_path, monkeypatch
):
    import shutil

    keyring = tmp_path / "keyring"
    keyring.mkdir(mode=0o700)
    gpg = [
        "gpg",
        "--homedir",
        str(keyring),
        "--batch",
        "--pinentry-mode",
        "loopback",
        "--passphrase",
        "",
    ]
    subprocess.run(
        [
            *gpg,
            "--quick-generate-key",
            "Release Fixture <fixture@example.invalid>",
            "rsa2048",
            "sign",
            "0",
        ],
        check=True,
        capture_output=True,
    )
    try:
        listing = subprocess.check_output(
            [*gpg, "--with-colons", "--list-secret-keys"], text=True
        )
        fingerprint = next(
            line.split(":")[9]
            for line in listing.splitlines()
            if line.startswith("fpr:")
        )
        secret = subprocess.check_output(
            [*gpg, "--armor", "--export-secret-keys"], text=True
        )
        config = yaml.safe_load(
            (ROOT / "buildish-release-tooling/release-config.yaml").read_text()
        )
        config["artifacts"]["signing"]["expected_fingerprint"] = fingerprint
        tooling = tmp_path / "fixture-tooling"
        tooling.mkdir()
        for name in ("run.py", "release-tooling.sh"):
            shutil.copyfile(ROOT / "buildish-release-tooling" / name, tooling / name)
        (tooling / "release-config.yaml").write_text(yaml.safe_dump(config))
        monkeypatch.setattr(w, "ROOT", tooling)
        monkeypatch.setenv("BUILDISH_RELEASE_TOOLING_PYTHON", sys.executable)
        monkeypatch.setenv("BUILDISH_GPG_PRIVATE_KEY", secret)
        monkeypatch.setenv("BUILDISH_GPG_PASSPHRASE", "")
        monkeypatch.setenv("MANIFEST_PATH", str(tmp_path / "signing-result.json"))
        monkeypatch.setenv("GITHUB_STEP_SUMMARY", str(tmp_path / "summary.md"))
        # Strip test readback sidecars to obtain the exact unsigned input set.
        for path in repository.rglob("*"):
            if path.is_file() and path.relative_to(
                repository
            ).as_posix() not in w.primary_paths("1.2.3"):
                path.unlink()
        signed = tmp_path / "signed"
        w.sign_repository(repository, signed, "1.2.3")
        archive = tmp_path / w.archive_name("1.2.3")
        w.pack(signed, archive, "1.2.3")
        extracted = tmp_path / "extracted"
        w.unpack(archive, extracted, "1.2.3")
        for name in w.primary_paths("1.2.3"):
            subprocess.run(
                [
                    *gpg,
                    "--verify",
                    str(extracted / (name + ".asc")),
                    str(extracted / name),
                ],
                check=True,
                capture_output=True,
            )
    finally:
        subprocess.run(
            ["gpgconf", "--homedir", str(keyring), "--kill", "all"],
            check=True,
            capture_output=True,
        )
