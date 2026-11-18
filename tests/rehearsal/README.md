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

# Local workflow rehearsal

`tests/rehearse_release_workflows.py` runs disposable copies of the actual candidate
and promotion workflow YAML through the **published 0.1.4 harness and `gh act`**.
It never dispatches GitHub workflows or supplies real service credentials. Normal
unit tests do not start containers or run this slower rehearsal automatically.

Use an approved existing environment with `buildish-release-tooling==0.1.4` and
PyYAML (a package dependency), the installed `gh act` extension, an existing Docker
compatible daemon, and the cached `ghcr.io/catthehacker/ubuntu:act-26.04` image.
Point at already available JDK 17/21 installations and the component's Gradle cache:

```sh
build/release-tooling-venv/bin/python tests/rehearse_release_workflows.py \
  --java17 /absolute/existing/jdk17 --java21 /absolute/existing/jdk21
# Continue retained fixture work after a diagnostic interruption:
build/release-tooling-venv/bin/python tests/rehearse_release_workflows.py --resume \
  --java17 /absolute/existing/jdk17 --java21 /absolute/existing/jdk21
```

`--output` selects a fresh output directory; default is `build/workflow-rehearsal`.
Existing output is refused unless `--resume` is explicit. Changes to rehearsal code
should normally use fresh output. All Git commits/configuration used by the test
belong to disposable repositories below that directory, never the developer's main
or docs worktrees. The real repository's remotes, hooks, credentials and branches
are not reconfigured. JDK mounts are read-only; Gradle reuses the repository cache.
The workflow's normal runner-temp installer still obtains the exact published wheel.
Its version and installation origin appear in every job log.

## What is real and what is adapted

The driver freezes current tracked/non-ignored source files into a disposable main
branch, supplies source version `1.2.3-SNAPSHOT` and fixture notes, and uses the
harness's generated signing key. The production fingerprint is not replaced in the developer checkout.
CI responses identify the configured GitHub Actions workflow/check suite; they are
simulated provider observations, not evidence that hosted CI ran on the fixture.

The original job graph, dispatch inputs, conditions, digest handoffs, CLI calls,
source builds, signature creation, Maven packaging, candidate reuse, manifest sealing
and immutable GitHub verification run as authored. The published harness supplies
local checkout/artifact actions and its stateful GitHub shim. No Release Tooling
source checkout is provided: the console launcher runs its installed wheel with
Python isolation. The harness's source-path hint refers to an absent directory.

The explicit test-only adaptations are:

- Existing JDKs replace runner acquisition. `$/` local-action syntax is rendered as
  `./` for the installed act version. An additional context-only Git remote in each
  disposable workspace lets act derive the canonical repository name without
  resolving the harness's origin redirect into a filesystem path. It is never
  fetched or pushed; the production repository/source guards stay enabled.
- `sitecustomize.py` intercepts only Central/Portal `urllib` HTTP requests when the
  disposable workflow explicitly configures `ATR_REHEARSAL_REGISTRY_ROOT` and its
  fixture Python path. Central's real multipart request is decoded and its exact
  ZIP entries stored; reads return those bytes or injected visibility/failure states.
- A separate trusted bootstrap job builds the packaging plugin without selected
  source execution. The signing job loads its digest-verified local repository,
  runs ATR Maven Bundle signing/checksum/ZIP tasks and verifies all retained bytes.
- Central fixtures stage uploads privately, independently check the required four
  checksum sidecars and signature presence, and expose public files only after
  the deployment status reaches `PUBLISHED`. This models asynchronous validation;
  it does not implement Central's complete POM/keyserver admission service.
- The trusted Portal publisher executes its ordinary local Maven publication task
  instead of its remote upload task. Its exact POM, module and JAR outputs populate
  the Portal fixture, including a registration marker and Central parent-POM view.
  Missing version pages use the observed HTTP 400 “Plugin Not Found” HTML response;
  Maven URLs still use 404 for absent files.
- Gradle consumers resolve those fixture registry trees through `file://` URLs.
  These are real plugin consumers, while HTTP transport/Portal admission remain
  service fixtures. Post-upload polling budgets are shortened for injected failures.

