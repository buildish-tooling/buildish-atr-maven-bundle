<!--
Copyright 2026 The Buildish Authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Buildish ATR Maven Bundle for Gradle

Create local Maven publication bundles for review or later upload to Apache
Trusted Releases (ATR), Maven Central or Njord. The plugin collects selected
native Maven publications, prepares their sidecars once and writes repository
ZIPs, Njord transportable bundles (NTB), or exploded files. It performs no upload
or release orchestration. Producing a bundle does not establish acceptance by a
remote service.

This is development software. The project documentation site is
[Buildish ATR Maven Bundle](https://buildish.org/components/atr-maven-bundle/).

Read the [unreleased getting-started guide](https://buildish.org/components/atr-maven-bundle/development/getting-started/)
and [unreleased reference](https://buildish.org/components/atr-maven-bundle/development/reference/).
The authored website content lives in [site/pages/](site/pages/) and [docs/](docs/).

## Configure settings and native publications

Apply the plugin in `settings.gradle.kts`, using an available plugin version and
its repository. It is a settings plugin; applying it to a project fails.

```kotlin
import org.buildish.atrmavenbundle.gradleplugin.AtrMavenBundleExtension

plugins {
    id("org.buildish.atr-maven-bundle.gradle-plugin") version "<version>"
}

extensions.configure<AtrMavenBundleExtension>("atrMavenBundle") {
    // Defaults: requireSignatures=true, signMissing=false.
    // Optional; otherwise use the root project's final name/version.
    baseName.set("my-release")
    bundleVersion.set("1.0")

    excludeProject(":internal")
    excludePublication(":library", "experimental")
}
```

The equivalent Groovy settings extension is `atrMavenBundle { ... }`. Exclusions
use exact absolute Gradle project paths and exact publication names; a project
exclusion does not recursively exclude its children. Settings are frozen after
settings evaluation.

Configure `maven-publish` in projects that publish. For example, in a Java
library's `build.gradle.kts`:

```kotlin
plugins {
    `java-library`
    `maven-publish`
}
group = "org.example"
version = "1.0"
publishing {
    publications {
        create<MavenPublication>("main") { from(components["java"]) }
    }
}
```

The root project's publications are eligible too. Native Gradle plugin marker
POMs, customized POMs, custom artifacts, platform/BOMs and emitted Gradle module
metadata retain their native publishing behavior. Projects without selected
Maven publications contribute nothing. Required unpublished project dependencies
may still compile; they acquire no publication or bundle payload merely by being
dependencies. The plugin publishes each selected publication to its own local
export repository and does not invoke broad `publish`, `publishToMavenLocal`, or
user-configured publishing destinations.

## Choose outputs

Run these root tasks from your consumer build:

| Layout | Archive | Exploded files | Archive alias |
| --- | --- | --- | --- |
| Maven Central | `:atrCentralArchive` | `:atrCentralFiles` | `:atrCentral` |
| Njord repository | `:atrNjordArchive` | `:atrNjordFiles` | `:atrNjord` |
| Njord transportable | `:atrNjordTransportableArchive` | `:atrNjordTransportableFiles` | `:atrNjordTransportable` |

`:atrMavenBundle` aliases `:atrNjordArchive`. Request several concrete tasks to
produce several layouts or both archive and files; they share one
`:prepareAtrMavenBundle` execution.

```shell
./gradlew :atrCentralArchive :atrNjordArchive :atrNjordTransportableFiles
```

With the default root build directory, outputs are
`build/atr-maven-bundle/<creator-id>/<baseName>-<bundleVersion>`
directories, or the same paths with `.zip` (`central`, `njord`) or `.ntb`
(`njord-transportable`). Archive entries start at the repository root, without
an extra directory prefix. Names must be nonempty filename components. Internal
exports and inventories stay outside final output directories. Files outputs
synchronize owned trees and remove stale contents.

Central and Njord currently share the Maven repository layout. Snapshot
publications keep native timestamped artifact filenames and version-directory
metadata. Their inclusion is not a claim that Central's release-upload service
accepts snapshots. NTB follows Njord **0.9.10** properties and artifact/metadata
indexes: pure releases and pure snapshots work; a mixed selection fails only
for NTB. Exclude publications or use repository-layout outputs for a mixed tree.
NTB files are an exploded transportable tree, not a claim that an importer
accepts a directory.

## Checksums, signatures and CLI policy

Shared preparation validates retained MD5, SHA-1, SHA-256 and SHA-512 checksums
and completes absent checksums for primary publication files and retained native
snapshot metadata. Valid existing bytes are preserved. Malformed or mismatching
checksums, orphan sidecars and destination collisions fail. It does not perform
full Central admission validation or cryptographic verification of signatures.

By default, every primary file (including POM and emitted `.module`) must have a
nonempty signature. Existing signatures are preserved. Missing-signature
generation is off by default; when enabled, only absent primary signatures are
generated. An existing empty signature fails the presence policy and is not
repaired. Existing signature checksums are checked and retained, but no new
checksums of signatures, recursive sidecars or signatures of checksums/snapshot
metadata are generated.

Policy precedence is explicit preparation option > project property > settings
convention. Project properties accept only `true` or `false`, case-insensitively.
Only `:prepareAtrMavenBundle` accepts the Boolean options and their inverse forms:

```shell
# Unsigned output, using one policy for every requested creator.
./gradlew -PatrMavenBundle.requireSignatures=false :atrCentralArchive :atrNjordFiles

# Equivalent task options; disabling generation is a separate choice.
./gradlew :prepareAtrMavenBundle --no-require-signatures --no-sign-missing :atrCentralArchive :atrNjordFiles

# Generate missing signatures once, then require them for both archives.
./gradlew :prepareAtrMavenBundle --sign-missing :atrCentralArchive :atrNjordArchive
```

Settings can opt out with `requireSignatures.set(false)` and can enable generation
with `signMissing.set(true)`. The project properties are
`atrMavenBundle.requireSignatures` and `atrMavenBundle.signMissing`. Creator and
lifecycle tasks reject preparation options; put options immediately after the
preparation task as shown.

## Root signing

Generation uses the root project's public Gradle signing configuration. The
plugin applies root `signing` but creates no native per-publication `Sign` tasks.
For Kotlin DSL signing accessors, explicitly declare `signing` in the root
project too. One possible configuration uses the normal Gradle in-memory signer:

```kotlin
plugins { signing }
signing {
    useInMemoryPgpKeys(
        providers.gradleProperty("signingKey").orNull,
        providers.gradleProperty("signingPassword").orNull,
    )
}
```

Supply signing material using your normal local Gradle practices. Gradle's other
root signing configurations, including `useGpgCmd()`, can also provide the
signatory; GPG agent, hardware and keyring setup remain user-managed. A signer
is required only when a requested preparation actually needs to generate a
missing signature. Already-present signatures require no signer even when
`signMissing=true`. Missing files are signed individually, so hardware signing
may require one touch per primary. A preparation that runs again can sign again;
configuration-cache reuse does not promise skipped signing or reproducible new
signature bytes. The plugin never imports keys or configures a keyring.

## Reproducibility and compatibility

For identical prepared bytes and options under the same Gradle/JDK/compression
settings, archives use stable order, fixed timestamps and uniform 0644/0755
file/directory modes. Exploded copies use `1980-02-01T00:00:00Z`, including output
directories, with those permissions where POSIX attributes are supported. Native
publication inputs and bytes inside published archives remain unchanged. Owner,
group, ACLs and creation/change times are outside this guarantee. Newly generated
signatures and upstream artifacts can vary independently.

The plugin targets Gradle **9.1+** and Java **17+**; this repository builds with
Gradle **9.8.2** and Java **21**. Java 17 classfiles/JVM attributes and a Kotlin
2.2-compatible API boundary are checked. Packaged Kotlin DSL consumers pass on
Linux with Gradle 9.1.0/Java 17 and Gradle 9.8.2/Java 21, with strict configuration
cache and isolated projects. The broader Gradle 9.8.2/Java 21 tests cover
Java/Kotlin/Groovy/BOM publications and real ephemeral software signing. Native
Windows execution remains unqualified at present. Non-POSIX filesystem tests do
not establish Windows runtime support. GPG/hardware signing and live
ATR/Central/Njord import are not covered by these tests.

## Building and qualification

Build this repository with Java 21 and Gradle 9.8.2. `check` includes unit tests,
strict packaged consumer tests and the Stutter `compatTest` suite. The sparse
lock pins exactly Gradle 9.1.0/Java 17 and Gradle 9.8.2/Java 21. Make both JDKs
available to Gradle toolchain discovery. Stutter selects each Gradle/JVM pair;
TestKit downloads and caches the selected Gradle distributions as needed.

```shell
./gradlew -Dorg.gradle.java.installations.auto-download=false check
```

To run only one pair, use `:gradle-plugin:compatTestJava17Gradle9.1.0` or
`:gradle-plugin:compatTestJava21Gradle9.8.2`. `:gradle-plugin:compatTest` aggregates
both. These are ordinary Gradle test tasks; tests publish only to disposable
local fixture repositories. The Java toolchain download flag above controls JDK
provisioning independently of TestKit's Gradle distribution cache.

## Community

- [Contributing](CONTRIBUTING.md)
- [Code of Conduct](CODE_OF_CONDUCT.md)
- [Security policy](SECURITY.md)

## License

See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
