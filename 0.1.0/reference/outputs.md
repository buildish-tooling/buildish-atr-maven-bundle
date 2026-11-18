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

Run these root tasks from your consumer build:

| Layout | Archive | Exploded files | Archive alias |
| --- | --- | --- | --- |
| Maven Central | `:atrCentralArchive` | `:atrCentralFiles` | `:atrCentral` |
| Njord repository | `:atrNjordArchive` | `:atrNjordFiles` | `:atrNjord` |
| Njord transportable | `:atrNjordTransportableArchive` | `:atrNjordTransportableFiles` | `:atrNjordTransportable` |

`:atrMavenBundle` aliases `:atrNjordArchive`. Request several concrete tasks to
produce several layouts or both archive and files; they share one
`:prepareAtrMavenBundle` execution.

```shell
./gradlew :atrCentralArchive :atrNjordArchive :atrNjordTransportableFiles
```

With the default root build directory, outputs are
`build/atr-maven-bundle/<creator-id>/<baseName>-<bundleVersion>`
directories, or the same paths with `.zip` (`central`, `njord`) or `.ntb`
(`njord-transportable`). Archive entries start at the repository root, without
an extra directory prefix. Names must be nonempty filename components. Internal
exports and inventories stay outside final output directories. Files outputs
synchronize owned trees and remove stale contents.

Central and Njord currently share the Maven repository layout. Snapshot
publications keep native timestamped artifact filenames and version-directory
metadata. Their inclusion is not a claim that Central's release-upload service
accepts snapshots. NTB follows Njord **0.9.10** properties and artifact/metadata
indexes: pure releases and pure snapshots work; a mixed selection fails only
for NTB. Exclude publications or use repository-layout outputs for a mixed tree.
NTB files are an exploded transportable tree, not a claim that an importer
accepts a directory.

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
