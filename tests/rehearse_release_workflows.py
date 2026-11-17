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

"""Execute disposable copies of the component workflows with the published act harness.

Only provider acquisition/upload and runner provisioning are adapted. No live
credentials or sibling Release Tooling source are used. All fixture writes stay
under --output (default: build/workflow-rehearsal).
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "buildish-tooling/buildish-atr-maven-bundle"
VERSION = "1.2.3"


def git(directory, *args):
    return subprocess.check_output(
        [
            "git",
            "-C",
            str(directory),
            "-c",
            "user.name=Rehearsal",
            "-c",
            "user.email=rehearsal@example.invalid",
            "-c",
            "commit.gpgsign=false",
            "-c",
            "core.hooksPath=/dev/null",
            *args,
        ],
        text=True,
    ).strip()


def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(yaml.safe_dump(value, sort_keys=False))


def prepare(args):
    output = args.output.resolve()
    output.mkdir(parents=True)
    frozen = output / "source"
    frozen.mkdir()
    names = (
        subprocess.check_output(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
            cwd=ROOT,
        )
        .decode()
        .split("\0")
    )
    for name in names:
        if name and (ROOT / name).is_file():
            destination = frozen / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / name, destination)
    (output / "registries").mkdir()
    (output / "registries/policy.json").write_text("{}\n")
    # Freeze actual component code, with a fixture version/notes and test signing key.
    (frozen / "version.txt").write_text(VERSION + "-SNAPSHOT\n")
    notes = frozen / f"buildish-release-tooling/release-notes/{VERSION}.md"
    notes.parent.mkdir(parents=True, exist_ok=True)
    notes.write_text("# Disposable rehearsal\nExact fixture source and artifacts.\n")
    config = yaml.safe_load(
        (frozen / "buildish-release-tooling/release-config.yaml").read_text()
    )
    config["artifacts"]["signing"].pop("expected_fingerprint")
    dump(frozen / "buildish-release-tooling/release-config.yaml", config)
    java21, java17 = args.java21.resolve(), args.java17.resolve()
    gradle_cache = args.gradle_cache.resolve()
    mounts = [
        (java21, "/fixture-java21", "ro"),
        (java17, "/fixture-java17", "ro"),
        (gradle_cache, str(gradle_cache), "rw"),
        (output / "registries", "/fixture-registries", "rw"),
    ]
    options = " ".join(
        f"--volume={host}:{container}:{mode}" for host, container, mode in mounts
    )
    (frozen / ".actrc").write_text(
        "--container-options " + options + "\n--concurrent-jobs 1\n"
    )
    provision = {
        "name": "Use existing fixture JDKs and Gradle cache",
        "shell": "bash",
        "run": "printf 'JAVA_HOME=/fixture-java21\\nJAVA_HOME_17_X64=/fixture-java17\\nJAVA_HOME_21_X64=/fixture-java21\\nGRADLE_USER_HOME="
        + str(gradle_cache)
        + '\\n\' >> "$GITHUB_ENV"\n'
        "printf '/fixture-java21/bin\\n' >> \"$GITHUB_PATH\"\n",
    }
    # Keep the production installer and console launcher; expose the installed wheel
    # to the published harness's gh shim (never to a sibling source checkout).
    action = frozen / ".github/actions/setup-release-tooling/action.yml"
    payload = yaml.safe_load(action.read_text())
    payload["runs"]["steps"][0]["run"] += (
        '\nprintf "%s/bin\\n" "$RUNNER_TEMP/release-tooling" >> "$GITHUB_PATH"\n'
    )
    dump(action, payload)
    for name in ("candidate", "promote"):
        path = frozen / f".github/workflows/release-{name}.yml"
        workflow = yaml.safe_load(path.read_text())
        workflow["env"] = {
            "ATR_REHEARSAL_REGISTRY_ROOT": "/fixture-registries",
            "PYTHONPATH": "${{ github.workspace }}/tests/rehearsal",
        }
        for job in workflow["jobs"].values():
            steps = []
            for step in job["steps"]:
                step = dict(step)
                uses = step.get("uses", "")
                if uses.startswith("actions/setup-java@"):
                    steps.append(provision)
                    continue
                if uses.startswith("$/"):
                    step["uses"] = "./" + uses[2:]
                if "run" in step:
                    script = step["run"].replace(
                        "--attempts 91 --retry-delay 10 --total-timeout 960",
                        "--attempts 3 --retry-delay 0.1 --total-timeout 5",
                    )
                    script = script.replace(
                        '"https://repo.maven.apache.org/maven2/"',
                        '"file:///fixture-registries/central"',
                    )
                    script = script.replace(
                        '"https://plugins.gradle.org/m2/"',
                        '"file:///fixture-registries/portal"',
                    )
                    if "portal-publisher publishPlugins" in script:
                        script = script.replace(
                            "portal-publisher publishPlugins",
                            "portal-publisher publishAllPublicationsToRehearsalRepository",
                        )
                        script += '\npython3 tests/rehearsal/registry_fixture.py publish-portal buildish-release-tooling/portal-publisher/build/repository "$RELEASE_VERSION"\n'
                    step["run"] = script
                steps.append(step)
            # Every job gets the same cached runtime, including registry consumers.
            job["steps"] = steps if provision in steps else [provision, *steps]
        dump(path, workflow)
    git(frozen, "init", "-b", "main")
    git(frozen, "add", ".")
    git(frozen, "commit", "-m", "Freeze disposable release workflow rehearsal")
    git(frozen, "remote", "add", "origin", f"https://github.com/{REPOSITORY}.git")
    dump(
        output / "release-harness.yaml",
        {
            "schema_version": "1",
            "self_repository": {
                "repository_id": REPOSITORY,
                "local_path": str(frozen),
                "local_checkout_mode": "when_repository_omitted",
            },
        },
    )
    return output, frozen


def scenario(output, frozen, name, promotion=False, retained_manifest=False):
    behaviors = [
        {
            "match": {"argv_contains": ["/check-runs?per_page=100"]},
            "result": {
                "stdout": json.dumps(
                    {
                        "check_runs": [
                            {
                                "name": "Required Checks",
                                "status": "completed",
                                "conclusion": "success",
                                "app": {"slug": "github-actions"},
                                "check_suite": {"id": 42},
                            }
                        ]
                    }
                )
            },
        },
        {
            "match": {"argv_contains": ["/status"]},
            "result": {"stdout": '{"statuses":[]}'},
        },
        {
            "match": {"argv_contains": ["/actions/runs?head_sha="]},
            "result": {
                "stdout": json.dumps(
                    {
                        "workflow_runs": [
                            {
                                "path": ".github/workflows/ci.yml",
                                "event": "push",
                                "head_branch": "main",
                                "check_suite_id": 42,
                            }
                        ]
                    }
                )
            },
        },
        {
            "match": {"argv_prefix": ["release", "view"]},
            "result": {"stdout": "1" if retained_manifest else "0"},
        },
    ]
    inputs = (
        {"version": VERSION, "source_ref": "main"}
        if not promotion
        else {
            "version": VERSION,
            "candidate_tag": "harness:seeded-candidate-tag",
            "candidate_manifest_digest": "harness:seeded-release-asset-sha256:candidate-manifest.json",
        }
    )
    payload = {
        "name": name,
        "backend": "act",
        "secrets": {
            "GITHUB_TOKEN": "fixture-token",
            "CENTRAL_USERNAME": "fixture",
            "CENTRAL_PASSWORD": "fixture",
            "GRADLE_PUBLISH_KEY": "fixture",
            "GRADLE_PUBLISH_SECRET": "fixture",
        },
        "tool_behaviors": {"gh": behaviors},
        "workflow": {
            "path": f"source/.github/workflows/release-{'promote' if promotion else 'candidate'}.yml",
            "harness_config": "release-harness.yaml",
            "inputs": inputs,
            "gpg_fixture": "generated-signing-key",
        },
    }
    path = output / f"{name}.yaml"
    dump(path, payload)
    return path


def assert_selected_jobs(report, scenario_path):
    payload = yaml.safe_load(scenario_path.read_text())
    workflow = yaml.safe_load(
        (scenario_path.parent / payload["workflow"]["path"]).read_text()
    )
    expected = set(workflow["jobs"])
    assert expected and set(report["selected_job_ids"]) == expected, report[
        "selected_job_ids"
    ]


def run(output, scenario_path, *, seed=None, expected_failure=None):
    executable = Path(sys.executable).parent / "buildish-release-harness"
    command = [
        str(executable),
        "run",
        str(scenario_path),
        "--workspace-root",
        str(output / "runs"),
    ]
    if seed:
        command.extend(["--seed-from", str(seed)])
    with (output / (scenario_path.stem + ".log")).open("w") as log:
        # act derives repository context through `git remote get-url`. Its origin
        # is the harness's local insteadOf URL. A context-only remote without the
        # .git suffix avoids that redirect; no command fetches/pushes this remote.
        wrapper = output / "bin/act"
        wrapper.parent.mkdir(exist_ok=True)
        wrapper.write_text(
            "#!"
            + sys.executable
            + "\n"
            + """import json, os, sys, subprocess
