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

import org.jetbrains.gradle.ext.copyright
import org.jetbrains.gradle.ext.encodings
import org.jetbrains.gradle.ext.settings

buildscript { repositories { maven { url = java.net.URI("https://plugins.gradle.org/m2/") } } }

plugins {
  id("atr-maven-bundle-spotless")
  id("idea")
  id("eclipse")
  alias(libs.plugins.idea.ext)
  id("atr-maven-bundle-publishing")
}

val ideName =
  "Buildish ATR Maven Bundle ${rootProject.version.toString().replace("^([0-9.]+).*", "\\1")}"

if (providers.systemProperty("idea.sync.active").getOrElse("false").toBoolean()) {
  // There's no proper way to set the name of the IDEA project (when "just importing" or
  // syncing the Gradle project)
  val ideaDir = rootProject.layout.projectDirectory.dir(".idea")
  ideaDir.asFile.mkdirs()
  ideaDir.file(".name").asFile.writeText(ideName)

  idea {
    module {
      isDownloadJavadoc = false // was 'true', but didn't work
      isDownloadSources = false // was 'true', but didn't work
      inheritOutputDirs = true

      excludeDirs =
        excludeDirs +
          setOf(
            projectDir.resolve("build-logic/.kotlin"),
            projectDir.resolve("integration-tests/build"),
            projectDir.resolve("site/resources/_gen"),
            projectDir.resolve("site/build"),
            projectDir.resolve("logs"),
            projectDir.resolve("polaris-venv"),
            projectDir.resolve(".idea"),
            projectDir.resolve("build"),
          )
    }

    project.settings {
      copyright {
        useDefault = "ApacheLicense-v2"
        profiles.create("ApacheLicense-v2") {
          // strip trailing LF
          val copyrightText = rootProject.file("codestyle/copyright-header.txt").readText()
          notice = copyrightText
        }
      }

      encodings.encoding = "UTF-8"
      encodings.properties.encoding = "UTF-8"
    }
  }
}

eclipse { project { name = ideName } }

tasks.named<Wrapper>("wrapper") {
  actions.addLast {
    val script = scriptFile.readText()
    val scriptLines = script.lines().toMutableList()

    val insertAtLine =
      scriptLines.indexOf("# Use the maximum available, or set MAX_FD != -1 to use that value.")
    scriptLines.add(insertAtLine, "")
    scriptLines.add(insertAtLine, $$"[ -f \"${APP_HOME}/.env\" ] && . \"${APP_HOME}/.env\"")
    scriptLines.add(insertAtLine, $$". \"${APP_HOME}/gradle/gradlew-include.sh\"")

    scriptFile.writeText(scriptLines.joinToString("\n"))
  }
}

// The packaged consumer resolves the real marker, implementation and parent POM locally.
publishing {
  repositories {
    maven {
      name = "functionalTest"
      url = layout.buildDirectory.dir("functional-test-repository").get().asFile.toURI()
    }
  }
}

tasks {
  val resetFunctionalTestRepository =
    register<Delete>("resetFunctionalTestRepository") {
      delete(layout.buildDirectory.dir("functional-test-repository"))
    }

  named("publishMavenJavaPublicationToFunctionalTestRepository") {
    dependsOn(resetFunctionalTestRepository)
  }

  val rat =
    register<Exec>("rat") {
      group = LifecycleBasePlugin.VERIFICATION_GROUP
      description = "Executes RAT checks."
      executable = "tools/rat/rat-check.sh"
    }

  named("check") {
    dependsOn(rat)
  }
}
