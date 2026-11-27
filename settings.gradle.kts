/*
 * Copyright 2026 The Buildish Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

includeBuild("build-logic") { name = "buildish-atr-maven-bundle-build-logic" }

includeBuild("gradle/license-check-plugin") {
  name = "buildish-atr-maven-bundle-license-check-plugin"
}

include("gradle-plugin")

if (!JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_21)) {
  throw GradleException(
    """

        Build aborted...

        The Buildish ATR Maven Bundle build requires Java 21.
        Detected Java version: ${JavaVersion.current()}

        """,
  )
}

rootProject.name = "buildish-atr-maven-bundle"

@Suppress("UnstableApiUsage")
val baseVersion =
  providers.fileContents(layout.settingsDirectory.file("version.txt")).asText.map { it.trim() }

gradle.beforeProject {
  version = baseVersion.get()
  group = "org.buildish.atr-maven-bundle"
}

val ideImportActive =
  providers.systemProperty("idea.active").getOrElse("false").toBoolean() ||
    providers.systemProperty("buildship.active").getOrElse("false").toBoolean()

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}

pluginManagement { repositories { gradlePluginPortal() } }

plugins {
  if (
    providers.environmentVariable("CI").isPresent ||
      providers.systemProperty("allow-java-download").map(String::toBoolean).getOrElse(false)
  ) {
    // Enable automatic Java toolchain download in CI or when explicitly requested by the user.
    // If in doubt, install the required Java toolchain manually, preferably using a "proper"
    // package manager. The downside of letting Gradle automatically download toolchains is that
    // these will only get downloaded once, but not automatically updated.
    id("org.gradle.toolchains.foojay-resolver-convention") version ("1.0.0")
  }
}
