---
title: "Export a Njord transportable bundle"
description: "Create a local .ntb archive from Gradle Maven publications for import into Njord."
weight: 15
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

Apply the settings plugin and configure your Maven publications as shown in the
[quickstart](../getting-started/). Use a selection containing either releases or
snapshots.

Run this from the root of your Gradle build:

```shell
./gradlew :njordTransportableBundle
```

For the quickstart's example name and version, the output is
`build/maven-bundles/njord-transportable/my-library-1.0.ntb`. The archive contains
the Maven repository tree, plus the indexes and properties Njord uses for
import. Hand the file to [`njord:import`](https://maveniverse.eu/docs/njord/plugin-documentation/import-mojo.html).

The task uses the same [checksum and signing policy](../reference/signing/) as
the repository ZIP. For the unsigned quickstart fixture, pass
`-PatrMavenBundle.requireSignatures=false` when trying this output locally. For
mixed release and snapshot publications, use the
[repository ZIP](../reference/outputs/) or narrow the publication selection.
