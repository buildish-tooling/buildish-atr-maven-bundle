---
title: "Getting started"
description: "Apply the settings plugin and create your first Maven publication bundle."
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

Use **Gradle 9.1+** and **Java 17+** in the build consuming this plugin.

## Apply the settings plugin

In `settings.gradle.kts`, choose a plugin version available from your configured
plugin repositories. Replace `<version>` with that version:

```kotlin
plugins {
    id("org.buildish.atr-maven-bundle.gradle-plugin") version "<version>"
}
rootProject.name = "my-library"
```

For Groovy settings, use `settings.gradle`:

```groovy
plugins {
    id 'org.buildish.atr-maven-bundle.gradle-plugin' version '<version>'
}
rootProject.name = 'my-library'
```

Apply this plugin in settings. The `maven-publish` plugin and publication
configuration belong in each publishing project's build script.

## Configure a Maven publication

For example, a Java library's `build.gradle.kts` can contain:

```kotlin
plugins {
    `java-library`
    `maven-publish`
}
group = "org.example"
version = "1.0"
publishing {
    publications {
        create<MavenPublication>("main") { from(components["java"]) }
    }
}
```

Each actual Maven publication is included automatically, including publications
on the root project. Projects without Maven publications need no opt-out. See
[publication selection](../reference/configuration/) for exclusions.

## Create a first bundle

For an unsigned local trial, disable the default signature-presence requirement:

```shell
./gradlew -PatrMavenBundle.requireSignatures=false :atrCentralArchive
```

With the example name and version, this creates
`build/atr-maven-bundle/central/my-library-1.0.zip`. The ZIP starts at the Maven
repository root and contains the native publication files plus their checksums.
The plugin uses private local export repositories; this command does not publish
to your configured remote repositories or Maven local.

For a release with existing signatures, retain the default presence check:

```shell
./gradlew :atrCentralArchive
```

To generate missing signatures, first configure [root signing](../reference/signing/),
then run:

```shell
./gradlew :prepareAtrMavenBundle --sign-missing :atrCentralArchive
```

## Select other outputs

Request several tasks together to share one preparation and signing pass:

```shell
./gradlew :atrCentralArchive :atrNjordArchive :atrNjordTransportableFiles
```

The [output reference](../reference/outputs/) describes every task and layout.
Producing a bundle does not upload it or establish acceptance by a remote service.
