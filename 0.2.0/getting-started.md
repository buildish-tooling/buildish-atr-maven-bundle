---
title: "Create a Maven Central bundle from Gradle"
description: "Apply the settings plugin, create a local repository ZIP, and prepare a signed Maven Central bundle."
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

Use **Gradle 9.1+** and **Java 17+**. This example uses version **0.2.0** of
Buildish Maven Bundles for Gradle.

## Apply the settings plugin

Add this to `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.buildish.atr-maven-bundle.gradle-plugin") version "0.2.0"
}

rootProject.name = "my-library"
```

In a Groovy build, add this to `settings.gradle`:

```groovy
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id 'org.buildish.atr-maven-bundle.gradle-plugin' version '0.2.0'
}

rootProject.name = 'my-library'
```

If your settings already declare plugin repositories, add `mavenCentral()` to
that list. Keep the `maven-publish` plugin and your publication configuration in
each publishing project's build script.

## Create your first ZIP

Use your existing Maven publications or try this small Java library example in
`build.gradle.kts`:

```kotlin
plugins {
    `java-library`
    `maven-publish`
}

group = "org.example"
version = "1.0"

publishing {
    publications {
        create<MavenPublication>("main") {
            from(components["java"])
        }
    }
}
```

Publications in all projects are included by default. See
[publication selection](../reference/configuration/) if you need exclusions.

Run an unsigned local trial:

```shell
./gradlew -PatrMavenBundle.requireSignatures=false :mavenRepositoryZip
```

With the example name and version, open
`build/maven-bundles/repository/my-library-1.0.zip`. It contains the publication
files and checksums in Maven repository layout.

## Prepare a signed bundle for Maven Central

For a Java library release, use a namespace you control and configure sources,
Javadoc, and the required POM metadata. For the trial project, add the following
to `build.gradle.kts` and replace the example project details with your own:

```kotlin
java {
    withSourcesJar()
    withJavadocJar()
}

publishing.publications.named<MavenPublication>("main") {
    pom {
        name.set("My Library")
        description.set("A description of the library.")
        url.set("https://github.com/example/my-library")
        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set("maintainer")
                name.set("Library Maintainer")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/example/my-library.git")
            developerConnection.set("scm:git:ssh://git@github.com/example/my-library.git")
            url.set("https://github.com/example/my-library")
        }
    }
}
```

Choose a signing path:

- If Gradle already signs your publications, run `./gradlew :mavenRepositoryZip`.
  The plugin preserves those signatures and checks that the required files have one.
- To generate missing signatures, add `signing` to the root build's existing
  `plugins` block and [configure the signer](../reference/signing/#root-signing).
  Then run `./gradlew :prepareMavenBundle --sign-missing :mavenRepositoryZip`.

Inspect the resulting ZIP and follow [Maven Central's requirements](https://central.sonatype.org/publish/requirements/)
and [bundle upload instructions](https://central.sonatype.org/publish/publish-portal-upload/).
For a Njord import, see [Export a Njord transportable bundle](../njord/).
