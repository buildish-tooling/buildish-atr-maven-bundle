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

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.Properties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.buildish.atrmavenbundle.gradleplugin.internal.PublicationIdentity
import org.buildish.atrmavenbundle.gradleplugin.internal.creator.PreparedBundle
import org.buildish.atrmavenbundle.gradleplugin.internal.creator.bundleCreator
import org.buildish.atrmavenbundle.gradleplugin.internal.model.FileRole
import org.buildish.atrmavenbundle.gradleplugin.internal.model.InventoryFile
import org.buildish.atrmavenbundle.gradleplugin.internal.model.PublicationInventory
import org.buildish.atrmavenbundle.gradleplugin.internal.tasks.normalizeBundleFiles
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BundleCreatorTest {
  @TempDir lateinit var directory: Path

  @Test
  fun releaseTransportableMetadataMatchesPinnedReaderAndKeepsArtifactRelationships() {
    val layout = bundleCreator("njord-transportable").plan(bundle("1.0", "1.0"))
    val text = layout.metadata.getValue(".meta/repository.properties")
    assertThat(text)
      .isEqualTo(
        """
        allowRedeploy=false
        checksumAlgorithmFactories=SHA-512,SHA-256,SHA-1,MD5
        created=0
        name=release-sca-00001
        omitChecksumsForExtensions=.asc,.sigstore,.sigstore.json
        repositoryMode=RELEASE
        templateName=release-sca
        writeMode=WRITE_ONCE
        """
          .trimIndent() + "\n",
      )
    val properties = Properties().apply { text.byteInputStream(Charsets.US_ASCII).use { load(it) } }
    assertThat(properties.getProperty("created").toLong()).isZero()
    assertThat(properties.getProperty("repositoryMode")).isEqualTo("RELEASE")
    assertThat(properties).hasSize(8)
    assertThat(layout.metadata.getValue(".meta/artifacts"))
      .isEqualTo(
        """
        org.example:lib:jar.asc:sources:1.0=org/example/lib/1.0/lib-1.0-sources.jar.asc
        org.example:lib:jar:sources:1.0=org/example/lib/1.0/lib-1.0-sources.jar
        org.example:lib:module:1.0=org/example/lib/1.0/lib-1.0.module
        org.example:lib:pom:1.0=org/example/lib/1.0/lib-1.0.pom
        """
          .trimIndent() + "\n",
      )
    assertThat(layout.metadata).doesNotContainKey(".meta/metadata")
    assertThat(layout.repository).isEqualTo(directory.toFile())
  }

  @Test
  fun snapshotsIndexNativeTimestampedVersionAndBaseVersionMetadataSeparately() {
    val layout =
      bundleCreator("njord-transportable")
        .plan(bundle("1.0-SNAPSHOT", "1.0-20261026.120000-3", true))
    assertThat(layout.metadata.getValue(".meta/repository.properties"))
      .contains(
        "name=snapshot-sca-00001\n",
        "templateName=snapshot-sca\n",
        "repositoryMode=SNAPSHOT\n",
      )
    assertThat(layout.metadata.getValue(".meta/artifacts"))
      .contains(
        "org.example:lib:jar:sources:1.0-20261026.120000-3=org/example/lib/1.0-SNAPSHOT/lib-1.0-20261026.120000-3-sources.jar\n",
      )
    assertThat(layout.metadata.getValue(".meta/metadata"))
      .isEqualTo(
        "org.example:lib:1.0-SNAPSHOT:maven-metadata.xml=org/example/lib/1.0-SNAPSHOT/maven-metadata.xml\n",
      )
    val reversed =
      bundle("1.0-SNAPSHOT", "1.0-20261026.120000-3", true).let {
        it.copy(
          inventories =
            it.inventories.map { inventory -> inventory.copy(files = inventory.files.reversed()) },
        )
      }
    assertThat(bundleCreator("njord-transportable").plan(reversed).metadata)
      .isEqualTo(layout.metadata)
  }

  @Test
  fun mixedInputsFailOnlyTransportableCreator() {
    val release = bundle("1.0", "1.0")
    val mixed =
      release.copy(
        inventories =
          release.inventories + bundle("1.0-SNAPSHOT", "1.0-20261026.120000-3").inventories,
      )
    for (id in listOf("central", "njord")) {
      assertThat(bundleCreator(id).id).isEqualTo(id)
      assertThat(bundleCreator(id).plan(mixed).metadata).isEmpty()
    }
    assertThatThrownBy { bundleCreator("njord-transportable").plan(mixed) }
      .hasMessageContaining("mixed release and snapshot")
  }

  @Test
  fun explodedCopiesNormalizeRootChildrenAndSupportedPermissions() {
    val root = directory.resolve("tree with spaces")
    Files.createDirectories(root.resolve("nested"))
    val file = root.resolve("nested/payload with spaces.txt")
    Files.writeString(file, "unchanged bytes")
    normalizeBundleFiles(root)
    Files.walk(root).use { paths ->
      paths.forEach { path ->
        assertThat(Files.getLastModifiedTime(path).toInstant())
          .isEqualTo(Instant.parse("1980-02-01T00:00:00Z"))
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java)?.let { view ->
          assertThat(view.readAttributes().permissions())
            .isEqualTo(
              PosixFilePermissions.fromString(
                if (Files.isDirectory(path)) "rwxr-xr-x" else "rw-r--r--",
              ),
            )
        }
      }
    }
    assertThat(Files.readString(file)).isEqualTo("unchanged bytes")
  }

  @Test
  fun normalizationWorksWithANonPosixProvider() {
    FileSystems.newFileSystem(directory.resolve("no posix.zip"), mapOf("create" to "true")).use { fs
      ->
      val root = fs.getPath("/bundle")
      Files.createDirectories(root.resolve("nested"))
      Files.writeString(root.resolve("nested/payload"), "bytes")
      assertThat(Files.getFileAttributeView(root, PosixFileAttributeView::class.java)).isNull()
      normalizeBundleFiles(root)
      assertThat(Files.getLastModifiedTime(root).toInstant())
        .isEqualTo(Instant.parse("1980-02-01T00:00:00Z"))
    }
  }

  private fun bundle(
    baseVersion: String,
    publishedVersion: String,
    metadata: Boolean = false,
  ): PreparedBundle {
    val prefix = "org/example/lib/$baseVersion/lib-$publishedVersion"
    val files =
      listOf(
        InventoryFile("$prefix-sources.jar", FileRole.PRIMARY, publishedVersion, "jar", "sources"),
        InventoryFile(
          "$prefix-sources.jar.asc",
          FileRole.SIGNATURE,
          publishedVersion,
          "jar.asc",
          "sources",
        ),
        InventoryFile(
          "$prefix-sources.jar.sha256",
          FileRole.CHECKSUM,
          publishedVersion,
          "jar.sha256",
          "sources",
        ),
        InventoryFile("$prefix.pom", FileRole.PRIMARY, publishedVersion, "pom", null),
        InventoryFile("$prefix.module", FileRole.PRIMARY, publishedVersion, "module", null),
      ) +
        if (metadata)
          listOf(
            InventoryFile(
              "org/example/lib/$baseVersion/maven-metadata.xml",
              FileRole.SNAPSHOT_METADATA,
              null,
              null,
              null,
            ),
          )
        else emptyList()
    return PreparedBundle(
      directory.toFile(),
      listOf(
        PublicationInventory(
          PublicationIdentity(":", "main", "org.example", "lib", baseVersion),
          directory.toString(),
          files,
        ),
      ),
    )
  }
}
