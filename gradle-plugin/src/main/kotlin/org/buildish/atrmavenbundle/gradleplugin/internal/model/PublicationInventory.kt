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

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path
import org.buildish.atrmavenbundle.gradleplugin.internal.PublicationIdentity

internal enum class FileRole {
  PRIMARY,
  SIGNATURE,
  CHECKSUM,
  SNAPSHOT_METADATA,
}

internal data class ArtifactSpec(val extension: String, val classifier: String?) : Serializable

internal data class InventoryFile(
  val path: String,
  val role: FileRole,
  val version: String?,
  val extension: String?,
  val classifier: String?,
)

internal data class PublicationInventory(
  val publication: PublicationIdentity,
  val exportRoot: String,
  val files: List<InventoryFile>,
)

internal object InventoryCodec {
  private const val FORMAT = "atr-maven-bundle-inventory-1"

  /** Writes the private versioned handoff format, sorting each publication's repository paths. */
  fun write(path: Path, inventories: List<PublicationInventory>) {
    Files.createDirectories(path.parent)
    DataOutputStream(Files.newOutputStream(path).buffered()).use { output ->
      output.writeUTF(FORMAT)
      output.writeInt(inventories.size)
      inventories.forEach { inventory ->
        val publication = inventory.publication
        listOf(
            publication.projectPath,
            publication.name,
            publication.groupId,
            publication.artifactId,
            publication.version,
            inventory.exportRoot,
          )
          .forEach(output::writeUTF)
        output.writeInt(inventory.files.size)
        inventory.files
          .sortedBy { it.path }
          .forEach { file ->
            output.writeUTF(file.path)
            output.writeUTF(file.role.name)
            listOf(file.version, file.extension, file.classifier).forEach { value ->
              output.writeBoolean(value != null)
              if (value != null) output.writeUTF(value)
            }
          }
      }
    }
  }

  /** Loads detached publication records, rejecting unsupported handoff-format versions. */
  fun read(path: Path): List<PublicationInventory> =
    DataInputStream(Files.newInputStream(path).buffered()).use { input ->
      require(input.readUTF() == FORMAT) { "Unsupported publication inventory: $path" }
      List(input.readInt()) {
        val identity =
          PublicationIdentity(
            input.readUTF(),
            input.readUTF(),
            input.readUTF(),
            input.readUTF(),
            input.readUTF(),
          )
        val root = input.readUTF()
        val files =
          List(input.readInt()) {
            val relativePath = input.readUTF()
            val role = FileRole.valueOf(input.readUTF())
            val values = List(3) { if (input.readBoolean()) input.readUTF() else null }
            InventoryFile(relativePath, role, values[0], values[1], values[2])
          }
        PublicationInventory(identity, root, files)
      }
    }
}
