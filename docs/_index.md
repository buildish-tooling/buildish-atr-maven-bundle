---
title: "Buildish Maven Bundles for Gradle documentation"
description: "Create a Maven repository ZIP or a Njord transportable bundle from Gradle."
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

Turn your existing Gradle Maven publications into a local Maven repository ZIP
for Maven Central or an `.ntb` for Njord import. Use the version navigation to
choose the documentation for the plugin release you use.

## Guides and reference

- [Create a Maven Central bundle from Gradle](getting-started/)
- [Export a Njord transportable bundle](njord/)
- [Configuration](reference/configuration/): defaults, exclusions and policy precedence.
- [Outputs](reference/outputs/): task names, bundle layouts and output paths.
- [Checksums and signing](reference/signing/): reuse existing sidecars or generate missing ones.
- [Compatibility](reference/compatibility/): supported runtimes, reproducibility and build checks.
- [Security threat model](threat-model/): trust boundaries, controls and responsibilities.
