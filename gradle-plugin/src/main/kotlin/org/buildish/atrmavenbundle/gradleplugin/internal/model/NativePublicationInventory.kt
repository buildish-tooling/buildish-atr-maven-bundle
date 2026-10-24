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

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.buildish.atrmavenbundle.gradleplugin.internal.PublicationIdentity
import org.w3c.dom.Element

internal object NativePublicationInventory {
  private val checksumExtensions = listOf("md5", "sha1", "sha256", "sha512")

  /**
   * Selects publication files and existing sidecars from a native Maven export. Snapshot filenames
   * come from native metadata; repository bookkeeping outside the version directory is omitted.
   */
  fun collect(
    root: Path,
    publication: PublicationIdentity,
    artifacts: List<ArtifactSpec>,
  ): PublicationInventory {
    val versionPath =
      "${publication.groupId.replace('.', '/')}/${publication.artifactId}/${publication.version}"
    val versionDirectory = repositoryFile(root, versionPath)
    require(Files.isDirectory(versionDirectory, NOFOLLOW_LINKS)) {
      "Missing native export for ${publication.projectPath}/${publication.name}: $versionPath"
    }
    Files.walk(root).use { files ->
      files.forEach {
        require(!Files.isSymbolicLink(it)) {
          "Unsupported symlink in ${publication.projectPath}/${publication.name}: $it"
        }
      }
    }
    val snapshot = publication.version.endsWith("-SNAPSHOT")
    val versions =
      if (snapshot) snapshotVersions(versionDirectory.resolve("maven-metadata.xml")) else emptyMap()
    validateSnapshotFiles(root, publication, versionPath, versions)
    val primaries = primaryFiles(root, publication, artifacts, versionPath, versions)
    val known = withSidecars(versionDirectory, versionPath, primaries, snapshot)
    rejectOrphanSidecars(versionDirectory, versionPath, publication, known.keys)
    require(primaries.isNotEmpty()) {
      "Native export has no primary files for ${publication.projectPath}/${publication.name}"
    }
    return PublicationInventory(
      publication,
      root.toAbsolutePath().normalize().toString(),
      known.values.sortedBy { it.path },
    )
  }

  /** Requires every file explicitly referenced by native snapshot metadata to exist. */
  private fun validateSnapshotFiles(
    root: Path,
    publication: PublicationIdentity,
    versionPath: String,
    versions: Map<ArtifactSpec, String>,
  ) {
    versions.forEach { (artifact, version) ->
      val path = "$versionPath/${artifactFilename(publication.artifactId, version, artifact)}"
      require(Files.isRegularFile(repositoryFile(root, path), NOFOLLOW_LINKS)) {
        "Missing metadata-referenced native export for ${publication.projectPath}/${publication.name}: $path"
      }
    }
  }

  /** Selects native artifact, POM and optional module files, leaving attached sidecars separate. */
  private fun primaryFiles(
    root: Path,
    publication: PublicationIdentity,
    artifacts: List<ArtifactSpec>,
    versionPath: String,
    versions: Map<ArtifactSpec, String>,
  ): Map<String, InventoryFile> {
    val candidates = linkedMapOf<String, InventoryFile>()
    (artifacts + ArtifactSpec("pom", null) + ArtifactSpec("module", null)).distinct().forEach {
      artifact ->
      val version =
        if (publication.version.endsWith("-SNAPSHOT")) versions[artifact] else publication.version
      val mandatory =
        artifact.extension == "pom" ||
          (artifact in artifacts &&
            !artifact.extension.endsWith(".asc") &&
            checksumExtensions.none { artifact.extension.endsWith(".$it") })
      require(version != null || !mandatory) {
        "Missing native snapshot artifact metadata for ${publication.projectPath}/${publication.name}: $artifact"
      }
      if (version != null) {
        val classifier = artifact.classifier?.takeIf { it.isNotEmpty() }
        val filename = artifactFilename(publication.artifactId, version, artifact)
        val source = repositoryFile(root, "$versionPath/$filename")
        require(Files.isRegularFile(source, NOFOLLOW_LINKS) || !mandatory) {
          "Missing native export for ${publication.projectPath}/${publication.name}: $versionPath/$filename"
        }
        if (Files.isRegularFile(source, NOFOLLOW_LINKS)) {
          candidates[filename] =
            InventoryFile(
              "$versionPath/$filename",
              FileRole.PRIMARY,
              version,
              artifact.extension,
              classifier,
            )
        }
      }
    }
    return candidates.filterKeys { filename ->
      !(filename.endsWith(".asc") && candidates.containsKey(filename.removeSuffix(".asc"))) &&
        checksumExtensions.none {
          filename.endsWith(".$it") && candidates.containsKey(filename.removeSuffix(".$it"))
        }
    }
  }

