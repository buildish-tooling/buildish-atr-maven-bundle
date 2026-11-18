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

"""Central upload and exact-byte public Maven readback for this component."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from html import unescape
from http.client import IncompleteRead
from pathlib import Path
from urllib.parse import urlencode

from workflow import (
    PLUGIN,
    REPOSITORY,
    check_digest,
    digest,
    primary_paths,
    read,
    signed_paths,
    write,
)

BASES = {
    "central": "https://repo.maven.apache.org/maven2/",
    "portal": "https://plugins.gradle.org/m2/",
}


def expected_files(repository, version, target):
    repository = Path(repository)
    names = primary_paths(version)
    if target == "portal":
        # Parent POM is published only to Central. Portal may serve it by redirect.
        names = {name for name in names if "/buildish-atr-maven-bundle/" not in name}
    else:
        names = signed_paths(version)
    return {name: repository / name for name in sorted(names)}


def verify_portal_marker(observed, retained, repository, version):
    """Allow only the Portal's metadata-free, single-dependency marker mapping."""
    namespace = "{http://maven.apache.org/POM/4.0.0}"

    def children(element, allowed):
        result = {}
        if (element.text or "").strip() or any(
            (child.tail or "").strip() for child in element
        ):
            raise ValueError("Unexpected marker text")
        if element.attrib and element.tag != namespace + "project":
            raise ValueError("Unexpected marker attributes")
        for child in element:
            name = child.tag.removeprefix(namespace)
            if child.tag != namespace + name or name not in allowed or name in result:
                raise ValueError("Unexpected or duplicate marker element")
            result[name] = child
        return result

    def project(data):
        if b"<!DOCTYPE" in data.upper() or b"<!ENTITY" in data.upper():
            raise ValueError("Unexpected marker document declaration")
        root = ET.fromstring(data)
        if root.tag != namespace + "project" or set(root.attrib) - {
            "{http://www.w3.org/2001/XMLSchema-instance}schemaLocation"
        }:
            raise ValueError("Unexpected marker project")
        return root

    def scalar(element, expected):
        if len(element) or element.attrib or (element.text or "").strip() != expected:
            raise ValueError("Marker coordinates or dependency differ")

    fields = {
        "modelVersion",
        "groupId",
        "artifactId",
        "version",
        "packaging",
        "dependencies",
    }
    try:
        for data, original in ((observed, False), (retained, True)):
            values = children(
                project(data),
                fields | ({"name", "description", "parent"} if original else set()),
            )
            if not fields <= values.keys():
                raise ValueError("Incomplete marker")
            for name, expected in {
                "modelVersion": "4.0.0",
                "groupId": PLUGIN,
                "artifactId": PLUGIN + ".gradle.plugin",
                "version": version,
                "packaging": "pom",
            }.items():
                scalar(values[name], expected)
            dependencies = values["dependencies"]
            if (
                dependencies.attrib
                or len(dependencies) != 1
                or dependencies[0].tag != namespace + "dependency"
            ):
                raise ValueError(
                    "Expected exactly one marker implementation dependency"
                )
            dependency = children(dependencies[0], {"groupId", "artifactId", "version"})
            for name, expected in {
                "groupId": "org.buildish.atr-maven-bundle",
                "artifactId": "gradle-plugin",
                "version": version,
            }.items():
                scalar(dependency[name], expected)
            if "parent" in values:
                parent = children(
                    values["parent"], {"groupId", "artifactId", "version"}
                )
                for name, expected in {
                    "groupId": "org.buildish.atr-maven-bundle",
                    "artifactId": "buildish-atr-maven-bundle",
                    "version": version,
                }.items():
                    scalar(parent[name], expected)
                path = (
                    Path(repository)
                    / f"org/buildish/atr-maven-bundle/buildish-atr-maven-bundle/{version}/buildish-atr-maven-bundle-{version}.pom"
                )
                # Dropping this parent is safe only while it supplies metadata alone.
                parent_values = children(
                    project(path.read_bytes()),
                    {
                        "modelVersion",
                        "groupId",
                        "artifactId",
                        "version",
                        "packaging",
                        "name",
                        "description",
                        "url",
                        "inceptionYear",
                        "organization",
                        "licenses",
                        "developers",
                        "mailingLists",
                        "scm",
                        "issueManagement",
                    },
                )
                for name, expected in {
                    "modelVersion": "4.0.0",
                    "groupId": "org.buildish.atr-maven-bundle",
                    "artifactId": "buildish-atr-maven-bundle",
                    "version": version,
                    "packaging": "pom",
                }.items():
                    scalar(parent_values[name], expected)
    except (ET.ParseError, KeyError, ValueError) as error:
        raise ValueError("Conflicting portal plugin-marker mapping") from error


