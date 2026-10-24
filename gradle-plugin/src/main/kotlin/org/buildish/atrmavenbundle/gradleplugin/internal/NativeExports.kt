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
package org.buildish.atrmavenbundle.gradleplugin.internal

import java.util.HexFormat
import org.buildish.atrmavenbundle.gradleplugin.internal.model.ArtifactSpec
import org.buildish.atrmavenbundle.gradleplugin.internal.tasks.InventoryPublication
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.Delete

/**
 * Wires one publication through Gradle's native Maven writer into a private, freshly reset export.
 * The outgoing inventory and directory carry the producing task dependencies to the root.
 */
internal fun installNativeExport(
  project: Project,
  state: BundleProjectState,
  publication: MavenPublication,
  identity: PublicationIdentity,
) {
  val id = HexFormat.of().formatHex(publication.name.toByteArray(Charsets.UTF_8))
  val exportDirectory = project.layout.buildDirectory.dir("atr-maven-bundle-internal/exports/$id")
  val repositoryName = "atrMavenBundle$id"
  val publishing = project.extensions.getByType(PublishingExtension::class.java)
  require(publishing.repositories.findByName(repositoryName) == null) {
    "Publication export repository name is already in use: $repositoryName"
  }
  publishing.repositories.maven {
    it.name = repositoryName
    it.url = exportDirectory.get().asFile.toURI()
  }
  val reset =
    project.tasks.register("resetAtrMavenExport$id", Delete::class.java) {
      it.delete(exportDirectory)
      it.isFollowSymlinks = false
    }
  val publishName =
    "publish${publication.name.replaceFirstChar { it.uppercaseChar() }}PublicationTo${repositoryName.replaceFirstChar { it.uppercaseChar() }}Repository"
  val publish = project.tasks.named(publishName, PublishToMavenRepository::class.java)
  publish.configure { it.dependsOn(reset) }
  val artifactSpecs =
    publication.artifacts.map {
      ArtifactSpec(it.extension, it.classifier?.takeIf(String::isNotEmpty))
    }
  val inventory =
    project.tasks.register("inventoryAtrMavenPublication$id", InventoryPublication::class.java) {
      it.dependsOn(publish)
      it.publication.set(identity)
      it.artifacts.set(artifactSpecs)
      it.exportDirectory.set(exportDirectory)
      it.inventoryFile.set(
        project.layout.buildDirectory.file(
          "atr-maven-bundle-internal/inventories/$id.atr-inventory",
        ),
      )
    }
  val inventoryFile = inventory.flatMap { it.inventoryFile }
  project.configurations.getByName(OUTGOING_CONFIGURATION).outgoing.apply {
    artifact(inventoryFile)
    artifact(exportDirectory) { it.builtBy(inventory) }
  }
  state.localFiles.from(inventoryFile, exportDirectory)
  state.localFiles.builtBy(inventory)
}
