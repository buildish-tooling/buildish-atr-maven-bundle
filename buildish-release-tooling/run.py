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

"""Invoke the installed console command, never a sibling checkout or internal API."""

import importlib.metadata as metadata
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def main():
    distribution = metadata.distribution("buildish-release-tooling")
    if distribution.version != "0.1.4":
        raise ValueError(
            f"Expected buildish-release-tooling==0.1.4, found {distribution.version}"
        )
    direct = json.loads(distribution.read_text("direct_url.json") or "{}")
    if direct.get("dir_info", {}).get("editable") or not distribution.read_text(
        "WHEEL"
    ):
        raise ValueError("Use a published wheel installation")
    command = Path(sys.executable).parent / "buildish-release-tooling"
    if sys.argv[1:] == ["--package-info"]:
        print(
            json.dumps(
                {
                    "version": distribution.version,
                    "command": str(command),
                    "distribution": str(distribution.locate_file("")),
                    "requires_python": distribution.metadata["Requires-Python"],
                }
            )
        )
        return
    if len(sys.argv) < 2:
        raise ValueError("Expected a release CLI command")
    if os.environ.get("BUILDISH_RELEASE_TOOLING_DIR"):
        raise ValueError("Source-checkout overrides are not supported")
    # Reject argparse abbreviations as well as the full configuration option.
    if any(
        arg.startswith("--") and "--component-config".startswith(arg.split("=", 1)[0])
        for arg in sys.argv[2:]
    ):
        raise ValueError("The launcher supplies the component configuration")
    config = ROOT / "release-config.yaml"
    os.execv(
        sys.executable,
        [
            sys.executable,
            "-I",
            str(command),
            sys.argv[1],
            "--component-config",
            str(config),
            *sys.argv[2:],
        ],
    )


if __name__ == "__main__":
    try:
        main()
    except (ValueError, metadata.PackageNotFoundError, OSError) as error:
        sys.exit(f"release-tooling: {error}")
