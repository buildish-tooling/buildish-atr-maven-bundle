---
title: "Security threat model"
description: "Draft trust boundaries, security properties and responsibilities for local Maven bundles."
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

## Status and scope

**Draft; reviewed 2026-10-26.** This model describes the current local bundle
creation path of the Buildish ATR Maven Bundle Gradle settings plugin. It is
context for users and report triage, not an audit, proof of security, or final
security-team disposition. Suspected vulnerabilities belong in the private
reporting process in `SECURITY.md`: [security@buildish.org](mailto:security@buildish.org).

The scope includes publication selection, native local Maven exports, inventory
parsing, repository preparation, checksum completion, optional signing, and
archive/directory outputs. It also includes the plugin's use of Gradle, the JVM
XML parser and configured signatories at those boundaries. Build scripts,
other plugins, Gradle/JVM internals, GPG agents and downstream uploaders have
separate responsibilities described below. Test fixtures are evidence for
behavior, not deployed services or an attacker entry point.

Provenance tags distinguish *(documented)* behavior in the linked project
references, *(maintainer)* trust assumptions confirmed on 2026-10-26, and
*(inferred)* observations or analysis derived from source review. Confirmation
of the environmental assumptions does not turn source observations into a
security guarantee. No vulnerability finding or severity score is established
by this document.

## Intended use and assets

*(documented)* — A user runs root Gradle tasks to turn actual Maven publications
into local repository ZIPs, Njord transportable bundles or exploded directories.
Collection and sidecar preparation are shared by the requested outputs. The
plugin performs no upload or release orchestration. See the
[configuration](../reference/configuration/), [outputs](../reference/outputs/) and
[signing](../reference/signing/) references.

*(inferred)* — Assets are publication bytes and coordinates, the integrity
of selected bundle contents, unrelated workspace files, final output files, and the authority and
confidentiality of signing material. Release reviewers and downstream uploaders
also depend on understanding what the bundle's checks do and do not establish.

## Actors, assumptions and trust boundaries

| Actor or boundary | Trust and responsibility | Evidence |
| --- | --- | --- |
| Build owner and release operator | Choose the code to execute, selected publications, task options, output locations and signing identity. | *(maintainer; 2026-10-26)* |
| Build scripts, applied plugins and artifact-producing tasks | Execute with the build user's permissions. They can change publication bytes and invoke signing independently of this plugin. Configuration-cache compatibility and isolated projects are not process sandboxes. | *(maintainer; 2026-10-26)* |
| Publication data | Coordinates, exported files, snapshot XML and sidecars cross a data-validation boundary into plugin-owned preparation. They can be malformed even in an otherwise trusted build. They are not proof of publisher identity. | *(inferred)* |
| Local exports and inventories | Private task handoffs, not a supported import format for downloaded inventories or arbitrary repositories. A writable workspace is not an authenticated storage channel. | *(maintainer; 2026-10-26)*; *(inferred)* |
| Filesystem and concurrent writers | Build-owned paths are under the operator's control and remain stable during execution. Untrusted users/processes do not share write access to the build tree. | *(maintainer; 2026-10-26)* |
| Gradle/JVM and configured signer | Supply task execution, file-copy/archive operations, XML parsing and signing implementations. Signers may invoke external programs and agents with their own behavior. | *(inferred)* |
| Bundle recipient or upload service | Receives files, without receiving a security approval from this plugin. Establishes key trust, upload authorization and repository admission independently. | *(documented)* |

*(inferred)* — A malicious dependency may influence the artifact produced by a trusted build;
this plugin does not analyze the dependency's behavior or establish the safety
of that artifact. A malicious actor controlling a build script already has code
execution in that build. A report still needs assessment if plugin behavior
exposes a different principal's files or credentials, violates a claimed data
boundary, or grants authority the actor did not already possess.

## Data and executable flow

*(inferred)* — All plugin tasks execute within the ordinary Gradle build
security context; the following steps are data handoffs, not privilege elevation.

1. The settings extension freezes options and project paths. Project-local
   lifecycle actions discover Maven publications and apply exact exclusions.
2. Each selected publication is published through Gradle's native Maven writer
   to a private local export repository. Only the selected export tasks are
   dependencies of preparation; broad remote publication tasks are not invoked.
3. Inventory tasks select native publication files, existing sidecars and native
   snapshot metadata, recording paths and publication facts in a private binary
   handoff. Snapshot XML is parsed with external DTD/schema access disabled and
   DOCTYPE declarations rejected.
4. Shared preparation reads inventories, resolves source paths, rejects basic
   ownership/path problems and copies files into a fresh prepared repository.
   Existing checksums are verified and missing checksums completed. Missing
   primary signatures may be generated through the configured root signatory.
5. Creators consume the prepared tree and, for NTB, generate format metadata.
   Archive tasks write staging ZIPs before installing final archives. Files
   tasks synchronize their owned destination directories.
