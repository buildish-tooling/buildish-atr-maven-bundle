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

"""File-backed Central/Portal service fixtures; used only by rewritten act workflows."""

from __future__ import annotations

import argparse
import io
import json
import os
import shutil
import urllib.error
import urllib.request
import zipfile
from email.parser import BytesParser
from email.policy import default
from pathlib import Path
from urllib.parse import urlsplit

ROOT_ENV = "ATR_REHEARSAL_REGISTRY_ROOT"
HOSTS = {"repo.maven.apache.org": "central", "plugins.gradle.org": "portal"}


def root():
    return Path(os.environ[ROOT_ENV])


def record(event, **details):
    with (root() / "events.jsonl").open("a") as stream:
        stream.write(json.dumps({"event": event, **details}, sort_keys=True) + "\n")


class Response(io.BytesIO):
    def __init__(self, data, url):
        super().__init__(data)
        self.url = url

    def geturl(self):
        return self.url


def urlopen(request, timeout=None, **kwargs):
    url = request.full_url if isinstance(request, urllib.request.Request) else request
    parsed = urlsplit(url)
    method = (
        request.get_method() if isinstance(request, urllib.request.Request) else "GET"
    )
    if parsed.hostname == "central.sonatype.com":
        if (
            method != "POST"
            or parsed.path != "/api/v1/publisher/upload"
            or parsed.query != "publishingType=AUTOMATIC"
        ):
            raise ValueError("Unexpected Central fixture operation")
        if request.get_header("Authorization") is None:
            raise ValueError("Central upload omitted authorization")
        headers = f"Content-Type: {request.get_header('Content-type')}\r\nMIME-Version: 1.0\r\n\r\n".encode()
        message = BytesParser(policy=default).parsebytes(headers + request.data)
        bundles = [
            p.get_payload(decode=True)
            for p in message.iter_parts()
            if p.get_param("name", header="content-disposition") == "bundle"
        ]
        if len(bundles) != 1:
            raise ValueError("Expected one multipart Central bundle")
        with zipfile.ZipFile(io.BytesIO(bundles[0])) as archive:
            for item in archive.infolist():
                relative = Path(item.filename)
                if relative.is_absolute() or ".." in relative.parts:
                    raise ValueError("Unsafe fixture upload")
                destination = root() / "central" / relative
                data = archive.read(item)
                if destination.exists() and destination.read_bytes() != data:
                    raise ValueError("Conflicting Central upload")
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
        record("upload-central", files=len(archive.namelist()))
        return Response(b"fixture-central-deployment", url)
    if parsed.hostname not in HOSTS or method != "GET":
        raise ValueError(
            f"Unexpected registry fixture request: {method} {parsed.hostname}"
        )
    target = HOSTS[parsed.hostname]
    if target == "portal" and parsed.path.startswith("/plugin/"):
        path = root() / "portal-registration"
    else:
        prefix = "/maven2/" if target == "central" else "/m2/"
        if not parsed.path.startswith(prefix):
            raise ValueError("Unexpected Maven fixture path")
        relative = Path(parsed.path.removeprefix(prefix))
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("Unsafe fixture read")
        path = root() / target / relative
    policy_file = root() / "policy.json"
    policy = json.loads(policy_file.read_text()) if policy_file.exists() else {}
    job = os.environ.get("BUILDISH_HARNESS_JOB_ID", "")
    if policy.get("fail_verification") == target and job == "verify-" + target:
        raise urllib.error.HTTPError(url, 503, "fixture verification outage", {}, None)
    if (
        policy.get("partial_preflight") == target
        and job == "inspect-" + target
        and parsed.path.endswith(".jar")
    ):
        record("partial-preflight", target=target)
        raise urllib.error.HTTPError(url, 404, "fixture propagation", {}, None)
    if not path.is_file():
        raise urllib.error.HTTPError(url, 404, "fixture not published", {}, None)
    data = path.read_bytes()
    if policy.get("conflict") == target and parsed.path.endswith(".jar"):
        data = b"conflicting fixture artifact"
    record("read", target=target, path=parsed.path, job=job)
    return Response(data, url)


def publish_portal(repository, version):
    repository = Path(repository)
    central = root() / "central"
    portal = root() / "portal"
    # Require actual publisher output before adding Portal's Central fallback view.
    expected = {
        path.relative_to(central): path
        for path in central.rglob("*")
        if path.is_file()
        and path.suffix in (".pom", ".module", ".jar")
        and not path.name.startswith("buildish-atr-maven-bundle-")
    }
    if len(expected) != 6:
        raise ValueError("Expected six retained Portal primary files")
    for relative, retained in expected.items():
        published = repository / relative
        if not published.is_file() or published.read_bytes() != retained.read_bytes():
            raise ValueError(f"Missing or changed Portal publisher output: {relative}")
    # Portal's Maven read view resolves the parent already published to Central.
    shutil.copytree(central, portal, dirs_exist_ok=True)
    for path in repository.rglob("*"):
        if path.is_file() and not path.name.startswith("maven-metadata"):
            destination = portal / path.relative_to(repository)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(path.read_bytes())
    (root() / "portal-registration").write_text(version)
    record("upload-portal", version=version)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["publish-portal"])
    parser.add_argument("repository")
    parser.add_argument("version")
    args = parser.parse_args()
    publish_portal(args.repository, args.version)
