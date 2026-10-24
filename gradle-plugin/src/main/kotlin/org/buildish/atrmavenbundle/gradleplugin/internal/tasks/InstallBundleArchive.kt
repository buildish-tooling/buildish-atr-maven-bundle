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

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Replaces the final archive only after Gradle has successfully written its staging ZIP. */
@DisableCachingByDefault(because = "This inexpensive task installs an already-built archive.")
internal abstract class InstallBundleArchive : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val stagedArchive: RegularFileProperty
  @get:OutputFile abstract val archiveFile: RegularFileProperty

  /**
   * Installs a completed archive through a sibling temporary file, using atomic replacement if
   * available.
   */
  @TaskAction
  fun install() {
    val destination = archiveFile.get().asFile.toPath()
    Files.createDirectories(destination.parent)
    val temporary = Files.createTempFile(destination.parent, ".atr-archive-", ".tmp")
    try {
      Files.copy(stagedArchive.get().asFile.toPath(), temporary, REPLACE_EXISTING)
      try {
        Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, destination, REPLACE_EXISTING)
      }
    } finally {
      Files.deleteIfExists(temporary)
    }
  }
}
