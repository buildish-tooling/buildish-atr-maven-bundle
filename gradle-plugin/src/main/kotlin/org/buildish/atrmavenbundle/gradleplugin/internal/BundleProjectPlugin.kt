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

import org.buildish.atrmavenbundle.gradleplugin.PrepareAtrMavenBundle
import org.buildish.atrmavenbundle.gradleplugin.internal.tasks.BundleFiles
import org.buildish.atrmavenbundle.gradleplugin.internal.tasks.BundleMetadata
import org.buildish.atrmavenbundle.gradleplugin.internal.tasks.InstallBundleArchive
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Attribute
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip

internal const val PROJECT_STATE = "atrMavenBundleInternal"
internal const val OUTGOING_CONFIGURATION = "atrMavenBundleElements"
internal const val INCOMING_CONFIGURATION = "atrMavenBundleInputs"
internal val PUBLICATION_DATA_ATTRIBUTE: Attribute<String> =
  Attribute.of("org.buildish.atr-maven-bundle.publication-data", String::class.java)
internal const val PUBLICATION_DATA_VALUE = "inventory"

internal class BundleProjectPlugin : Plugin<Project> {
  /** Exposes local exports on every project and registers shared preparation and root outputs. */
  override fun apply(project: Project) {
    val state = project.extensions.getByType(BundleProjectState::class.java)
    project.configurations.create(OUTGOING_CONFIGURATION) {
      it.isCanBeConsumed = true
      it.isCanBeResolved = false
      it.isTransitive = false
      it.attributes.attribute(PUBLICATION_DATA_ATTRIBUTE, PUBLICATION_DATA_VALUE)
    }
    if (project.path != ":") return
    project.pluginManager.apply("signing")

    state.bundleFiles.from(state.localFiles, publicationInputs(project, state))
    val preparation = registerPreparation(project, state)
    val outputStem = outputStem(state)
    listOf(
        "Central" to "central",
        "Njord" to "njord",
        "NjordTransportable" to "njord-transportable",
      )
      .forEach { (taskPart, id) ->
        registerCreatorOutputs(project, preparation, outputStem, taskPart, id)
      }
    project.tasks.register("atrMavenBundle") {
      it.group = "publishing"
      it.dependsOn("atrNjordArchive")
    }
  }

  /** Resolves only detached publication exports through nontransitive project dependencies. */
  private fun publicationInputs(project: Project, state: BundleProjectState): Configuration {
    val inputs =
      project.configurations.create(INCOMING_CONFIGURATION) {
        it.isCanBeConsumed = false
        it.isCanBeResolved = true
        it.isTransitive = false
        it.attributes.attribute(PUBLICATION_DATA_ATTRIBUTE, PUBLICATION_DATA_VALUE)
      }
    state.settings.projectPaths
      .filter { it != ":" && it !in state.settings.excludedProjects }
      .forEach { path ->
        project.dependencies.add(inputs.name, project.dependencies.project(mapOf("path" to path)))
      }
    return inputs
  }

  /** Registers the single owner of merged publication files, checksums and missing signatures. */
  private fun registerPreparation(
    project: Project,
    state: BundleProjectState,
  ): TaskProvider<PrepareAtrMavenBundle> {
    return project.tasks.register("prepareAtrMavenBundle", PrepareAtrMavenBundle::class.java) {
      it.group = "publishing"
      it.description = "Prepares the shared local Maven publication bundle repository."
      it.publicationExports.from(state.bundleFiles)
      it.requireSignatures.convention(
        booleanSetting(
          project,
          "atrMavenBundle.requireSignatures",
          state.settings.requireSignatures,
        ),
      )
      it.signMissing.convention(
        booleanSetting(project, "atrMavenBundle.signMissing", state.settings.signMissing),
      )
      it.repositoryDirectory.set(
        project.layout.buildDirectory.dir("atr-maven-bundle-internal/prepared-repository"),
      )
      it.inventoryFile.set(
        project.layout.buildDirectory.file("atr-maven-bundle-internal/prepared.atr-inventory"),
      )
    }
  }

