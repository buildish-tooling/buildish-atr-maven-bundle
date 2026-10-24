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

import org.buildish.atrmavenbundle.gradleplugin.internal.PublicationIdentity
import org.buildish.atrmavenbundle.gradleplugin.internal.model.ArtifactSpec
import org.buildish.atrmavenbundle.gradleplugin.internal.model.InventoryCodec
import org.buildish.atrmavenbundle.gradleplugin.internal.model.NativePublicationInventory
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Inventories describe the current native publication result.")
internal abstract class InventoryPublication : DefaultTask() {
  @get:Input abstract val publication: Property<PublicationIdentity>
  @get:Input abstract val artifacts: ListProperty<ArtifactSpec>
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val exportDirectory: DirectoryProperty
  @get:OutputFile abstract val inventoryFile: RegularFileProperty

  init {
    outputs.upToDateWhen { false }
  }

  /** Records the files actually written by the native publisher, including generated sidecars. */
  @TaskAction
  fun inventory() {
    val result =
      NativePublicationInventory.collect(
        exportDirectory.get().asFile.toPath(),
        publication.get(),
        artifacts.get(),
      )
    InventoryCodec.write(inventoryFile.get().asFile.toPath(), listOf(result))
  }
}