def inspect(repository, version, target, fetch=None, deadline=None, observations=None):
    fetch = fetch or urllib.request.urlopen

    def budget():
        remaining = 30 if deadline is None else min(30, deadline - time.monotonic())
        if remaining <= 0:
            raise ConnectionError("Readback deadline exhausted")
        return remaining

    portal_absent = False
    if target == "portal":
        # Maven fallback alone does not prove registration in the Plugin Portal.
        page = f"https://plugins.gradle.org/plugin/{PLUGIN}/{version}"
        try:
            with fetch(page, timeout=budget()) as response:
                if response.geturl().rstrip("/") != page:
                    raise ValueError(
                        "Plugin Portal did not retain the exact version URL"
                    )
                response.read()
        except urllib.error.HTTPError as error:
            # The Portal uses HTTP 400 for an unpublished plugin/version page.
            # Do not mistake other bad requests for permission to publish.
            absent = error.code == 404
            if error.code == 400:
                body = unescape(error.read().decode("utf-8", errors="replace"))
                absent = (
                    "<title>Gradle - Plugin Not Found</title>" in body
                    and f'<h1>Version "{version}" of plugin "{PLUGIN}" not found</h1>'
                    in body
                )
            if not absent:
                if error.code in (408, 429) or error.code >= 500:
                    raise ConnectionError(
                        f"Transient Portal registration HTTP {error.code}"
                    ) from None
                raise ValueError(
                    f"Plugin Portal registration HTTP {error.code}"
                ) from None
            portal_absent = True
        except (
            urllib.error.URLError,
            TimeoutError,
            ConnectionError,
            IncompleteRead,
        ) as error:
            raise ConnectionError(
                "Transient Portal registration read failure"
            ) from error
    expected = expected_files(repository, version, target)
    missing, found = [], []
    for name, path in expected.items():
        try:
            with fetch(BASES[target] + name, timeout=budget()) as response:
                observed = response.read()
        except urllib.error.HTTPError as error:
            if error.code == 404:
                missing.append(name)
                continue
            if error.code in (408, 429) or error.code >= 500:
                raise ConnectionError(f"Transient {target} HTTP {error.code}") from None
            raise ValueError(f"{target} HTTP {error.code} for {name}") from None
        except (
            urllib.error.URLError,
            TimeoutError,
            ConnectionError,
            IncompleteRead,
        ) as error:
            raise ConnectionError(f"Transient {target} read failure") from error
        actual = {"sha256": hashlib.sha256(observed).hexdigest(), "size": len(observed)}
        if actual != {"sha256": digest(path), "size": path.stat().st_size}:
            marker = f"{PLUGIN.replace('.', '/')}/{PLUGIN}.gradle.plugin/{version}/{PLUGIN}.gradle.plugin-{version}.pom"
            if target != "portal" or name != marker:
                raise ValueError(f"Conflicting {target} publication: {name}")
            verify_portal_marker(observed, path.read_bytes(), repository, version)
            actual.update(
                verification="plugin-marker-mapping-v1",
                retained_sha256=digest(path),
                retained_size=path.stat().st_size,
            )
        if observations is not None:
            observations[name] = actual
        found.append(name)
    if portal_absent:
        return "absent"
    if not missing:
        return "complete"
    return "partial" if found else "absent"


