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

val readableName = "Buildish ATR Maven Bundle Plugin"

description = readableName

val repoUrl = "https://buildish.org/components/atr-maven-bundle"

gradlePlugin {
  website = repoUrl
  vcsUrl = repoUrl
  plugins {
    create("atrMavenBundle") {
      id = "org.buildish.atr-maven-bundle.gradle-plugin"
      displayName = readableName
      implementationClass = "org.buildish.atrmavenbundle.gradleplugin.AtrMavenBundlePlugin"
      description = project.description
      tags.addAll("publishing", "maven", "atr", "apache")
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
          dependsOn(
            ":publishMavenJavaPublicationToFunctionalTestRepository",
            "publishPluginMavenPublicationToFunctionalTestRepository",
            "publishAtrMavenBundlePluginMarkerMavenPublicationToFunctionalTestRepository",
          )
          inputs.dir(layout.settingsDirectory.dir("build/functional-test-repository"))
          systemProperty(
            "plugin.repository",
            layout.settingsDirectory.dir("build/functional-test-repository").asFile.absolutePath,
          )
          systemProperty("plugin.version", project.version.toString())
          systemProperty(
            "plugin.shaded.jar",
            tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }.get().asFile.absolutePath,
          )
          systemProperty("test.gradle.user.home", gradle.gradleUserHomeDir.absolutePath)
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
          dependsOn(
            ":publishMavenJavaPublicationToFunctionalTestRepository",
            "publishPluginMavenPublicationToFunctionalTestRepository",
            "publishAtrMavenBundlePluginMarkerMavenPublicationToFunctionalTestRepository",
          )
          inputs.dir(layout.settingsDirectory.dir("build/functional-test-repository"))
          systemProperty(
            "plugin.repository",
            layout.settingsDirectory.dir("build/functional-test-repository").asFile.absolutePath,
          )
          systemProperty("plugin.version", project.version.toString())
          systemProperty(
            "plugin.shaded.jar",
            tasks.named<Jar>("shadowJar").get().archiveFile.get().asFile.absolutePath,
          )
          systemProperty("gradle.installation", gradle.gradleHomeDir!!.absolutePath)
          systemProperty("test.gradle.user.home", gradle.gradleUserHomeDir.absolutePath)
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
