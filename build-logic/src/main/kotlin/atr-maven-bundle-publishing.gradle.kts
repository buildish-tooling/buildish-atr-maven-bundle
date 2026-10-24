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

import com.github.jengelman.gradle.plugins.shadow.ShadowPlugin
import groovy.util.Node
import groovy.util.NodeList

plugins {
  `maven-publish`
  signing
}

publishing {
  publications {
    // java-gradle-plugin supplies pluginMaven (including Shadow integration).
    if (!plugins.hasPlugin("java-gradle-plugin")) {
      register<MavenPublication>("mavenJava")
    }

    withType(MavenPublication::class.java) {
      val projectName = project.name
      val projectGroup = project.group.toString()
      val projectDescription = project.description.toString()
      val projectVersion = project.version.toString()
      val isRootProject = project == rootProject
      val parentName = "buildish-atr-maven-bundle"
      val repoUrl = "https://github.com/buildish-tooling/buildish-atr-maven-bundle"
      val scmUrl = "scm:git:$repoUrl"

      groupId = projectGroup
      version = projectVersion

      pom {
        name = projectName
        description = projectDescription

        if (isRootProject) {
          inceptionYear.set("2026")
          url.set(repoUrl)
          organization {
            name.set("Buildish")
            url.set("https://buildish.org")
          }
          licenses {
            license {
              name.set("Apache-2.0") // SPDX-ID
              url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
          }
          mailingLists {
            mailingList {
              name.set("Buildish Dev Mailing List")
              subscribe.set("dev+subscribe@buildish.org")
              unsubscribe.set("dev+unsubscribe@buildish.org")
              post.set("dev@buildish.org")
              archive.set("https://groups.google.com/a/buildish.org/g/dev")
            }
          }
          scm {
            connection.set(scmUrl)
            developerConnection.set(scmUrl)
            url.set("$repoUrl/tree/main")
            if (!projectVersion.endsWith("-SNAPSHOT")) {
              tag.set("buildish-atr-maven-bundle-$projectVersion")
            }
          }
          issueManagement {
            system.set("GitHub")
            url.set("$repoUrl/issues")
          }
        }

        if (!isRootProject) {
          withXml {
            val projectNode = asNode()
            val parentNode = projectNode.appendNode("parent")
            parentNode.appendNode("groupId", projectGroup)
            parentNode.appendNode("artifactId", parentName)
            parentNode.appendNode("version", projectVersion)
          }
        }
      }

      suppressPomMetadataWarningsFor("testApiElements")
      suppressPomMetadataWarningsFor("testJavadocElements")
      suppressPomMetadataWarningsFor("testRuntimeElements")
      suppressPomMetadataWarningsFor("testSourcesElements")
      suppressPomMetadataWarningsFor("testFixturesApiElements")
      suppressPomMetadataWarningsFor("testFixturesJavadocElements")
      suppressPomMetadataWarningsFor("testFixturesRuntimeElements")
      suppressPomMetadataWarningsFor("testFixturesSourcesElements")

      if (project.plugins.hasPlugin(ShadowPlugin::class.java)) {
        if (name == "mavenJAva") {
          configureShadowPublishing(project, this, softwareComponentFactory)
        }
      } else {
        val javaPlatformComponent = components.findByName("javaPlatform")
        if (javaPlatformComponent != null) {
          from(javaPlatformComponent)
        } else {
          val javaComponent = components.findByName("java")
          if (javaComponent != null) {
            if (javaComponent is AdhocComponentWithVariants) {
              listOf("testFixturesApiElements", "testFixturesRuntimeElements").forEach { cfg ->
                configurations.findByName(cfg)?.apply {
                  javaComponent.addVariantsFromConfiguration(this) { skip() }
                }
              }
            }
            from(javaComponent)
          }
        }
      }
    }

    signing {
      if (
        providers.gradleProperty("release").isPresent ||
          providers.gradleProperty("doSign").isPresent
      ) {
        val signingKey = providers.gradleProperty("signingKey").orNull
        val signingPassword = providers.gradleProperty("signingPassword").orNull
        useInMemoryPgpKeys(signingKey, signingPassword)

        if (providers.gradleProperty("useGpgAgent").isPresent) {
          useGpgCmd()
        }
      } else {
        tasks.withType(Sign::class).configureEach { enabled = false }
      }
    }
  }
}

tasks {
  // Gradle complains when a Gradle module metadata ("pom on steroids") is generated with an
  // enforcedPlatform() dependency - but Quarkus requires enforcedPlatform(), so we have to
  // allow it.
  withType<GenerateModuleMetadata>().configureEach {
    suppressedValidationErrors.add("enforced-platform")
  }
}

/**
 * "Proper" publication of shadow-jar instead of the "main" jar, with "the right" Gradle's module
 * metadata that refers to the shadow-jar instead of the "main" jar, which is not published by
 * Nessie.
 *
 * Pieces of this function are taken from the `Java(Base)Plugin` and `ShadowExtension`.
 */
private fun configureShadowPublishing(
  project: Project,
  mavenPublication: MavenPublication,
  softwareComponentFactory: SoftwareComponentFactory,
) {
  fun isPublishable(element: ConfigurationVariant): Boolean {
    for (artifact in element.artifacts) {
      if (JavaBasePlugin.UNPUBLISHABLE_VARIANT_ARTIFACTS.contains(artifact.type)) {
        return false
      }
    }
    return true
  }

  val shadowJar = project.tasks.named("shadowJar")

  val shadowApiElements =
    project.configurations.create("shadowApiElements") {
      isCanBeConsumed = true
      isCanBeResolved = false
      attributes {
        attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_API))
        attribute(
          Category.CATEGORY_ATTRIBUTE,
          project.objects.named(Category::class.java, Category.LIBRARY),
        )
        attribute(
          LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
          project.objects.named(LibraryElements::class.java, LibraryElements.JAR),
        )
        attribute(
          Bundling.BUNDLING_ATTRIBUTE,
          project.objects.named(Bundling::class.java, Bundling.SHADOWED),
        )
      }
      outgoing.artifact(shadowJar)
    }

  val component = softwareComponentFactory.adhoc("shadow")
  component.addVariantsFromConfiguration(shadowApiElements) {
    if (isPublishable(configurationVariant)) {
      mapToMavenScope("compile")
    } else {
      skip()
    }
  }
  // component.addVariantsFromConfiguration(configurations.getByName("runtimeElements")) {
  component.addVariantsFromConfiguration(
    project.configurations.getByName("shadowRuntimeElements"),
  ) {
    if (isPublishable(configurationVariant)) {
      mapToMavenScope("runtime")
    } else {
      skip()
    }
  }
  // Sonatype requires the javadoc and sources jar to be present, but the
  // Shadow extension does not publish those.
  // Those configurations can be created after the Shadow plugin has been applied.
  project.configurations
    .matching { it.name == "javadocElements" || it.name == "sourcesElements" }
    .configureEach {
      component.addVariantsFromConfiguration(this) {}
    }

  mavenPublication.from(component)

  // This a replacement to add dependencies to the pom, if necessary. Equivalent to
  // 'shadowExtension.component(mavenPublication)', which we cannot use.

  mavenPublication.pom {
    withXml {
      val node = asNode()
      val depNode = node.get("dependencies")
      val dependenciesNode =
        if ((depNode as NodeList).isNotEmpty()) depNode[0] as Node
        else node.appendNode("dependencies")
      val shadowProjectDependencies =
        project.configurations
          .getByName("shadow")
          .allDependencies
          .withType(ProjectDependency::class.java)
          .map { PomDependency(it.group, it.name, it.version) }
      shadowProjectDependencies.forEach {
        val dependencyNode = dependenciesNode.appendNode("dependency")
        dependencyNode.appendNode("groupId", it.groupId)
        dependencyNode.appendNode("artifactId", it.artifactId)
        dependencyNode.appendNode("version", it.version)
        dependencyNode.appendNode("scope", "runtime")
      }
    }
  }
}

private data class PomDependency(val groupId: String?, val artifactId: String, val version: String?)
