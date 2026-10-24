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

import org.assertj.core.api.Assertions.assertThat
import org.buildish.atrmavenbundle.gradleplugin.internal.BundleProjectState
import org.buildish.atrmavenbundle.gradleplugin.internal.BundleSettings
import org.buildish.atrmavenbundle.gradleplugin.internal.INCOMING_CONFIGURATION
import org.buildish.atrmavenbundle.gradleplugin.internal.InstallBundleProject
import org.buildish.atrmavenbundle.gradleplugin.internal.OUTGOING_CONFIGURATION
import org.buildish.atrmavenbundle.gradleplugin.internal.PUBLICATION_DATA_ATTRIBUTE
import org.buildish.atrmavenbundle.gradleplugin.internal.PUBLICATION_DATA_VALUE
import org.buildish.atrmavenbundle.gradleplugin.internal.PublicationIdentity
import org.buildish.atrmavenbundle.gradleplugin.internal.SnapshotBundleProject
import org.buildish.atrmavenbundle.gradleplugin.internal.tasks.InventoryPublication
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.ivy.IvyPublication
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class BundleProjectPluginTest {
  private val settings =
    BundleSettings(
      requireSignatures = true,
      signMissing = false,
      baseName = null,
      bundleVersion = null,
      excludedProjects = setOf(":excluded"),
      excludedPublications = setOf(":" to "experimental"),
      projectPaths = listOf(":", ":library", ":excluded"),
    )

  @Test
  fun preparationCanBeRealizedBeforeLocalFinalizationAndAcceptBooleanOptions() {
    val root = ProjectBuilder.builder().build()
    InstallBundleProject(settings.copy(projectPaths = listOf(":"))).execute(root)
    val preparation =
      root.tasks.named("prepareAtrMavenBundle", PrepareAtrMavenBundle::class.java).get()
    assertThat(preparation.requireSignatures.get()).isTrue()
    assertThat(preparation.signatory.isPresent).isFalse()
    preparation.requireSignaturesOption(false)
    preparation.signMissingOption(true)
    SnapshotBundleProject().execute(root)
    assertThat(preparation.requireSignatures.get()).isFalse()
    assertThat(preparation.signMissing.get()).isTrue()
    assertThat(preparation.signatory.isPresent).isFalse()
  }

  @Test
  fun allCreatorOutputsSharePreparationProvidersAndUseFinalNames() {
    val root = ProjectBuilder.builder().withName("root with spaces").build()
    InstallBundleProject(settings.copy(projectPaths = listOf(":"))).execute(root)
    val preparation = root.tasks.named("prepareAtrMavenBundle").get()
    root.version = "2.0"
    SnapshotBundleProject().execute(root)
    listOf(
        "Central" to "central",
        "Njord" to "njord",
        "NjordTransportable" to "njord-transportable",
      )
      .forEach { (part, id) ->
        val zip =
          root.tasks
            .named(
              "createAtr${part}Archive",
              org.gradle.api.tasks.bundling.Zip::class.java,
            )
            .get()
        assertThat(zip.taskDependencies.getDependencies(zip)).contains(preparation)
        assertThat(zip.isPreserveFileTimestamps).isFalse()
        assertThat(zip.isReproducibleFileOrder).isTrue()
        val files =
          root.tasks
            .named(
              "atr${part}Files",
              org.buildish.atrmavenbundle.gradleplugin.internal.tasks.BundleFiles::class.java,
            )
            .get()
        assertThat(files.taskDependencies.getDependencies(files)).contains(preparation)
        assertThat(files.outputDirectory.get().asFile.path.replace('\\', '/'))
          .endsWith("atr-maven-bundle/$id/root with spaces-2.0")
        val archive =
          root.tasks
            .named(
              "atr${part}Archive",
              org.buildish.atrmavenbundle.gradleplugin.internal.tasks.InstallBundleArchive::class
                .java,
            )
            .get()
        assertThat(archive.archiveFile.get().asFile.name)
          .isEqualTo("root with spaces-2.0." + if (id == "njord-transportable") "ntb" else "zip")
        val alias = root.tasks.named("atr$part").get()
        assertThat(alias.taskDependencies.getDependencies(alias)).containsExactly(archive)
      }
  }

  @Test
  fun installationIsNonPublishingAndRootHasOnlyDetachedNonSelfDependencies() {
    val root = ProjectBuilder.builder().withName("root").build()
    ProjectBuilder.builder().withName("library").withParent(root).build()
    ProjectBuilder.builder().withName("excluded").withParent(root).build()
    InstallBundleProject(settings).execute(root)
    SnapshotBundleProject().execute(root)
    assertThat(root.plugins.hasPlugin("maven-publish")).isFalse()
    val outgoing = root.configurations.getByName(OUTGOING_CONFIGURATION)
    assertThat(outgoing.isCanBeConsumed).isTrue()
    assertThat(outgoing.isCanBeResolved).isFalse()
    assertThat(outgoing.isTransitive).isFalse()
    assertThat(outgoing.outgoing.artifacts).isEmpty()
    assertThat(outgoing.extendsFrom).isEmpty()
    val incoming = root.configurations.getByName(INCOMING_CONFIGURATION)
    assertThat(incoming.isCanBeConsumed).isFalse()
    assertThat(incoming.isCanBeResolved).isTrue()
    assertThat(incoming.isTransitive).isFalse()
    assertThat(incoming.extendsFrom).isEmpty()
    assertThat(incoming.attributes.getAttribute(PUBLICATION_DATA_ATTRIBUTE))
      .isEqualTo(PUBLICATION_DATA_VALUE)
    assertThat(incoming.dependencies.withType(ProjectDependency::class.java).map { it.path })
      .containsExactly(":library")
    assertThat(root.tasks.withType(InventoryPublication::class.java)).isEmpty()
  }

  @Test
  fun finalizationCopiesOnlySelectedFinalMavenFactsAndFinalRootNaming() {
    val root = ProjectBuilder.builder().withName("root").build()
    ProjectBuilder.builder().withName("library").withParent(root).build()
    ProjectBuilder.builder().withName("excluded").withParent(root).build()
    InstallBundleProject(settings).execute(root)
    root.pluginManager.apply("maven-publish")
    root.pluginManager.apply("ivy-publish")
    val publications = root.extensions.getByType(PublishingExtension::class.java).publications
    val main = publications.create("main", MavenPublication::class.java)
    publications.create("experimental", MavenPublication::class.java)
    publications.create("ivy", IvyPublication::class.java)
    main.groupId = "final.group"
    main.artifactId = "custom-artifact"
    main.version = "1.2-SNAPSHOT"
    root.version = "root-final"
    SnapshotBundleProject().execute(root)
    val state = root.extensions.getByType(BundleProjectState::class.java)
    val inventories = root.tasks.withType(InventoryPublication::class.java).toList()
    assertThat(inventories).hasSize(1)
    val inventory = inventories.single()
    assertThat(inventory.publication.get())
      .isEqualTo(PublicationIdentity(":", "main", "final.group", "custom-artifact", "1.2-SNAPSHOT"))
    val exports = root.configurations.getByName(OUTGOING_CONFIGURATION).outgoing.artifacts
    assertThat(exports).hasSize(2)
    assertThat(exports.flatMap { it.buildDependencies.getDependencies(null) }).contains(inventory)
    assertThat(state.baseName.get()).isEqualTo("root")
    assertThat(state.bundleVersion.get()).isEqualTo("root-final")
    main.artifactId = "mutated-after-snapshot"
    assertThat(inventory.publication.get().artifactId).isEqualTo("custom-artifact")
  }

  @Test
  fun excludedAndPublishingWithoutPublicationsRemainEmptyAndChildrenHaveNoAggregator() {
    val root = ProjectBuilder.builder().build()
    val child = ProjectBuilder.builder().withName("excluded").withParent(root).build()
    InstallBundleProject(settings).execute(child)
    child.pluginManager.apply("maven-publish")
    val publications = child.extensions.getByType(PublishingExtension::class.java).publications
    assertThat(publications).isEmpty()
    publications.create("main", MavenPublication::class.java)
    SnapshotBundleProject().execute(child)
    assertThat(child.tasks.withType(InventoryPublication::class.java)).isEmpty()
    assertThat(child.configurations.findByName(INCOMING_CONFIGURATION)).isNull()
    assertThat(child.configurations.getByName(OUTGOING_CONFIGURATION).outgoing.artifacts).isEmpty()
  }
}
