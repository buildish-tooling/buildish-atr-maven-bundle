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

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.buildish.atrmavenbundle.gradleplugin.internal.PublicationIdentity
import org.buildish.atrmavenbundle.gradleplugin.internal.model.ArtifactSpec
import org.buildish.atrmavenbundle.gradleplugin.internal.model.FileRole
import org.buildish.atrmavenbundle.gradleplugin.internal.model.InventoryCodec
import org.buildish.atrmavenbundle.gradleplugin.internal.model.InventoryFile
import org.buildish.atrmavenbundle.gradleplugin.internal.model.NativePublicationInventory
import org.buildish.atrmavenbundle.gradleplugin.internal.model.PrepareRepository
import org.buildish.atrmavenbundle.gradleplugin.internal.model.PublicationInventory
import org.buildish.atrmavenbundle.gradleplugin.internal.model.repositoryFile
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PublicationInventoryTest {
  @TempDir lateinit var directory: Path
  private val identity = PublicationIdentity(":library", "main", "org.example", "library", "1.0")

  @Test
  fun releaseInventoryRetainsOnlyNativePublicationFilesAndTheirSidecars() {
    val root = directory.resolve("export").createDirectories()
    val version = root.resolve("org/example/library/1.0").createDirectories()
    listOf(
        "library-1.0.jar",
        "library-1.0.pom",
        "library-1.0.module",
        "library-1.0-sources.jar",
        "library-1.0.jar.asc",
        "library-1.0.jar.asc.sha256",
        "library-1.0.jar.sha1",
      )
      .forEach { version.resolve(it).writeText(it) }
    root.resolve("org/example/library/maven-metadata.xml").writeText("bookkeeping")
    version.resolve(".local-store").writeText("ignored")
    val inventory =
      NativePublicationInventory.collect(
        root,
        identity,
        listOf(
          ArtifactSpec("jar", null),
          ArtifactSpec("jar", "sources"),
          ArtifactSpec("jar.asc", null),
        ),
      )
    assertThat(inventory.files).hasSize(7)
    assertThat(inventory.files.filter { it.role == FileRole.PRIMARY }.map { it.extension })
      .containsExactly("jar", "jar", "module", "pom")
    assertThat(inventory.files.single { it.path.endsWith(".jar.asc") }.role)
      .isEqualTo(FileRole.SIGNATURE)
    assertThat(inventory.files.single { it.path.endsWith(".asc.sha256") }.role)
      .isEqualTo(FileRole.CHECKSUM)
    assertThat(inventory.files.map { it.path }).noneMatch {
      it.contains("metadata") || it.contains("local-store")
    }
    val codec = directory.resolve("inventory.atr-inventory")
    InventoryCodec.write(codec, listOf(inventory))
    assertThat(InventoryCodec.read(codec)).containsExactly(inventory)
  }

  @Test
  fun extensionlessCustomArtifactsUseTheNativeFilenameWithoutATrailingDot() {
    val root = directory.resolve("export").createDirectories()
    val version = root.resolve("org/example/library/1.0").createDirectories()
    version.resolve("library-1.0.pom").writeText("pom")
    version.resolve("library-1.0-payload").writeText("custom")
    version.resolve("library-1.0-payload.asc").writeText("signature")
    version.resolve("library-1.0-payload.sha256").writeText("native checksum")
    val inventory =
      NativePublicationInventory.collect(root, identity, listOf(ArtifactSpec("", "payload")))
    assertThat(inventory.files.map { it.path })
      .contains("org/example/library/1.0/library-1.0-payload")
    assertThat(
        inventory.files
          .single { it.classifier == "payload" && it.role == FileRole.PRIMARY }
          .extension,
      )
      .isEmpty()
    assertThat(inventory.files.single { it.role == FileRole.SIGNATURE }.extension).isEqualTo("asc")
    assertThat(inventory.files.single { it.role == FileRole.CHECKSUM }.extension)
      .isEqualTo("sha256")
  }

  @Test
  fun nativeSnapshotMetadataDeterminesTimestampedVersionsWithoutRenamingBytes() {
    val root = directory.resolve("export").createDirectories()
    val snapshot = identity.copy(version = "1.0-SNAPSHOT")
    val version = root.resolve("org/example/library/1.0-SNAPSHOT").createDirectories()
    val stamp = "1.0-20261026.100000-7"
    val metadata =
      """
      <metadata><versioning><snapshotVersions>
        <snapshotVersion><extension>jar</extension><value>$stamp</value></snapshotVersion>
        <snapshotVersion><extension>jar</extension><classifier>sources</classifier><value>$stamp</value></snapshotVersion>
        <snapshotVersion><extension>pom</extension><value>$stamp</value></snapshotVersion>
      </snapshotVersions></versioning></metadata>
    """
        .trimIndent()
    version.resolve("maven-metadata.xml").writeText(metadata)
    version.resolve("maven-metadata.xml.sha256").writeText("native checksum")
    listOf(
        "library-$stamp.jar",
        "library-$stamp-sources.jar",
        "library-$stamp.pom",
        "library-$stamp.jar.asc",
        "library-$stamp.jar.sha512",
      )
      .forEach { version.resolve(it).writeText(it) }
    val inventory =
      NativePublicationInventory.collect(
        root,
        snapshot,
        listOf(ArtifactSpec("jar", null), ArtifactSpec("jar", "sources")),
      )
    assertThat(inventory.files.filter { it.version != null }.map { it.version }.distinct())
      .containsExactly(stamp)
    assertThat(inventory.files.single { it.path.endsWith("-sources.jar") }.classifier)
      .isEqualTo("sources")
    assertThat(inventory.files.single { it.path.endsWith("maven-metadata.xml") }.role)
      .isEqualTo(FileRole.SNAPSHOT_METADATA)
    val prepared = directory.resolve("prepared")
    PrepareRepository.prepare(listOf(inventory), prepared)
    assertThat(prepared.resolve("org/example/library/1.0-SNAPSHOT/maven-metadata.xml").readText())
      .isEqualTo(metadata)
    assertThat(prepared.resolve("org/example/library/1.0-SNAPSHOT/library-$stamp.jar").readText())
      .isEqualTo("library-$stamp.jar")
  }

  @Test
  fun missingPomOrDeclaredArtifactCannotDisappearFromAnInventory() {
    val root = directory.resolve("export").createDirectories()
    val version = root.resolve("org/example/library/1.0").createDirectories()
    version.resolve("library-1.0.jar").writeText("jar")
    assertThatIllegalArgumentException()
      .isThrownBy {
        NativePublicationInventory.collect(root, identity, listOf(ArtifactSpec("jar", null)))
      }
      .withMessageContaining("Missing native export")
      .withMessageContaining("library-1.0.pom")
    version.resolve("library-1.0.pom").writeText("pom")
    Files.delete(version.resolve("library-1.0.jar"))
    assertThatIllegalArgumentException()
      .isThrownBy {
        NativePublicationInventory.collect(root, identity, listOf(ArtifactSpec("jar", null)))
      }
      .withMessageContaining("Missing native export")
      .withMessageContaining("library-1.0.jar")
  }

  @Test
  fun snapshotMetadataCannotReferenceAMissingPayload() {
    val root = directory.resolve("export").createDirectories()
    val version = root.resolve("org/example/library/1.0-SNAPSHOT").createDirectories()
    version
      .resolve("maven-metadata.xml")
      .writeText(
        "<metadata><snapshotVersion><extension>jar</extension><value>1.0-20261026.100000-7</value></snapshotVersion></metadata>",
      )
    assertThatIllegalArgumentException()
      .isThrownBy {
        NativePublicationInventory.collect(
          root,
          identity.copy(version = "1.0-SNAPSHOT"),
          listOf(ArtifactSpec("jar", null)),
        )
      }
      .withMessageContaining("metadata-referenced")
      .withMessageContaining("1.0-20261026.100000-7.jar")
  }

  @Test
  fun rejectsAnExistingSymlinkWithoutCreatingOrChangingOne() {
    val root = Path.of("/proc/self/fd")
    assumeTrue(
      Files.isSymbolicLink(root.resolve("0")),
      "Host has no existing procfs descriptor symlink.",
    )
    assertThatIllegalArgumentException()
      .isThrownBy { repositoryFile(root, "0") }
      .withMessageContaining("Unsupported symlink")
  }

  @Test
  fun duplicateOwnershipFailsBeforeCopyingEvenForIdenticalBytes() {
    val first = inventory("first", "org/example/library/1.0/library-1.0.pom", "same")
    val second = inventory("second", first.files.single().path, "same")
    val target = directory.resolve("prepared")
    assertThatIllegalArgumentException()
      .isThrownBy { PrepareRepository.prepare(listOf(first, second), target) }
      .withMessageContaining("Destination collision")
      .withMessageContaining("library-1.0.pom")
      .withMessageContaining("first")
      .withMessageContaining("second")
    assertThat(Files.exists(target)).isFalse()
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "../outside",
        "/outside",
        "a/../../outside",
        "a/./file",
        "a//file",
        "C:/outside",
        "a\\outside",
      ],
  )
  fun rejectsInvalidInventoryPaths(path: String) {
    val bad =
      PublicationInventory(
        identity,
        directory.toString(),
        listOf(InventoryFile(path, FileRole.PRIMARY, "1.0", "pom", null)),
      )
    assertThatIllegalArgumentException()
      .isThrownBy { PrepareRepository.prepare(listOf(bad), directory.resolve("prepared")) }
      .withMessageContaining(":library/main")
      .withMessageContaining("path")
  }

  @Test
  fun rejectsEmptySelectionAndMissingFiles() {
    assertThatIllegalArgumentException()
      .isThrownBy { PrepareRepository.prepare(emptyList(), directory.resolve("prepared")) }
      .withMessageContaining("No Maven publications")
    val native = inventory("main", "org/example/library/1.0/library-1.0.pom", "pom")
    Files.delete(Path.of(native.exportRoot).resolve(native.files.single().path))
    assertThatIllegalArgumentException()
      .isThrownBy { PrepareRepository.prepare(listOf(native), directory.resolve("prepared")) }
      .withMessageContaining("Missing export")
  }

  @Test
  fun nativeOrphanCompanionsCannotBeSilentlyDropped() {
    val root = directory.resolve("export").createDirectories()
    val version = root.resolve("org/example/library/1.0").createDirectories()
    version.resolve("library-1.0.pom").writeText("pom")
    val orphan = version.resolve("missing.jar.asc")
    orphan.writeText("signature")
    assertThatIllegalArgumentException()
      .isThrownBy { NativePublicationInventory.collect(root, identity, emptyList()) }
      .withMessageContaining("Orphan sidecar")
      .withMessageContaining("missing.jar.asc")
    Files.delete(orphan)
    version.resolve("missing.jar.sha256").writeText("checksum")
    assertThatIllegalArgumentException()
      .isThrownBy { NativePublicationInventory.collect(root, identity, emptyList()) }
      .withMessageContaining("Orphan sidecar")
      .withMessageContaining("missing.jar.sha256")
  }

  @Test
  fun caseCollisionFollowsTheActualDestinationFilesystemAndRebuildRemovesStaleFiles() {
    val first = inventory("first", "a/File.pom", "first")
    val second = inventory("second", "a/file.pom", "second")
    val probe = directory.resolve("CaseProbe")
    Files.createFile(probe)
    val insensitive = Files.exists(directory.resolve("caseprobe"))
    Files.delete(probe)
    val destination = directory.resolve("prepared")
    if (insensitive) {
      assertThatIllegalArgumentException()
        .isThrownBy { PrepareRepository.prepare(listOf(first, second), destination) }
        .withMessageContaining("Destination collision")
        .withMessageContaining("first")
        .withMessageContaining("second")
      assertThat(destination.resolve("a/File.pom").readText()).isEmpty()
    } else {
      PrepareRepository.prepare(listOf(first, second), destination)
      assertThat(destination.resolve("a/File.pom").readText()).isEqualTo("first")
      assertThat(destination.resolve("a/file.pom").readText()).isEqualTo("second")
      PrepareRepository.prepare(listOf(first), destination)
      assertThat(Files.exists(destination.resolve("a/file.pom"))).isFalse()
    }
  }

  private fun inventory(name: String, path: String, contents: String): PublicationInventory {
    val root = directory.resolve(name).createDirectories()
    val file = root.resolve(path)
    file.parent.createDirectories()
    file.writeText(contents)
    return PublicationInventory(
      identity.copy(name = name),
      root.toString(),
      listOf(InventoryFile(path, FileRole.PRIMARY, "1.0", "pom", null)),
    )
  }
}