6. A separate user-selected process reviews or uploads the output. Native build
   dependency resolution or a configured signer may involve external services;
   the plugin's local output workflow is not a guarantee of an offline build.

Publication contents are opaque bytes except for recognized sidecars and native
snapshot XML. JARs and other payload archives are not unpacked or executed by
bundle preparation. Archive extraction safety in a recipient is a separate
boundary from the paths used to create this bundle.

## Security-relevant controls and limits

| Property | Current behavior and limit | Evidence |
| --- | --- | --- |
| Publication scope | Actual Maven publications are eligible by default, including root publications. Exact exclusions remove selections. An unpublished dependency may build but gains no publication merely by being a dependency. This is selection, not an authorization system between Gradle projects. | *(documented)*; *(inferred)* |
| Repository path handling | Repository-relative paths reject absolute paths, traversal segments, backslashes and Windows drive prefixes. Resolution rejects symlink components from the export root downward. Native export collection also rejects symlinks found in its tree. This does not establish the safety of arbitrary ancestors of a configured build directory. | *(inferred)* |
| Destination ownership | Duplicate destination paths fail even for identical bytes. Preparation reserves destination files on the actual filesystem before copying payloads, detecting aliases such as case collisions and file/directory conflicts. No cross-process lock or race-resistant filesystem sandbox is provided. | *(inferred)* |
| Basic completeness | Missing expected primary files/POMs, missing snapshot-metadata targets and recognizable orphan sidecars fail. This is basic consistency checking, not complete Maven/POM or remote-admission validation. | *(documented)*; *(inferred)* |
| Checksum consistency | Existing recognized MD5, SHA-1, SHA-256 and SHA-512 checksums are checked against the retained bytes. Missing primary/metadata checksums are generated; valid existing bytes are preserved. Checksums supplied beside a substituted payload do not authenticate it. Legacy hashes are compatibility sidecars, not a claim of collision resistance or identity. | *(documented)*; *(inferred)* |
| Signature policy | Nonempty primary signatures are required by default; presence can be disabled explicitly. Generation is separately disabled by default. Existing signatures are preserved, including their lack of verified key identity. Any nonempty `.asc` content can satisfy presence; no cryptographic verification is performed. | *(documented)*; *(inferred)* |
| Signing scope | Generation signs absent primary signatures using root Gradle signing. It does not sign checksum or snapshot-metadata files, repair empty signatures, or verify an existing signature against a trusted key set. Each file is a signing operation; multiple creators share one preparation task. | *(documented)*; *(inferred)* |
| Signing opt-out | The effective generation option gates the nested signatory input, avoiding key-identity evaluation when generation is disabled. When enabled, Gradle may inspect a configured signer during input snapshotting, even when signatures are already present. Opt-out does not constrain independent build tasks or another plugin. | *(inferred)* |
| Output replacement | A completed staging archive is copied to a sibling temporary file and moved into place, atomically where supported, with a replacement fallback. This reduces partial final archives; it is not a power-loss durability guarantee. Directory synchronization is not transactional and removes stale files in its destination. | *(inferred)* |
| Reproducibility | Fixed ordering, timestamps and modes support comparisons of identical prepared bytes. They do not prove provenance or artifact safety; newly generated signatures and upstream payloads can differ. | *(documented)*; *(inferred)* |

## Signing material and filesystem ownership

*(inferred)* — The plugin delegates signing to Gradle's root signatory and
ASCII signature type. It does not import keys or configure keyrings. Its bundle
layout has no intended entry for secret keys or passwords, but a build can
explicitly publish any file, including a secret, as an artifact. No content
scanner prevents that mistake. Signing configuration itself is separate from
publication selection.

*(maintainer; 2026-10-26)* — The operator controls key provisioning, signer configuration,
agent policy, passphrases, revocation, and trust in the selected key.

*(inferred)* — Treat the build process, Gradle state/configuration-cache storage, CI logs and diagnostic
artifacts as potentially sensitive. The plugin provides no independent secret
storage or guarantee that delegated Gradle/signatory state excludes key material
from every cache or diagnostic. Granting an untrusted build access to a release
signer violates this assumption; hardware confirmation alone does not identify
the intended release bytes.

*(maintainer; 2026-10-26)*; *(inferred)* — Native exports, prepared trees, metadata trees
and exploded outputs are reset or synchronized in plugin-owned locations under
configured build directories by default. The exact Gradle file/task properties
remain customizable by trusted build code. These locations must be disposable
outputs, not a repository checkout, shared directory or storage containing
unrelated files. The prepared-tree deletion helper does not follow child symlinks and rejects
a symlink root; this is not a guarantee covering every Gradle copy/delete or
archive-output operation or a hostile concurrent writer. Restrictive access to
workspace parents and isolated CI workspaces remain environmental controls.

