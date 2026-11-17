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


def sign_repository(source, destination, version):
    source, destination = Path(source), Path(destination)
    entries = files(source)
    if entries.keys() != primary_paths(version):
        raise ValueError("Unsigned repository differs from release contract")
    if len({path.name for path in entries.values()}) != len(entries):
        raise ValueError("Signing inputs have duplicate basenames")
    with tempfile.TemporaryDirectory() as temporary:
        # The supported CLI signs opaque files without running selected-source build code.
        subprocess.run(
            [
                "bash",
                str(ROOT / "release-tooling.sh"),
                "prepare-release-assets",
                "--output-dir",
                temporary,
                *map(str, entries.values()),
            ],
            check=True,
        )
        for name, source_path in entries.items():
            target = destination / name
            target.parent.mkdir(parents=True, exist_ok=True)
            for suffix in ("", ".asc", ".sha256", ".sha512"):
                shutil.copyfile(
                    Path(temporary) / (source_path.name + suffix),
                    Path(str(target) + suffix),
                )


def pack(source, archive, version):
    entries = files(source)
    expected = {
        name + suffix
        for name in primary_paths(version)
        for suffix in ("", ".asc", ".sha256", ".sha512")
    }
    if entries.keys() != expected:
        raise ValueError("Signed repository differs from release contract")
    Path(archive).parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive, "x", compression=zipfile.ZIP_DEFLATED) as output_zip:
        for name, path in entries.items():
            info = zipfile.ZipInfo(name, (1980, 2, 1, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            output_zip.writestr(info, path.read_bytes())


def unpack(archive, destination, version):
    destination = Path(destination)
    expected = {
        name + suffix
        for name in primary_paths(version)
        for suffix in ("", ".asc", ".sha256", ".sha512")
    }
    with zipfile.ZipFile(archive) as contents:
        if (
            len(contents.namelist()) != len(expected)
            or set(contents.namelist()) != expected
        ):
            raise ValueError("Maven ZIP inventory differs from contract")
        for item in contents.infolist():
            name = PurePosixPath(item.filename)
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
    for name in primary_paths(version):
        check_digest(
            destination / name,
            (destination / (name + ".sha256")).read_text().split()[0],
        )


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
            "sign",
            "pack",
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
        "sign": sign_repository,
        "pack": pack,
        "unpack": unpack,
        "download": download,
        "fetch-tag": fetch_tag,
    }[args.command](*args.args)


if __name__ == "__main__":
    main()
