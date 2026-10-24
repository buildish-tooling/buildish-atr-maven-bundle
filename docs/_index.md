---
title: "ATR Maven Bundle documentation"
description: "Configure and use the ATR Maven Bundle settings plugin."
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

Create local bundles from the Maven publications in your Gradle build. The
plugin shares collection, checksum completion and optional signing across all
requested output tasks.

These documents describe the development version. Start with the
[getting-started guide](getting-started/), then use the [reference](reference/)
for individual settings and tasks.

## Guides and reference

- [Getting started](getting-started/): apply the settings plugin and create a first bundle.
- [Configuration](reference/configuration/): defaults, exclusions and policy precedence.
- [Outputs](reference/outputs/): task names, bundle layouts and output paths.
- [Checksums and signing](reference/signing/): reuse existing sidecars or generate missing ones.
- [Compatibility](reference/compatibility/): supported runtimes, reproducibility and build checks.
- [Security threat model](threat-model/): trust boundaries, controls and responsibilities.
