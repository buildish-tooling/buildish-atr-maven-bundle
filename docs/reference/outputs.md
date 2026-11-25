---
title: "Tasks and outputs"
description: "Choose archive or directory tasks and understand the generated layouts."
weight: 20
---

<!--
Copyright 2026 The Buildish Authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

Run these tasks from the root of your build:

| Format | Archive | Directory | Output location |
| --- | --- | --- | --- |
| Maven repository | `:mavenRepositoryZip` | `:mavenRepositoryFiles` | `build/maven-bundles/repository/` |
| Njord transportable | `:njordTransportableBundle` | `:njordTransportableFiles` | `build/maven-bundles/njord-transportable/` |

Output names use `<baseName>-<bundleVersion>`, with `.zip` for the Maven
repository archive and `.ntb` for the Njord transportable archive. For example,
`my-library` at version `1.0` gives
`build/maven-bundles/repository/my-library-1.0.zip`. Archive entries start at the
repository root. Internal exports and inventories stay outside the final output
directories. Directory tasks synchronize their output and remove stale files.

- Use the repository ZIP for the [Maven Central workflow](../../getting-started/).
  Repository-format outputs also preserve native timestamped snapshots and their
  version-directory metadata for local use. Central's release upload is for
  release versions.
- Use the `.ntb` archive for [Njord import](../../njord/). The format follows
  Njord 0.9.10 properties and artifact and metadata indexes. It accepts selections
  consisting entirely of releases or entirely of snapshots. For mixed
  selections use a repository-format output or exclude publications first.
  The directory form exposes the transportable tree for inspection.

Request multiple outputs together to share one preparation and signing pass:

```shell
./gradlew :mavenRepositoryZip :njordTransportableBundle
```

## Repeated builds

Native exports and shared preparation use declared Gradle inputs and outputs,
but upstream POM generation, publishing and signing can execute again. Build
caching and up-to-date skips are not guaranteed. Configuration-cache reuse
retains the configured task graph; it does not imply that artifact preparation
or signing will be skipped.

Each archive is written to an internal staging location before replacing its
final output file. Exploded output tasks synchronize their own destination tree,
including removal of stale files. Treat these destinations as generated outputs.

See [checksums and signing](../signing/) for the shared preparation policy and
[compatibility](../compatibility/) for reproducibility guarantees.
