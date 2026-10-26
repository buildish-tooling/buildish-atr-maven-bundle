---
title: "Compatibility and reproducibility"
description: "Runtime requirements, deterministic outputs and contributor verification."
weight: 40
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

For identical prepared bytes and options under the same Gradle/JDK/compression
settings, archives use stable order, fixed timestamps and uniform 0644/0755
file/directory modes. Exploded copies use `1980-02-01T00:00:00Z`, including output
directories, with those permissions where POSIX attributes are supported. Native
publication inputs and bytes inside published archives remain unchanged. Owner,
group, ACLs and creation/change times are outside this guarantee. Newly generated
signatures and upstream artifacts can vary independently.

The plugin targets Gradle **9.1+** and Java **17+**; this repository builds with
Gradle **9.8.2** and Java **21**. Java 17 classfiles/JVM attributes and a Kotlin
2.2-compatible API boundary are checked. Packaged Kotlin DSL consumers pass on
Linux with Gradle 9.1.0/Java 17 and Gradle 9.8.2/Java 21, with strict configuration
cache and isolated projects. The broader Gradle 9.8.2/Java 21 tests cover
Java/Kotlin/Groovy/BOM publications and real ephemeral software signing. Native
Windows execution remains unqualified at present. Non-POSIX filesystem tests do
not establish Windows runtime support. GPG/hardware signing and live
ATR/Central/Njord import are not covered by these tests.

## Building and qualification

Build this repository with Java 21 and Gradle 9.8.2. `check` includes unit tests,
strict packaged consumer tests and the Stutter `compatTest` suite. The sparse
lock pins exactly Gradle 9.1.0/Java 17 and Gradle 9.8.2/Java 21. Make both JDKs
available to Gradle toolchain discovery. Stutter selects each Gradle/JVM pair;
TestKit downloads and caches the selected Gradle distributions as needed.

```shell
./gradlew -Dorg.gradle.java.installations.auto-download=false check
```

To run only one pair, use `:gradle-plugin:compatTestJava17Gradle9.1.0` or
`:gradle-plugin:compatTestJava21Gradle9.8.2`. `:gradle-plugin:compatTest` aggregates
both. These are ordinary Gradle test tasks; tests publish only to disposable
local fixture repositories. The Java toolchain download flag above controls JDK
provisioning independently of TestKit's Gradle distribution cache.


## Continuous integration

The GitHub Actions CI workflow runs on pull requests, pushes to `main` and
`release/**`, and manual dispatch. Its Linux build installs Java 17 and 21, uses
Java 21 for the build, and runs `check`, including RAT, unit tests, packaged
functional tests and both Stutter compatibility pairs. A separate zizmor job
checks the workflow definitions. `Required Checks` requires Linux, Windows and
zizmor to succeed.

The repository build uses its configured configuration-cache and isolated-project
settings. Consumer fixtures independently require strict configuration-cache
storage/reuse and isolated projects.

### Windows qualification job

The independent Windows job uses `gradlew.bat` and Java 21 to build and run the
existing software-signing fixture through `:gradle-plugin:functionalTest`. The
fixture creates a consumer build in a path containing spaces and checks strict
configuration-cache storage/reuse and isolated projects. It uses the plugin
packaged by that native Windows build and its private local Maven repository.
There is no separate Gradle installation action or cross-platform test payload.

The fixture covers all three creators' archive/files outputs, signature checks,
one shared signing pass, configuration-cache reuse and fixed-byte reproducibility.
It generates ephemeral test keys and requires no release credentials. Windows
JUnit results and HTML reports are retained as CI artifacts. A successful native
Windows run supplies qualification evidence; defining this job or running the
fixture on Linux does not by itself qualify Windows.
