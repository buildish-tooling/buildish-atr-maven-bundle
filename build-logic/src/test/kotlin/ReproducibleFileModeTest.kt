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

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReproducibleFileModeTest {
  @TempDir lateinit var directory: Path

  @Test
  fun nonPosixFilesUseRegularFileMode() {
    FileSystems.newFileSystem(directory.resolve("files.zip"), mapOf("create" to "true")).use {
      filesystem ->
      val file = Files.writeString(filesystem.getPath("/payload.txt"), "payload")
      assertThat(Files.getFileAttributeView(file, PosixFileAttributeView::class.java)).isNull()
      assertThat(reproducibleFileMode(file)).isEqualTo("644")
    }
  }

  @Test
  fun posixFilesPreserveOnlyTheOwnerExecutableBit() {
    assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"))
    val file = Files.writeString(directory.resolve("payload.txt"), "payload")
    mapOf("rw-r--r--" to "644", "rwx------" to "755", "rw-r-xr-x" to "644").forEach {
      (permissions, expected) ->
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions))
      assertThat(reproducibleFileMode(file)).isEqualTo(expected)
    }
  }
}
