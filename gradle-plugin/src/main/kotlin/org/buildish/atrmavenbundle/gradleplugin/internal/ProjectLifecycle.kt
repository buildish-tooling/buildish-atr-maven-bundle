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
import org.gradle.api.Action
import org.gradle.api.IsolatedAction
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension
import org.gradle.plugins.signing.type.pgp.ArmoredSignatureType

@Suppress("UnstableApiUsage")
internal class InstallBundleProject(private val settings: BundleSettings) :
  IsolatedAction<Project> {
  /** Installs only this project's wiring using the detached settings snapshot. */
  override fun execute(project: Project) {
    project.extensions.create(PROJECT_STATE, BundleProjectState::class.java, settings)
    project.pluginManager.apply(BundleProjectPlugin::class.java)
  }
}

@Suppress("UnstableApiUsage")
internal class FinalizeBundleProject : IsolatedAction<Project> {
  /** Defers publication discovery until this project's existing evaluation callbacks finish. */
  override fun execute(project: Project) {
    // Isolated afterProject runs before ordinary afterEvaluate listeners. Append a local
    // action here so native publishing and already-registered user callbacks finish first.
    project.afterEvaluate(SnapshotBundleProject())
  }
}

internal class SnapshotBundleProject : Action<Project> {
  /** Captures final publication identities and root defaults without retaining project models. */
  override fun execute(project: Project) {
    val state = project.extensions.getByType(BundleProjectState::class.java)
    val settings = state.settings
    if (project.path !in settings.excludedProjects && project.plugins.hasPlugin("maven-publish")) {
      project.extensions
        .getByType(PublishingExtension::class.java)
        .publications
        .withType(MavenPublication::class.java)
        .filter { (project.path to it.name) !in settings.excludedPublications }
        .forEach { publication ->
          val identity =
            PublicationIdentity(
              project.path,
              publication.name,
              publication.groupId,
              publication.artifactId,
              publication.version,
            )
          installNativeExport(project, state, publication, identity)
        }
    }
    if (project.path == ":") {
      state.baseName.set(settings.baseName ?: project.name)
      state.bundleVersion.set(settings.bundleVersion ?: project.version.toString())
      val signing = project.extensions.getByType(SigningExtension::class.java)
      project.tasks.named("prepareAtrMavenBundle", PrepareAtrMavenBundle::class.java).configure {
        it.signatory.set(signing.signatory)
        it.signatureType.set(ArmoredSignatureType())
      }
      state.baseName.disallowChanges()
      state.bundleVersion.disallowChanges()
    }
  }
}