  /** Defers output naming until the root project's final name/version defaults are available. */
  private fun outputStem(state: BundleProjectState): Provider<String> {
    return state.baseName.zip(state.bundleVersion) { baseName, version ->
      require(
        listOf(baseName, version).all {
          it.isNotBlank() && it.none { c -> c in "/\\:" } && it != "." && it != ".."
        },
      ) {
        "Bundle name and version must be nonempty filename components."
      }
      "$baseName-$version"
    }
  }

  /** Registers independent archive/files outputs and the archive alias for one creator. */
  private fun registerCreatorOutputs(
    project: Project,
    preparation: TaskProvider<PrepareAtrMavenBundle>,
    outputStem: Provider<String>,
    taskPart: String,
    id: String,
  ) {
    val zip = registerStagedArchive(project, preparation, taskPart, id)
    val extension = if (id == "njord-transportable") "ntb" else "zip"
    val archive =
      project.tasks.register("atr${taskPart}Archive", InstallBundleArchive::class.java) {
        it.group = "publishing"
        it.description = "Creates the local $id bundle archive."
        it.stagedArchive.set(zip.flatMap { staged -> staged.archiveFile })
        it.archiveFile.set(
          project.layout.buildDirectory.file(
            outputStem.map { stem -> "atr-maven-bundle/$id/$stem.$extension" },
          ),
        )
      }
    project.tasks.register("atr${taskPart}Files", BundleFiles::class.java) {
      it.group = "publishing"
      it.description = "Synchronizes the local $id bundle files."
      it.creatorId.set(id)
      it.inventoryFile.set(preparation.flatMap { prepared -> prepared.inventoryFile })
      it.repositoryDirectory.set(
        preparation.flatMap { prepared -> prepared.repositoryDirectory },
      )
      it.outputDirectory.set(
        project.layout.buildDirectory.dir(
          outputStem.map { stem -> "atr-maven-bundle/$id/$stem" },
        ),
      )
    }
    project.tasks.register("atr$taskPart") {
      it.group = "publishing"
      it.dependsOn(archive)
    }
  }

  /** Streams the prepared tree into a reproducible ZIP, adding metadata only when required. */
  private fun registerStagedArchive(
    project: Project,
    preparation: TaskProvider<PrepareAtrMavenBundle>,
    taskPart: String,
    id: String,
  ): TaskProvider<Zip> {
    val metadata =
      if (id == "njord-transportable") {
        project.tasks.register(
          "createAtrNjordTransportableMetadata",
          BundleMetadata::class.java,
        ) {
          it.inventoryFile.set(preparation.flatMap { prepared -> prepared.inventoryFile })
          it.repositoryDirectory.set(
            preparation.flatMap { prepared -> prepared.repositoryDirectory },
          )
          it.metadataDirectory.set(
            project.layout.buildDirectory.dir("atr-maven-bundle-internal/metadata/$id"),
          )
        }
      } else null
    return project.tasks.register("createAtr${taskPart}Archive", Zip::class.java) {
      metadata?.let { source ->
        it.from(source.flatMap { generated -> generated.metadataDirectory })
      }
      it.from(preparation.flatMap { prepared -> prepared.repositoryDirectory })
      it.destinationDirectory.set(
        project.layout.buildDirectory.dir("atr-maven-bundle-internal/archives"),
      )
      it.archiveFileName.set("$id.zip")
      it.isPreserveFileTimestamps = false
      it.isReproducibleFileOrder = true
      it.includeEmptyDirs = false
      it.filePermissions { permissions -> permissions.unix("644") }
      it.dirPermissions { permissions -> permissions.unix("755") }
    }
  }
}

/** Reads a strict boolean project property lazily, falling back to the settings DSL value. */
private fun booleanSetting(project: Project, name: String, fallback: Boolean) =
  project.providers
    .gradleProperty(name)
    .map { value ->
      when {
        value.equals("true", ignoreCase = true) -> true
        value.equals("false", ignoreCase = true) -> false
        else -> throw IllegalArgumentException("Project property '$name' must be true or false.")
      }
    }
    .orElse(fallback)
