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

# Release readiness — 2026-11-18

Central rejected the first `0.1.0` candidate's Maven bundle. The local correction
uses ATR Maven Bundle for signing, checksums and ZIP creation, removes NMCP, and
keeps a bounded Python Central client. No replacement candidate, key upload,
workflow dispatch or remote publication was performed by this correction.
See [contract](RELEASE-CONTRACT.md), [operator guide](RELEASE-PROCESS.md) and
[docs maintenance](../tools/versioned-docs/README.md).

Latest live attempt: promotion run `44253747978` for `v0.1.1-rc1` passed Central
publication (`PUBLISHED`) and exact-byte/consumer verification, then stopped at
`inspect-portal`. The Portal's unpublished-version page returned HTTP 400 instead
of 404. The local correction recognizes only that exact not-found page and keeps
other 400s fatal. Deploy it and freshly dispatch promotion with the same approved
candidate tag/digest; Central's immutable files must be reused. Portal publication
and final GitHub publication have not completed in that run. Earlier `0.1.0`
recovery notes below remain historical; the active publication version is `0.1.1`.

## Composition and baselines

- [x] Published `buildish-release-tooling==0.1.4`, Python >=3.11; no sibling source
  checkout or package extra. Production and rehearsal use its installed wheel.
  Released source: `b4542a195705c13c3806ca34fed8e821bedc20c8`.
  Reviewed workflow/recovery reference: `80045552b0b75f3770aa09daff6e2120a543042d`.
- [x] Candidate source resolves once, requires trusted push/main `Required Checks`,
  and builds without signing/publication credentials. `version.txt` may remain
  `X.Y.Z-SNAPSHOT`; only the build checkout is set to stable `X.Y.Z`.
- [x] A separate credential-free `build-tooling` job builds the packaging plugin
  from `github.workflow_sha`. Its producer-bound digest protects the repository
  loaded by the trusted signing job; selected-source code cannot produce that tool.
- [x] The trusted publisher's bundle mode runs ATR Maven Bundle signing, checksum
  completion and ZIP creation. It preserves all seven primary payloads, produces
  42 files, and independently checks checksums/signatures/full fingerprint.
  Release Tooling signs the outer ZIP. Python does not sign or create ZIPs.
- [x] Signing fingerprint remains `D52A35E355AB82E26B879B629890F738DF4BB337`.
  The Portal mode loads no candidate plugin, receives no signing secret, and
  uploads retained candidate files without compiling or generating payload JARs.
- [x] NMCP is removed. The Central helper submits once, retains the receipt and
  polls authenticated deployment status until `PUBLISHED`; `FAILED` immediately
  fails the upload job with validation details. Safe status/readback requests retry
  transient errors within explicit budgets. Ambiguous uploads require inspection.
- [x] Both Central and Plugin Portal public readback/consumers must pass before
  sealing/publishing GitHub. Matching sealed bytes are reused; GitHub is published
  last with the exact producer manifest digest and no subsequent asset uploads.
- [x] Component-owned versioned-docs helpers derive from source-only reference
  `07fbbefeaf300dd12aa5734e0e4172bc0229a7a9`; they are not in the pinned package.
  Existing scoped component metadata and aggregate binding/action were reviewed;
  no sibling repository was edited. Development docs remain separate/unreleased.

## Validation evidence

- [x] Portal HTTP 400 correction: 57 Python tests and 23 subtests, repository
  `check` and changed-file lint passed. Read-only inspection of the real
  `v0.1.1-rc1` bundle, verified against the run's candidate-manifest digest,
  returned Central `complete` and Portal `absent`. Evidence:
  `build/portal-inspection-0.1.1/readback.json`. No live mutation was performed.
- [x] Repository `check`: Gradle 9.9.1 / JDK 21, Java 17/21 consumer compatibility,
  functional/unit/build-logic tests, formatting, RAT and docs helpers passed after
  NMCP removal. Earlier 9.8.2 runs covered docs worktree presence/absence.
- [x] Python: 52 tests and 23 subtests passed. Includes bare Maven sidecar format,
  digest conflicts, signature/fingerprint/payload checks, asynchronous deployment
  failure, bounded transient retries, changed handoffs, cancellation/skip policy,
  and an independent shallow fetch preserving a newer trusted HEAD.
- [x] The real Central client against the independent fixture accepts an upload,
  then exits nonzero on failed checksum validation, retains error details and never
  exposes failed staged files as public. This reproduces the live failure class.
