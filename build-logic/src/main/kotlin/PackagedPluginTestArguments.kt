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

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/**
 * System properties for TestKit tests that consume the packaged plugin.
 *
 * Plain `systemProperty(...)` values become cache-key inputs verbatim, so absolute paths would tie
 * cache entries to one checkout and Gradle user home. Machine-specific locations are therefore
 * `@Internal`; what they point to is fingerprinted by content instead (the shaded JAR here, the
 * functional-test repository by the test task itself).
 */
abstract class PackagedPluginTestArguments : CommandLineArgumentProvider {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val shadedJar: RegularFileProperty

  @get:Internal abstract val repository: DirectoryProperty

  @get:Input abstract val pluginVersion: Property<String>

  @get:Internal abstract val gradleUserHome: Property<String>

  /**
   * Only the location of the running Gradle distribution; its version is already part of every
   * task's cache key via the task implementation's classloader.
   */
  @get:Internal abstract val gradleInstallation: Property<String>

  override fun asArguments(): Iterable<String> =
    listOfNotNull(
      "-Dplugin.repository=${repository.get().asFile.absolutePath}",
      "-Dplugin.version=${pluginVersion.get()}",
      "-Dplugin.shaded.jar=${shadedJar.get().asFile.absolutePath}",
      "-Dtest.gradle.user.home=${gradleUserHome.get()}",
      gradleInstallation.orNull?.let { "-Dgradle.installation=$it" },
    )
}
