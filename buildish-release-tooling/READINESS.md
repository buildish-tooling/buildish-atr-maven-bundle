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

# Release readiness — 2026-11-17

Current verdict: **local release rehearsal passed**; live first publication still
requires the deployment prerequisites below. No hosted release was dispatched or
published, no developer-worktree commit or push was made, and no remote settings
were changed. Both Maven Central and the Gradle Plugin Portal are required. See
[contract](RELEASE-CONTRACT.md), [operator steps](RELEASE-PROCESS.md) and
[docs maintenance](../tools/versioned-docs/README.md).

## Implemented and assessed

- [x] Exact published `buildish-release-tooling==0.1.4`; runtime requires Python >=3.11.
  Local evidence uses Python 3.14 and the wheel under `build/release-tooling-venv/`,
  not sibling source. Production installs into runner-temp. No PyPI extra needed.
- [x] Released contracts: tag `v0.1.4`, source
  `b4542a195705c13c3806ca34fed8e821bedc20c8`.
  Reviewed workflow/recovery baseline:
  `80045552b0b75f3770aa09daff6e2120a543042d`.
- [x] Candidate/promotion workflows: trusted source CI, credential-separated build,
  retained signed Maven ZIP, exact candidate/digest input, producer-bound handoffs,
  component-owned registry upload/readback, successful consumer gates, GitHub last.
- [x] Full fingerprint explicitly selected:
  `D52A35E355AB82E26B879B629890F738DF4BB337`.
- [x] Component-owned docs helpers adapted from source-only reference
  `07fbbefeaf300dd12aa5734e0e4172bc0229a7a9` and Mammoth Cache's scoped inventory
  preservation. They are not shipped CLI features. Default import is incremental.
- [x] Existing component metadata reviewed/preserved: slug/artifact
  `atr-maven-bundle`, stable `vX.Y.Z`, docs root `docs`.
- [x] Existing aggregate catalog and shared build action reviewed read-only:
  source-relative publication-required snapshot, independent mandatory branch
  checkout, explicit publication profile in native and container paths.
  The implemented scoped snapshot contract supersedes the original prompt's
  aggregate-only version policy ownership. No sibling files were edited here.

## Validation evidence

- [x] Repository `check` passed with Gradle 9.9.1 / JDK 21 (the wrapper-selected
  version, cached distribution SHA-256 verified), including Java 17/21 packaged
  compatibility, functional/unit/build-logic tests, RAT, formatting and new docs
  tests. Cached Gradle 9.8.2 also passed with and without the docs worktree.
  Build-version documentation now matches the 9.9.1 wrapper; consumer compatibility
  remains pinned to Gradle 9.1.0/Java 17 and 9.8.2/Java 21.
- [x] Python suite: 41 tests plus 23 unittest subtests passed against installed 0.1.4.
  Includes absent/partial/matching/conflicting registry
  states, transient polling, changed handoffs, archive inventory and independent
  shallow-clone fetch of an older final tag while HEAD stays on a newer workflow.
- [x] Published harness custom backend: actual installed CLI candidate and promotion,
  matching-file resume, sealed draft, required manifest digest, locked-publication
  resume without upload, conflicting existing asset rejection. This is isolated
  lifecycle rehearsal, not execution of the complete workflow YAML or live registries.
- [x] A disposable stable-version build exported real plugin artifacts. The trusted
  Portal publisher reproduced all six plugin/marker primary bytes in a disposable
  Maven repository, with no source compilation or JAR generation. A separate
  settings-plugin consumer created a bundle from the retained implementation.
  The real seven-file export was also signed with an isolated fixture key, packed,
  extracted, cryptographically verified, and consumed as a settings plugin to
  produce a Central bundle. This does not qualify the real deployment signing key.
- [x] New workflow/action zizmor scan passed. Workflow-policy tests require essential
  final results individually and allow explicitly skipped upload dependencies;
  cancellation/failed verification cannot satisfy the final job's condition.
- [x] Aggregate `make -C site site-check-local` passed without this docs checkout
  (using a disposable stage path). Disposable Pipeline fixtures covered local
  absence, publication-required absence, valid selected released docs, missing
  promised docs, hidden/withdrawn versions without originals, malformed inventory,
  conflicting selected publication states, and development-stage exclusion of
  historical trees, inventory JSON and `.git`. The executable fixture is
  `tests/site_snapshot_integration.py` (requires the existing sibling Pipeline env).
  Current Pipeline reports excluded latest designations as unavailable in staged
  metadata without failing `check`; deliberate `latestStable` policy correction
  remains required. No automatic `/latest/` route or development fallback is inferred.
- [x] Runner selection checked: installed `gh act` 0.2.87 lists all four candidate
  and nine promotion jobs. The published 0.1.4 harness maps `ubuntu-26.04` to
  `ghcr.io/catthehacker/ubuntu:act-26.04`; that image is already cached locally.
