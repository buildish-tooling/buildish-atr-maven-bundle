# Copyright 2026 The Buildish Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Route registry HTTP only inside an explicitly configured disposable rehearsal."""

import os

if os.environ.get("ATR_REHEARSAL_REGISTRY_ROOT"):
    import urllib.request

    from registry_fixture import urlopen

    original = urllib.request.urlopen

    def fixture_urlopen(request, *args, **kwargs):
        from urllib.parse import urlsplit

        url = getattr(request, "full_url", request)
        if urlsplit(url).hostname in {
            "central.sonatype.com",
            "repo.maven.apache.org",
            "plugins.gradle.org",
        }:
            return urlopen(request, *args, **kwargs)
        return original(request, *args, **kwargs)

    urllib.request.urlopen = fixture_urlopen
