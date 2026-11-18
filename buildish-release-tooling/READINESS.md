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

Central is the primary distribution. Final publication requires verified Central
publication and accepted Portal submission; public Portal verification follows
approval in a separate workflow. No candidate was rebuilt, workflow dispatched,
credential changed or remote publication performed by this local correction.
See [contract](RELEASE-CONTRACT.md), [operator guide](RELEASE-PROCESS.md) and
[docs maintenance](../tools/versioned-docs/README.md).

Release `0.1.1` completed in promotion run `44268075229` and was published at
`2026-11-18T08:36:34Z`. The Portal is now approved. Follow-up run `44282532213`
stopped because its final-manifest input contained the candidate digest. The
correct sealed digest is
`b4c5a5e5af8ed4973187346088cf8de8ea267d4f615dd62c44ff534dd09c1801`,
verified against both the producer artifact and the published manifest.

A live read-only check found the five implementation files byte-identical, while
the Portal regenerated the marker POM with the same coordinates/dependency and
omitted descriptive metadata/parent. The local correction validates only that
specific mapping and records both observed/retained hashes. No package, release,
tag or committed docs snapshot was changed, and no workflow was dispatched.

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
- [x] Central readback/consumer checks and successful Portal submission gate final
  publication. Portal public approval/readback/consumer verification follows in
  `release-verify-portal.yml`, selected by sealed final-manifest SHA-256. The final
  manifest records pending verification truthfully and stays unchanged afterward.
  Matching sealed bytes are reused; GitHub publishes with the producer digest.
- [x] Component-owned versioned-docs helpers derive from source-only reference
  `07fbbefeaf300dd12aa5734e0e4172bc0229a7a9`; they are not in the pinned package.
  Existing scoped component metadata and aggregate binding/action were reviewed;
  no sibling repository was edited. Development docs remain separate/unreleased.

## Validation evidence

- [x] Approved Portal correction: 87 Python tests and 23 subtests, repository
  `check` and lint passed. Live anonymous readback verified five exact files plus
  the regenerated marker mapping, and a real `0.1.1` Portal consumer succeeded.
  Evidence: `build/portal-followup-0.1.1-diagnosis/`. The actual read-only workflow
  also passed in `gh act` with a regenerated-marker fixture, recording observed
  and retained hashes with no registry or Release uploads. Evidence:
  `build/portal-marker-rehearsal/results.json`. The corrected workflow has not been
  dispatched against GitHub; the published final manifest remains unchanged.
- [x] Central-primary Python regressions: 69 tests and 23 subtests passed, including
  installed-wheel publication/recovery with pending Portal evidence, prior-run
  identity/candidate checks, and read-only follow-up workflow policy. Repository
  `check`, changed-file lint and workflow scan passed. Real prior-submission proof
  was checked read-only; no deployment credentials were read.
- [x] Central-primary actual-workflow rehearsal passed all nine promotion jobs
  with approval pending, follow-up failure before approval, follow-up success
  afterward, and locked recovery with both uploads skipped. Evidence:
  `build/central-primary-rehearsal/results.json`. Follow-up and recovery performed
  zero registry/Release asset uploads; matching sealed bytes were preserved.
  All jobs recorded installed wheel `0.1.4` in Python 3.14 `site-packages`.
  This is isolated fixture evidence, not live Portal approval or proof of hosted
  GitHub skip propagation. Older matrices below cover their historical contracts.
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
  `version.txt` at `0.1.1-SNAPSHOT` until the normal post-release development bump.
- [x] The later `0.1.1` Central publication and verification succeeded, superseding
  the old attempt's missing-public-key deployment blocker. No key material was read
  during this change.
- [x] Central and Portal submission succeeded, and final `0.1.1` publication
  completed in run `44268075229`. No promotion rerun or new candidate is needed.
- [ ] Merge the marker-readback correction with successful CI, then freshly
  dispatch `release-verify-portal.yml` for `0.1.1` using the sealed digest above.
  Retain the follow-up evidence; do not change the locked pending snapshot.
- [ ] Preserve the historical `0.1.0` tag/candidate and record their incomplete
  publication state during docs curation. They are not the current recovery target.
- [ ] After successful publication, manually import exact-tag docs, curate release
  selection/latest and deploy the aggregate site. Incomplete/retracted attempts
  remain hidden/withdrawn inventory records if not completed; a tag alone is not
  proof of a usable release. Keep static development links marked unreleased.

Optional later work: automate post-release docs updates with separate write
permissions, or upstream typed Central/Portal evidence adapters. Neither is
required for this corrected composition.
