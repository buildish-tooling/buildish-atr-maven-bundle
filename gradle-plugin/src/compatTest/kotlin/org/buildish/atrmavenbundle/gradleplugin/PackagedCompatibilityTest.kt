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
package org.buildish.atrmavenbundle.gradleplugin

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PackagedCompatibilityTest {
  @TempDir lateinit var temporary: Path

  @Test
  fun packagedSettingsPluginLoadsKotlinDslAndBundlesOnTheExactRuntimePair() {
    val version = System.getProperty("compat.gradle.version")
    assertThat(version).isIn("9.1.0", "9.8.2")
    val javaVersion = if (version == "9.1.0") 17 else 21
    assertThat(Runtime.version().feature()).isEqualTo(javaVersion)
    JarFile(File(System.getProperty("plugin.shaded.jar"))).use { archive ->
      val header =
        archive
          .getInputStream(
            archive.getJarEntry(
              "org/buildish/atrmavenbundle/gradleplugin/AtrMavenBundlePlugin.class",
            ),
          )
          .use { it.readNBytes(8) }
      assertThat((header[6].toInt() and 255) * 256 + (header[7].toInt() and 255)).isEqualTo(61)
    }
    val implementation =
      Path.of(
        System.getProperty("plugin.repository"),
        "org/buildish/atr-maven-bundle/gradle-plugin",
        System.getProperty("plugin.version"),
      )
    Files.list(implementation).use { files ->
      val module = files.filter { it.toString().endsWith(".module") }.findFirst().orElseThrow()
      assertThat(Files.readString(module))
        .contains("\"org.gradle.jvm.version\": 17")
        .doesNotContain("kotlin-stdlib")
    }
    val project = Files.createDirectory(temporary.resolve("packaged consumer with spaces"))
    project
      .resolve("settings.gradle.kts")
      .writeText(
        """
      import org.buildish.atrmavenbundle.gradleplugin.AtrMavenBundleExtension
      pluginManagement { repositories { maven { url = uri("${File(System.getProperty("plugin.repository")).toURI()}") } } }
      plugins { id("org.buildish.atr-maven-bundle.gradle-plugin") version "${System.getProperty("plugin.version")}" }
      rootProject.name = "compat-consumer"
      check(gradle.gradleVersion == "$version")
      check(Runtime.version().feature() == $javaVersion)
      extensions.configure<AtrMavenBundleExtension>("atrMavenBundle") {
        requireSignatures.set(false)
        baseName.set("compat bundle")
      }
    """
          .trimIndent(),
      )
    project
      .resolve("build.gradle.kts")
      .writeText(
        """
        plugins { `java-library`; `maven-publish` }
        group = "org.example"
        version = "1.0"
        publishing { publications { create<MavenPublication>("main") { from(components["java"]) } } }
        """
          .trimIndent(),
      )
    val source = project.resolve("src/main/java/org/example/Library.java")
    Files.createDirectories(source.parent)
    source.writeText("package org.example; public class Library {}\n")
    val first = runner(project, version).build()
    assertThat(first.output)
      .contains("Configuration cache entry stored")
      .containsIgnoringCase("Isolated Projects")
    val second = runner(project, version).build()
    assertThat(second.output).contains("Configuration cache entry reused")
    assertThat(
        project.resolve(
          "build/atr-maven-bundle/njord/compat bundle-1.0/org/example/compat-consumer/1.0/compat-consumer-1.0.jar",
        ),
      )
      .isRegularFile()
  }

  private fun runner(project: Path, version: String) =
    GradleRunner.create()
      .withProjectDir(project.toFile())
      .withGradleVersion(version)
      .withTestKitDir(File(System.getProperty("test.gradle.user.home"), "test-kit"))
      .withArguments(
        "atrNjordArchive",
        "atrNjordFiles",
        "--stacktrace",
        "--offline",
        "--configuration-cache",
        "--configuration-cache-problems=fail",
        if (version == "9.1.0") "-Dorg.gradle.unsafe.isolated-projects=true"
        else "-Dorg.gradle.isolated-projects=true",
        "--gradle-user-home",
        System.getProperty("test.gradle.user.home"),
        "-Dorg.gradle.java.installations.auto-download=false",
        "-Dorg.gradle.java.home=${System.getProperty("java.home")}",
      )
}