def verify(
    repository, version, target, result, attempts=91, retry_delay=10, total_timeout=960
):
    deadline = time.monotonic() + total_timeout
    for attempt in range(attempts):
        try:
            observed = {}
            if (
                inspect(
                    repository,
                    version,
                    target,
                    deadline=deadline,
                    observations=observed,
                )
                == "complete"
            ):
                write(
                    result,
                    {
                        "target": target,
                        "version": version,
                        "base_url": BASES[target],
                        "files": observed
                        or {
                            name: {"sha256": digest(path), "size": path.stat().st_size}
                            for name, path in expected_files(
                                repository, version, target
                            ).items()
                        },
                    },
                )
                return
        except ConnectionError:
            pass
        if attempt + 1 == attempts or time.monotonic() + retry_delay >= deadline:
            break
        time.sleep(retry_delay)
    raise ValueError(f"{target} publication not visible within verification budget")


def upload_central(archive, result):
    token = base64.b64encode(
        f"{os.environ['CENTRAL_USERNAME']}:{os.environ['CENTRAL_PASSWORD']}".encode()
    ).decode()
    boundary = uuid.uuid4().hex
    body = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; '
        f'filename="bundle.zip"\r\nContent-Type: application/zip\r\n\r\n'
    ).encode()
    body += Path(archive).read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(
        "https://central.sonatype.com/api/v1/publisher/upload?publishingType=AUTOMATIC",
        data=body,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            deployment = response.read().decode().strip()
    except (urllib.error.URLError, TimeoutError, ConnectionError, IncompleteRead):
        # Do not print request headers, credential-bearing exception details, or retry uploads.
        raise ValueError(
            "Central upload failed or timed out; inspect Portal deployments before retrying"
        ) from None
    write(result, {"deployment_id": deployment, "bundle_sha256": digest(archive)})


def wait_central(result, attempts=91, retry_delay=10, total_timeout=960):
    record = read(result)
    deployment = record["deployment_id"]
    if not deployment:
        raise ValueError("Central did not return a deployment ID")
    token = base64.b64encode(
        f"{os.environ['CENTRAL_USERNAME']}:{os.environ['CENTRAL_PASSWORD']}".encode()
    ).decode()
    deadline = time.monotonic() + total_timeout
    for attempt in range(attempts):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        request = urllib.request.Request(
            "https://central.sonatype.com/api/v1/publisher/status?"
            + urlencode({"id": deployment}),
            data=b"",
            headers={"Authorization": f"Bearer {token}"},
        )
        try:
            with urllib.request.urlopen(
                request, timeout=min(30, remaining)
            ) as response:
                status = json.load(response)
            if not isinstance(status, dict):
                raise TypeError("Central status response is not an object")
            if status.get("deploymentId") != deployment:
                raise ValueError("Central status returned a different deployment ID")
            state = status.get("deploymentState")
            if state not in {
                "PENDING",
                "VALIDATING",
                "VALIDATED",
                "PUBLISHING",
                "PUBLISHED",
                "FAILED",
            }:
                raise ValueError(f"Unknown Central deployment state: {state}")
            if state != record.get("deployment_state"):
                print(f"Central deployment {deployment}: {state}", flush=True)
            record["deployment_state"] = state
            record["errors"] = status.get("errors", {})
            write(result, record)
            if state == "FAILED":
                raise ValueError(
                    f"Central deployment {deployment} failed: {json.dumps(status.get('errors', {}), sort_keys=True)}"
                )
            if state == "PUBLISHED":
                return
        except urllib.error.HTTPError as error:
            if error.code not in (404, 408, 429) and error.code < 500:
                raise ValueError(
                    f"Central deployment status HTTP {error.code}"
                ) from None
        except (
            urllib.error.URLError,
            TimeoutError,
            ConnectionError,
            IncompleteRead,
            json.JSONDecodeError,
        ):
            pass
        if attempt + 1 == attempts or time.monotonic() + retry_delay >= deadline:
            break
        time.sleep(retry_delay)
    raise ValueError(
        f"Central deployment {deployment} not published within verification budget; inspect the retained receipt and Portal deployment"
    )


def evidence(state_path, central_path, portal_path, destination):
    state = read(state_path)
    version = state["release"]["version"]
    for target, path in (("central", central_path), ("portal", portal_path)):
        record = read(path)
        if (
            record["target"] != target
            or record["version"] != version
            or record["base_url"] != BASES[target]
        ):
            raise ValueError("Registry evidence differs from approved release")
        kind = (
            "submission"
            if record.get("public_verification") == "pending"
            else "readback"
        )
        if kind == "submission" and (
            target != "portal"
            or record.get("submission") != "accepted"
            or record.get("candidate_manifest_digest")
            != state["candidate_manifest_digest"]
        ):
            raise ValueError("Pending Portal evidence differs from approved candidate")
        state.setdefault("verification_results", []).append(
            {
                "kind": f"atr-maven-bundle-{target}-{kind}",
                "uri": BASES[target],
                "digest": digest(path),
                "claims": {
                    "record": json.dumps(record, sort_keys=True, separators=(",", ":"))
                },
            }
        )
    write(destination, state)


def previous_portal_submission(run_id, candidate_digest):
    """Read proof of an earlier successful submission of this exact candidate."""
    if not re.fullmatch(r"[1-9][0-9]*", run_id):
        raise ValueError("Expected a numeric Portal submission run ID")

    def api(path, *options):
        return json.loads(
            subprocess.check_output(
                ["gh", "api", f"repos/{REPOSITORY}/{path}", *options], text=True
            )
        )

    run = api(f"actions/runs/{run_id}")
    if (
        run["repository"]["full_name"] != REPOSITORY
        or run["path"] != ".github/workflows/release-promote.yml"
        or run["event"] != "workflow_dispatch"
        or run["head_branch"] != "main"
        or run["status"] != "completed"
    ):
        raise ValueError(
            "Prior submission must come from a completed trusted promotion run"
        )
    job_lines = subprocess.check_output(
        [
            "gh",
            "api",
            f"repos/{REPOSITORY}/actions/runs/{run_id}/jobs?per_page=100",
            "--paginate",
            "--jq",
            ".jobs[] | @json",
        ],
        text=True,
    ).splitlines()
    jobs = [
        job for job in map(json.loads, job_lines) if job["name"] == "publish-portal"
    ]
    if len(jobs) != 1 or jobs[0]["conclusion"] != "success":
        raise ValueError("Prior Portal submission job did not succeed")
    with tempfile.TemporaryDirectory(prefix="portal-submission-") as directory:
        subprocess.run(
            [
                "gh",
                "run",
                "download",
                run_id,
                "--repo",
                REPOSITORY,
                "--name",
                f"promotion-input-{run_id}",
                "--dir",
                directory,
            ],
            check=True,
        )
        check_digest(Path(directory) / "candidate-manifest.json", candidate_digest)
    return {"run_id": run_id, "job_id": jobs[0]["id"], "workflow_sha": run["head_sha"]}


def record_portal(
    repository,
    version,
    candidate_path,
    destination,
    preflight_status,
    publish_result,
    prior_run="",
):
    candidate_digest = digest(candidate_path)
    candidate = read(candidate_path)
    if candidate["release"]["version"] != version:
        raise ValueError("Portal submission version differs from candidate")
    if publish_result not in {"success", "skipped"}:
        raise ValueError(
            "No successful Portal submission; upload job failed or was cancelled"
        )
    if prior_run:
        proof = previous_portal_submission(prior_run, candidate_digest)
    elif publish_result == "success":
        proof = {"run_id": os.environ["GITHUB_RUN_ID"], "job": "publish-portal"}
    elif preflight_status == "complete":
        # Anonymous exact-byte inspection also establishes an accepted publication.
        if inspect(repository, version, "portal") != "complete":
            raise ValueError("Previously available Portal publication is incomplete")
        proof = {"source": "matching public Portal publication"}
    else:
        raise ValueError(
            "No successful Portal submission; supply its previous run ID to resume"
        )
    names = expected_files(repository, version, "portal")
    record = {
        "target": "portal",
        "version": version,
        "base_url": BASES["portal"],
        "candidate_manifest_digest": candidate_digest,
        "submission": "accepted",
        "public_verification": "pending",
        "files": {
            name: {"sha256": digest(path), "size": path.stat().st_size}
            for name, path in names.items()
        },
    }
    # Stable sealed facts exclude run IDs: recovery must preserve matching bytes.
    write(Path(destination) / "readback.json", record)
    write(Path(destination) / "submission-proof.json", proof)
    print(
        "Portal submission accepted; public verification pending. Run release-verify-portal.yml after approval.",
        flush=True,
    )


def final_inputs(manifest_path, expected, version):
    check_digest(manifest_path, expected)
    manifest = read(manifest_path)
    if (
        manifest["release"]["version"] != version
        or manifest["release"]["component"]["id"] != "buildish-atr-maven-bundle"
        or manifest["final_tag"]["name"] != "v" + version
    ):
        raise ValueError("Final manifest differs from selected release")
    return manifest


def final_state(manifest_path, expected, version, destination):
    """Build the released CLI's verification state from the sealed final only."""
    manifest = final_inputs(manifest_path, expected, version)
    promoted = manifest["promoted_candidate"]
    state = {
        key: manifest[key]
        for key in (
            "release",
            "source",
            "final_tag",
            "artifacts",
            "verification_results",
            "publications",
        )
    }
    for key in ("release_notes", "materialization", "tooling"):
        if key in manifest:
            state[key] = manifest[key]
    state["candidate"] = promoted["candidate"]
    state["candidate_manifest_digest"] = promoted["manifest"]["digest"]
    # PromotionState in 0.1.4 requires this field, but the final manifest omits it.
    # Recover the same Git committer timestamp used by resolve-candidate, after
    # fetching and validating the exact final tag; no candidate lookup is needed.
    epoch = subprocess.check_output(
        ["git", "show", "-s", "--format=%ct", manifest["source"]["commit_sha"]],
        text=True,
    ).strip()
    if not epoch.isdigit():
        raise ValueError("Cannot resolve the final source commit timestamp")
    state["source_date_epoch"] = int(epoch)
    write(destination, state)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("inspect", "verify"):
        sub = commands.add_parser(command)
        sub.add_argument("target", choices=BASES)
        sub.add_argument("repository")
        sub.add_argument("version")
        if command == "verify":
            sub.add_argument("result")
            sub.add_argument("--attempts", type=int, default=91)
            sub.add_argument("--retry-delay", type=float, default=10)
            sub.add_argument("--total-timeout", type=float, default=960)
    sub = commands.add_parser("upload-central")
    sub.add_argument("archive")
    sub.add_argument("result")
    sub = commands.add_parser("wait-central")
    sub.add_argument("result")
    sub.add_argument("--attempts", type=int, default=91)
    sub.add_argument("--retry-delay", type=float, default=10)
    sub.add_argument("--total-timeout", type=float, default=960)
    sub = commands.add_parser("evidence")
    sub.add_argument("paths", nargs=4)
    sub = commands.add_parser("record-portal")
    for name in (
        "repository",
        "version",
        "candidate",
        "destination",
        "preflight_status",
        "publish_result",
    ):
        sub.add_argument(name)
    sub.add_argument("--prior-run", default="")
    sub = commands.add_parser("final-inputs")
    for name in ("manifest", "digest", "version"):
        sub.add_argument(name)
    sub = commands.add_parser("final-state")
    for name in ("manifest", "digest", "version", "destination"):
        sub.add_argument(name)
    args = parser.parse_args()
    if args.command == "inspect":
        print(inspect(args.repository, args.version, args.target))
    elif args.command == "verify":
        verify(
            args.repository,
            args.version,
            args.target,
            args.result,
            args.attempts,
            args.retry_delay,
            args.total_timeout,
        )
    elif args.command == "upload-central":
        upload_central(args.archive, args.result)
    elif args.command == "wait-central":
        wait_central(args.result, args.attempts, args.retry_delay, args.total_timeout)
    elif args.command == "record-portal":
        record_portal(
            args.repository,
            args.version,
            args.candidate,
            args.destination,
            args.preflight_status,
            args.publish_result,
            args.prior_run,
        )
    elif args.command == "final-inputs":
        final_inputs(args.manifest, args.digest, args.version)
    elif args.command == "final-state":
        final_state(args.manifest, args.digest, args.version, args.destination)
    else:
        evidence(*args.paths)


if __name__ == "__main__":
    main()
