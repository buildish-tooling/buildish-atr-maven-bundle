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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE

/**
 * Preserves the POSIX owner executable bit, defaulting to regular-file mode on other filesystems.
 */
internal fun reproducibleFileMode(path: Path): String {
  val permissions =
    Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
      ?.readAttributes()
      ?.permissions()
  return if (permissions?.contains(OWNER_EXECUTE) == true) "755" else "644"
}
