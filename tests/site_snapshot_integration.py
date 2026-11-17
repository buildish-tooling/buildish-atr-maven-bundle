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

"""Read-only sibling Pipeline integration using disposable component/catalog fixtures.

Run explicitly; requires the owning Pipeline checkout's existing .venv.
"""

import json
import os
import shutil
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory

import yaml

root = Path(__file__).resolve().parents[1]


def exercise(temporary, pipeline_root, catalog_path):
    fixture = Path(temporary)
    source = fixture / "buildish-atr-maven-bundle"
    for tree in ("docs", "site"):
        shutil.copytree(
            root / tree,
            source / tree,
            dirs_exist_ok=True,
            ignore=shutil.ignore_patterns("releases"),
        )
    catalog = yaml.safe_load(catalog_path.read_text())
    catalog["sources"] = {"atr-maven-bundle": catalog["sources"]["atr-maven-bundle"]}
    catalog["components"] = [
        c for c in catalog["components"] if c["slug"] == "atr-maven-bundle"
    ]
    catalog["site"] = {"pagesRoot": "site-content"}
    (fixture / "site-content").mkdir(exist_ok=True)
    (fixture / "catalog.yaml").write_text(yaml.safe_dump(catalog))
    python = pipeline_root / ".venv/bin/python"
    cli = [str(python), "-m", "buildish_site_pipeline"]
    env = {"PYTHONPATH": str(pipeline_root / "src")}
    env = {**os.environ, **env}

    def run(command, profile, success):
        if command == "build":
            shutil.rmtree(fixture / ".stage", ignore_errors=True)
        result = subprocess.run(
            [
                *cli,
                command,
                "--profile",
                profile,
                "--catalog",
                str(fixture / "catalog.yaml"),
                "--workspace-root",
                str(fixture),
                "--report-format",
                "text",
                "--report-output",
                "-",
            ],
            env=env,
            capture_output=True,
            text=True,
        )
        print(
            command,
            profile,
            result.returncode,
            result.stdout[-900:],
            result.stderr[-500:],
        )
        assert (result.returncode == 0) == success

    run("check", "local", True)
    run("check", "publication", False)
    release = source / "docs/releases/1.2.3"
    release.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source / "docs", release, ignore=shutil.ignore_patterns("releases"))
    with (release / "_index.md").open("a") as stream:
        stream.write("\nRELEASED_ORIGINAL_FIXTURE\n")
    unselected = source / "docs/releases/9.8.7"
    unselected.mkdir()
    (unselected / "_index.md").write_text("UNSELECTED_ORIGINAL_FIXTURE\n")
    (source / "docs/releases/.git").write_text("gitdir: fixture-only")
    snapshot = {
        "schemaVersion": 1,
        "providers": [
            {
                "key": "atr-maven-bundle-tags",
                "type": "git-tags",
                "fetchedAt": "2026-01-01T00:00:00Z",
                "displayName": "ATR Maven Bundle tags",
                "baseUrl": "https://github.com/buildish-tooling/buildish-atr-maven-bundle",
            }
        ],
        "records": [
            {
                "provider": "atr-maven-bundle-tags",
                "kind": "released",
                "componentSlug": "atr-maven-bundle",
                "artifactKey": "atr-maven-bundle",
                "version": "1.2.3",
                "tag": "v1.2.3",
                "commitSha": "1" * 40,
                "externalId": "fixture:1.2.3",
            }
        ],
        "artifacts": [
            {
                "componentSlug": "atr-maven-bundle",
                "artifactKey": "atr-maven-bundle",
                "latestStable": "1.2.3",
                "releaseLines": [{"key": "1.2", "latest": "1.2.3"}],
                "releases": [{"version": "1.2.3", "releaseLine": "1.2"}],
            }
        ],
    }
    path = source / "docs/releases/provider-snapshot.json"
    path.write_text(json.dumps(snapshot))
    run("check", "publication", True)
    run("build", "publication", True)
    stage = fixture / ".stage"
    assert stage.is_dir()
    staged = [p for p in stage.rglob("*") if p.is_file()]
    assert any("1.2.3" in p.parts for p in staged)
    assert all("9.8.7" not in p.parts for p in staged)
    assert all(p.name not in (".git", "provider-snapshot.json") for p in staged)
    assert all("development/releases/" not in p.as_posix() for p in staged)
    release_items = json.loads((stage / "data/releases.json").read_text())["items"]
    assert any(item["version"] == "1.2.3" for item in release_items)
    # Missing selected original docs still fail, even though the inventory exists.
    shutil.rmtree(release)
    run("check", "publication", False)
    # Hidden/withdrawn versions remain inventoried without promising original docs.
    for state in ["hidden", "withdrawn"]:
        snapshot["artifacts"][0].pop("latestStable", None)
        snapshot["artifacts"][0]["releaseLines"] = []
        snapshot["artifacts"][0]["releases"] = [
            {"version": "1.2.3", "publicationState": state}
        ]
        path.write_text(json.dumps(snapshot))
        run("check", "publication", True)
        run("build", "publication", True)
        assert not any(
            "RELEASED_ORIGINAL_FIXTURE" in p.read_text() for p in stage.rglob("*.md")
        )
        snapshot["artifacts"][0]["latestStable"] = "1.2.3"
        path.write_text(json.dumps(snapshot))
        # Current Pipeline suppresses the excluded target but does not diagnose
        # this stale recommendation. Keep the operator correction explicit.
        run("check", "publication", True)
        run("build", "publication", True)
        assert not any("latest" in p.parts for p in stage.rglob("*.md"))
        artifact_items = json.loads((stage / "data/artifacts.json").read_text())[
            "items"
        ]
        designation = artifact_items[0]["releaseDesignations"]["latestStable"]
        assert designation["publicationState"] == state
        assert designation["selected"] is False
        assert designation["availability"]["kind"] == "unavailable"
        assert "href" not in designation
        snapshot["artifacts"][0].pop("latestStable")
    snapshot["records"][0]["publicationState"] = "published"
    snapshot["artifacts"][0]["latestStable"] = "1.2.3"
    path.write_text(json.dumps(snapshot))
    run("check", "publication", False)
    path.write_text("{malformed")
    run("check", "local", False)


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--pipeline-root", type=Path, default=root.parent / "buildish-site-pipeline"
    )
    parser.add_argument(
        "--catalog", type=Path, default=root.parent / "buildish/site/catalog.yaml"
    )
    args = parser.parse_args()
    with TemporaryDirectory(prefix="atr-site-integration-") as temporary:
        exercise(temporary, args.pipeline_root.resolve(), args.catalog.resolve())
