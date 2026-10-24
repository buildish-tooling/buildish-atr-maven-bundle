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

import java.util.Properties
import kotlin.jvm.java
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.named

plugins {
  `java-library`
  `java-test-fixtures`
  `jvm-test-suite`
  checkstyle
  id("atr-maven-bundle-spotless")
  id("atr-maven-bundle-reproducible")
  id("net.ltgt.errorprone")
}

checkstyle {
  val checkstyleVersion =
    versionCatalogs
      .named("libs")
      .findVersion("checkstyle")
      .orElseThrow { GradleException("checkstyle version not found in libs.versions.toml") }
      .requiredVersion
  toolVersion = checkstyleVersion
  configFile = layout.settingsDirectory.file("codestyle/checkstyle.xml").asFile
  isIgnoreFailures = false
  maxErrors = 0
  maxWarnings = 0
}

java {
  withJavadocJar()
  withSourcesJar()
}

tasks {
  register("compileAll") {
    group = "build"
    description = "Runs all compilation and jar tasks"
    dependsOn(tasks.withType<AbstractCompile>(), tasks.withType<ProcessResources>())
  }

  register("format") {
    group = "verification"
    description = "Runs all code formatting tasks"
    dependsOn("spotlessApply")
  }

  named<Test>("test") { jvmArgs("-Duser.language=en") }

  withType<Test>().configureEach {
    systemProperty("file.encoding", "UTF-8")
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    systemProperty("user.variant", "")
  }

  withType<Jar>().configureEach {
    manifest {
      attributes(
        // Do not add any (more or less) dynamic information to jars, because that makes Gradle's
        // caching way less efficient. Note that version and Git information are already added to
        // jar
        // manifests for release(-like) builds.
        "Implementation-Title" to "Buildish ATR Maven Bundle",
        "Implementation-Vendor" to "Buildish",
        "Implementation-URL" to "https://buildish.org/",
      )
    }

    from(layout.settingsDirectory) {
      include("LICENSE", "NOTICE").eachFile { path = "META-INF/$sourceName" }
    }
  }

  withType<Javadoc>().configureEach {
    val opt = options as CoreJavadocOptions
    // don't spam log w/ "warning: no @param/@return"
    opt.addStringOption("Xdoclint:-reference", "-quiet")
  }

  withType(JavaCompile::class.java).configureEach {
    options.release = 21
    options.compilerArgs.addAll(
      listOf("-Xlint:unchecked", "-Xlint:deprecation", "-XDaddTypeAnnotationsToSymbol=true"),
    )
    options.errorprone.disableAllWarnings = true
    options.errorprone.disableWarningsInGeneratedCode = true
    options.errorprone.excludedPaths =
      ".*/${layout.buildDirectory.get().asFile.relativeTo(projectDir)}/generated(-openapi)?/.*"
    val errorproneRules = layout.settingsDirectory.file("codestyle/errorprone-rules.properties")
    inputs.file(errorproneRules).withPathSensitivity(PathSensitivity.RELATIVE)
    options.errorprone.checks.putAll(
      provider {
        val service =
          gradle.sharedServices.registerIfAbsent(
            "errorProneConfig",
            ErrorProneConfigService::class.java,
          ) {
            parameters.configFile = errorproneRules
          }
        service.get().errorproneConfig
      },
    )
  }
}

abstract class ErrorProneConfigService : BuildService<ErrorProneConfigService.Parameters> {
  interface Parameters : BuildServiceParameters {
    val configFile: RegularFileProperty
  }

  val errorproneConfig: Map<String, CheckSeverity> by lazy {
    parameters.configFile.get().asFile.reader().use {
      val rules = Properties()
      rules.load(it)
      rules
        .mapKeys { e -> (e.key as String).trim() }
        .mapValues { e -> (e.value as String).trim() }
        .filter { e -> e.key.isNotEmpty() && e.value.isNotEmpty() }
        .mapValues { e -> CheckSeverity.valueOf(e.value) }
        .toMap()
    }
  }
}
