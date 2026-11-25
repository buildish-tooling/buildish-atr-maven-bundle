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

# Buildish Maven Bundles for Gradle

Package your Gradle build's existing Maven publications into a local ZIP for
Maven Central. Inspect the ZIP and upload it separately. The plugin can also
export a transportable bundle for Njord import.

The settings plugin requires **Gradle 9.1+** and **Java 17+**. Apply it in
`settings.gradle.kts` or `settings.gradle`, and keep your publications configured
with `maven-publish` in your projects.

- [Maven Central quickstart and released guides](https://buildish.org/components/atr-maven-bundle/latest/)
- [Njord output and import](https://buildish.org/components/atr-maven-bundle/#njord)
- [0.2.0 migration notes](buildish-release-tooling/release-notes/0.2.0.md)

## Contributing

Build the repository with Java 21 and run `./gradlew check`. The
[compatibility reference](docs/reference/compatibility.md) lists the JDKs and
Gradle versions used in the checks. See [Contributing](CONTRIBUTING.md),
the [Code of Conduct](CODE_OF_CONDUCT.md), and the [Security policy](SECURITY.md).

Maintainers can follow the [release process](buildish-release-tooling/RELEASE-PROCESS.md)
and [versioned docs guide](tools/versioned-docs/README.md).

## License

See [LICENSE](LICENSE) and [NOTICE](NOTICE).
