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

# Released documentation maintenance

These component-owned Bash/Python standard-library scripts require Python 3.11+
and Git with `worktree add --orphan` support for initial creation. They derive from
Release Tooling's source-only helpers at
`07fbbefeaf300dd12aa5734e0e4172bc0229a7a9`, with the scoped inventory preservation
and excluded-release handling used by Mammoth Cache. The published **0.1.4** CLI
supplies none of these helpers.

There is one artifact, `atr-maven-bundle`, one version, and one `docs/` tree.
Maven Central and the Plugin Portal distribute the same plugin; they do not have
independent docs histories. Stable tags are `vX.Y.Z`, pointing at the reviewed
source commit. All defaults, provider IDs and release URLs belong to
`buildish-tooling/buildish-atr-maven-bundle`.

The independent orphan branch `versioned-docs` has no ancestry with main. Its
root holds `provider-snapshot.json` and directories such as `1.2.3/`. Check it out
at `docs/releases/`; development docs remain on the source branch.

```sh
# Existing published branch: fetch only when it does not exist locally.
tools/versioned-docs/checkout.sh
# Explicit fetch and fast-forward only; refuses dirty/divergent worktrees.
tools/versioned-docs/update.sh
# First creation only, with separate authorization; no commit/fetch/push.
tools/versioned-docs/checkout.sh --create
```

Checkout/update accept `--remote`, `--source`, `--worktree`, and `--branch`.
The default remote is this component's HTTPS URL, never Release Tooling's.
Neither command changes remote configuration. Import never fetches.

After verifying Maven Central publication and the finalized GitHub release,
import the matching docs. Use the Maven Central setup in the quickstart until
the release is publicly available on the Plugin Portal too:

```sh
# Fetch the exact final tag separately, when authorized and missing locally.
git fetch --no-tags origin refs/tags/v1.2.3:refs/tags/v1.2.3
tools/versioned-docs/import.sh --version 1.2.3
# Ordinary incremental discovery: all locally available stable tags, no cutoff.
tools/versioned-docs/import.sh
# Optional selection or minimum floor.
tools/versioned-docs/import.sh --minimum-version 1.2.0
# Controlled reconstruction from immutable tags.
tools/versioned-docs/import.sh --full-rebuild --version 1.2.3
```

Imports record the resolved commit and copy that commit's docs without rewriting
relative links. A tag proves discovery, not successful publication. Verify hosted
facts before adding publication timestamps or recommending a release. For a failed
attempt retain its record and curate `publicationState: hidden`; for a withdrawn
release use `withdrawn` (or `tombstoned` for removed original content). This changes
docs publication policy, not remote releases or package retraction.

Incremental import preserves existing docs and all curated metadata. Full rebuild
reconstructs selected trees while preserving whole records, curated fields,
unselected versions and records with tags absent locally. Both reject moved tags.
Dirty/foreign worktrees, unowned destinations, symlinks and conflicting scoped/provider
states fail before writes. All exports are prepared before replacing managed trees;
a filesystem failure during replacement can still leave partial changes. Preserve
wanted work and inspect the diff before recovery. No staging, commits, pushes,
history rewrites or implicit rollback occur.

```sh
git -C docs/releases status
# Includes tracked changes; inspect new untracked files separately.
git -C docs/releases diff
python3 -m json.tool docs/releases/provider-snapshot.json
```

Curate `artifacts[]` release selections and `latestStable` according to the current
Pipeline contract; imports never advance recommendations. Explicit scoped and
provider-record publication states must agree. Keep excluded records to prevent
rediscovery. Hidden/withdrawn/tombstoned originals need not remain available.
A stale latest recommendation needs deliberate correction, never fallback to
unreleased development docs.

The existing aggregate catalog binds `atr-maven-bundle` to named source
`atr-maven-bundle`, path `docs/releases/provider-snapshot.json`, with
`requirement: publication`. Its shared build action independently checks out source
and docs branches and passes the publication profile in native and container paths.
Inventory and docs therefore come from the same docs-branch commit. Pipeline itself
does not fetch. Local absence is allowed; publication absence and present malformed
or incomplete selected content must fail. Component metadata owns artifact identity
and static versioning; the scoped snapshot supplies version facts and curated policy.
The original aggregate-only version-policy proposal has been superseded by this
implemented contract.

Before website publication, run the aggregate repository's `make -C site
site-check-local`; publication must also pass `SITE_PIPELINE_PROFILE=publication`.
After released docs deploy, update static links to released routes or a supported
release-derived alias. Until then label development links as unreleased.

`docs/releases/` is excluded from source tracking and RAT traversal. Release bundles
contain exactly the Maven publication files; source archives use Git-tracked source,
so the ignored worktree cannot enter either. Never force-add it to the source branch.

Run `python3 tests/site_snapshot_integration.py` in an approved Release Tooling test
environment to exercise the existing sibling Pipeline CLI with disposable inputs;
optional `--pipeline-root` and `--catalog` select the reviewed local consumer.
It never renames or removes real docs checkouts. A stale excluded `latestStable`
currently does not fail Pipeline checking; review and correct it explicitly.

Run `python3 -B -m unittest discover -s tests -p test_versioned_docs.py` or Gradle
`versionedDocsTest` (included in `check`). Tests use disposable repositories, fixture
commits, local remotes, and process-local identities. A manual post-release update is
sufficient. Commit/push and website deployment remain separately authorized actions.

## Aggregate site trigger on the docs branch

`publish-site.yml` beside this guide is the component-owned template for
`docs/releases/.github/workflows/publish-site.yml` on `versioned-docs`. It follows
Mammoth Cache's docs-branch trigger: a non-deletion push to this repository's
`versioned-docs` dispatches `site-publish.yml` on `buildish-tooling/buildish` main.
It does not import docs, create package releases, or grant its default token any
permissions. It requires `BUILDISH_SITE_PUBLISH_TOKEN` with Actions write access
to the aggregate repository, available to the docs-branch workflow.

The local workflow has been prepared without committing, pushing or dispatching.
For future initial branch creation, copy the template before the separately
authorized docs-branch commit:

```sh
mkdir -p docs/releases/.github/workflows
cp tools/versioned-docs/publish-site.yml docs/releases/.github/workflows/publish-site.yml
```

Review the workflow and inventory together. A later authorized push will trigger
the aggregate site build; token availability and aggregate branch acquisition
remain deployment prerequisites. Imports/full rebuilds preserve this root workflow.