- [x] Published 0.1.4 harness + `gh act`: all four candidate jobs and all nine
  promotion jobs selected; actual successful status records required for every
  essential job. Corrected first publication executed both registry uploads,
  exact readbacks, real Gradle consumers, manifest sealing and final publication
  against the immutable GitHub fixture. Locked and matching-partial recovery
  skipped both registry uploads, ran both verifiers and finalization, preserved
  sealed bytes and performed **zero GitHub asset uploads**. A Portal verification
  outage blocked publication; recovery succeeded without registry re-upload.
  Conflicting Central bytes failed preflight and blocked all downstream publishing.
  Every recovery case performed zero registry uploads.
- [x] Rehearsal exposed and fixed an ordering bug: fresh promotion state must first
  regain component-owned verification records before 0.1.4 can restage a retained
  sealed manifest. GitHub staging now follows both registry verifiers. The CLI
  regression reproduces the old failure and proves recovery with rebuilt evidence.
- [x] Evidence: `build/workflow-rehearsal/results.json`, per-case JSON/logs and
  actual job-status files; corrected first publication independently repeated at
  `build/workflow-first-publication-current/results.json`. Package logs establish
  `0.1.4` from runner-local Python 3.14 `site-packages`, with no sibling source
  checkout. Sealed SHA-256:
  `c3ead8b78f1024d6f7deb7c9876009915975c2fa711158deb7b740993971dcef`.
  See `tests/rehearsal/README.md` for reproducible commands, fixture adaptations
  and evidence limits. The simulated version `1.2.3` is not a component release.
- [x] Docs-branch site trigger prepared locally, with source template
  `tools/versioned-docs/publish-site.yml`; fake-`gh` tests verify the exact aggregate
  dispatch and missing-token failure. Full-rebuild regression preserves it.
- [ ] Live GitHub status/skip propagation and cancellation remain unproven by act.
  Cancellation is covered by static workflow-policy checks. Existing CI's
  `ubuntu-slim` and Windows paths are outside this Linux release rehearsal;
  Windows requires its own runner. No Linux substitution establishes Windows support.
- [ ] Live Central/Portal publication, registry permissions, metadata retention,
  initial Portal approval, consumer readback and successful final GitHub jobs.
  Existing Release Tooling self-promotion run 43917189837 proves its Python path only.
- [ ] Real signing usability check with selected exported key/passphrase remains.
- [ ] Published docs navigation after first successful release; no successful local
  or hosted component tags/releases existed at assessment. No historical release
  inventory or snapshots can honestly be imported yet.

## Hosted facts verified read-only

- Public repository `buildish-tooling/buildish-atr-maven-bundle`, default `main`.
  No hosted releases and no hosted `versioned-docs` branch at assessment.
- Active branch ruleset `25871960`: reviewed PR, linear history, deletion/force-push
  protection, GitHub Actions `Required Checks`. Admin/role bypasses exist.
- Actions enabled, selected-action policy, SHA pinning required, default workflow
  token read-only. The current CI workflow is active; release workflows are local
  additions, not yet activated remotely. Recent main CI at
  `8bd16f14f0f4e1b6a8a4e8fd6f789d92509b63a6` succeeded.
- `release-candidate` environment exists and restricts deployments to `main`, with
  no reviewer rule returned. Its secret names are
  `BUILDISH_RELEASE_GPG_SECRET_SUBKEY` and `BUILDISH_RELEASE_GPG_PASSPHRASE`;
  values were not read. No repository secrets/variables or environment variables
  were returned. Org-secret inspection returned 403; inherited availability is
  not verified. No permission/authentication changes were attempted.
- `maven-central` and `gradle-plugin-portal` environments were absent. The repository
  read did not expose a Release immutability setting; verify it manually.

## Required first-release actions

- [ ] Merge/deploy workflows/config and release-preparation notes/version; obtain
  successful trusted CI on the chosen exact source. Keep main-only deployment policy.
- [ ] Establish Central namespace and Plugin Portal plugin ID/account ownership,
  terms and credentials; create the two described upload environments.
- [ ] Approve and perform the isolated real-key usability check; publish the public key.
- [ ] Confirm live action policy, draft write-token usability and immutability setting.
- [x] Approved local `docs/releases/` worktree created on the unborn independent
  `versioned-docs` branch, with one empty component inventory and the Mammoth-style site trigger.
  Its source template is `tools/versioned-docs/publish-site.yml`. No commit, push or
  historical docs import was performed; there are no completed releases to import.
- [ ] Commit/publish that docs branch through separately authorized operations.
  Make `BUILDISH_SITE_PUBLISH_TOKEN` (aggregate Actions write) available to the docs
  branch before pushing; its workflow dispatches the aggregate site build on push.
  Mandatory aggregate checkout otherwise blocks site publication, intentionally.
- [ ] Author actual first-release notes and an unused stable `version.txt`, review
  the candidate identity/digest and approve promotion. Validate every final job.
- [ ] After successful completion, manually import exact-tag docs, curate release
  selection/latest and deploy the separately owned aggregate site. Until deployment,
  keep static development links explicitly marked unreleased.

Optional later work: automate the post-release docs update with separate write
permissions, or upstream typed Central/Portal evidence adapters. Neither permits
weakening present publication gates or rebuilding immutable packages.
