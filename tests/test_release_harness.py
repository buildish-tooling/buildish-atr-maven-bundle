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

"""Installed 0.1.4 CLI against its stateful, immutable GitHub harness adapter.

This exercises CLI composition, not GitHub Actions skip propagation or live services.
"""

import json
import shlex
import subprocess
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]


def test_candidate_promotion_locked_recovery_and_conflicting_assets(tmp_path):
    cli = shlex.quote(str(Path(sys.executable).parent / "buildish-release-tooling"))
    harness = str(Path(sys.executable).parent / "buildish-release-harness")
    config = yaml.safe_load(
        (ROOT / "buildish-release-tooling/release-config.yaml").read_text()
    )
    # Provider CI and signing are tested separately. This fixture isolates lifecycle bytes.
    config["source"].pop("checks")
    config["source"]["require_default_branch_ancestor"] = False
    config["artifacts"]["signing"] = None
    config["artifacts"]["checksums"] = []
    run = cli + ' "$command" --component-config release-config.yaml'
    script = r"""
set -euo pipefail
export GIT_AUTHOR_NAME=Fixture GIT_AUTHOR_EMAIL=fixture@example.invalid
export GIT_COMMITTER_NAME=Fixture GIT_COMMITTER_EMAIL=fixture@example.invalid
git init -b main
git -c core.hooksPath=/dev/null -c commit.gpgsign=false add release-config.yaml buildish-release-tooling/release-notes/1.2.3.md
git -c core.hooksPath=/dev/null -c commit.gpgsign=false commit -m fixture
mkdir -p .buildish-release-harness/git-origins
git clone --bare . .buildish-release-harness/git-origins/self
git remote add origin https://github.com/buildish-tooling/buildish-atr-maven-bundle.git
call() { local command="$1"; shift; CLI_COMMAND "$@"; }
MANIFEST_PATH="$PWD/candidate-state.json" call resolve-candidate 1.2.3 main
call create-candidate-tag --candidate-state candidate-state.json
printf 'representative retained Maven bundle\n' > maven.zip
MANIFEST_PATH="$PWD/candidate-stage.json" call stage-github-candidate --candidate-state candidate-state.json --asset maven.zip
# Matching already-uploaded assets are retained; publication does not clobber.
MANIFEST_PATH="$PWD/candidate-stage.json" call stage-github-candidate --candidate-state candidate-state.json --asset maven.zip
MANIFEST_PATH="$PWD/candidate-manifest.json" call create-candidate-manifest --candidate-state candidate-state.json --stage-result candidate-stage.json
call attach-github-candidate-manifest --candidate-state candidate-state.json --candidate-manifest candidate-manifest.json
candidate_digest="$(sha256sum candidate-manifest.json | cut -d' ' -f1)"
call finalize-github-candidate --candidate-tag v1.2.3-rc1 --candidate-manifest-digest "$candidate_digest"
MANIFEST_PATH="$PWD/promotion.json" call resolve-promotion --candidate-tag v1.2.3-rc1 --candidate-manifest candidate-manifest.json --candidate-manifest-digest "$candidate_digest" 1.2.3
call create-final-tag --promotion-state promotion.json
MANIFEST_PATH="$PWD/stage.json" call stage-github-final-release --release-state promotion.json --asset maven.zip
call verify-github-final-release --release-state promotion.json
# Supported generic verification records retain component-owned registry evidence.
python3 buildish-release-tooling/registries.py evidence promotion.json central.json portal.json sealed-state.json
mv sealed-state.json promotion.json
MANIFEST_PATH="$PWD/release-manifest-v1.json" call create-release-manifest --release-state promotion.json --publication-result stage.json
python3 -c 'import json; assert len(json.load(open("release-manifest-v1.json"))["verification_results"]) == 2'
call attach-github-release-manifest --release-state promotion.json --release-manifest release-manifest-v1.json
sealed="$(sha256sum release-manifest-v1.json | cut -d' ' -f1)"
if call publish-github-final-release --release-state promotion.json --require-manifest-digest "${sealed/0/1}BAD"; then exit 41; fi
call publish-github-final-release --release-state promotion.json --require-manifest-digest "$sealed"
call verify-github-final-release --release-state promotion.json
# A new dispatch resolves fresh state without the previous readback records.
MANIFEST_PATH="$PWD/fresh.json" call resolve-promotion --candidate-tag v1.2.3-rc1 --candidate-manifest candidate-manifest.json --candidate-manifest-digest "$candidate_digest" 1.2.3
# 0.1.4 cannot restage custom sealed verification records against that bare state.
if call stage-github-final-release --release-state fresh.json --asset maven.zip; then exit 43; fi
# Independently repeat readbacks before restaging with the same component evidence.
python3 buildish-release-tooling/registries.py evidence fresh.json central.json portal.json recovered.json
mv recovered.json promotion.json
# Locked-publication recovery must do no uploads, preserve sealed bytes, and verify.
MANIFEST_PATH="$PWD/restage.json" call stage-github-final-release --release-state promotion.json --asset maven.zip
call attach-github-release-manifest --release-state promotion.json --release-manifest release-manifest-v1.json
call publish-github-final-release --release-state promotion.json --require-manifest-digest "$sealed"
call verify-github-final-release --release-state promotion.json
printf 'conflicting bytes\n' > maven.zip
if call stage-github-final-release --release-state promotion.json --asset maven.zip; then exit 42; fi
# A packaging-only correction can select a new candidate on the unchanged source
# after a final tag exists; a fresh trusted workflow revision may run the command.
approved_source="$(git rev-parse HEAD)"
printf 'trusted workflow correction\n' > workflow-fix.txt
git add workflow-fix.txt
git -c core.hooksPath=/dev/null -c commit.gpgsign=false commit -m 'Trusted workflow fix'
MANIFEST_PATH="$PWD/rc2.json" call resolve-candidate 1.2.3 "$approved_source"
python3 -c 'import json; p=json.load(open("rc2.json")); assert p["candidate"]["tag"]["name"] == "v1.2.3-rc2"; assert p["source"]["commit_sha"] == p["final_tag_identity"]["target_commit"]'
test "$(git rev-parse 'v1.2.3^{commit}')" = "$approved_source"
""".replace("CLI_COMMAND", run)
    scenario = {
        "name": "atr-candidate-promotion-recovery",
        "backend": "custom",
        "env": {
            "GH_TOKEN": "fixture-only",
            "GITHUB_REPOSITORY": "buildish-tooling/buildish-atr-maven-bundle",
        },
        "workspace_files": [
            {
                "path": "buildish-release-tooling/registries.py",
                "content": (
                    ROOT / "buildish-release-tooling/registries.py"
                ).read_text(),
            },
            {
                "path": "buildish-release-tooling/workflow.py",
                "content": (ROOT / "buildish-release-tooling/workflow.py").read_text(),
            },
            {
                "path": "central.json",
                "content": json.dumps(
                    {
                        "target": "central",
                        "version": "1.2.3",
                        "base_url": "https://repo.maven.apache.org/maven2/",
                        "files": {"maven.zip": {"sha256": "f" * 64}},
                    }
                ),
            },
            {
                "path": "portal.json",
                "content": json.dumps(
                    {
                        "target": "portal",
                        "version": "1.2.3",
                        "base_url": "https://plugins.gradle.org/m2/",
                        "files": {"maven.zip": {"sha256": "f" * 64}},
                    }
                ),
            },
            {"path": "release-config.yaml", "content": yaml.safe_dump(config)},
            {
                "path": "buildish-release-tooling/release-notes/1.2.3.md",
                "content": "# Fixture\nRetained reviewed notes.\n",
            },
        ],
        "jobs": [{"id": "release", "steps": [{"id": "lifecycle", "run": script}]}],
    }
    path = tmp_path / "scenario.yaml"
    path.write_text(yaml.safe_dump(scenario))
    result = subprocess.run(
        [harness, "run", str(path), "--workspace-root", str(tmp_path)],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode:
        # Keep the command log visible when diagnosing an actual CLI failure.
        paths = list(tmp_path.glob("**/lifecycle*.log"))
        raise AssertionError(
            result.stdout + result.stderr + "\n".join(p.read_text() for p in paths)
        )
    report = json.loads(result.stdout)
    assert report["selected_job_ids"] == ["release"]
    assert report["job_statuses"] == {"release": "success"}
    assert not report["failed_job_ids"]
