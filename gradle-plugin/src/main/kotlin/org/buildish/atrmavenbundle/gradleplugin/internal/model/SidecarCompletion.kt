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

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import org.gradle.plugins.signing.signatory.Signatory
import org.gradle.plugins.signing.type.SignatureType

internal object SidecarCompletion {
  private val algorithms =
    linkedMapOf("md5" to "MD5", "sha1" to "SHA-1", "sha256" to "SHA-256", "sha512" to "SHA-512")

  /**
   * Verifies existing checksums, fills missing primary/metadata checksums, and optionally signs
   * unsigned primaries. Existing signatures are preserved; their cryptographic validity is not
   * checked.
   */
  fun complete(
    inventories: List<PublicationInventory>,
    requireSignatures: Boolean,
    signMissing: Boolean,
    signatory: Signatory?,
    signatureType: SignatureType?,
  ): List<PublicationInventory> = inventories.map { inventory ->
    val root = Path.of(inventory.exportRoot)
    val owner = "${inventory.publication.projectPath}/${inventory.publication.name}"
    val files = inventory.files.associateBy { it.path }.toMutableMap()
    validateRelationships(files, owner)
    completeChecksums(root, files, owner)
    completeSignatures(
      root,
      inventory.files.filter { it.role == FileRole.PRIMARY },
      files,
      owner,
      requireSignatures,
      signMissing,
      signatory,
      signatureType,
    )
    inventory.copy(files = files.values.sortedBy { it.path })
  }

  /** Checks that recognized sidecars refer to an eligible file in the same publication. */
  private fun validateRelationships(files: Map<String, InventoryFile>, owner: String) {
    files.values.forEach { file ->
      when (file.role) {
        FileRole.SIGNATURE ->
          require(
            file.path.endsWith(".asc") &&
              files[file.path.removeSuffix(".asc")]?.role == FileRole.PRIMARY,
          ) {
            "Orphan signature for $owner: ${file.path}"
          }
        FileRole.CHECKSUM -> {
          val extension = algorithms.keys.firstOrNull { file.path.endsWith(".$it") }
          val target = extension?.let { files[file.path.removeSuffix(".$it")] }
          require(
            target != null &&
              target.role in
                listOf(FileRole.PRIMARY, FileRole.SNAPSHOT_METADATA, FileRole.SIGNATURE),
          ) {
            "Orphan checksum for $owner: ${file.path}"
          }
        }
        else -> Unit
      }
    }
  }

  /** Preserves verified checksum bytes and fills only missing primary/metadata checksums. */
  private fun completeChecksums(
    root: Path,
    files: MutableMap<String, InventoryFile>,
    owner: String,
  ) {
    // Existing signature checksums are verified, but no new checksum layer is added to signatures.
    val targets =
      files.values.filter { file ->
        file.role == FileRole.PRIMARY ||
          file.role == FileRole.SNAPSHOT_METADATA ||
          (file.role == FileRole.SIGNATURE &&
            algorithms.keys.any { files.containsKey("${file.path}.$it") })
      }
    targets.forEach { target ->
      val source = repositoryFile(root, target.path)
      val hashes = Files.newInputStream(source).use(::digests)
      algorithms.keys.forEach { algorithm ->
        val path = "${target.path}.$algorithm"
        val existing = files[path]
        if (existing != null) {
          verify(
            repositoryFile(root, path),
            source.fileName.toString(),
            hashes.getValue(algorithm),
            owner,
          )
        } else if (target.role != FileRole.SIGNATURE) {
          Files.writeString(repositoryFile(root, path), hashes.getValue(algorithm) + "\n")
          files[path] =
            target.copy(
              path = path,
              role = FileRole.CHECKSUM,
              extension =
                target.extension?.let { if (it.isEmpty()) algorithm else "$it.$algorithm" },
            )
        }
      }
    }
  }

  /** Signs absent primary signatures once and applies the independent presence requirement. */
  private fun completeSignatures(
    root: Path,
    primaries: List<InventoryFile>,
    files: MutableMap<String, InventoryFile>,
    owner: String,
    requireSignatures: Boolean,
    signMissing: Boolean,
    signatory: Signatory?,
    signatureType: SignatureType?,
  ) {
    primaries.forEach { primary ->
      val path = "${primary.path}.asc"
      if (!files.containsKey(path) && signMissing) {
        signPrimary(root, primary, owner, signatory, signatureType)
        files[path] =
          primary.copy(
            path = path,
            role = FileRole.SIGNATURE,
            extension = primary.extension?.let { if (it.isEmpty()) "asc" else "$it.asc" },
          )
      }
      if (requireSignatures) {
        require(
          files[path]?.role == FileRole.SIGNATURE && Files.size(repositoryFile(root, path)) > 0,
        ) {
          "Missing or empty signature for $owner: $path"
        }
      }
    }
  }

  /** Invokes Gradle's configured signer without modifying an existing signature. */
  private fun signPrimary(
    root: Path,
    primary: InventoryFile,
    owner: String,
    signatory: Signatory?,
    signatureType: SignatureType?,
  ) {
    val signer =
      requireNotNull(signatory) {
        "No root signatory is configured; configure Gradle signing in the root project to generate a signature for $owner: ${primary.path}"
      }
    val type =
      requireNotNull(signatureType) {
        "No ASCII signature type is configured for $owner: ${primary.path}"
      }
    require(type.extension == "asc") {
      "An ASCII .asc signature type is required for $owner: ${primary.path}"
    }
    val destination = repositoryFile(root, "${primary.path}.asc")
    val generated =
      type
        .sign(signer, repositoryFile(root, primary.path).toFile())
        .toPath()
        .toAbsolutePath()
        .normalize()
    require(generated == destination && Files.isRegularFile(destination, NOFOLLOW_LINKS)) {
      "Signature was not produced for $owner: ${primary.path}"
    }
  }

  /** Computes all supported hashes in one bounded-buffer pass; the caller owns the stream. */
  internal fun digests(input: InputStream): Map<String, String> {
    val digests = algorithms.mapValues { MessageDigest.getInstance(it.value) }
    val buffer = ByteArray(64 * 1024)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      digests.values.forEach { it.update(buffer, 0, read) }
    }
    return digests.mapValues { HexFormat.of().formatHex(it.value.digest()) }
  }

  /**
   * Accepts raw hexadecimal or GNU checksum text naming the target, preserving the original file.
   */
  private fun verify(checksum: Path, basename: String, expected: String, owner: String) {
    val bytes = Files.newInputStream(checksum).use { input -> input.readNBytes(4097) }
    require(bytes.size <= 4096) { "Malformed checksum for $owner: ${checksum.fileName}" }
    val text = bytes.toString(Charsets.UTF_8).trim()
    val hex = "[0-9a-fA-F]{${expected.length}}"
    val value =
      if (Regex(hex).matches(text)) text
      else {
        val gnu = Regex("($hex)[ \t]+\\*?(.+)").matchEntire(text)
        require(gnu != null && gnu.groupValues[2] == basename) {
          "Malformed checksum or wrong filename for $owner: ${checksum.fileName}"
        }
        gnu.groupValues[1]
      }
    require(value.equals(expected, ignoreCase = true)) {
      "Checksum mismatch for $owner: ${checksum.fileName}"
    }
  }
}
