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

# Release contract

This is a Buildish-owned public component, not an ASF release merely because of
its checkout location or Apache license. The plugin creates local publication
bundles; publishing this plugin is a separate operation from uploading a consumer's
bundles to ATR, Central or Njord.

## Required release scope

| Destination | Content and consumer | Completion condition |
| --- | --- | --- |
| GitHub (authoritative release record) | Immutable `vX.Y.Z`, reviewed notes, signed Maven ZIP, SHA-256/SHA-512 sidecars, `release-manifest-v1.json` | Publish last, after registry readback and consumer checks; verify locked inventory |
| Maven Central (required distribution) | `org.buildish.atr-maven-bundle:gradle-plugin:X.Y.Z`, parent POM, plugin marker, module metadata, sources/Javadoc, signatures/checksums | Exact retained files visible and a settings-plugin consumer succeeds |
| Gradle Plugin Portal (required distribution) | Plugin `org.buildish.atr-maven-bundle.gradle-plugin`, implementation, marker and metadata | Version registered, retained primary bytes readable, settings-plugin consumer succeeds |

The ZIP contains seven primary files: parent POM; plugin POM, module metadata,
shaded implementation JAR, sources JAR, Javadoc JAR; and the marker POM. Every primary
has an ASCII-armored OpenPGP signature and SHA-256/SHA-512 sidecars. GitHub also
retains a signature and both checksums for the ZIP. There are no separately shipped
NTBs, consumer-created ZIPs, unshaded/test-fixture JARs, OCI images, npm packages or
PyPI distributions. GitHub's generated source archives identify source, without
promising archive byte stability across tag names.

Both registries are required by the maintainer's adoption decision. Existing source
already used Maven Publish, Plugin Publish and NMCP, but configured only local test
publication. Central needs the parent POM before Portal consumers can resolve it.
Central upload uses the standard Publisher API with the retained ZIP; Portal uses
a small trusted Gradle publishing project with compilation/archive tasks disabled
and POM/module outputs copied from the retained repository. Neither executes
selected-source build code with registry credentials.

## Identity and lifecycle choices

- Dispatch selects the exact stable version. Committed `version.txt` must contain
  that version or the same version with `-SNAPSHOT`. The credential-free build job
  writes the stable version only in its selected-source checkout before Gradle
  runs; main and the reviewed source commit remain unchanged. Candidate and final
  tags identify that original commit, so rebuilding from a tag requires the same
  version-setting step. Promotion reuses the resulting candidate artifacts.
- Source resolves once from the explicit ref or protected `main`; it must be an
  ancestor of main with successful `Required Checks` from `.github/workflows/ci.yml`,
  event `push`, branch `main`. The CI aggregator requires each essential job.
- Candidates use immutable `vX.Y.Z-rcN` tags and retained public prereleases.
  Promotion requires the exact tag and independently retained manifest SHA-256.
  The dispatch represents maintainer approval of that identity; no ASF vote is inferred.
- Candidate and final tags identify the same reviewed source commit. No generated
  Action commit or moving Git aliases are needed. Promotion reuses signed candidate
  artifacts without rebuilding. Source/signature/note changes require a new candidate;
  changed bytes at published registry coordinates require a new version.
- Signing uses full fingerprint `D52A35E355AB82E26B879B629890F738DF4BB337`, explicitly
  selected by the maintainer. Credential presence does not prove key usability.
- Trusted workflow/configuration/tool code runs from `github.workflow_sha`; the
  selected-source build runs in a separate credential-free checkout. Producer digests
  bind state and complete artifact transports at privilege boundaries.
- Mutation workflows share a non-canceling repository/version concurrency group.
  Manual publication must provide equivalent serialization.

## Pinned external tooling and limitations

Use published `buildish-release-tooling==0.1.4`, source tag `v0.1.4`, commit
`b4542a195705c13c3806ca34fed8e821bedc20c8`, Python >=3.11. No extra is needed:
there is no PyPI publication or attestation verification here. The launcher checks
installed distribution metadata and invokes its supported console script with an
explicit component configuration. It neither imports internal modules as an API
nor discovers sibling source checkouts or installs anything automatically.

Workflow/recovery reference: `80045552b0b75f3770aa09daff6e2120a543042d`.
[Successful self-promotion](https://github.com/buildish-tooling/buildish-release-tooling/actions/runs/43917189837)
confirms its skipped-PyPI-upload recovery path, not this component's registries.
Later retry diagnostics are not assumed to be in the wheel.

0.1.4 has no Central/Portal secondary-publication adapter. The component owns upload,
exact-byte readback, propagation budgets and consumer checks. Supported generic
`verification_results` bind deterministic readback records into the sealed v1
manifest; there is no invented secondary-target kind or v2 manifest. This does not
provide registry-wide enumeration of unknown extra coordinates. It verifies the
finite declared release inventory and rejects conflicting bytes at those identities.
The Portal registration-page check supplements Maven readback because Central
fallback alone cannot prove Portal registration. Live first-release validation must
confirm the Portal retains the expected POM/module bytes and registration semantics.
If it transforms them, stop promotion, retain the candidate and any draft, and document
the precise equivalence/verification contract before changing the gate.

An optional upstream improvement would add typed `maven-central` and
`gradle-plugin-portal` publication/verification records: stable coordinates, finite
file inventories, exact digests, Portal registration, deployment IDs, bounded
propagation, matching-partial recovery, durable evidence, and draft sealing. Its
acceptance tests should cover these services independently of PyPI and preserve v1
readability. This adoption does not claim those adapters ship in 0.1.4.
