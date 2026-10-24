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
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AtrMavenBundleExtensionTest {
  private val project = ProjectBuilder.builder().build()
  private val extension = project.objects.newInstance(AtrMavenBundleExtension::class.java)

  @Test
  fun defaultsKeepNamingAbsentUntilTheRootCanSupplyItsFinalValues() {
    assertThat(extension.requireSignatures.get()).isTrue()
    assertThat(extension.signMissing.get()).isFalse()
    assertThat(extension.baseName.isPresent).isFalse()
    assertThat(extension.bundleVersion.isPresent).isFalse()
    assertThat(extension.projectExclusions).isEmpty()
    assertThat(extension.publicationExclusions).isEmpty()
  }

  @Test
  fun scalarValuesAcceptLazyProviders() {
    val name = project.objects.property(String::class.java).convention("initial")
    extension.baseName.set(name)
    extension.bundleVersion.set(project.providers.provider { "1.2.3" })
    extension.requireSignatures.set(project.providers.provider { false })
    extension.signMissing.set(project.providers.provider { true })
    name.set("final")
    assertThat(extension.baseName.get()).isEqualTo("final")
    assertThat(extension.bundleVersion.get()).isEqualTo("1.2.3")
    assertThat(extension.requireSignatures.get()).isFalse()
    assertThat(extension.signMissing.get()).isTrue()
  }

  @Test
  fun exclusionsAreExactDeduplicatedAndDoNotRequireExistingProjects() {
    extension.excludeProject(":")
    extension.excludeProject(":missing:nested")
    extension.excludeProject(":missing:nested")
    extension.excludePublication(":library", "experimental")
    extension.excludePublication(":library", "Experimental")
    extension.excludePublication(":library", "experimental")
    assertThat(extension.projectExclusions).containsExactly(":", ":missing:nested")
    assertThat(extension.publicationExclusions)
      .containsExactly(":library" to "experimental", ":library" to "Experimental")
    val snapshot = extension.projectExclusions
    extension.excludeProject(":other")
    assertThat(snapshot).doesNotContain(":other")
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "",
        "library",
        "library:api",
        ":library:",
        "::library",
        ":a::b",
        ":a/b",
        ":a\\b",
        ":*",
        ": ",
      ],
  )
  fun exclusionsRejectRelativeOrMalformedProjectPaths(path: String) {
    assertThatIllegalArgumentException()
      .isThrownBy { extension.excludeProject(path) }
      .withMessageContaining("exact absolute Gradle project path")
    assertThatIllegalArgumentException()
      .isThrownBy { extension.excludePublication(path, "maven") }
      .withMessageContaining("exact absolute Gradle project path")
  }

  @Test
  fun settingsFreezeProviderValuesAndRejectLaterMutation() {
    val source = project.objects.property(String::class.java).convention("first")
    extension.baseName.set(source)
    extension.excludeProject(":excluded")
    val snapshot = extension.freeze(listOf(":", ":child"))
    source.set("later")
    assertThat(snapshot.baseName).isEqualTo("first")
    assertThat(snapshot.bundleVersion).isNull()
    assertThat(snapshot.excludedProjects).containsExactly(":excluded")
    assertThat(snapshot.projectPaths).containsExactly(":", ":child")
    assertThatIllegalStateException().isThrownBy { extension.signMissing.set(true) }
    assertThatIllegalStateException().isThrownBy { extension.excludeProject(":later") }
    assertThatIllegalStateException().isThrownBy { extension.excludePublication(":child", "later") }
  }

  @Test
  fun pluginDescriptorRegistersTheSettingsEntryPoint() {
    val descriptor =
      javaClass.classLoader
        .getResourceAsStream(
          "META-INF/gradle-plugins/org.buildish.atr-maven-bundle.gradle-plugin.properties",
        )!!
        .bufferedReader()
        .use { it.readText() }
    assertThat(descriptor).contains("implementation-class=${AtrMavenBundlePlugin::class.java.name}")
    assertThat(AtrMavenBundlePlugin::class.java.getAnnotation(Metadata::class.java).metadataVersion)
      .containsExactly(2, 2, 0)
  }
}