  /** Adds native snapshot metadata and sidecars without manufacturing new repository files. */
  private fun withSidecars(
    versionDirectory: Path,
    versionPath: String,
    primaries: Map<String, InventoryFile>,
    snapshot: Boolean,
  ): Map<String, InventoryFile> {
    val known = primaries.toMutableMap()
    if (snapshot)
      known["maven-metadata.xml"] =
        InventoryFile(
          "$versionPath/maven-metadata.xml",
          FileRole.SNAPSHOT_METADATA,
          null,
          null,
          null,
        )
    primaries.forEach { (filename, primary) ->
      if (Files.isRegularFile(versionDirectory.resolve("$filename.asc"), NOFOLLOW_LINKS)) {
        known["$filename.asc"] =
          primary.copy(
            path = "${primary.path}.asc",
            role = FileRole.SIGNATURE,
            extension = primary.extension?.let { if (it.isEmpty()) "asc" else "$it.asc" },
          )
      }
    }
    known.toMap().forEach { (filename, target) ->
      checksumExtensions.forEach { algorithm ->
        if (Files.isRegularFile(versionDirectory.resolve("$filename.$algorithm"), NOFOLLOW_LINKS)) {
          known["$filename.$algorithm"] =
            target.copy(
              path = "${target.path}.$algorithm",
              role = FileRole.CHECKSUM,
              extension =
                target.extension?.let { if (it.isEmpty()) algorithm else "$it.$algorithm" },
            )
        }
      }
    }
    return known
  }

  /** Rejects recognizable sidecars whose target was not selected from this publication. */
  private fun rejectOrphanSidecars(
    versionDirectory: Path,
    versionPath: String,
    publication: PublicationIdentity,
    knownNames: Set<String>,
  ) {
    Files.list(versionDirectory).use { files ->
      files
        .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
        .forEach { file ->
          val filename = file.fileName.toString()
          if (
            !filename.startsWith(".") &&
              filename !in knownNames &&
              (filename.endsWith(".asc") || checksumExtensions.any { filename.endsWith(".$it") })
          ) {
            throw IllegalArgumentException(
              "Orphan sidecar for ${publication.projectPath}/${publication.name}: $versionPath/$filename",
            )
          }
        }
    }
  }

  /** Uses Maven's classifier suffix and omits the dot for extensionless artifacts. */
  private fun artifactFilename(
    artifactId: String,
    version: String,
    artifact: ArtifactSpec,
  ): String {
    val classifier = artifact.classifier?.takeIf { it.isNotEmpty() }?.let { "-$it" } ?: ""
    val extension = artifact.extension.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: ""
    return "$artifactId-$version$classifier$extension"
  }

  /** Reads native timestamped versions by extension/classifier without resolving external XML. */
  private fun snapshotVersions(metadata: Path): Map<ArtifactSpec, String> {
    require(Files.isRegularFile(metadata, NOFOLLOW_LINKS)) {
      "Missing native snapshot metadata: $metadata"
    }
    val factory =
      DocumentBuilderFactory.newInstance().apply {
        @Suppress("HttpUrlsUsage")
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
      }
    val document = Files.newInputStream(metadata).use { factory.newDocumentBuilder().parse(it) }
    val entries = document.getElementsByTagName("snapshotVersion")
    return (0 until entries.length).associate { index ->
      val entry = entries.item(index) as Element
      /** Returns the first named child value from this native snapshot-version entry. */
      fun text(name: String): String? = entry.getElementsByTagName(name).item(0)?.textContent
      ArtifactSpec(
        requireNotNull(text("extension")),
        text("classifier")?.takeIf { it.isNotEmpty() },
      ) to requireNotNull(text("value"))
    }
  }
}

/** Resolves a nonempty repository-relative path, rejecting traversal and symlink components. */
internal fun repositoryFile(root: Path, relativePath: String): Path {
  require(
    relativePath.isNotEmpty() &&
      '\\' !in relativePath &&
      !Regex("^[A-Za-z]:").containsMatchIn(relativePath),
  ) {
    "Invalid repository path: $relativePath"
  }
  val relative = Path.of(relativePath)
  require(
    !relative.isAbsolute &&
      relativePath.split('/').none { it.isEmpty() || it == "." || it == ".." },
  ) {
    "Invalid repository path: $relativePath"
  }
  val normalizedRoot = root.toAbsolutePath().normalize()
  val resolved = normalizedRoot.resolve(relative).normalize()
  require(resolved.startsWith(normalizedRoot) && resolved != normalizedRoot) {
    "Repository path escapes export: $relativePath"
  }
  var current = normalizedRoot
  require(!Files.isSymbolicLink(current)) { "Unsupported symlink: $current" }
  relative.forEach { part ->
    current = current.resolve(part)
    require(!Files.isSymbolicLink(current)) { "Unsupported symlink: $current" }
  }
  return resolved
}
