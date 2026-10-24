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

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.buildish.atrmavenbundle.gradleplugin.internal.PublicationIdentity
import org.buildish.atrmavenbundle.gradleplugin.internal.model.FileRole
import org.buildish.atrmavenbundle.gradleplugin.internal.model.InventoryFile
import org.buildish.atrmavenbundle.gradleplugin.internal.model.PublicationInventory
import org.buildish.atrmavenbundle.gradleplugin.internal.model.SidecarCompletion
import org.gradle.plugins.signing.signatory.Signatory
import org.gradle.plugins.signing.type.SignatureType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SidecarCompletionTest {
  @TempDir lateinit var directory: Path
  private val hashes =
    linkedMapOf(
      "md5" to "900150983cd24fb0d6963f7d28e17f72",
      "sha1" to "a9993e364706816aba3e25717850c26c9cd0d89d",
      "sha256" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      "sha512" to
        "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
    )

  @Test
  fun missingChecksumsUseTheFourKnownDigestsAndLfWithoutRecursing() {
    val inventory = inventory("payload.txt")
    val result = complete(inventory)
    hashes.forEach { (extension, digest) ->
      assertThat(directory.resolve("payload.txt.$extension").readText()).isEqualTo("$digest\n")
    }
    assertThat(result.files).hasSize(5)
    assertThat(result.files.filter { it.role == FileRole.CHECKSUM }).hasSize(4)
    assertThat(result.files.map { it.path }).noneMatch {
      it.endsWith(".asc") || it.contains("sha256.sha")
    }
  }

  @Test
  fun retainedTrimmedHexAndGnuChecksumsKeepTheirExactBytes() {
    var inventory = inventory("payload.txt")
    val contents =
      mapOf(
        "md5" to " \t${hashes.getValue("md5")}\r\n",
        "sha1" to "${hashes.getValue("sha1").uppercase()}\n",
        "sha256" to "${hashes.getValue("sha256")}  payload.txt\n",
        "sha512" to "${hashes.getValue("sha512")} *payload.txt\r\n",
      )
    contents.forEach { (extension, text) ->
      inventory = sidecar(inventory, "payload.txt.$extension", FileRole.CHECKSUM, text)
    }
    complete(inventory)
    contents.forEach { (extension, text) ->
      assertThat(directory.resolve("payload.txt.$extension").readText()).isEqualTo(text)
    }
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "not-a-digest",
        "1234",
        "900150983cd24fb0d6963f7d28e17f72  other.txt",
        "900150983cd24fb0d6963f7d28e17f72\nextra",
      ],
  )
  fun malformedOrWrongFilenameChecksumFailsWithoutReplacement(text: String) {
    val inventory = sidecar(inventory("payload.txt"), "payload.txt.md5", FileRole.CHECKSUM, text)
    assertThatIllegalArgumentException()
      .isThrownBy { complete(inventory) }
      .withMessageContaining("Malformed checksum")
      .withMessageContaining(":library/main")
    assertThat(directory.resolve("payload.txt.md5").readText()).isEqualTo(text)
  }

  @Test
  fun mismatchingAndOversizedChecksumsFailWithoutReplacement() {
    val incorrect = "0".repeat(64)
    val inventory =
      sidecar(inventory("payload.txt"), "payload.txt.sha256", FileRole.CHECKSUM, incorrect)
    assertThatIllegalArgumentException()
      .isThrownBy { complete(inventory) }
      .withMessageContaining("Checksum mismatch")
    assertThat(directory.resolve("payload.txt.sha256").readText()).isEqualTo(incorrect)
    directory.resolve("payload.txt.sha256").writeText("x".repeat(5000))
    assertThatIllegalArgumentException()
      .isThrownBy { complete(inventory) }
      .withMessageContaining("Malformed checksum")
    assertThat(Files.size(directory.resolve("payload.txt.sha256"))).isEqualTo(5000)
  }

  @Test
  fun existingSignatureAndItsChecksumAreRetainedWithoutAdditionalSignatureChecksums() {
    var inventory = sidecar(inventory("payload.txt"), "payload.txt.asc", FileRole.SIGNATURE, "abc")
    inventory =
      sidecar(
        inventory,
        "payload.txt.asc.md5",
        FileRole.CHECKSUM,
        hashes.getValue("md5").uppercase(),
      )
    val signer = CountingSignatory()
    val type = TestSignatureType()
    val result =
      SidecarCompletion.complete(
          listOf(inventory),
          requireSignatures = true,
          signMissing = true,
          signatory = signer,
          signatureType = type,
        )
        .single()
    assertThat(signer.calls).isZero()
    assertThat(type.calls).isZero()
    assertThat(directory.resolve("payload.txt.asc").readText()).isEqualTo("abc")
    assertThat(directory.resolve("payload.txt.asc.md5").readText())
      .isEqualTo(hashes.getValue("md5").uppercase())
    assertThat(result.files.map { it.path })
      .doesNotContain("payload.txt.asc.sha1", "payload.txt.asc.asc")
    directory.resolve("payload.txt.asc.md5").writeText("0".repeat(32))
    assertThatIllegalArgumentException()
      .isThrownBy { complete(inventory) }
      .withMessageContaining("Checksum mismatch")
  }

  @Test
  fun existingSignaturesNeedNoSignerAndEmptySignaturesAreNeverRepaired() {
    val inventory =
      sidecar(inventory("payload.txt"), "payload.txt.asc", FileRole.SIGNATURE, "signature")
    SidecarCompletion.complete(
      listOf(inventory),
      requireSignatures = true,
      signMissing = true,
      signatory = null,
      signatureType = null,
    )
    directory.resolve("payload.txt.asc").writeText("")
    val signer = CountingSignatory()
    assertThatIllegalArgumentException()
      .isThrownBy {
        SidecarCompletion.complete(
          listOf(inventory),
          requireSignatures = true,
          signMissing = true,
          signatory = signer,
          signatureType = TestSignatureType(),
        )
      }
      .withMessageContaining("Missing or empty signature")
    assertThat(signer.calls).isZero()
    SidecarCompletion.complete(
      listOf(inventory),
      requireSignatures = false,
      signMissing = true,
      signatory = null,
      signatureType = null,
    )
    assertThat(directory.resolve("payload.txt.asc").readText()).isEmpty()
  }

  @Test
  fun absentSignaturesOnlyRequireASignerWhenGenerationIsActuallyNeeded() {
    val inventory = inventory("payload.txt")
    complete(inventory)
    assertThatIllegalArgumentException()
      .isThrownBy {
        SidecarCompletion.complete(
          listOf(inventory),
          requireSignatures = true,
          signMissing = false,
          signatory = null,
          signatureType = null,
        )
      }
      .withMessageContaining("Missing or empty signature")
    assertThatIllegalArgumentException()
      .isThrownBy {
        SidecarCompletion.complete(
          listOf(inventory),
          requireSignatures = false,
          signMissing = true,
          signatory = null,
          signatureType = null,
        )
      }
      .withMessageContaining("configure Gradle signing in the root project")
  }

  @Test
  fun eachMissingPrimaryIsSignedOnceEvenForIdenticalBytesAndMetadataIsNotSigned() {
    var inventory = inventory("payload.txt", "other.pom")
    directory.resolve("maven-metadata.xml").writeText("abc")
    inventory =
      inventory.copy(
        files =
          inventory.files +
            InventoryFile("maven-metadata.xml", FileRole.SNAPSHOT_METADATA, null, null, null),
      )
    val signer = CountingSignatory()
    val type = TestSignatureType()
    val result =
      SidecarCompletion.complete(
          listOf(inventory),
          requireSignatures = true,
          signMissing = true,
          signatory = signer,
          signatureType = type,
        )
        .single()
    assertThat(signer.calls).isEqualTo(2)
    assertThat(type.calls).isEqualTo(2)
    assertThat(result.files.filter { it.role == FileRole.SIGNATURE }.map { it.path })
      .containsExactly("other.pom.asc", "payload.txt.asc")
    assertThat(result.files.map { it.path })
      .contains("maven-metadata.xml.sha256")
      .doesNotContain("maven-metadata.xml.asc", "payload.txt.asc.sha256")
    SidecarCompletion.complete(
      listOf(result),
      requireSignatures = true,
      signMissing = true,
      signatory = signer,
      signatureType = type,
    )
    assertThat(signer.calls).isEqualTo(2)
  }

  @Test
  fun orphanAndRecursiveCompanionsFailBeforeCompletion() {
    val inventory = inventory("payload.txt")
    val orphan = sidecar(inventory, "missing.asc", FileRole.SIGNATURE, "signature")
    assertThatIllegalArgumentException()
      .isThrownBy { complete(orphan) }
      .withMessageContaining("Orphan signature")
    val checksum = sidecar(inventory, "missing.sha1", FileRole.CHECKSUM, "hash")
    assertThatIllegalArgumentException()
      .isThrownBy { complete(checksum) }
      .withMessageContaining("Orphan checksum")
    val first = sidecar(inventory, "payload.txt.md5", FileRole.CHECKSUM, hashes.getValue("md5"))
    val recursive = sidecar(first, "payload.txt.md5.md5", FileRole.CHECKSUM, "hash")
    assertThatIllegalArgumentException()
      .isThrownBy { complete(recursive) }
      .withMessageContaining("Orphan checksum")
  }

  @Test
  fun digestReadsUseOneBoundedPass() {
    var remaining = 16 * 1024 * 1024
    var maxBuffer = 0
    var total = 0
    val input =
      object : InputStream() {
        override fun read(): Int = error("Expected buffered reads")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
          maxBuffer = maxOf(maxBuffer, length)
          if (remaining == 0) return -1
          val size = minOf(remaining, length, 4096)
          buffer.fill(42, offset, offset + size)
          remaining -= size
          total += size
          return size
        }
      }
    assertThat(SidecarCompletion.digests(input).mapValues { it.value.length })
      .containsAllEntriesOf(mapOf("md5" to 32, "sha1" to 40, "sha256" to 64, "sha512" to 128))
    assertThat(total).isEqualTo(16 * 1024 * 1024)
    assertThat(maxBuffer).isLessThanOrEqualTo(64 * 1024)
  }

  @Test
  fun extensionlessCoordinatesDoNotIntroduceALeadingDotForSidecars() {
    val inventory =
      inventory("payload").let {
        it.copy(files = it.files.map { file -> file.copy(extension = "") })
      }
    val result =
      SidecarCompletion.complete(
          listOf(inventory),
          requireSignatures = true,
          signMissing = true,
          signatory = CountingSignatory(),
          signatureType = TestSignatureType(),
        )
        .single()
    assertThat(result.files.single { it.path == "payload.asc" }.extension).isEqualTo("asc")
    assertThat(result.files.single { it.path == "payload.sha256" }.extension).isEqualTo("sha256")
  }

  private fun inventory(vararg names: String): PublicationInventory {
    names.forEach { directory.resolve(it).writeText("abc") }
    return PublicationInventory(
      PublicationIdentity(":library", "main", "org.example", "library", "1.0"),
      directory.toString(),
      names.map {
        InventoryFile(it, FileRole.PRIMARY, "1.0", it.substringAfterLast('.', ""), null)
      },
    )
  }

  private fun sidecar(
    inventory: PublicationInventory,
    name: String,
    role: FileRole,
    contents: String,
  ): PublicationInventory {
    directory.resolve(name).writeText(contents)
    return inventory.copy(
      files = inventory.files + InventoryFile(name, role, "1.0", name.substringAfter('.'), null),
    )
  }

  private fun complete(inventory: PublicationInventory): PublicationInventory =
    SidecarCompletion.complete(
        listOf(inventory),
        requireSignatures = false,
        signMissing = false,
        signatory = null,
        signatureType = null,
      )
      .single()

  private class CountingSignatory : Signatory {
    var calls = 0

    override fun getName(): String = "fixture"

    override fun getKeyId(): String = "fixture-id"

    override fun sign(input: InputStream, destination: OutputStream) {
      calls++
      input.transferTo(OutputStream.nullOutputStream())
      destination.write("signature".toByteArray())
    }

    override fun sign(input: InputStream): ByteArray =
      ByteArrayOutputStream().also { sign(input, it) }.toByteArray()
  }

  private class TestSignatureType : SignatureType {
    var calls = 0

    override fun getExtension(): String = "asc"

    override fun fileFor(file: File): File = File(file.path + ".asc")

    override fun combinedExtension(file: File): String =
      file.extension.takeIf { it.isNotEmpty() }?.let { "$it.asc" } ?: "asc"

    override fun sign(signatory: Signatory, file: File): File {
      calls++
      val output = fileFor(file)
      Files.newInputStream(file.toPath()).use { input ->
        Files.newOutputStream(output.toPath()).use { sign(signatory, input, it) }
      }
      return output
    }

    override fun sign(signatory: Signatory, input: InputStream, destination: OutputStream) {
      signatory.sign(input, destination)
    }
  }
}