- [x] Native packaging prototype used an encrypted Ed25519 signing subkey, retained
  real component primary artifacts, and independently verified every signature,
  checksum and primary byte. Evidence: `build/trusted-packaging-fixture/result.json`.
- [x] Fresh five-job candidate rehearsal passed using installed 0.1.4 and `gh act`;
  this includes the separate trusted bootstrap and native signing/bundling.
  Evidence: `build/native-release-rehearsal/candidate.json`.
- [x] Refreshed installed-wheel/`gh act` matrix passed all five candidate and nine
  first-publication jobs, locked/partial recovery, failed-verification recovery,
  conflicting-file rejection, and accepted-upload/failed-Central-validation.
  Evidence: `build/native-release-rehearsal/results.json`. Matching locked/partial
  recovery preserved the sealed manifest with zero GitHub asset uploads; all
  recoveries performed zero registry uploads. Failed validation retained its error
  receipt and blocked every downstream job. Earlier reports under
  `build/workflow-rehearsal/` and `build/release-0.1.0-candidate-rehearsal/` used the
  defective Maven sidecar contract and establish historical lifecycle behavior only.
- [x] Changed workflow/action scan passed. Linux release jobs use the published
  harness's supported `ubuntu-26.04` mapping. These rehearsals do not prove Windows
  behavior, hosted runner isolation, live cancellation/skip propagation, settings,
  credentials, registry approval, or complete Central POM/keyserver admission.
- [x] Prior aggregate/Pipeline fixtures covered local missing snapshots, required
  publication absence, valid released docs, missing promised originals, malformed
  inventory, hidden/withdrawn states, and exclusion of historical trees/inventory
  and `.git` from development scanning. Excluded latest designations currently need
  deliberate policy correction; no automatic `/latest/` route is inferred.

## Observed live state and first-release work

- [x] Public repository: `buildish-tooling/buildish-atr-maven-bundle`, default main;
  ruleset `25871960`, reviewed PR/linear history and GitHub Actions Required Checks.
  Actions are enabled with SHA pinning, selected-action policy and read-only default
  token permissions. Candidate and promotion workflows are active.
- [x] Main-only `release-candidate` contains the two signing and four registry secret
  names. Values/usability were not inspected; inherited org-secret availability
  returned 403. Separate Central/Portal upload environments are not needed.
- [x] Independent `versioned-docs` is published at
  `9b68f3ddbbc8e6400c5e25665c112116565e0d85`, with inventory and site trigger.
  Aggregate dispatch token availability still needs operator confirmation.
- [x] Actual attempt `44139922783`: public candidate `v0.1.0-rc1`; Central reported
  missing MD5/SHA-1, wrong SHA sidecar format, and unavailable public key. All 14
  SHA digest values match the primary bytes. The old upload step only checked
  submission acceptance. The run was cancelled; final publication did not execute.
- [x] Immutable `v0.1.0` already identifies
  `0c37378301845806620140102d0b32e009d055d4`. All seven public Central primary URLs
  returned 404 at diagnosis; account-side deployment state still needs inspection.
- [ ] Review/commit/deploy these corrections with successful trusted CI. Keep main's
  `version.txt` at `0.1.0-SNAPSHOT` until the normal post-release development bump.
- [ ] Publish the existing public certificate (including signing subkey) to a
  Central-supported keyserver and confirm lookup. Validate the actual exported
  key/passphrase through approved isolated signing; no normal keyring changes.
- [ ] Confirm namespace/plugin-ID ownership, accepted terms, initial Portal approval,
  draft write-token usability and GitHub Release immutability setting.
- [ ] New bundle/signatures require a new candidate. To retain `0.1.0`, use the
  exact original source commit above with the corrected trusted main workflow.
  Preserve RC1 and the final tag. Different source or conflicting published
  coordinates require a new version; do not retarget/delete immutable state.
- [ ] Retain/review the new candidate's actual tag and manifest digest, then
  separately authorize promotion. Require every essential final job to succeed.
- [ ] After successful publication, manually import exact-tag docs, curate release
  selection/latest and deploy the aggregate site. Incomplete/retracted attempts
  remain hidden/withdrawn inventory records if not completed; a tag alone is not
  proof of a usable release. Keep static development links marked unreleased.

Optional later work: automate post-release docs updates with separate write
permissions, or upstream typed Central/Portal evidence adapters. Neither is
required for this corrected composition.