## Abuse and misuse cases

*(inferred)* — These scenarios describe boundaries and expected handling,
not confirmed exploitable vulnerabilities.

| Scenario | Handling, residual risk and responsibility |
| --- | --- |
| A producer supplies a path that escapes its repository or collides with another publication | Plugin path/ownership checks reject the supported data path. A bypass affecting unrelated files is a project-side report to investigate, subject to a demonstrated attacker capability. |
| A payload is replaced together with matching checksums and a nonempty fake signature | Consistency and presence can pass. The recipient/operator must establish signature validity and key trust separately; the plugin makes no authenticity claim. |
| An untrusted change runs in a release job with signing credentials | Build code can read available secrets or request signatures with existing authority. The CI/operator boundary must separate untrusted builds from signing authority. A separate plugin-caused authority expansion remains reportable. |
| Another process changes paths or bytes during preparation, signing or output installation | No hostile-writer or snapshot-isolation guarantee exists. The operator owns workspace access and concurrent-build isolation. Static symlink rejection does not eliminate time-of-check/time-of-use races. |
| A build redirects an owned output into valuable storage | Reset/sync can delete contents at that selected location. Output property configuration is trusted; operators must reserve disposable directories. An unintended escape with safe configuration is a different, project-side question. |
| An artifact embeds executable, malicious or confidential content | Payloads are bundled as supplied. Producers and release reviewers own content safety; no malware, secret or nested-archive scanner is provided. |
| Very large exports, inventories or XML exhaust time, disk or heap | Hashing uses a bounded buffer, but there are no global file-count, byte-size, XML-size or execution-time quotas. Artifact producers and build operators own resource budgets; this is not a public ingestion service. |
| A failed preparation leaves partial output or an earlier archive remains after failure | Failure is signaled by Gradle; output existence alone is not success. Consumers must require a successful build and select that run's output before upload. Files output is not an atomic snapshot. |
| A bundle is altered after generation or uploaded to the wrong repository | Output access, transport integrity, destination selection and uploader credentials are outside local bundle creation. Signing primary files does not authenticate the whole outer archive or NTB indexes. |

## Responsibility and triage

*(documented)* — `SECURITY.md` owns disclosure handling and states that this draft
is non-authoritative. Maintainers and the security team decide report disposition.

*(inferred)* — Project responsibility includes enforcing the plugin's claimed path, selection,
ownership and sidecar behavior, invoking the selected signer only within the
configured generation path, and accurately documenting output semantics. The
operator controls executable build inputs, workspace access, resource limits,
secrets, signing agents and the upload process. Gradle/JVM/signatory defects need
assessment of the actual dependency and reachable plugin behavior; invoking a
dependency does not by itself establish a plugin vulnerability.

Triage should identify the actor's starting access, affected asset, claimed
invariant and concrete path across a boundary. An invariant violation may be a
correctness bug, a security issue, or both depending on that evidence. Deliberate
unsigned output, absent cryptographic verification, or already-authorized build
code reading its own environment are not sufficient alone to establish a
security issue. Neither an out-of-scope label nor this draft should suppress a
report demonstrating an additional trust-boundary crossing. A test using
privileged setup, fabricated authority or mocked trust is not proof of attacker
reachability.

## Evidence and verification limits

*(inferred)* — Source areas reviewed for this model:

| Area | Relevant implementation and tests |
| --- | --- |
| Selection and task wiring | `AtrMavenBundleExtension`, `ProjectLifecycle`, `NativeExports`, `BundleProjectPlugin`; `BundleProjectPluginTest`, `PackagedSettingsPluginTest` |
| Native paths and handoffs | `NativePublicationInventory`, `PublicationInventory`, `PrepareRepository`; `PublicationInventoryTest` |
| Checksums and signing | `PrepareAtrMavenBundle`, `SidecarCompletion`; `SidecarCompletionTest`, `SigningPreparationTest` |
| Final outputs | `BundleCreator`, `BundleOutputs`, `InstallBundleArchive`; `BundleCreatorTest`, packaged functional tests |

Existing tests exercise invalid/traversing paths, duplicate ownership,
filesystem case behavior, missing native files, sidecar consistency, signature
preservation, software signature generation, signing opt-out and configuration
cache reuse. They establish bounded regression coverage, not a general security
proof. Native Windows execution, hardware/GPG signing and live remote import
remain qualification gaps. Hostile concurrent-writer resistance and adversarial
resource limits are not claimed or established by these tests. Platform-specific
path reports must be assessed on the affected native filesystem.

## When to revisit this model

*(inferred)* — Revisit this model if creators become third-party extensions,
inventories become external inputs, the plugin extracts archives, performs cryptographic trust
validation, accepts remote input, uploads releases, or promises safe operation in
shared writable workspaces. Those features introduce additional boundaries.
