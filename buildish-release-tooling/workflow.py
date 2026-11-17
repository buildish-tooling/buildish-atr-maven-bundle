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

"""Component packaging and byte handoffs; lifecycle operations belong to the CLI."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parent
REPOSITORY = "buildish-tooling/buildish-atr-maven-bundle"
VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
PLUGIN = "org.buildish.atr-maven-bundle.gradle-plugin"
GROUP = "org/buildish/atr-maven-bundle"
MAVEN_CHECKSUMS = ("md5", "sha1", "sha256", "sha512")


def read(path):
    return json.loads(Path(path).read_text())


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def check_digest(path, expected):
    if not re.fullmatch(r"[0-9a-f]{64}", expected) or digest(path) != expected:
        raise ValueError(f"Producer digest mismatch: {path}")


def output(name, value):
    with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
        stream.write(f"{name}={value}\n")


def files(directory):
    directory = Path(directory)
    result = {}
    for path in sorted(directory.rglob("*")):
        if path.is_symlink():
            raise ValueError(f"Symlink in release transport: {path}")
        if path.is_file():
            result[path.relative_to(directory).as_posix()] = path
    return result


def seal(directory):
    directory = Path(directory)
    entries = {
        name: {"sha256": digest(path), "size": path.stat().st_size}
        for name, path in files(directory).items()
        if name != "transport.json"
    }
    if not entries:
        raise ValueError("Empty transport")
    write(directory / "transport.json", entries)
    output("bundle_digest", digest(directory / "transport.json"))


def verify(directory, expected):
    directory = Path(directory)
    check_digest(directory / "transport.json", expected)
    entries = read(directory / "transport.json")
    actual = files(directory)
    actual.pop("transport.json")
    if not entries or entries.keys() != actual.keys():
        raise ValueError("Transport contains missing or unexpected files")
    for name, record in entries.items():
        check_digest(actual[name], record["sha256"])
        if actual[name].stat().st_size != record["size"]:
            raise ValueError(f"Transport size mismatch: {name}")


def preflight(mode):
    version = os.environ["RELEASE_VERSION"]
    if not re.fullmatch(VERSION, version):
        raise ValueError("Expected stable X.Y.Z version")
    if (
        os.environ.get("GITHUB_REPOSITORY") != REPOSITORY
        or os.environ.get("GITHUB_REF") != "refs/heads/main"
    ):
        raise ValueError("Dispatch the trusted main workflow in this repository")
    if mode == "promotion":
        if not re.fullmatch(
            rf"v{re.escape(version)}-rc[1-9][0-9]*", os.environ["CANDIDATE_TAG"]
        ):
            raise ValueError("Candidate must match the exact requested version")
        if not re.fullmatch(r"[0-9a-f]{64}", os.environ["CANDIDATE_MANIFEST_DIGEST"]):
            raise ValueError("Independently retained candidate digest required")


def state_outputs(path):
    state = read(path)
    output("source_commit", state["source"]["commit_sha"])
    output("state_digest", digest(path))
    if "candidate" in state:
        output("candidate_tag", state["candidate"]["tag"]["name"])


def primary_paths(version):
    if not re.fullmatch(VERSION, version):
        raise ValueError("Expected stable version")
    base = f"{GROUP}/gradle-plugin/{version}/gradle-plugin-{version}"
    parent = f"{GROUP}/buildish-atr-maven-bundle/{version}/buildish-atr-maven-bundle-{version}.pom"
    marker = f"{PLUGIN.replace('.', '/')}/{PLUGIN}.gradle.plugin/{version}/{PLUGIN}.gradle.plugin-{version}.pom"
    return {
        parent,
        marker,
        *(
            base + suffix
            for suffix in (".pom", ".module", ".jar", "-sources.jar", "-javadoc.jar")
        ),
    }


def archive_name(version):
    primary_paths(version)
    return f"buildish-atr-maven-bundle-{version}-maven.zip"


def asset_names(version):
    return {
        archive_name(version) + suffix for suffix in ("", ".asc", ".sha256", ".sha512")
    }


def signed_paths(version):
    return {
        name + suffix
        for name in primary_paths(version)
        for suffix in ("", ".asc", *("." + algorithm for algorithm in MAVEN_CHECKSUMS))
    }


def validate_maven_checksums(repository, version):
    repository = Path(repository)
    for name in primary_paths(version):
        data = (repository / name).read_bytes()
        for algorithm in MAVEN_CHECKSUMS:
            sidecar = repository / (name + "." + algorithm)
            if (
                sidecar.read_text(encoding="ascii").strip()
                != hashlib.new(algorithm, data).hexdigest()
            ):
                raise ValueError(f"Invalid Maven {algorithm} checksum: {name}")


def export_repository(source, destination, version):
    source, destination = Path(source), Path(destination)
    expected = primary_paths(version)
    actual = files(source)
    # Native Maven writer adds mutable discovery metadata and checksum sidecars.
    # Keep exactly this component's immutable version payload, never repository metadata.
    primary = {
        name
        for name in actual
        if not name.endswith((".md5", ".sha1", ".sha256", ".sha512"))
        and not name.endswith("maven-metadata.xml")
    }
    if primary != expected:
        raise ValueError(
            f"Maven publication set differs: missing={expected - primary}, extra={primary - expected}"
        )
    for name in sorted(expected):
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(actual[name], target)


def verify_bundle(repository, archive, version, fingerprint=""):
    """Check native packaging preserved payloads and signed with the approved key."""
    with tempfile.TemporaryDirectory() as temporary:
        temporary = Path(temporary)
        unpack(archive, temporary / "repository", version)
        keyring = temporary / "keyring"
        keyring.mkdir(mode=0o700)
        gpg = ["gpg", "--no-options", "--homedir", str(keyring), "--batch"]
        try:
            subprocess.run(
                [*gpg, "--import"],
                input=os.environ["BUILDISH_GPG_PRIVATE_KEY"].encode(),
                check=True,
                capture_output=True,
            )
            for name in primary_paths(version):
                retained = Path(repository) / name
                packaged = temporary / "repository" / name
                if retained.read_bytes() != packaged.read_bytes():
                    raise ValueError(f"Bundling changed retained primary file: {name}")
                result = subprocess.run(
                    [
                        *gpg,
                        "--status-fd=1",
                        "--verify",
                        str(packaged) + ".asc",
                        str(packaged),
                    ],
                    check=True,
                    capture_output=True,
                    text=True,
                )
                signatures = [
                    line.split()[2:]
                    for line in result.stdout.splitlines()
                    if line.startswith("[GNUPG:] VALIDSIG ")
                ]
                if len(signatures) != 1 or (
                    fingerprint
                    and fingerprint.upper() not in (signatures[0][0], signatures[0][-1])
                ):
                    raise ValueError(f"Unexpected release signing fingerprint: {name}")
        finally:
            subprocess.run(
                ["gpgconf", "--homedir", str(keyring), "--kill", "all"],
                check=False,
                capture_output=True,
            )


def unpack(archive, destination, version):
    destination = Path(destination)
    expected = signed_paths(version)
    with zipfile.ZipFile(archive) as contents:
        entries = [item for item in contents.infolist() if not item.is_dir()]
        if (
            len(entries) != len(expected)
            or {item.filename for item in entries} != expected
        ):
            raise ValueError("Maven ZIP inventory differs from contract")
        for item in contents.infolist():
            name = PurePosixPath(item.filename)
            if item.is_dir():
                if (
                    name.is_absolute()
                    or ".." in name.parts
                    or not any(name in PurePosixPath(path).parents for path in expected)
                ):
                    raise ValueError("Unexpected Maven ZIP directory")
                continue
            if (
                name.is_absolute()
                or ".." in name.parts
                or (item.external_attr >> 16) & 0o170000 != 0o100000
            ):
                raise ValueError("Non-regular or unsafe Maven ZIP entry")
            path = destination / name
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("xb") as stream:
                stream.write(contents.read(item))
    validate_maven_checksums(destination, version)


def download(manifest, expected, destination, version):
    check_digest(manifest, expected)
    payload = read(manifest)
    tag = payload["candidate"]["tag"]["name"]
    if tag != os.environ["CANDIDATE_TAG"] or payload["release"]["version"] != version:
        raise ValueError("Candidate identity differs from approval")
    assets = payload["artifacts"]
    if len(assets) != len(asset_names(version)) or {
        a["logical_name"] for a in assets
    } != asset_names(version):
        raise ValueError("Candidate assets differ from component contract")
    Path(destination).mkdir()
    for asset in assets:
        name = asset["logical_name"]
        subprocess.run(
            [
                "gh",
                "release",
                "download",
                tag,
                "--repo",
                REPOSITORY,
                "--pattern",
                name,
                "--dir",
                str(destination),
            ],
            check=True,
        )
        path = Path(destination) / name
        check_digest(path, asset["digests"]["sha256"])
        if path.stat().st_size != asset["size_bytes"]:
            raise ValueError("Candidate asset size differs")


def fetch_tag(state_path, expected):
    check_digest(state_path, expected)
    state = read(state_path)
    tag = state["final_tag"]["name"]
    if not re.fullmatch(rf"v{VERSION}", tag):
        raise ValueError("Unexpected final tag")
    subprocess.run(
        [
            "git",
            "fetch",
            "--no-tags",
            "--depth=1",
            "origin",
            f"refs/tags/{tag}:refs/tags/{tag}",
        ],
        check=True,
    )
    observed = subprocess.check_output(
        ["git", "rev-parse", f"refs/tags/{tag}^{{commit}}"], text=True
    ).strip()
    if observed != state["source"]["commit_sha"]:
        raise ValueError("Final tag differs from approved source")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=(
            "preflight",
            "outputs",
            "seal",
            "verify",
            "digest",
            "export",
            "verify-bundle",
            "unpack",
            "download",
            "fetch-tag",
        ),
    )
    parser.add_argument("args", nargs="*")
    args = parser.parse_args()
    {
        "preflight": preflight,
        "outputs": state_outputs,
        "seal": seal,
        "verify": verify,
        "digest": check_digest,
        "export": export_repository,
        "verify-bundle": verify_bundle,
        "unpack": unpack,
        "download": download,
        "fetch-tag": fetch_tag,
    }[args.command](*args.args)


if __name__ == "__main__":
    main()
