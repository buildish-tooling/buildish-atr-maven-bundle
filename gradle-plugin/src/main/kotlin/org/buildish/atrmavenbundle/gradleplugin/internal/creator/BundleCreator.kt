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
package org.buildish.atrmavenbundle.gradleplugin.internal.creator

import java.io.File
import org.buildish.atrmavenbundle.gradleplugin.internal.model.FileRole
import org.buildish.atrmavenbundle.gradleplugin.internal.model.PublicationInventory

internal data class PreparedBundle(
  val repository: File,
  val inventories: List<PublicationInventory>,
)

internal data class BundleLayout(val repository: File, val metadata: Map<String, String>)

internal interface BundleCreator {
  val id: String

  /** Describes repository content and format metadata without copying or modifying artifacts. */
  fun plan(bundle: PreparedBundle): BundleLayout
}

internal class RepositoryBundleCreator(override val id: String) : BundleCreator {
  /** Uses the shared Maven repository tree directly for repository-only bundle formats. */
  override fun plan(bundle: PreparedBundle) = BundleLayout(bundle.repository, emptyMap())
}

internal object TransportableBundleCreator : BundleCreator {
  override val id = "njord-transportable"

  /** Builds deterministic Njord indexes and properties for a single release or snapshot kind. */
  override fun plan(bundle: PreparedBundle): BundleLayout {
    val kinds = bundle.inventories.map { it.publication.version.endsWith("-SNAPSHOT") }.toSet()
    require(kinds.size <= 1) {
      "Njord transportable bundles cannot contain mixed release and snapshot publications."
    }
    val snapshot = kinds.singleOrNull() == true
    val prefix = if (snapshot) "snapshot" else "release"
    val properties =
      sortedMapOf(
        "name" to "$prefix-sca-00001",
        "templateName" to "$prefix-sca",
        "created" to "0",
        "repositoryMode" to if (snapshot) "SNAPSHOT" else "RELEASE",
        "allowRedeploy" to "false",
        "writeMode" to "WRITE_ONCE",
        "checksumAlgorithmFactories" to "SHA-512,SHA-256,SHA-1,MD5",
        "omitChecksumsForExtensions" to ".asc,.sigstore,.sigstore.json",
      )
    val artifacts = mutableListOf<String>()
    val metadata = mutableListOf<String>()
    bundle.inventories.forEach { inventory ->
      val publication = inventory.publication
      inventory.files.forEach { file ->
        when (file.role) {
          FileRole.PRIMARY,
          FileRole.SIGNATURE -> {
            val classifier = file.classifier?.let { ":$it" } ?: ""
            artifacts +=
              "${publication.groupId}:${publication.artifactId}:${file.extension}$classifier:${file.version}=${file.path}"
          }
          FileRole.SNAPSHOT_METADATA ->
            metadata +=
              "${publication.groupId}:${publication.artifactId}:${publication.version}:maven-metadata.xml=${file.path}"
          FileRole.CHECKSUM -> Unit
        }
      }
    }
    val generated =
      sortedMapOf(
        ".meta/repository.properties" to
          properties.entries.joinToString("") { "${it.key}=${it.value}\n" },
        ".meta/artifacts" to artifacts.sorted().joinToString("") { "$it\n" },
      )
    if (metadata.isNotEmpty())
      generated[".meta/metadata"] = metadata.sorted().joinToString("") { "$it\n" }
    return BundleLayout(bundle.repository, generated)
  }
}

/** Resolves the built-in creator used by a registered output task. */
internal fun bundleCreator(id: String): BundleCreator =
  when (id) {
    "central",
    "njord" -> RepositoryBundleCreator(id)
    "njord-transportable" -> TransportableBundleCreator
    else -> error("Unknown internal bundle creator: $id")
  }
