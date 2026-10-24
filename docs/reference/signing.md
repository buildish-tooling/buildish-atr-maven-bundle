---
title: "Checksums and signing"
description: "Preserve native sidecars and optionally generate missing checksums and signatures."
weight: 30
---

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

Disabling generation also prevents input snapshotting from inspecting the
configured signer's key identity. When generation is enabled, Gradle can inspect
a configured signer while evaluating task inputs, even if native signatures are
already present.

## Common failures

| Failure | Action |
| --- | --- |
| Missing or empty signature | Supply native signatures, enable generation, or explicitly disable the presence requirement. An empty existing signature must be replaced at its source. |
| No root signatory configured | Configure root Gradle signing before requesting generation of absent signatures. |
| Checksum mismatch | Correct the exported payload or its existing checksum; mismatching sidecars are not silently replaced. |
| Destination collision | Give the selected publications distinct repository paths, or exclude one publication. |
| Unknown option on an output task | Put Boolean options immediately after `:prepareAtrMavenBundle`. |

See [configuration](../configuration/) for settings and exclusions.
