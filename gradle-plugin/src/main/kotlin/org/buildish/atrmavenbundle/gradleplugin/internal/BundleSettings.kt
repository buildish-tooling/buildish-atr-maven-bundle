/*
 * Copyright 2026 The Buildish Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.buildish.atrmavenbundle.gradleplugin.internal

import java.io.Serializable

internal data class BundleSettings(
  val requireSignatures: Boolean,
  val signMissing: Boolean,
  val baseName: String?,
  val bundleVersion: String?,
  val excludedProjects: Set<String>,
  val excludedPublications: Set<Pair<String, String>>,
  val projectPaths: List<String>,
) : Serializable

internal data class PublicationIdentity(
  val projectPath: String,
  val name: String,
  val groupId: String,
  val artifactId: String,
  val version: String,
) : Serializable
