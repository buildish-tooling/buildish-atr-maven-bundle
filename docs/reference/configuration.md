---
title: "Configuration"
description: "Configure bundle naming, publication exclusions and signature policy."
weight: 10
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

Apply the plugin in `settings.gradle.kts`, using an available plugin version and
its repository. It is a settings plugin; applying it to a project fails.

```kotlin
import org.buildish.atrmavenbundle.gradleplugin.AtrMavenBundleExtension

plugins {
    id("org.buildish.atr-maven-bundle.gradle-plugin") version "<version>"
}

extensions.configure<AtrMavenBundleExtension>("atrMavenBundle") {
    // Defaults: requireSignatures=true, signMissing=false.
    // Optional; otherwise use the root project's final name/version.
    baseName.set("my-release")
    bundleVersion.set("1.0")

    excludeProject(":internal")
    excludePublication(":library", "experimental")
}
```

The equivalent Groovy settings extension is `atrMavenBundle { ... }`. Exclusions
use exact absolute Gradle project paths and exact publication names; a project
exclusion does not recursively exclude its children. Settings are frozen after
settings evaluation.

The root project's publications are eligible too. Native Gradle plugin marker
POMs, customized POMs, custom artifacts, platform/BOMs and emitted Gradle module
metadata retain their native publishing behavior. Projects without selected
Maven publications contribute nothing. Required unpublished project dependencies
may still compile; they acquire no publication or bundle payload merely by being
dependencies. The plugin publishes each selected publication to its own local
export repository and does not invoke broad `publish`, `publishToMavenLocal`, or
user-configured publishing destinations.

## Settings and defaults

| Setting | Default | Meaning |
| --- | --- | --- |
| `requireSignatures` | `true` | Require a nonempty signature for every primary publication file. |
| `signMissing` | `false` | Generate absent primary signatures using the root signatory. |
| `baseName` | Root project's final name | Base name used in archive and directory names. |
| `bundleVersion` | Root project's final version | Version used in archive and directory names. |

Bundle naming does not change the Maven coordinates or version of a publication.
Snapshot publications are eligible too. Selecting an output task chooses a
creator and archive/files variant; there is no separate creator-selection DSL.

## Policy overrides

The effective Boolean policy uses this precedence:

1. Explicit option on `:prepareAtrMavenBundle`.
2. Gradle project property.
3. Settings extension value or its default.

The project properties are `atrMavenBundle.requireSignatures` and
`atrMavenBundle.signMissing`. Each accepts only `true` or `false`, ignoring case.

```shell
./gradlew -PatrMavenBundle.requireSignatures=false :atrCentralArchive
./gradlew :prepareAtrMavenBundle --no-require-signatures --no-sign-missing :atrCentralArchive
```

The preparation options are `--require-signatures`, `--no-require-signatures`,
`--sign-missing` and `--no-sign-missing`. Place them immediately after the
preparation task. Output tasks and aliases do not accept these options.

See [checksums and signing](../signing/) for how presence checks and generation
interact, and [outputs](../outputs/) for task names and paths.
