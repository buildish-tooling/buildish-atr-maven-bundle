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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.jar.JarFile
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PackagedSettingsPluginTest {
  @TempDir lateinit var projectDir: Path

  @Test
  fun typedSettingsDslLoadsThePublishedShadedPluginAndReusesConfigurationCache() {
    val repository = File(System.getProperty("plugin.repository"))
    val version = System.getProperty("plugin.version")
    val implementation = repository.resolve("org/buildish/atr-maven-bundle/gradle-plugin/$version")
    val jars = implementation.listFiles()!!.filter { it.extension == "jar" }
    val primaryJars = jars.filterNot {
      it.name.endsWith("-sources.jar") || it.name.endsWith("-javadoc.jar")
    }
    assertThat(primaryJars).hasSize(1)
    val jar = primaryJars.single()
    assertThat(jar.readBytes()).isEqualTo(File(System.getProperty("plugin.shaded.jar")).readBytes())
    assertThat(jars.map { it.name }).noneMatch {
      it.contains("unshaded") || it.contains("test-fixtures")
    }
    JarFile(jar).use { archive ->
      val pluginClass =
        archive.getJarEntry(
          "org/buildish/atrmavenbundle/gradleplugin/AtrMavenBundlePlugin.class",
        )
      val bytes = archive.getInputStream(pluginClass).use { it.readNBytes(8) }
      assertThat((bytes[6].toInt() and 255) * 256 + (bytes[7].toInt() and 255)).isEqualTo(61)
      assertThat(archive.entries().asSequence().map { it.name }.toList()).noneMatch {
        it.startsWith("kotlin/") || it.contains("/shadow/kotlin/")
      }
    }
    val metadata = implementation.listFiles()!!.single { it.extension == "module" }.readText()
    assertThat(metadata).contains("\"org.gradle.jvm.version\": 17")
    assertThat(metadata).doesNotContain("unshaded", "testFixtures", "kotlin-stdlib")
    val pom = implementation.listFiles()!!.single { it.extension == "pom" }.readText()
    assertThat(pom).doesNotContain("kotlin-stdlib")

    settings(
      """
      import org.buildish.atrmavenbundle.gradleplugin.AtrMavenBundleExtension

      plugins {
        id("org.buildish.atr-maven-bundle.gradle-plugin") version "$version"
      }
      rootProject.name = "packaged-consumer"
      include(":child")
      extensions.configure<AtrMavenBundleExtension>("atrMavenBundle") {
        check(requireSignatures.get())
        check(!signMissing.get())
        check(!baseName.isPresent)
        check(!bundleVersion.isPresent)
        excludeProject(":missing")
        excludePublication(":child", "experimental")
        requireSignatures.set(providers.provider { false })
        signMissing.set(true)
        baseName.set("release")
        bundleVersion.set("1.2.3")
        check(!requireSignatures.get() && signMissing.get())
        check(baseName.get() == "release" && bundleVersion.get() == "1.2.3")
      }
      println("Typed settings DSL configured")
      """
        .trimIndent(),
    )
    projectDir.resolve("child").createDirectories()
    projectDir.resolve("build.gradle.kts").writeText("")
    val first = runner().build()
    assertThat(first.output)
      .contains(
        "Typed settings DSL configured",
        "Configuration cache entry stored",
        "Isolated Projects",
      )
    val second = runner().build()
    assertThat(second.output).contains("Configuration cache entry reused")
  }

  @Test
  fun nativeExportsPrepareTheFirstReproducibleNjordArchiveWithSnapshotsAndIsolation() {
    aggregationFixture()
    val first =
      runner("atrMavenBundle", "atrCentralArchive", "atrCentralFiles", "atrNjordFiles").build()
    assertThat(first.output).contains("Configuration cache entry stored", "Isolated Projects")
    assertThat(first.tasks.map { it.path })
      .contains(":prepareAtrMavenBundle", ":atrNjordArchive", ":helper:compileJava")
      .noneMatch {
        it.startsWith(":excluded:") ||
          it.contains("ExperimentalPublication") ||
          it.contains("ToSentinelRepository") ||
          it.contains("publishToMavenLocal")
      }
    val archive = projectDir.resolve("build/atr-maven-bundle/njord/release-1.2.3.zip")
    val rootExport = projectDir.resolve("build/atr-maven-bundle-internal/exports/6d61696e")
    val libraryExport =
      projectDir.resolve("libs/api/build/atr-maven-bundle-internal/exports/6d61696e")
    ZipFile(archive.toFile()).use { zip ->
      val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
      assertThat(names)
        .contains(
          "org/example/root-late/2.0/root-late-2.0.jar",
          "org/example/root-late/2.0/root-late-2.0-sources.jar",
          "org/example/root-late/2.0/root-late-2.0-payload.txt",
          "org/example/root-late/2.0/root-late-2.0.module",
          "org/example/late-created/3.0/late-created-3.0.pom",
          "org/example/kotlin-library/6.0/kotlin-library-6.0.jar",
          "org/example/groovy-library/6.0/groovy-library-6.0.jar",
          "org/example/fixture-bom/6.0/fixture-bom-6.0.pom",
          "org/example/fixture-bom/6.0/fixture-bom-6.0.module",
          "org/example/fixture/org.example.fixture.gradle.plugin/5.0/org.example.fixture.gradle.plugin-5.0.pom",
        )
      // Gradle sorts directory entries while traversing the repository tree.
      assertThat(names).containsExactlyElementsOf(names.sortedBy { it.replace('/', '\u0000') })
      assertThat(names)
        .doesNotContain(
          "org/example/root-late/maven-metadata.xml",
          "org/example/library-custom/maven-metadata.xml",
          "org/example/fixture-bom/6.0/fixture-bom-6.0.jar",
          "org/example/fixture/org.example.fixture.gradle.plugin/5.0/org.example.fixture.gradle.plugin-5.0.jar",
        )
      assertThat(names).noneMatch { path ->
        listOf("org/example/helper/", "org/example/excluded/", "org/example/empty-publishing/")
          .any(path::startsWith)
      }
      val pom =
        zip
          .getInputStream(zip.getEntry("org/example/root-late/2.0/root-late-2.0.pom"))
          .bufferedReader()
          .use { it.readText() }
      assertThat(pom).contains("customized fixture POM")
      assertThat(
          zip
            .getInputStream(zip.getEntry("org/example/root-late/2.0/root-late-2.0-payload.txt"))
            .bufferedReader()
            .use { it.readText() },
        )
        .isEqualTo("custom payload")
      listOf(
          rootExport to "org/example/root-late/2.0",
          libraryExport to "org/example/library-custom/4.0-SNAPSHOT",
        )
        .forEach { (root, versionPath) ->
          Files.walk(root.resolve(versionPath)).use { files ->
            files.filter(Files::isRegularFile).forEach { source ->
              val relative = root.relativize(source).toString().replace(File.separatorChar, '/')
              assertThat(zip.getInputStream(zip.getEntry(relative)).use { it.readBytes() })
                .isEqualTo(Files.readAllBytes(source))
            }
          }
        }
      val metadata = "org/example/library-custom/4.0-SNAPSHOT/maven-metadata.xml"
      assertThat(names).contains(metadata, "$metadata.sha256")
      val nativeMetadata =
        zip.getInputStream(zip.getEntry(metadata)).bufferedReader().use { it.readText() }
      val timestamped = Regex("<value>(4\\.0-[^<]+)</value>").find(nativeMetadata)!!.groupValues[1]
      assertThat(names)
        .contains("org/example/library-custom/4.0-SNAPSHOT/library-custom-$timestamped.jar")
      assertThat(names)
        .doesNotContain("org/example/library-custom/4.0-SNAPSHOT/library-custom-4.0-SNAPSHOT.jar")
    }
    listOf("helper", "excluded", "empty-publishing").forEach { path ->
      assertThat(Files.exists(projectDir.resolve("$path/build/atr-maven-bundle-internal/exports")))
        .isFalse()
    }
    resolveProducedMavenLayout()
    assertThat(Files.exists(projectDir.resolve("sentinel-repository"))).isFalse()
    val staleNative = rootExport.resolve("org/example/root-late/2.0/stale.txt")
    staleNative.writeText("stale native output")
    val prepared = projectDir.resolve("build/atr-maven-bundle-internal/prepared-repository")
    prepared.resolve("stale.txt").writeText("stale prepared output")
    val second =
      runner("atrMavenBundle", "atrCentralArchive", "atrCentralFiles", "atrNjordFiles").build()
    assertThat(second.output).contains("Configuration cache entry reused")
    assertThat(Files.exists(staleNative)).isFalse()
    assertThat(Files.exists(prepared.resolve("stale.txt"))).isFalse()
    val before = Files.readAllBytes(archive)
    val beforeEntries =
      ZipFile(archive.toFile()).use { zip ->
        zip
          .entries()
          .asSequence()
          .filterNot { it.isDirectory }
          .associate { it.name to zip.getInputStream(it).use { input -> input.readBytes() } }
      }
    val payload = prepared.resolve("org/example/root-late/2.0/root-late-2.0-payload.txt")
    Files.setLastModifiedTime(payload, FileTime.fromMillis(1700000000000))
    if (Files.getFileStore(payload).supportsFileAttributeView("posix"))
      Files.setPosixFilePermissions(payload, PosixFilePermissions.fromString("rwx------"))
    runner("atrNjordArchive", "-x", "prepareAtrMavenBundle", "--rerun-tasks").build()
    ZipFile(archive.toFile()).use { zip ->
      beforeEntries.forEach { (path, bytes) ->
        assertThat(zip.getInputStream(zip.getEntry(path)).use { it.readBytes() })
          .`as`("Fixed prepared entry %s", path)
          .isEqualTo(bytes)
      }
    }
    assertThat(Files.readAllBytes(archive)).isEqualTo(before)
    assertZipModes(before)
    ZipFile(archive.toFile()).use { zip ->
      assertThat(zip.entries().asSequence().map { it.time }.distinct().toList()).hasSize(1)
    }

    val mixedNtb = runner("atrNjordTransportableArchive").buildAndFail()
    assertThat(mixedNtb.output)
      .contains("Njord transportable bundles cannot contain mixed release and snapshot")
    assertThat(Files.exists(archive)).isTrue()
    assertThat(Files.exists(projectDir.resolve("build/atr-maven-bundle/central/release-1.2.3.zip")))
      .isTrue()

    val settingsFile = projectDir.resolve("settings.gradle")
    settingsFile.writeText(
      settingsFile
        .readText()
        .replace("baseName.set('release')", "")
        .replace("bundleVersion.set('1.2.3')", "") +
        "\natrMavenBundle { excludePublication(':', 'late') }\n",
    )
    runner("atrNjordArchive").build()
    val defaultArchive =
      projectDir.resolve("build/atr-maven-bundle/njord/packaged-consumer-root-final.zip")
    ZipFile(defaultArchive.toFile()).use { zip ->
      assertThat(zip.entries().asSequence().map { it.name }.toList()).noneMatch {
        it.contains("late-created")
      }
    }
    settingsFile.writeText(
      settingsFile
        .readText()
        .replace("requireSignatures.set(false)", "requireSignatures.set(true)"),
    )
    val unsigned = runner("atrNjordArchive").buildAndFail()
    assertThat(unsigned.output).contains("Missing or empty signature")
  }

  private fun resolveProducedMavenLayout() {
    val resolver = projectDir.resolve("maven resolver with spaces").createDirectories()
    val repository = projectDir.resolve("build/atr-maven-bundle/njord/release-1.2.3").toUri()
    resolver.resolve("settings.gradle").writeText("rootProject.name = 'local-maven-resolver'\n")
    resolver
      .resolve("build.gradle")
      .writeText(
        """
      repositories { maven { url = uri('$repository') } }
      configurations { bundled { canBeConsumed = false; transitive = false } }
      dependencies { bundled 'org.example:root-late:2.0'; bundled 'org.example:fixture-bom:6.0@pom' }
      abstract class ResolveBundle extends DefaultTask {
        @InputFiles abstract ConfigurableFileCollection getResolvedFiles()
        @TaskAction void verify() {
          assert resolvedFiles.files*.name.sort() == ['fixture-bom-6.0.pom', 'root-late-2.0.jar']
        }
      }
      tasks.register('resolveBundle', ResolveBundle) { resolvedFiles.from(configurations.bundled) }
    """
          .trimIndent(),
      )
    val first = runner("resolveBundle", "--offline").withProjectDir(resolver.toFile()).build()
    assertThat(first.output).contains("Configuration cache entry stored", "Isolated Projects")
    val second = runner("resolveBundle", "--offline").withProjectDir(resolver.toFile()).build()
    assertThat(second.output).contains("Configuration cache entry reused")
  }

  private fun assertZipModes(bytes: ByteArray) {
    val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var offset = data.getInt(bytes.size - 6)
    val count = data.getShort(bytes.size - 12).toInt() and 65535
    repeat(count) {
      assertThat(data.getInt(offset)).isEqualTo(0x02014b50)
      val nameLength = data.getShort(offset + 28).toInt() and 65535
      val name = String(bytes, offset + 46, nameLength, Charsets.UTF_8)
      val permissions = (data.getInt(offset + 38) ushr 16) and 511
      assertThat(permissions).isEqualTo(if (name.endsWith('/')) 493 else 420)
      offset +=
        46 +
          nameLength +
          (data.getShort(offset + 30).toInt() and 65535) +
          (data.getShort(offset + 32).toInt() and 65535)
    }
  }

  @Test
  fun applyingTheEntryPointToAProjectFailsClearly() {
    settings("rootProject.name = \"unsupported-project-entry\"")
    projectDir
      .resolve("build.gradle.kts")
      .writeText(
        """
      plugins {
        id("org.buildish.atr-maven-bundle.gradle-plugin") version "${System.getProperty("plugin.version")}"
      }
      """
          .trimIndent(),
      )
    val failure = runner().buildAndFail()
    assertThat(failure.output).contains("org.buildish.atr-maven-bundle.gradle-plugin", "Settings")
  }

  private fun aggregationFixture() {
    val repository = File(System.getProperty("plugin.repository")).toURI()
    val version = System.getProperty("plugin.version")
    projectDir
      .resolve("settings.gradle")
      .writeText(
        """
      pluginManagement { repositories { maven { url = uri('$repository') }; mavenCentral(); gradlePluginPortal() } }
      plugins { id 'org.buildish.atr-maven-bundle.gradle-plugin' version '$version' }
      rootProject.name = 'packaged-consumer'
      include ':libs:api', ':helper', ':empty-publishing', ':excluded', ':native-plugin', ':kotlin-library', ':groovy-library', ':bom'
      atrMavenBundle {
        requireSignatures.set(false)
        baseName.set('release')
        bundleVersion.set('1.2.3')
        excludeProject(':excluded')
        excludePublication(':', 'experimental')
        excludePublication(':libs:api', 'experimental')
      }
      """
          .trimIndent(),
      )
    projectDir
      .resolve("build.gradle")
      .writeText(
        """
        plugins { id 'java-library'; id 'maven-publish' }
        group = 'org.example'
        java { withSourcesJar() }
        abstract class Payload extends DefaultTask {
          @OutputFile abstract RegularFileProperty getOutputFile()
          @TaskAction void writePayload() {
            def output = outputFile.get().asFile
            output.parentFile.mkdirs()
            output.text = 'custom payload'
          }
        }
        def payload = tasks.register('payload', Payload) { outputFile.set(layout.buildDirectory.file('payload.txt')) }
        publishing {
          repositories { maven { name = 'sentinel'; url = uri('sentinel-repository') } }
          publications {
            main(MavenPublication) {
              from components.java
              artifactId = 'initial'
              version = '2.0'
              pom { description = 'customized fixture POM' }
              artifact(payload.flatMap { it.outputFile }) { classifier = 'payload'; extension = 'txt' }
            }
            experimental(MavenPublication)
          }
        }
        afterEvaluate {
          version = 'root-final'
          publishing.publications.main.artifactId = 'root-late'
          publishing.publications.create('late', MavenPublication) {
            groupId = 'org.example'; artifactId = 'late-created'; version = '3.0'
          }
        }
        def incoming = configurations.getByName('atrMavenBundleInputs')
        assert !incoming.transitive && incoming.canBeResolved && !incoming.canBeConsumed
        assert incoming.dependencies.every { it.path != ':' && it.path != ':excluded' }
        """
          .trimIndent(),
      )
    projectDir
      .resolve("gradle.properties")
      .writeText("kotlin.compiler.execution.strategy=in-process\n")
    val scripts =
      mapOf(
        "kotlin-library" to
          """
          plugins { id 'org.jetbrains.kotlin.jvm' version '2.4.21'; id 'maven-publish' }
          group = 'org.example'; version = '6.0'
          repositories { mavenCentral() }
          publishing { publications { main(MavenPublication) { from components.java } } }
          """
            .trimIndent(),
        "groovy-library" to
          """
          plugins { id 'groovy'; id 'maven-publish' }
          group = 'org.example'; version = '6.0'
          dependencies { implementation localGroovy() }
          publishing { publications { main(MavenPublication) { from components.java } } }
          """
            .trimIndent(),
        "bom" to
          """
          plugins { id 'java-platform'; id 'maven-publish' }
          group = 'org.example'; version = '6.0'
          dependencies { constraints { api 'org.example:root-late:2.0' } }
          publishing { publications { main(MavenPublication) { from components.javaPlatform; artifactId = 'fixture-bom' } } }
          """
            .trimIndent(),
        "libs/api" to
          """
          plugins { id 'java-library'; id 'maven-publish' }
          group = 'org.example'
          publishing { publications { main(MavenPublication) { from components.java }; experimental(MavenPublication) } }
          afterEvaluate {
            publishing.publications.main.artifactId = 'library-custom'
            publishing.publications.main.version = '4.0-SNAPSHOT'
          }
          dependencies { api project(':helper'); atrMavenBundleElements 'missing.fixture:must-not-resolve:1' }
          """
            .trimIndent(),
        "native-plugin" to
          """
          plugins { id 'java-gradle-plugin'; id 'maven-publish' }
          group = 'org.example'; version = '5.0'
          gradlePlugin { plugins { fixture { id = 'org.example.fixture'; implementationClass = 'org.example.Fixture' } } }
          """
            .trimIndent(),
        "helper" to
          """
          plugins { id 'java-library' }
          group = 'org.example'; version = '1.0'
          assert !plugins.hasPlugin('maven-publish')
          """
            .trimIndent(),
        "empty-publishing" to "plugins { id 'maven-publish' }",
        "excluded" to
          """
          plugins { id 'maven-publish' }
          publishing { publications { main(MavenPublication) } }
          """
            .trimIndent(),
      )
    scripts.forEach { (path, script) ->
      val target = projectDir.resolve(path).createDirectories()
      target.resolve("build.gradle").writeText(script)
    }
    projectDir
      .resolve("kotlin-library/src/main/kotlin/org/example")
      .createDirectories()
      .resolve("KotlinLibrary.kt")
      .writeText("package org.example\nclass KotlinLibrary\n")
    projectDir
      .resolve("groovy-library/src/main/groovy/org/example")
      .createDirectories()
      .resolve("GroovyLibrary.groovy")
      .writeText("package org.example\nclass GroovyLibrary {}\n")
    val sources = projectDir.resolve("src/main/java/org/example").createDirectories()
    sources.resolve("Library.java").writeText("package org.example; public class Library {}\n")
    projectDir
      .resolve("helper/src/main/java/org/example")
      .createDirectories()
      .resolve("Helper.java")
      .writeText("package org.example; public class Helper {}\n")
    projectDir
      .resolve("libs/api/src/main/java/org/example")
      .createDirectories()
      .resolve("Api.java")
      .writeText("package org.example; public class Api extends Helper {}\n")
    projectDir
      .resolve("native-plugin/src/main/java/org/example")
      .createDirectories()
      .resolve("Fixture.java")
      .writeText(
        "package org.example; import org.gradle.api.*; public class Fixture implements Plugin<Project> { public void apply(Project project) {} }\n",
      )
  }

  private fun settings(body: String) {
    val repository = File(System.getProperty("plugin.repository")).toURI()
    projectDir
      .resolve("settings.gradle.kts")
      .writeText(
        """
      pluginManagement {
        repositories { maven { url = uri("$repository") } }
      }
      $body
      """
          .trimIndent(),
      )
  }

  private fun runner(task: String = "help", vararg extraArguments: String): GradleRunner =
    GradleRunner.create()
      .withProjectDir(projectDir.toFile())
      .withGradleInstallation(File(System.getProperty("gradle.installation")))
      .withTestKitDir(File(System.getProperty("test.gradle.user.home"), "test-kit"))
      .withArguments(
        listOf(
          task,
          "--stacktrace",
          "--configuration-cache",
          "--configuration-cache-problems=fail",
          "-Dorg.gradle.isolated-projects=true",
          "--gradle-user-home",
          System.getProperty("test.gradle.user.home"),
          "-Dorg.gradle.java.installations.auto-download=false",
        ) + extraArguments,
      )
}
