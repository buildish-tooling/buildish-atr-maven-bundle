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

plugins { java }

// The payload contains only compiled tests, runtime JARs and a local Maven repository.
val pluginVersion = providers.fileContents(layout.projectDirectory.file("version.txt")).asText

tasks.register<Test>("qualifyWindows") {
  description = "Runs the packaged signing/output fixture on the native host."
  group = "verification"
  useJUnitPlatform()
  testClassesDirs = files("test-classes")
  classpath = testClassesDirs + fileTree("lib") { include("*.jar") }
  systemProperty("plugin.repository", layout.projectDirectory.dir("repository").asFile.absolutePath)
  systemProperty("plugin.version", pluginVersion.get().trim())
  systemProperty("gradle.installation", gradle.gradleHomeDir!!.absolutePath)
  systemProperty(
    "test.gradle.user.home",
    layout.projectDirectory.dir(".gradle/test-user-home").asFile.absolutePath,
  )
  systemProperty("file.encoding", "UTF-8")
  systemProperty("user.language", "en")
  filter {
    includeTestsMatching(
      "org.buildish.atrmavenbundle.gradleplugin.SigningPreparationTest.softwareSigningSurvivesEarlyRealizationCliOverrideAndConfigurationCacheReuse",
    )
  }
}
