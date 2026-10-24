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

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Date
import java.util.zip.ZipFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyPacket
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SigningPreparationTest {
  @TempDir lateinit var projectDir: Path

  @Test
  fun softwareSigningSurvivesEarlyRealizationCliOverrideAndConfigurationCacheReuse() {
    projectDir = Files.createDirectory(projectDir.resolve("consumer with spaces"))
    val publicKey = ephemeralKey()
    fixture(withSigner = true)
    val args =
      arrayOf(
        "prepareAtrMavenBundle",
        "--sign-missing",
        "atrNjordArchive",
        "atrCentralArchive",
        "atrNjordTransportableArchive",
        "atrCentralFiles",
        "atrNjordFiles",
        "atrNjordTransportableFiles",
        "-PatrMavenBundle.signMissing=false",
        "-PatrMavenBundle.requireSignatures=true",
      )
    val first = runner(*args).build()
    assertThat(first.output).contains("Configuration cache entry stored", "Isolated Projects")
    assertThat(first.tasks.filter { it.path == ":prepareAtrMavenBundle" }).hasSize(1)
    assertThat(first.tasks.map { it.path }).noneMatch {
      it.substringAfterLast(':').startsWith("sign")
    }
    assertThat(
        projectDir.resolve("build/sign-count.txt").readText().lines().filter { it.isNotEmpty() },
      )
      .hasSize(2)
    verifySignatures(publicKey)
    val prepared =
      projectDir.resolve(
        "build/atr-maven-bundle-internal/prepared-repository/org/example/fixture/1.0",
      )
    Files.list(prepared).use { files -> assertThat(files.count()).isEqualTo(12) }
    assertThat(
        projectDir
          .resolve(
            "build/atr-maven-bundle/njord/signed bundle-1.0/org/example/fixture/1.0/fixture-1.0.txt.asc",
          )
          .toFile(),
      )
      .isFile()
    projectDir.resolve("payload.txt").writeText("changed payload")
    val second = runner(*args).build()
    assertThat(second.output).contains("Configuration cache entry reused")
    assertThat(second.task(":prepareAtrMavenBundle")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(
        projectDir.resolve("build/sign-count.txt").readText().lines().filter { it.isNotEmpty() },
      )
      .hasSize(4)
    verifySignatures(publicKey)
    val archives = assertCreatorOutputsEqualFiles("signed bundle-1.0")
    val fixedSignatures =
      prepared
        .toFile()
        .listFiles()!!
        .filter { it.name.endsWith(".asc") }
        .associate { it.name to it.readBytes() }
    val payload = prepared.resolve("fixture-1.0.txt")
    Files.setLastModifiedTime(payload, FileTime.fromMillis(1700000000000))
    if (Files.getFileStore(payload).supportsFileAttributeView("posix"))
      Files.setPosixFilePermissions(payload, PosixFilePermissions.fromString("rwx------"))
    projectDir
      .resolve("build/atr-maven-bundle/njord/signed bundle-1.0/stale.txt")
      .writeText("stale")
    runner(
        "atrCentralArchive",
        "atrNjordArchive",
        "atrNjordTransportableArchive",
        "atrCentralFiles",
        "atrNjordFiles",
        "atrNjordTransportableFiles",
        "-x",
        "prepareAtrMavenBundle",
        "--rerun-tasks",
      )
      .build()
    archives.forEach { (path, bytes) -> assertThat(Files.readAllBytes(path)).isEqualTo(bytes) }
    assertCreatorOutputsEqualFiles("signed bundle-1.0")
    fixedSignatures.forEach { (name, bytes) ->
      assertThat(Files.readAllBytes(prepared.resolve(name))).isEqualTo(bytes)
    }
    assertThat(
        projectDir.resolve("build/sign-count.txt").readText().lines().filter { it.isNotEmpty() },
      )
      .hasSize(4)
  }

  @Test
  fun snapshotTransportableOutputsUseNativeSnapshotCoordinatesAndMetadata() {
    fixture(withSigner = false)
    val buildFile = projectDir.resolve("build.gradle")
    buildFile.writeText(buildFile.readText().replace("version = '1.0'", "version = '1.0-SNAPSHOT'"))
    val first = runner("atrNjordTransportableArchive", "atrNjordTransportableFiles").build()
    assertThat(first.output).contains("Configuration cache entry stored", "Isolated Projects")
    val second = runner("atrNjordTransportableArchive", "atrNjordTransportableFiles").build()
    assertThat(second.output).contains("Configuration cache entry reused")
    val root =
      projectDir.resolve("build/atr-maven-bundle/njord-transportable/signed bundle-1.0-SNAPSHOT")
    assertThat(root.resolve(".meta/repository.properties").readText())
      .contains("repositoryMode=SNAPSHOT\n")
    val artifactIndex = root.resolve(".meta/artifacts").readText()
    assertThat(artifactIndex)
      .containsPattern(
        "org.example:fixture:txt:1.0-[0-9]{8}\\.[0-9]{6}-[0-9]+=org/example/fixture/1.0-SNAPSHOT/fixture-",
      )
    assertThat(root.resolve(".meta/metadata").readText())
      .isEqualTo(
        "org.example:fixture:1.0-SNAPSHOT:maven-metadata.xml=org/example/fixture/1.0-SNAPSHOT/maven-metadata.xml\n",
      )
    ZipFile(
        projectDir
          .resolve("build/atr-maven-bundle/njord-transportable/signed bundle-1.0-SNAPSHOT.ntb")
          .toFile(),
      )
      .use { zip ->
        Files.walk(root).use { paths ->
          paths.filter(Files::isRegularFile).forEach { path ->
            val relative = root.relativize(path).toString().replace(File.separatorChar, '/')
            assertThat(zip.getInputStream(zip.getEntry(relative)).use { it.readBytes() })
              .isEqualTo(Files.readAllBytes(path))
          }
        }
      }
  }

  private fun assertCreatorOutputsEqualFiles(stem: String): Map<Path, ByteArray> =
    listOf("central", "njord", "njord-transportable").associate { id ->
      val filesRoot = projectDir.resolve("build/atr-maven-bundle/$id/$stem")
      val archive =
        projectDir.resolve(
          "build/atr-maven-bundle/$id/$stem." + if (id == "njord-transportable") "ntb" else "zip",
        )
      ZipFile(archive.toFile()).use { zip ->
        val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
        assertThat(names).containsExactlyElementsOf(names.sorted())
        if (id == "njord-transportable") {
          assertThat(names)
            .contains(".meta/repository.properties", ".meta/artifacts")
            .doesNotContain(".meta/metadata")
          assertThat(
              filesRoot.resolve(".meta/artifacts").readText().lines().filter { it.isNotEmpty() },
            )
            .hasSize(4)
        }
        Files.walk(filesRoot).use { paths ->
          val allPaths = paths.toList()
          val files = allPaths.filter(Files::isRegularFile)
          assertThat(
              files.map { filesRoot.relativize(it).toString().replace(File.separatorChar, '/') },
            )
            .containsExactlyInAnyOrderElementsOf(names)
          files.forEach { path ->
            val relative = filesRoot.relativize(path).toString().replace(File.separatorChar, '/')
            assertThat(zip.getInputStream(zip.getEntry(relative)).use { it.readBytes() })
              .isEqualTo(Files.readAllBytes(path))
          }
          allPaths.forEach { path ->
            assertThat(Files.getLastModifiedTime(path).toInstant())
              .isEqualTo(Instant.parse("1980-02-01T00:00:00Z"))
            if (Files.getFileStore(path).supportsFileAttributeView("posix"))
              assertThat(Files.getPosixFilePermissions(path))
                .isEqualTo(
                  PosixFilePermissions.fromString(
                    if (Files.isDirectory(path)) "rwxr-xr-x" else "rw-r--r--",
                  ),
                )
          }
        }
        assertThat(zip.entries().asSequence().map { it.time }.distinct().toList()).hasSize(1)
      }
      val bytes = Files.readAllBytes(archive)
      assertArchiveModes(bytes)
      archive to bytes
    }

  private fun assertArchiveModes(bytes: ByteArray) {
    fun little(offset: Int, count: Int) =
      (0 until count).fold(0) { result, index ->
        result or ((bytes[offset + index].toInt() and 255) shl (8 * index))
      }
    var offset = 0
    var entries = 0
    while (offset + 46 <= bytes.size) {
      if (little(offset, 4) == 0x02014b50) {
        val mode = little(offset + 38, 4) ushr 16
        assertThat(mode and 0x1ff).isIn(420, 493)
        val name = String(bytes, offset + 46, little(offset + 28, 2), Charsets.UTF_8)
        assertThat(mode and 0x1ff).isEqualTo(if (name.endsWith('/')) 493 else 420)
        entries++
        offset += 46 + little(offset + 28, 2) + little(offset + 30, 2) + little(offset + 32, 2)
      } else offset++
    }
    assertThat(entries).isPositive()
  }

  @Test
  fun preparationOwnsBooleanSwitchesAndProjectPropertiesOverrideSettings() {
    fixture(withSigner = false)
    val filesOnly = runner("atrNjordTransportableFiles").build()
    assertThat(filesOnly.tasks.map { it.path })
      .contains(":prepareAtrMavenBundle", ":atrNjordTransportableFiles")
      .noneMatch {
        it.startsWith(":createAtr") || it.endsWith("Archive")
      }
    val property =
      runner("atrNjordArchive", "-PatrMavenBundle.requireSignatures=true").buildAndFail()
    assertThat(property.output).contains("Missing or empty signature")
    runner(
        "prepareAtrMavenBundle",
        "--no-require-signatures",
        "--no-sign-missing",
        "atrNjordArchive",
        "-PatrMavenBundle.requireSignatures=true",
        "-PatrMavenBundle.signMissing=true",
      )
      .build()
    val presence =
      runner(
          "prepareAtrMavenBundle",
          "--require-signatures",
          "--no-sign-missing",
          "atrNjordArchive",
          "-PatrMavenBundle.requireSignatures=false",
        )
        .buildAndFail()
    assertThat(presence.output).contains("Missing or empty signature")
    val missing =
      runner(
          "prepareAtrMavenBundle",
          "--sign-missing",
          "atrNjordArchive",
          "-PatrMavenBundle.signMissing=false",
        )
        .buildAndFail()
    assertThat(missing.output)
      .contains("No root signatory is configured", "configure Gradle signing in the root project")
    val creator = runner("atrNjordArchive", "--no-require-signatures").buildAndFail()
    assertThat(creator.output).contains("Unknown command-line option '--no-require-signatures'")
    val invalid =
      runner("prepareAtrMavenBundle", "-PatrMavenBundle.requireSignatures=tru").buildAndFail()
    assertThat(invalid.output)
      .contains("Project property 'atrMavenBundle.requireSignatures' must be true or false")
  }

  @Test
  fun disabledSigningDoesNotResolveConfiguredKeyMaterial() {
    fixture(withSigner = false)
    Files.writeString(
      projectDir.resolve("build.gradle"),
      "\nsigning.useInMemoryPgpKeys('invalid key material', 'fixture')\n",
      java.nio.file.StandardOpenOption.APPEND,
    )
    val args =
      arrayOf(
        "prepareAtrMavenBundle",
        "--no-sign-missing",
        "atrCentralArchive",
        "-PatrMavenBundle.signMissing=true",
      )
    val first = runner(*args).build()
    assertThat(first.output).contains("Configuration cache entry stored", "Isolated Projects")
    assertThat(projectDir.resolve("build/atr-maven-bundle/central/signed bundle-1.0.zip").toFile())
      .isFile()
    assertThat(runner(*args).build().output).contains("Configuration cache entry reused")
  }

  private fun ephemeralKey(): PGPPublicKey {
    val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val pgp =
      JcaPGPKeyPair(PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.RSA_GENERAL, keyPair, Date())
    val sha1 = BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1)
    val encryptor =
      BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1)
        .build("fixture".toCharArray())
    val secret =
      PGPSecretKey(
        PGPSignature.DEFAULT_CERTIFICATION,
        pgp,
        "fixture@example.invalid",
        sha1,
        null,
        null,
        BcPGPContentSignerBuilder(pgp.publicKey.algorithm, HashAlgorithmTags.SHA256),
        encryptor,
      )
    ArmoredOutputStream(Files.newOutputStream(projectDir.resolve("fixture-key.asc"))).use {
      secret.encode(it)
    }
    return pgp.publicKey
  }

  private fun verifySignatures(publicKey: PGPPublicKey) {
    val version =
      projectDir.resolve(
        "build/atr-maven-bundle-internal/prepared-repository/org/example/fixture/1.0",
      )
    listOf("fixture-1.0.pom", "fixture-1.0.txt").forEach { name ->
      val signatureFile = version.resolve("$name.asc")
      assertThat(signatureFile.readText()).startsWith("-----BEGIN PGP SIGNATURE-----")
      Files.newInputStream(signatureFile).use { input ->
        val signature =
          (PGPObjectFactory(PGPUtil.getDecoderStream(input), BcKeyFingerprintCalculator())
            .nextObject() as PGPSignatureList)[0]
        signature.init(BcPGPContentVerifierBuilderProvider(), publicKey)
        Files.newInputStream(version.resolve(name)).use { source ->
          val buffer = ByteArray(8192)
          while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            signature.update(buffer, 0, read)
          }
        }
        assertThat(signature.verify()).isTrue()
      }
    }
  }

  private fun fixture(withSigner: Boolean) {
    val repository = File(System.getProperty("plugin.repository")).toURI()
    val pluginVersion = System.getProperty("plugin.version")
    projectDir
      .resolve("settings.gradle")
      .writeText(
        """
      pluginManagement { repositories { maven { url = uri('$repository') } } }
      plugins { id 'org.buildish.atr-maven-bundle.gradle-plugin' version '$pluginVersion' }
      rootProject.name = 'signing-consumer'
      atrMavenBundle { requireSignatures.set(false); signMissing.set(false); baseName.set('signed bundle') }
      """
          .trimIndent(),
      )
    projectDir.resolve("payload.txt").writeText("payload")
    val signing =
      if (withSigner)
        """
        signing.useInMemoryPgpKeys(providers.fileContents(layout.projectDirectory.file('fixture-key.asc')).asText.get(), 'fixture')
        class CountingSignatory implements Signatory {
          private final Signatory target
          private final File counter
          CountingSignatory(Signatory target, File counter) { this.target = target; this.counter = counter }
          String getName() { target.name }
          String getKeyId() { target.keyId }
          void sign(InputStream input, OutputStream output) {
            target.sign(input, output)
            counter.parentFile.mkdirs()
            counter.append('signed\n')
          }
          byte[] sign(InputStream input) {
            def output = new ByteArrayOutputStream()
            sign(input, output)
            output.toByteArray()
          }
        }
        class CountingProvider implements SignatoryProvider<Signatory> {
          private final SignatoryProvider target
          private final File counter
          CountingProvider(SignatoryProvider target, File counter) { this.target = target; this.counter = counter }
          void configure(SigningExtension extension, Closure closure) { target.configure(extension, closure) }
          Signatory getDefaultSignatory(Project project) {
            def signatory = target.getDefaultSignatory(project)
            signatory == null ? null : new CountingSignatory(signatory, counter)
          }
          Signatory getSignatory(String name) {
            def signatory = target.getSignatory(name)
            signatory == null ? null : new CountingSignatory(signatory, counter)
          }
        }
        def counter = layout.buildDirectory.file('sign-count.txt').get().asFile
        preparation.outputs.file(counter)
        signing.signatories = new CountingProvider(signing.signatories, counter)
        """
          .trimIndent()
      else ""
    projectDir
      .resolve("build.gradle")
      .writeText(
        """
      import org.gradle.plugins.signing.Sign
      import org.gradle.plugins.signing.SigningExtension
      import org.gradle.plugins.signing.signatory.Signatory
      import org.gradle.plugins.signing.signatory.SignatoryProvider

      plugins { id 'maven-publish' }
      group = 'org.example'; version = '1.0'
      publishing { publications { main(MavenPublication) { artifactId = 'fixture'; artifact(file('payload.txt')) { extension = 'txt' } } } }
      // Deliberate early realization: root signing is configured afterward.
      def preparation = tasks.named('prepareAtrMavenBundle').get()
      assert tasks.withType(Sign).empty
      $signing
      """
          .trimIndent(),
      )
  }

  private fun runner(vararg tasks: String): GradleRunner =
    GradleRunner.create()
      .withProjectDir(projectDir.toFile())
      .withGradleInstallation(File(System.getProperty("gradle.installation")))
      .withTestKitDir(File(System.getProperty("test.gradle.user.home"), "test-kit"))
      .withArguments(
        tasks.toList() +
          listOf(
            "--stacktrace",
            "--configuration-cache",
            "--configuration-cache-problems=fail",
            "-Dorg.gradle.isolated-projects=true",
            "--gradle-user-home",
            System.getProperty("test.gradle.user.home"),
            "-Dorg.gradle.java.installations.auto-download=false",
          ),
      )
}
