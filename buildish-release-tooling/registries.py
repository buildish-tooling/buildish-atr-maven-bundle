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
import time
import urllib.error
import urllib.request
import uuid
from html import unescape
from http.client import IncompleteRead
from pathlib import Path
from urllib.parse import urlencode

from workflow import PLUGIN, digest, primary_paths, read, signed_paths, write

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


def inspect(repository, version, target, fetch=None, deadline=None):
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
        if len(observed) != path.stat().st_size or hashlib.sha256(
            observed
        ).hexdigest() != digest(path):
            raise ValueError(f"Conflicting {target} publication: {name}")
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
            if inspect(repository, version, target, deadline=deadline) == "complete":
                write(
                    result,
                    {
                        "target": target,
                        "version": version,
                        "base_url": BASES[target],
                        "files": {
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
        state.setdefault("verification_results", []).append(
            {
                "kind": f"atr-maven-bundle-{target}-readback",
                "uri": BASES[target],
                "digest": digest(path),
                "claims": {
                    "record": json.dumps(record, sort_keys=True, separators=(",", ":"))
                },
            }
        )
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
    else:
        evidence(*args.paths)


if __name__ == "__main__":
    main()
