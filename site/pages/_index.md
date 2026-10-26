---
title: "ATR Maven Bundle for Gradle®"
description: "Create local Maven publication bundles for ATR, Maven Central and Njord."
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

Buildish ATR Maven Bundle collects your Gradle build's Maven publications into
local bundles for review and later upload. Choose a Maven repository ZIP, a
Njord transportable bundle, an exploded directory, or several outputs together.
Checksums and optional signatures are prepared once for all requested outputs.

The plugin uses Gradle's native Maven publishing to retain generated POMs,
Gradle module metadata, custom artifacts, BOMs and plugin marker publications.
Projects without Maven publications contribute no bundle files.

## Start using the plugin

The plugin targets **Gradle 9.1 or newer** and **Java 17 or newer**. Apply it in
`settings.gradle.kts` or `settings.gradle`, configure your normal Maven
publications, and select a root task:

```shell
./gradlew :atrCentralArchive
./gradlew :atrNjordTransportableArchive
```

Signature presence is required by default. You can preserve existing signatures,
configure generation of missing signatures, or explicitly allow unsigned output.
The [unreleased getting-started guide](development/getting-started/) covers setup
and a first bundle, including an unsigned local trial.

## Choose a bundle

| Output | Use |
| --- | --- |
| Maven Central | A ZIP or directory containing files in Maven repository layout. |
| Njord repository | The same Maven repository layout, for a Njord-oriented workflow. |
| Njord transportable | An NTB archive or directory with repository files and Njord indexes. |

The plugin creates local files. Uploading, remote admission checks and release
orchestration remain separate steps in your release process.

## Development documentation

These pages describe the unreleased development version:

- [Getting started](development/getting-started/)
- [Configuration and publication selection](development/reference/configuration/)
- [Tasks, layouts and output paths](development/reference/outputs/)
- [Checksums and signing](development/reference/signing/)
- [Compatibility and reproducibility](development/reference/compatibility/)