The production client, endpoints, package pin and workflows have no rehearsal switch.
`registry_fixture.py` is test code only. The generated signing key can be visible in
act's environment logs; it is disposable fixture material, never a deployment key.
The shared filesystem and fixture key do not simulate hosted runner isolation.

## Cases and retained evidence

The driver requires all five candidate jobs and all nine promotion jobs to be
selected. Essential jobs must actually create successful status records, rather
than merely receiving an inferred green result from the harness. Registry upload
jobs may lack a status record only when their conditions intentionally skip them.

Cases cover first publication with both uploads, locked matching publication with
both uploads skipped, matching partial preflight that becomes complete, failed
verification blocking GitHub staging/publication, recovery from that interruption, and conflicting
registry bytes that block publication. Recovery of a previously sealed release must retain its sealed-manifest digest.
Every recovery case must perform no registry uploads. Fixture `events.jsonl` records HTTP reads,
uploads and partial observations; per-case JSON records actual job execution.

The output retains scenario YAML, frozen workflow copies, installed-package job
logs, fake GitHub state, registry state, transport artifacts and reports. Fixture
GPG agents are stopped at driver exit. Keep evidence until review is complete, then
remove only the explicitly selected disposable output directory when authorized.

This is isolated workflow evidence. It does not prove GitHub's live status/skip
semantics, repository settings, usable deployment credentials, namespace ownership,
Portal approval, or live registry propagation/metadata behavior.

## Recorded adoption run

On 2026-11-17 the installed-wheel/act matrix passed candidate publication, first
publication, matching locked recovery, partial visibility, failed verification,
recovery from that failure, and conflicting-file rejection. The rehearsal exposed
an ordering bug, fixed by rebuilding registry evidence before final GitHub staging.
The corrected workflow also passed an independent fresh first-publication run.

The retained reports are `build/workflow-rehearsal/results.json` and
`build/workflow-first-publication-current/results.json`. Locked and partial recovery
preserved sealed SHA-256
`c3ead8b78f1024d6f7deb7c9876009915975c2fa711158deb7b740993971dcef`
with zero GitHub asset uploads; all recovery cases performed zero registry uploads.
The durable readiness summary and live deployment prerequisites are in
`buildish-release-tooling/READINESS.md`. Generated fixture evidence is ignored by Git.

The candidate fixture now commits `1.2.3-SNAPSHOT` and requests stable `1.2.3`,
exercising the workflow's build-checkout version update. The earlier complete
matrix used stable source versions. Its promotion/recovery evidence is retained;
normalization requires a fresh candidate run rather than reusing that old source.

The fresh `0.1.0` candidate-only run passed all four jobs with source/tag version
`0.1.0-SNAPSHOT`, artifact/JAR version `0.1.0`, a real settings-plugin consumer,
and fixture signing/publication. Its report is
`build/release-0.1.0-candidate-rehearsal/results.json`; it used the installed 0.1.4
wheel and did not publish to any live service.

The native packaging revision removes NMCP and Python ZIP/signature production.
Earlier reports described four-job candidates and the defective 28-file Maven
contract; they are historical lifecycle evidence, not admission evidence for the
corrected 42-file native bundle. New runs use five candidate jobs (including the
independent trusted-tooling bootstrap) and authenticated deployment-status polling.

The driver also forks an empty registry at `failed-central-state/` and injects a
Central validation error after upload acceptance. It requires `publish-central`
to fail, its receipt/error artifact to exist, all downstream publication jobs to
remain unexecuted, and no public fixture files or GitHub asset uploads. This case
is included in `results.json` and can be audited again with `--resume`.

The corrected native-packaging matrix passed on 2026-11-17. Its retained report is
`build/native-release-rehearsal/results.json`: all five candidate jobs and nine
first-publication jobs actually succeeded. Locked and partial recovery skipped
both uploads, ran both verifiers and final publication, preserved sealed SHA-256
`ec91c49748aee8e9a4ff147fd5b3e43b49c72c561b7256095f5a2e41a95603ec`,
and performed zero GitHub asset uploads. Failed verification blocked publication;
its recovery succeeded without registry uploads. Conflicting files blocked all
later publication. The separate Central rejection case failed `publish-central`,
retained the failed receipt, and exposed no staged files publicly. These reports
record installed wheel version `0.1.4` and its Python 3.14 `site-packages` origin.
They do not establish live registry admission or hosted Actions skip behavior.
