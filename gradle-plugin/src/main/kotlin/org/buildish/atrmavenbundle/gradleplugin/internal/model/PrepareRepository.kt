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
package org.buildish.atrmavenbundle.gradleplugin.internal.model

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal object PrepareRepository {
  private data class SelectedFile(
    val inventory: PublicationInventory,
    val file: InventoryFile,
    val source: Path,
  ) {
    val owner: String
      get() = "${inventory.publication.projectPath}/${inventory.publication.name}"
  }

  /**
   * Rebuilds the owned repository from detached inventories. Every destination is reserved before
   * copying so publications cannot overwrite each other, including on case-insensitive filesystems.
   */
  fun prepare(
    inventories: List<PublicationInventory>,
    destination: Path,
  ): List<PublicationInventory> {
    require(inventories.isNotEmpty()) { "No Maven publications selected for the ATR Maven bundle." }
    val selected = selectFiles(inventories)
    require(selected.any { it.file.role == FileRole.PRIMARY }) {
      "No primary publication files selected for the ATR Maven bundle."
    }
    val owners = linkedMapOf<String, SelectedFile>()
    selected.forEach { file ->
      val previous = owners.putIfAbsent(file.file.path, file)
      require(previous == null) {
        "Destination collision at ${file.file.path}: ${previous?.owner} and ${file.owner}"
      }
    }
    deleteOwnedTree(destination)
    Files.createDirectories(destination)
    val reservations = reserveDestinations(selected, destination)
    // Reserve every destination before copying any source bytes, including on case-insensitive
    // filesystems.
    reservations.forEach { (target, file) -> Files.copy(file.source, target, REPLACE_EXISTING) }
    return inventories.map {
      it.copy(exportRoot = destination.toAbsolutePath().normalize().toString())
    }
  }

  /**
   * Resolves selected sources before touching outputs, attributing missing files to their owner.
   */
  private fun selectFiles(inventories: List<PublicationInventory>): List<SelectedFile> =
    inventories.flatMap { inventory ->
      inventory.files.map { file ->
        val source =
          try {
            repositoryFile(Path.of(inventory.exportRoot), file.path)
          } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException(
              "${inventory.publication.projectPath}/${inventory.publication.name}: ${failure.message}",
              failure,
            )
          }
        require(Files.isRegularFile(source, NOFOLLOW_LINKS)) {
          "Missing export for ${inventory.publication.projectPath}/${inventory.publication.name}: ${file.path}"
        }
        SelectedFile(inventory, file, source)
      }
    }

  /**
   * Reserves paths on the actual output filesystem to detect aliases and file/directory clashes.
   */
  private fun reserveDestinations(
    selected: List<SelectedFile>,
    destination: Path,
  ): Map<Path, SelectedFile> {
    val reservations = linkedMapOf<Path, SelectedFile>()
    selected.forEach { file ->
      val target = repositoryFile(destination, file.file.path)
      try {
        Files.createDirectories(target.parent)
        Files.createFile(target)
      } catch (failure: IOException) {
        val previous =
          reservations.entries
            .firstOrNull { (path, _) ->
              (Files.exists(target, NOFOLLOW_LINKS) && Files.isSameFile(path, target)) ||
                target.startsWith(path) ||
                path.startsWith(target)
            }
            ?.value
        throw IllegalArgumentException(
          "Destination collision at ${file.file.path}: ${previous?.owner ?: "existing path"} and ${file.owner}",
          failure,
        )
      }
      reservations[target] = file
    }
    return reservations
  }
}

/** Deletes an owned output tree without following child symlinks; a symlink root is rejected. */
internal fun deleteOwnedTree(path: Path) {
  require(!Files.isSymbolicLink(path)) { "Unsupported symlink at owned output: $path" }
  if (Files.exists(path, NOFOLLOW_LINKS)) {
    Files.walk(path).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
  }
}
