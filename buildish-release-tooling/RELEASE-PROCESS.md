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

# Release operations

Read [the contract](RELEASE-CONTRACT.md) and [readiness](READINESS.md) first.
These are component-owned GitHub Actions workflows around an installed CLI,
ordinary Gradle builds, a Central Publisher API upload and Maven readback. No
sibling checkout is required in production. The separate publisher build uses a trusted bootstrap of ATR Maven Bundle for
candidate signing/checksums/ZIP creation, then uploads retained files to the Plugin
Portal during promotion. Python handles Central upload/status/readback. NMCP is
removed.

The commands below mutate remote state where indicated. Run them only after the
applicable approval. Nothing in this guide grants dispatch, credential changes,
commits, pushes or publication approval by itself.

## First deployment

1. Review/merge the workflow, configuration and test changes through normal review.
   Enable workflows on `main`. Keep GitHub Actions SHA pinning enabled and verify
   selected-action policy permits the pinned GitHub actions and self-repository actions.
2. Verify the active main/release branch ruleset: reviewed PRs and successful
   GitHub Actions `Required Checks`. The release config intentionally permits only
   main ancestry and a trusted push/main CI suite. It does not authorize arbitrary
   maintenance branches merely because CI runs there.
3. In `release-candidate`, retain the main-only deployment branch restriction.
   Check the existing secret names `BUILDISH_RELEASE_GPG_SECRET_SUBKEY` and
   `BUILDISH_RELEASE_GPG_PASSPHRASE`. The workflow maps them to CLI signing inputs.
   Through an approved isolated check, export the actual selected key, verify full
   fingerprint `D52A35E355AB82E26B879B629890F738DF4BB337`, and sign/verify with the
   matching passphrase. Print no secrets and do not import into a normal keyring.
   Secret names alone are insufficient evidence. Publish the public certificate,
   including its signing subkey, to a Central-supported keyserver. On the machine
   holding that certificate, the separately authorized command is:
   `gpg --keyserver hkps://keyserver.ubuntu.com --send-keys D52A35E355AB82E26B879B629890F738DF4BB337`.
   This publishes public key material to the keyserver; the secret key stays local.
   See [Central's key distribution instructions](https://central.sonatype.org/publish/requirements/gpg/#distributing-your-public-key).
   The isolated CLI probe below qualifies the key for outer ZIP signing; the
   candidate job also exercises Gradle signing and verifies every Maven signature.
4. In the same `release-candidate` environment, set `CENTRAL_USERNAME` and
   `CENTRAL_PASSWORD` to a Central Portal user token pair. Verify
   ownership of namespaces for both implementation/parent and plugin-marker groups,
   and the Central publishing terms. The upload requests `AUTOMATIC` publication.
5. Also in `release-candidate`, set `GRADLE_PUBLISH_KEY`/`GRADLE_PUBLISH_SECRET`.
   Establish the publisher account,
   plugin ID ownership and accepted Portal terms; initial plugin approval may be
   asynchronous. Only the Portal upload step receives these credentials. No OIDC
   or PyPI Trusted Publisher registration is needed for this composition.
6. Confirm write permissions for tag/draft/final jobs, GitHub Release immutability,
   branch deployment policies and action availability in the live repository.
   Draft reads can require push-capable credentials; draft verification runs with
   job-scoped `contents: write`. Any stricter approval-reviewer policy is a deployment
   decision, not inferred from another component.
7. Deploy an independent `versioned-docs` branch, even if its initial inventory is
   empty, before enabling aggregate publication that mandatorily checks it out.
   Follow [docs maintenance](../tools/versioned-docs/README.md); initial creation,
   commit and push are separate operations. The site catalog/action changes are
   already prepared in their owning repository and must be deployed separately.

## Deployment commands for separate approval

The workflows share the existing main-only `release-candidate` environment for
signing, tag/GitHub mutations and registry upload jobs. Its four registry secret
names were verified on 2026-11-17. No separate upload environments are needed.
If credentials need replacement, these separately authorized commands prompt for
values; never put secret values in command arguments:

```sh
repo=buildish-tooling/buildish-atr-maven-bundle
gh secret set CENTRAL_USERNAME --repo "$repo" --env release-candidate
gh secret set CENTRAL_PASSWORD --repo "$repo" --env release-candidate
gh secret set GRADLE_PUBLISH_KEY --repo "$repo" --env release-candidate
gh secret set GRADLE_PUBLISH_SECRET --repo "$repo" --env release-candidate
```

These enable upload jobs after deployment; they do not establish namespace or plugin
ownership. If setup is wrong, correct the environment or replace/revoke the affected
credential through a separately approved action. Do not delete publication state.
Verify existing policy first rather than recreating a working environment.

The initial `versioned-docs` branch is already published. For a new checkout that
still needs initial publication, first prepare the inventory and site trigger as
described in the docs maintenance guide. The separately authorized commands are:

```sh
git -C docs/releases add provider-snapshot.json .github/workflows/publish-site.yml
git -C docs/releases commit -m "Initialize ATR Maven Bundle released documentation inventory"
git -C docs/releases push origin HEAD:refs/heads/versioned-docs
```

This publishes only the independent docs branch and will run its aggregate-site
dispatch trigger. Ensure `BUILDISH_SITE_PUBLISH_TOKEN` is available to that branch
with Actions write access to `buildish-tooling/buildish`; the workflow grants its
default token no permissions. It changes no main ancestry or release artifacts. Correct later inventory errors with reviewed forward commits;
do not force-push or rewrite either history as release recovery.

For the separately approved signing usability check, use an existing approved wheel
environment and actual exported secret/public key files. This does not export from
or import into a normal keyring. The CLI validates the full authored fingerprint
and uses its own isolated signing home. Supply the actual matching passphrase via
masked input, never command arguments or logs:

```sh
set +x
probe="$(mktemp -d)"
printf 'ATR Maven Bundle signing qualification\n' > "$probe/probe.txt"
read -r -s -p 'Signing passphrase: ' BUILDISH_GPG_PASSPHRASE; printf '\n'
export BUILDISH_GPG_PASSPHRASE
BUILDISH_GPG_PRIVATE_KEY="$(cat "$EXPORTED_SECRET_KEY_FILE")" \
GITHUB_STEP_SUMMARY="$probe/summary.md" MANIFEST_PATH="$probe/result.json" \
  bash buildish-release-tooling/release-tooling.sh prepare-release-assets \
  --expected-signing-fingerprint D52A35E355AB82E26B879B629890F738DF4BB337 \
  --output-dir "$probe/signed" "$probe/probe.txt"
unset BUILDISH_GPG_PASSPHRASE
mkdir -m 700 "$probe/public-keyring"
gpg --homedir "$probe/public-keyring" --batch --import "$EXPORTED_PUBLIC_KEY_FILE"
gpg --homedir "$probe/public-keyring" --batch --verify \
  "$probe/signed/probe.txt.asc" "$probe/signed/probe.txt"
gpgconf --homedir "$probe/public-keyring" --kill all
rm -rf -- "$probe"
```

Set `BUILDISH_RELEASE_TOOLING_PYTHON` to the approved installed interpreter before
running the launcher. A failure requires inspection of the exported key, fingerprint
or passphrase; do not weaken the fingerprint gate. Preserve only nonsecret results.

## Trusted Maven bundle build

Candidate creation has five jobs: resolve; build the selected payload without
credentials; independently build the packaging plugin from the trusted workflow
revision; sign/package; publish the reviewed candidate assets. The separate
`buildish-release-tooling/portal-publisher` build serves both packaging and Portal
promotion, with explicit modes:

- Bundle mode takes `RELEASE_REPOSITORY` containing the seven unsigned primary
  files and `RELEASE_TOOLING_REPOSITORY` pointing to the digest-verified trusted
  bootstrap repository. `RELEASE_VERSION` selects the final Maven version.
- The trusted signing step supplies `BUILDISH_GPG_PRIVATE_KEY`,
  `BUILDISH_GPG_PASSPHRASE` and the full configured `BUILDISH_GPG_FINGERPRINT`.
  `-PbuildCentralBundle :atrCentralArchive` enables ATR Maven Bundle signing and
  checksum completion. No selected payload is compiled or regenerated there.
- The native ZIP is under
  `build/atr-maven-bundle/central/buildish-atr-maven-bundle-X.Y.Z.zip` relative to
  the publisher directory. Independent checks verify its exact file inventory,
  bare Maven checksums, primary-byte preservation and every signing fingerprint.
  Release Tooling then signs the outer GitHub ZIP asset.
- Portal promotion omits `-PbuildCentralBundle`, loads no candidate plugin and
  receives no signing credentials. `publishPlugins` uploads the retained files.

## Prepare each version and review a candidate

Choose an unused stable dispatch version `X.Y.Z`. Committed `version.txt` may
contain either `X.Y.Z-SNAPSHOT` or `X.Y.Z`; a different version is rejected.
Main can stay on the snapshot version. Before Gradle runs, the candidate build job
writes `X.Y.Z` only in its isolated, credential-free selected-source checkout.
Candidate and final tags still identify the original reviewed source commit.
Promotion uploads the retained candidate artifacts without rebuilding them.

Add reviewed, nonempty UTF-8 Markdown at
`buildish-release-tooling/release-notes/X.Y.Z.md`. Initial `0.1.0` notes are supplied
for maintainer review. The artifact version is stable; `rcN` belongs to the
candidate tag, not Maven coordinates. After completing `0.1.0`, advance main to
the next development snapshot through an ordinary source change.

To reproduce a release build from its tag, use a disposable checkout and set its
`version.txt` to the final version before running the same export tasks as the
candidate build. For `v0.1.0` this is `printf '%s\n' 0.1.0 > version.txt`.
This is a documented build preparation step; the workflow does not commit it or
change the source identity recorded by the release tooling.

Run the repository's normal `check` with the supported JDKs, then the release tests
in an approved environment containing `buildish-release-tooling==0.1.4` and
`pytest==9.2.0`:

```sh
./gradlew --no-daemon -Dorg.gradle.java.installations.auto-download=false check
python -B -m pytest tests -q
BUILDISH_RELEASE_TOOLING_PYTHON="$(command -v python)" \
  bash buildish-release-tooling/release-tooling.sh --package-info
```

Merge/push preparation through the normal approval process, then select the exact
main-reachable source with successful push CI. When dispatch is authorized:

```sh
gh workflow run release-candidate.yml --repo buildish-tooling/buildish-atr-maven-bundle \
  --ref main -f version=X.Y.Z -f source_ref=FULL_SOURCE_SHA
```

The workflow resolves source/checks, builds without release credentials, consumes
the exported plugin, signs the seven retained Maven files, creates/signs a ZIP,
and publishes a retained candidate. Record the reported candidate tag and SHA-256
of `candidate-manifest.json` independently of the Release download. Download that
exact candidate, compare the manifest digest and all assets, verify the ZIP signature
and the contained signatures against the independently trusted public key, and
exercise the consumer. Select that identity explicitly for approval.

The ZIP is also the exact Central upload bundle. For an approved local consumer
check after inspecting/extracting its contents:

```sh
bash buildish-release-tooling/consume.sh file:///absolute/retained/repository X.Y.Z /temporary/consumer
```

Do not extract unreviewed arbitrary archives over a working checkout. The workflow's
unpacker accepts only the component's finite regular-file Maven inventory.

## Promote an approved candidate

```sh
gh workflow run release-promote.yml --repo buildish-tooling/buildish-atr-maven-bundle \
  --ref main -f version=X.Y.Z -f candidate_tag=vX.Y.Z-rcN \
  -f candidate_manifest_digest=INDEPENDENTLY_RETAINED_SHA256
```

Promotion verifies and downloads candidate artifacts and creates the final tag on
that source. Immediate preflight rejects conflicting registry bytes. An absent
target uploads; complete matches skip upload; matching
partial state skips re-upload and waits for readback. The Central upload job saves
the deployment receipt and polls its authenticated status endpoint until `PUBLISHED`, with 91 attempts, 10-second delays and a
960-second deadline. `FAILED` immediately fails that job and reports validation
errors. The receipt artifact is retained even when validation fails. Public
readback remains a separate check; upload acceptance is not publication success.
Central must verify before Portal starts. Post-upload readback uses 91 attempts,
10-second delays and a 960-second overall budget per registry. It checks Central primaries and signed
sidecars, Portal primary bytes and version registration, then runs fresh consumers.

Both deterministic readback records are embedded as generic verification claims
in the stable v1 manifest, with producer-bound SHA-256s. They are durable within the
manifest, rather than additional late asset uploads. The final job fetches the exact
final tag from checked state without checking it out and stages the GitHub draft
with both readback records already in release state. This ordering lets 0.1.4
validate component-owned verification records when reusing a retained sealed
manifest. It preserves matching sealed bytes, attaches `release-manifest-v1.json`
to the draft, publishes with
`--require-manifest-digest`, and verifies without further uploads.

Require `resolve`, `stage`, `verify-central`, `verify-portal`, and `publish` each to
succeed. `publish-central` and `publish-portal` may be intentionally skipped when
matching files exist. An overall green run with skipped final publication is not a
completed release. After completion, retain candidate/final digests and perform the
manual versioned-docs import/curation/deployment. Docs deployment does not rebuild
packages and cannot turn a failed package release into success.

## Partial failure and recovery

| Observed state | Action |
| --- | --- |
| Transient failure before a mutation | Rerun failed jobs using the retained run inputs. Candidate resolution is not rerun just to obtain another RC. |
| Central upload accepted, response lost or visibility delayed | Inspect the Central Portal deployment first. Preserve its deployment ID/ZIP digest and finish that deployment; do not blindly upload again. Rerun verification with retained candidate inputs. |
| Central/Portal matching partial public files | No replacement upload is attempted. Readback retries missing/transient files and fails on conflicts. If propagation cannot complete, inspect the existing deployment/Portal approval and complete it through its normal approved process, then rerun. |
| Registry files complete, upload skipped | Verification, consumer tests and finalization must still execute. |
| Portal rejects/rewrites candidate POM or module bytes | Stop. Retain the candidate and any draft and establish the precise publication contract. A successful Central upload does not qualify the Portal path. |
| Changed source, notes or signatures | New candidate. If registry bytes at that version already exist, choose a new version; increasing only RC cannot replace them. |
| Sealed draft, final publication failed | Rerun finalization with the retained sealed-manifest digest. A complete fresh promotion may reuse the same candidate and sealed bytes. |
| Published immutable GitHub release | Revalidate matching state without uploads. Never clobber, delete/recreate or unlock it to bypass a conflict. If required evidence is missing, preserve records and document the incomplete release. |
| Workflow fix required | Fresh dispatch on the new workflow revision, retaining approved candidate tag/digest when payload and contract are unchanged. Retrying old jobs uses the old workflow. |
| Cancellation or failed verification | Publication remains blocked. Inspect partial remote state before resuming the same identity. |

For upload recovery, prefer a fresh promotion dispatch with the same exact
candidate tag/digest so the public preflights run again. Retrying only a failed
upload job retains its earlier upstream inspection result; inspect an uncertain
Central deployment before doing so. Read-only status polling can resume from the
retained deployment receipt without submitting another upload.

Workflow artifacts retain transport/verification state for 90 days. Preserve needed
inputs before expiry. A fresh promotion can reacquire the immutable candidate;
it must not infer approval from whichever candidate is newest. No automatic deletion,
yank, candidate replacement, force-tagging, or registry rollback is performed.

## Recover the failed 0.1.0 Central validation attempt

Run `44139922783` submitted the original `v0.1.0-rc1` ZIP. It lacked MD5/SHA-1
sidecars and its SHA-256/SHA-512 sidecars used sum-tool text instead of Maven's
bare-hex format. All 14 SHA digest values matched their primary files; this was a
packaging defect, not evidence of changed primary bytes. Central also could not
find the public signing key. The old upload step returned after submission, which
explains its green result. The run was subsequently cancelled; final GitHub
publication did not execute.

1. Deploy the corrected workflow on main through the normal approval process and
   publish the existing public certificate to a supported keyserver. Confirm the
   failed Central deployment did not publish anything. Public readback of all
   seven primary coordinates returned 404 at diagnosis; account-side deployment
   state still needs the operator's check.
2. Preserve `v0.1.0-rc1`, its manifest/assets, the failed Central deployment and the
   already-created final tag. `v0.1.0` points to
   `0c37378301845806620140102d0b32e009d055d4` and must not move.
3. Packaging and signatures change, so create/review a new candidate rather than
   retry promotion of RC1. To retain version `0.1.0`, select that **same source
   commit explicitly**, with the corrected trusted workflow on main. Release
   Tooling 0.1.4 can allocate the next RC while retaining the existing final tag.
   The new trusted packaging plugin is built independently of that older source.
   Historical source still contains its old build configuration (including NMCP),
   but it is not used for publication. Removing that history from the tagged
   source would require a new source/version, not moving the existing tag.
4. Retain the new candidate's actual tag and manifest digest and separately approve
   promotion. A fresh dispatch selects the corrected workflow revision; rerunning
   the old jobs does not. The final tag will match the candidate's original source.

If any conflicting coordinate was published, use a new version. If different
component source is required, the existing immutable `v0.1.0` also requires a new
version. Increasing RC alone cannot replace published files or retarget that tag.
Nothing in these instructions authorizes dispatch, key publication, deletion or
remote mutation automatically.
