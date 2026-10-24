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

import java.util.Collections
import org.buildish.atrmavenbundle.gradleplugin.internal.BundleSettings
import org.gradle.api.provider.Property

/** Settings shared by the publication bundles in this build. */
abstract class AtrMavenBundleExtension {
  /** Require a signature for each published primary file. */
  abstract val requireSignatures: Property<Boolean>

  /** Generate missing signatures using the root project's signing configuration. */
  abstract val signMissing: Property<Boolean>

  /** Optional bundle base name; when absent, use the root project's final name. */
  abstract val baseName: Property<String>

  /** Optional bundle version; when absent, use the root project's final version. */
  abstract val bundleVersion: Property<String>

  private var frozen = false
  private val excludedProjects = linkedSetOf<String>()
  private val excludedPublications = linkedSetOf<Pair<String, String>>()

  internal val projectExclusions: Set<String>
    get() = excludedProjects.toSet()

  internal val publicationExclusions: Set<Pair<String, String>>
    get() = excludedPublications.toSet()

  init {
    requireSignatures.convention(true)
    signMissing.convention(false)
  }

  /** Exclude every publication in the project at this exact absolute Gradle path. */
  fun excludeProject(path: String) {
    check(!frozen) { "ATR Maven bundle settings have been finalized." }
    validateProjectPath(path)
    excludedProjects.add(path)
  }

  /** Exclude this exact publication name in the project at this absolute Gradle path. */
  fun excludePublication(path: String, name: String) {
    check(!frozen) { "ATR Maven bundle settings have been finalized." }
    validateProjectPath(path)
    excludedPublications.add(path to name)
  }

  /** Finalizes the settings DSL and copies values safe to share with isolated project actions. */
  internal fun freeze(projectPaths: List<String>): BundleSettings {
    requireSignatures.finalizeValue()
    signMissing.finalizeValue()
    baseName.finalizeValue()
    bundleVersion.finalizeValue()
    frozen = true
    return BundleSettings(
      requireSignatures.get(),
      signMissing.get(),
      baseName.orNull,
      bundleVersion.orNull,
      Collections.unmodifiableSet(excludedProjects.toSet()),
      Collections.unmodifiableSet(excludedPublications.toSet()),
      Collections.unmodifiableList(projectPaths.toList()),
    )
  }

  /** Rejects patterns and relative paths; exclusions identify one exact Gradle project. */
  private fun validateProjectPath(path: String) {
    require(
      path == ":" ||
        (path.startsWith(":") &&
          path.drop(1).split(':').all { segment ->
            segment.isNotBlank() && segment.none { it in "/\\<>\"?*|" || it.isISOControl() }
          }),
    ) {
      "Expected an exact absolute Gradle project path (for example ':' or ':library:api'), got '$path'."
    }
  }
}
