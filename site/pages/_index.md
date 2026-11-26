---
title: "Maven Bundles for Gradle"
description: "Create a local Maven Central bundle ZIP from Gradle Maven publications or export a Njord transportable bundle."
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

Package existing Gradle Maven publications into a local Maven repository ZIP.
Review the ZIP locally, then upload it through the Maven Central Publisher Portal.
The plugin uses your existing `maven-publish` configuration. It requires
**Gradle 9.1+** and **Java 17+**.

> **Maven Bundles for Gradle {{< buildish-component-version >}} is released.** Use the `v{{< buildish-component-version >}}` tag for executable GitHub Actions. See the [release notes](releases/{{< buildish-component-version >}}/release-notes/).

---

## Maven Central

Apply the settings plugin in `settings.gradle.kts` or `settings.gradle`.
Follow the [released quickstart](releases/{{< buildish-component-version >}}/getting-started/)
to create your first ZIP and prepare a signed bundle for upload.

## Njord

Export a Njord Transportable Bundle (`.ntb`) when you want to import your
publications into Njord. See the [released output guide](releases/{{< buildish-component-version >}}/reference/outputs/)
for the task and file path.

## Apache Trusted Releases

Apache Trusted Releases (ATR) handles Apache release workflows. Create a local
Maven repository ZIP when preparing a Maven Central distribution. Use ATR's own
steps to [upload release files](https://releases.apache.org/docs/uploading-files)
and [record the distribution during the finish phase](https://releases.apache.org/docs/finish-phase).

## Released documentation

- [Configuration and publication selection](releases/{{< buildish-component-version >}}/reference/configuration/)
- [Tasks and outputs](releases/{{< buildish-component-version >}}/reference/outputs/)
- [Checksums and signing](releases/{{< buildish-component-version >}}/reference/signing/)
- [Compatibility and reproducibility](releases/{{< buildish-component-version >}}/reference/compatibility/)
