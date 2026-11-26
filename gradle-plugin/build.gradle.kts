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

import org.gradle.plugin.compatibility.compatibility
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.plugin.publish)
  alias(libs.plugins.stutter)
  id("com.gradleup.shadow")
  id("atr-maven-bundle-java")
  id("atr-maven-bundle-publishing")
}

val readableName = "Buildish Maven Bundles for Gradle"

description =
  "Build local Maven Central bundles from Gradle Maven publications for manual upload. " +
    "Apply in settings.gradle.kts or settings.gradle. Also exports Njord transportable bundles (.ntb). " +
    "Requires Gradle 9.1+ and Java 17+."

val repoUrl = "https://buildish.org/components/atr-maven-bundle"

gradlePlugin {
  website = repoUrl
  vcsUrl = "https://github.com/buildish-tooling/buildish-atr-maven-bundle"
  plugins {
    create("atrMavenBundle") {
      id = "org.buildish.atr-maven-bundle.gradle-plugin"
      displayName = readableName
      implementationClass = "org.buildish.atrmavenbundle.gradleplugin.AtrMavenBundlePlugin"
      description = project.description
      tags.addAll("maven-central", "maven-publish", "publishing", "njord", "settings")
      compatibility {
        features {
          configurationCache = true
          isolatedProjects = true
        }
      }
    }
  }
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach { options.release = 17 }

kotlin {
  compilerOptions {
    jvmTarget = JvmTarget.JVM_17
    languageVersion = KotlinVersion.KOTLIN_2_2
    apiVersion = KotlinVersion.KOTLIN_2_2
  }
}

dependencies {
  // Gradle 9.1 supplies Kotlin 2.2.0. Do not ship a newer, relocated Kotlin runtime.
  compileOnly(kotlin("stdlib", "2.2.0"))
  testImplementation(gradleTestKit())
  testImplementation(platform(libs.junit.bom))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation(libs.assertj.core)
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Test fixtures are internal test support, not part of the plugin's publication API.
(components["java"] as AdhocComponentWithVariants).apply {
  withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
  withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
}

publishing {
  repositories {
    maven {
      name = "functionalTest"
      url = layout.settingsDirectory.dir("build/functional-test-repository").asFile.toURI()
    }
  }
}

stutter {
  sparse.set(true)
  matrices {
    create("java17") {
      javaToolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
      gradleVersions { compatible("9.1.0") }
    }
    create("java21") {
      javaToolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
      gradleVersions { compatible("9.8.2") }
    }
  }
}

/** Lets a TestKit test task consume the plugin published to the functional-test repository. */
fun Test.usePackagedPlugin(withGradleInstallation: Boolean) {
  val repository = layout.settingsDirectory.dir("build/functional-test-repository")
  dependsOn(
    ":publishMavenJavaPublicationToFunctionalTestRepository",
    "publishPluginMavenPublicationToFunctionalTestRepository",
    "publishAtrMavenBundlePluginMarkerMavenPublicationToFunctionalTestRepository",
  )
  // Publishing a -SNAPSHOT writes timestamped file names and maven-metadata.xml files, which made
  // the cache key unique on every run. The remaining files are reproducible, so fingerprint only
  // their contents and leave the version to the `plugin.version` input.
  inputs
    .files(fileTree(repository) { exclude("**/maven-metadata.xml*") })
    .withPathSensitivity(PathSensitivity.NONE)
    .withPropertyName("functionalTestRepository")
  jvmArgumentProviders.add(
    objects.newInstance<PackagedPluginTestArguments>().apply {
      this.repository = repository
      pluginVersion = project.version.toString()
      shadedJar = tasks.shadowJar.flatMap { it.archiveFile }
      gradleUserHome = gradle.gradleUserHomeDir.absolutePath
      if (withGradleInstallation) {
        gradleInstallation = gradle.gradleHomeDir!!.absolutePath
      }
    },
  )
}

testing {
  suites {
    @Suppress("UnstableApiUsage") named<JvmTestSuite>("test") { useJUnitJupiter() }

    @Suppress("UnstableApiUsage")
    named<JvmTestSuite>("compatTest") {
      useJUnitJupiter(libs.junit.bom.get().version!!)
      dependencies {
        implementation(gradleTestKit())
        implementation(libs.assertj.core)
      }
      targets.all {
        testTask.configure {
          usePackagedPlugin(withGradleInstallation = false)
        }
      }
    }

    @Suppress("UnstableApiUsage")
    register<JvmTestSuite>("functionalTest") {
      useJUnitJupiter(libs.junit.bom.get().version!!)
      dependencies {
        implementation(gradleTestKit())
        implementation(libs.assertj.core)
        // Real signing fixture uses the signing libraries already supplied by this Gradle.
        implementation(
          fileTree(gradle.gradleHomeDir!!.resolve("lib/plugins")) {
            include("bcpg-*.jar", "bcprov-*.jar", "bcutil-*.jar")
          },
        )
      }
      targets.all {
        testTask.configure {
          usePackagedPlugin(withGradleInstallation = true)
          shouldRunAfter(tasks.named("test"))
        }
      }
    }
  }
}

tasks {
  matching {
    it.name == "publishPluginMavenPublicationToFunctionalTestRepository" ||
      it.name == "publishAtrMavenBundlePluginMarkerMavenPublicationToFunctionalTestRepository"
  }
    .configureEach { dependsOn(":resetFunctionalTestRepository") }

  named("check") {
    @Suppress("UnstableApiUsage")
    dependsOn(testing.suites.named("functionalTest"), testing.suites.named("compatTest"))
  }

  shadowJar {
    exclude("META-INF/*.kotlin_module")
    enableAutoRelocation = true
    relocationPrefix = "org.buildish.atrmavenbundle.gradleplugin.shadow"
    manifest { attributes["Implementation-Version"] = project.version }
    archiveClassifier = ""
    outputs.cacheIf { false } // do not cache uber/shaded jars
  }

  jar.configure {
    dependsOn(shadowJar)
    archiveClassifier = "unshaded"
  }
}
