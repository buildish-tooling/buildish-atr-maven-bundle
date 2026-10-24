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

import org.buildish.atrmavenbundle.gradleplugin.internal.model.InventoryCodec
import org.buildish.atrmavenbundle.gradleplugin.internal.model.PrepareRepository
import org.buildish.atrmavenbundle.gradleplugin.internal.model.SidecarCompletion
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.plugins.signing.signatory.Signatory
import org.gradle.plugins.signing.type.SignatureType
import org.gradle.work.DisableCachingByDefault

/** Prepares the shared repository tree used by local publication bundles. */
@DisableCachingByDefault(
  because = "Native export and sidecar preparation are not qualified for build caching.",
)
abstract class PrepareAtrMavenBundle : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val publicationExports: ConfigurableFileCollection
  @get:Input abstract val requireSignatures: Property<Boolean>
  @get:Input abstract val signMissing: Property<Boolean>
  /** Configured signer retained without inspecting keys while signature generation is disabled. */
  @get:Internal abstract val signatory: Property<Signatory>

  /**
   * Includes signer identity in task inputs only when the effective generation option enables it.
   */
  @get:Nested
  @get:Optional
  val effectiveSignatory: Signatory?
    get() = if (signMissing.get()) signatory.orNull else null

  @get:Nested @get:Optional abstract val signatureType: Property<SignatureType>
  @get:OutputDirectory abstract val repositoryDirectory: DirectoryProperty
  @get:OutputFile abstract val inventoryFile: RegularFileProperty

  init {
    requireSignatures.convention(true)
    signMissing.convention(false)
  }

  /** Overrides the signature-presence requirement for this shared preparation task. */
  @Option(
    option = "require-signatures",
    description = "Require nonempty signatures for primary publication files.",
  )
  fun requireSignaturesOption(enabled: Boolean) {
    requireSignatures.set(enabled)
  }

  /** Overrides generation of missing signatures for every bundle consuming this task. */
  @Option(
    option = "sign-missing",
    description = "Generate absent primary signatures using root Gradle signing.",
  )
  fun signMissingOption(enabled: Boolean) {
    signMissing.set(enabled)
  }

  /** Merges native exports, completes sidecars once, and records the prepared repository. */
  @TaskAction
  fun prepare() {
    val inventories =
      publicationExports.files
        .filter { it.isFile && it.name.endsWith(".atr-inventory") }
        .sortedBy { it.absolutePath }
        .flatMap { InventoryCodec.read(it.toPath()) }
    val prepared =
      PrepareRepository.prepare(
        inventories,
        repositoryDirectory.get().asFile.toPath(),
      )
    val completed =
      SidecarCompletion.complete(
        prepared,
        requireSignatures.get(),
        signMissing.get(),
        effectiveSignatory,
        signatureType.orNull,
      )
    InventoryCodec.write(inventoryFile.get().asFile.toPath(), completed)
  }
}
