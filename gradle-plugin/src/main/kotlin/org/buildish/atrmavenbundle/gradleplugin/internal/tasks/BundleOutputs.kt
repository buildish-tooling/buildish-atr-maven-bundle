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
package org.buildish.atrmavenbundle.gradleplugin.internal.tasks

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import javax.inject.Inject
import org.buildish.atrmavenbundle.gradleplugin.internal.creator.PreparedBundle
import org.buildish.atrmavenbundle.gradleplugin.internal.creator.bundleCreator
import org.buildish.atrmavenbundle.gradleplugin.internal.model.InventoryCodec
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Small deterministic format metadata is generated locally.")
internal abstract class BundleMetadata : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val inventoryFile: RegularFileProperty
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val repositoryDirectory: DirectoryProperty
  @get:OutputDirectory abstract val metadataDirectory: DirectoryProperty
  @get:Inject abstract val fileSystem: FileSystemOperations

  /** Replaces only the small transportable-format metadata tree consumed by the archive task. */
  @TaskAction
  fun generate() {
    val layout =
      bundleCreator("njord-transportable")
        .plan(
          PreparedBundle(
            repositoryDirectory.get().asFile,
            InventoryCodec.read(inventoryFile.get().asFile.toPath()),
          ),
        )
    val directory = metadataDirectory.get().asFile.toPath()
    fileSystem.delete { it.delete(directory) }
    writeBundleMetadata(directory, layout.metadata)
  }
}

@DisableCachingByDefault(because = "Owned exploded bundle copies are inexpensive to synchronize.")
internal abstract class BundleFiles : DefaultTask() {
  @get:Input abstract val creatorId: Property<String>
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val inventoryFile: RegularFileProperty
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val repositoryDirectory: DirectoryProperty
  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
  @get:Inject abstract val fileSystem: FileSystemOperations

  /** Synchronizes the repository and creator metadata, then normalizes owned output attributes. */
  @TaskAction
  fun sync() {
    val layout =
      bundleCreator(creatorId.get())
        .plan(
          PreparedBundle(
            repositoryDirectory.get().asFile,
            InventoryCodec.read(inventoryFile.get().asFile.toPath()),
          ),
        )
    fileSystem.sync { spec ->
      spec.from(layout.repository)
      spec.into(outputDirectory)
      spec.includeEmptyDirs = false
      spec.filePermissions { it.unix("644") }
      spec.dirPermissions { it.unix("755") }
    }
    val directory = outputDirectory.get().asFile.toPath()
    Files.createDirectories(directory)
    writeBundleMetadata(directory, layout.metadata)
    normalizeBundleFiles(directory)
  }
}

/**
 * Sets a fixed modification time and POSIX modes where supported, leaving Windows ACLs alone.
 * Children precede parents so filesystem updates cannot disturb normalized directory timestamps.
 */
internal fun normalizeBundleFiles(root: Path) {
  val time = FileTime.from(Instant.parse("1980-02-01T00:00:00Z"))
  Files.walk(root).use { paths ->
    paths.sorted(Comparator.reverseOrder()).forEach { path ->
      Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        ?.setPermissions(
          PosixFilePermissions.fromString(
            if (Files.isDirectory(path)) "rwxr-xr-x" else "rw-r--r--",
          ),
        )
      Files.setLastModifiedTime(path, time)
    }
  }
}

/** Writes creator-owned metadata with explicit encodings and no platform newline conversion. */
private fun writeBundleMetadata(directory: Path, metadata: Map<String, String>) {
  metadata.forEach { (path, text) ->
    val target = directory.resolve(path)
    Files.createDirectories(target.parent)
    Files.writeString(
      target,
      text,
      if (path.endsWith(".properties")) Charsets.US_ASCII else Charsets.UTF_8,
    )
  }
}
