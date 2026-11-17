#!/usr/bin/env bash
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

set -euo pipefail
repository="$1"
version="$2"
consumer="$3"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
mkdir -p "$consumer"
# repository is either a retained file:// repository or one exact public Maven URL.
cat > "$consumer/settings.gradle.kts" <<EOF
pluginManagement { repositories { maven { url = uri("$repository") } } }
plugins { id("org.buildish.atr-maven-bundle.gradle-plugin") version "$version" }
rootProject.name = "release-consumer"
EOF
cat > "$consumer/build.gradle.kts" <<'EOF'
plugins { java; `maven-publish` }
group = "org.example"
version = "1.0"
publishing { publications { create<MavenPublication>("main") { from(components["java"]) } } }
EOF
"${BUILDISH_GRADLE:-$PWD/gradlew}" --no-daemon --console=plain -p "$consumer" \
  -PatrMavenBundle.requireSignatures=false :atrCentralArchive
python3 - "$consumer" <<'PY'
from pathlib import Path
import sys
import zipfile
archives = list(Path(sys.argv[1]).glob('build/atr-maven-bundle/central/*.zip'))
assert len(archives) == 1, archives
with zipfile.ZipFile(archives[0]) as archive:
    assert 'org/example/release-consumer/1.0/release-consumer-1.0.pom' in archive.namelist()
    assert 'org/example/release-consumer/1.0/release-consumer-1.0.jar' in archive.namelist()
PY