from pathlib import Path
arguments = sys.argv[1:]
event = Path(arguments[arguments.index("-e") + 1])
payload = json.loads(event.read_text())
payload.update(ref="refs/heads/main", repository={"full_name": "buildish-tooling/buildish-atr-maven-bundle", "name": "buildish-atr-maven-bundle", "owner": {"login": "buildish-tooling"}, "default_branch": "main"})
event.write_text(json.dumps(payload))
subprocess.run(["git", "remote", "add", "act-context", "https://github.com/buildish-tooling/buildish-atr-maven-bundle"], check=True)
os.execvp("gh", ["gh", "act", *arguments, "--remote-name", "act-context"])
"""
        )
        wrapper.chmod(0o755)
        env = {
            **os.environ,
            "PATH": str(wrapper.parent) + os.pathsep + os.environ["PATH"],
        }
        result = subprocess.run(
            command, stdout=subprocess.PIPE, stderr=log, text=True, check=False, env=env
        )
    report_path = output / (scenario_path.stem + ".json")
    report_path.write_text(result.stdout)
    try:
        report = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise RuntimeError(
            f"Harness did not emit a report; inspect {output / (scenario_path.stem + '.log')}"
        ) from error
    assert_selected_jobs(report, scenario_path)
    workspace = Path(report["workspace"])
    actual = {}
    for job in report["selected_job_ids"]:
        status = (
            workspace / ".buildish-release-harness/job-statuses" / (job + ".status")
        )
        actual[job] = status.read_text().strip() if status.exists() else "not-executed"
    report["actual_job_statuses"] = actual
    report_path.write_text(json.dumps(report, indent=2) + "\n")
    if expected_failure:
        if (
            result.returncode == 0
            or actual.get(expected_failure) not in ("failure", "failed")
            or actual.get("publish") != "not-executed"
        ):
            raise ValueError(
                f"Expected verifier/conflict failure did not block publication: {actual}"
            )
    elif result.returncode:
        raise RuntimeError(
            f"Rehearsal failed; inspect {output / (scenario_path.stem + '.log')}"
        )
    elif not actual or any(
        value != "success"
        for job, value in actual.items()
        if job not in ("publish-central", "publish-portal")
    ):
        raise ValueError(f"Rehearsal did not execute required jobs: {report}")
    return audit_retained_report(
        output, scenario_path.stem, expected_failure=expected_failure
    )


def final_release(workspace, *, published):
    state = json.loads(
        (workspace / ".buildish-release-harness/shim-state.json").read_text()
    )
    release = state["gh_releases"].get("v" + VERSION)
    if not published and release is None:
        return None
    assert release["draft"] is not published
    assert release["immutable"] is published
    if published:
        stem = f"buildish-atr-maven-bundle-{VERSION}-maven.zip"
        assert {asset["name"] for asset in release["assets"]} == {
            *(stem + suffix for suffix in ("", ".asc", ".sha256", ".sha512")),
            "release-manifest-v1.json",
        }
        manifests = [
            asset
            for asset in release["assets"]
            if asset["name"] == "release-manifest-v1.json"
        ]
        assert len(manifests) == 1
        return manifests[0]["digest"]
    assert all(
        asset["name"] != "release-manifest-v1.json" for asset in release["assets"]
    )


def uploads(output):
    path = output / "registries/events.jsonl"
    records = (
        [json.loads(line) for line in path.read_text().splitlines()]
        if path.exists()
        else []
    )
    return [record for record in records if record["event"].startswith("upload-")]


def set_policy(output, policy):
    (output / "registries/policy.json").write_text(json.dumps(policy) + "\n")


def audit_retained_report(output, name, *, expected_failure=None):
    """Inspect actual records when continuing reports made before driver updates."""
    path = output / (name + ".json")
    report = json.loads(path.read_text())
    assert_selected_jobs(report, output / (name + ".yaml"))
    workspace = Path(report["workspace"])
    actual = {}
    for job in report["selected_job_ids"]:
        status = (
            workspace / ".buildish-release-harness/job-statuses" / (job + ".status")
        )
        actual[job] = status.read_text().strip() if status.exists() else "not-executed"
    if expected_failure:
        assert actual.get(expected_failure) in ("failure", "failed"), actual
        assert actual.get("publish") == "not-executed", actual
    else:
        assert actual and all(
            value == "success"
            for job, value in actual.items()
            if job not in ("publish-central", "publish-portal")
        ), actual
    report["actual_job_statuses"] = actual
    trace = workspace / ".buildish-release-harness/command-trace.jsonl"
    report["github_asset_uploads"] = sum(
        entry["tool"] == "gh" and entry["argv"][:2] == ["release", "upload"]
        for entry in map(json.loads, trace.read_text().splitlines())
    )
    package_info = []
    for line in (output / (name + ".log")).read_text().splitlines():
        marker = line.find('{"version": "0.1.4"')
        if marker >= 0:
            info = json.loads(line[marker:])
            assert "site-packages" in info["distribution"]
            package_info.append(info)
    assert package_info
    assert not (
        workspace
        / ".buildish-release-harness/repo-sources/buildish-tooling__buildish-release-tooling"
    ).exists()
    report["installed_package_invocations"] = package_info
    path.write_text(json.dumps(report, indent=2) + "\n")
    return workspace, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output", type=Path, default=ROOT / "build/workflow-rehearsal"
    )
    parser.add_argument("--java21", type=Path, required=True)
    parser.add_argument("--java17", type=Path, required=True)
    parser.add_argument("--gradle-cache", type=Path, default=ROOT / ".gradle/user-home")
    parser.add_argument(
        "--resume",
        action="store_true",
        help="Resume using the retained disposable source",
    )
    args = parser.parse_args()
    if args.resume:
        output = args.output.resolve()
        frozen = output / "source"
    else:
        output, frozen = prepare(args)
    progress_path = output / "progress.json"
    progress = json.loads(progress_path.read_text()) if progress_path.exists() else {}
    if "candidate" in progress:
        candidate, _candidate_report = audit_retained_report(output, "candidate")
    else:
        candidate, _candidate_report = run(
            output, scenario(output, frozen, "candidate")
        )
        progress["candidate"] = str(candidate)
        progress_path.write_text(json.dumps(progress, indent=2))
    print(f"Candidate executed: {candidate}", flush=True)
    if "first-publication" in progress:
        promotion, report = audit_retained_report(output, "first-publication")
    else:
        set_policy(output, {})
        before = len(uploads(output))
        promotion, report = run(
            output,
            scenario(output, frozen, "first-publication", promotion=True),
            seed=candidate,
        )
        assert {entry["event"] for entry in uploads(output)[before:]} == {
            "upload-central",
            "upload-portal",
        }
        assert report["actual_job_statuses"]["publish-central"] == "success"
        assert report["actual_job_statuses"]["publish-portal"] == "success"
        progress["first-publication"] = str(promotion)
        progress_path.write_text(json.dumps(progress, indent=2))
    print(f"First publication executed: {promotion}", flush=True)
    sealed_digest = final_release(promotion, published=True)
    for name, seed, policy, retained, failure in (
        ("complete-recovery", promotion, {}, True, None),
        ("partial-recovery", promotion, {"partial_preflight": "central"}, True, None),
        (
            "failed-verification",
            candidate,
            {"fail_verification": "portal"},
            False,
            "verify-portal",
        ),
        ("verification-recovery", None, {}, False, None),
        (
            "conflicting-publication",
            candidate,
            {"conflict": "central"},
            False,
            "inspect-central",
        ),
    ):
        if name in progress:
            audit_retained_report(output, name, expected_failure=failure)
            continue
        if seed is None:
            seed = Path(progress["failed-verification"])
        set_policy(output, policy)
        before = len(uploads(output))
        workspace, report = run(
            output,
            scenario(output, frozen, name, promotion=True, retained_manifest=retained),
            seed=seed,
            expected_failure=failure,
        )
        assert len(uploads(output)) == before, (
            "Recovery unexpectedly uploaded registry files"
        )
        if failure:
            final_release(workspace, published=False)
        else:
            recovered_digest = final_release(workspace, published=True)
            if retained:
                assert recovered_digest == sealed_digest
                assert report["github_asset_uploads"] == 0
            assert report["actual_job_statuses"]["publish-central"] == "not-executed"
            assert report["actual_job_statuses"]["publish-portal"] == "not-executed"
        progress[name] = str(workspace)
        progress_path.write_text(json.dumps(progress, indent=2))
        print(f"{name}: verified {report['actual_job_statuses']}", flush=True)
    set_policy(output, {})
    cases = {}
    for name in progress:
        report = json.loads((output / (name + ".json")).read_text())
        cases[name] = {
            "workspace": progress[name],
            "selected_jobs": report["selected_job_ids"],
            "actual_job_statuses": report.get("actual_job_statuses"),
            "github_asset_uploads": report.get("github_asset_uploads"),
        }
    (output / "results.json").write_text(
        json.dumps(
            {
                "package": "buildish-release-tooling==0.1.4",
                "live_publication": False,
                "sealed_manifest_digest": sealed_digest,
                "registry_uploads": uploads(output),
                "cases": cases,
            },
            indent=2,
        )
        + "\n"
    )
    print("All workflow rehearsal cases passed", flush=True)


if __name__ == "__main__":
    try:
        main()
    finally:
        # Shut down only agents generated in this invocation's retained fixtures.
        output = (
            Path(sys.argv[sys.argv.index("--output") + 1])
            if "--output" in sys.argv
            else ROOT / "build/workflow-rehearsal"
        )
        for home in output.glob(
            "runs/*/.buildish-release-harness/gpg-fixture/source-home"
        ):
            subprocess.run(
                ["gpgconf", "--homedir", str(home), "--kill", "all"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
