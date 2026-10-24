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

import org.buildish.atrmavenbundle.gradleplugin.internal.FinalizeBundleProject
import org.buildish.atrmavenbundle.gradleplugin.internal.InstallBundleProject
import org.gradle.api.Plugin
import org.gradle.api.initialization.ProjectDescriptor
import org.gradle.api.initialization.Settings

/** Settings entry point for configuring local Maven publication bundles. */
class AtrMavenBundlePlugin : Plugin<Settings> {
  /** Freezes settings after evaluation and installs project-local lifecycle actions. */
  @Suppress("UnstableApiUsage")
  override fun apply(settings: Settings) {
    val extension =
      settings.extensions.create("atrMavenBundle", AtrMavenBundleExtension::class.java)
    settings.gradle.settingsEvaluated {
      val paths = projectPaths(settings.rootProject)
      val snapshot = extension.freeze(paths)
      settings.gradle.lifecycle.beforeProject(InstallBundleProject(snapshot))
      settings.gradle.lifecycle.afterProject(FinalizeBundleProject())
    }
  }

  /** Collects descriptor paths without accessing mutable projects across isolation boundaries. */
  private fun projectPaths(descriptor: ProjectDescriptor): List<String> =
    listOf(descriptor.path) + descriptor.children.flatMap { projectPaths(it) }
}
