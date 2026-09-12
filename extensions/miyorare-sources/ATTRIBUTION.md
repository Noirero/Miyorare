# Miyorare Sources — Attribution

This directory stages Miyorare-owned Tsuki source packs. It does not claim ownership of the
third-party websites exposed by a parser.

## Upstream parser code

The initial curated packs are built from source code in **InvalidDavid/UMA**, pinned to the exact
commit recorded in `packs.json`. UMA is distributed under **GNU GPL v3.0** and is itself derived
from other open-source parser work. Miyorare keeps the upstream license and source provenance when
adapting or distributing that code.

Upstream repository: `https://github.com/InvalidDavid/UMA`

The build workflow injects the upstream `LICENSE` file and `miyorare-pack.json` provenance metadata
into every staging JAR. Any future code imported from a differently licensed project (for example,
Apache-2.0 code from Keiyoushi) must retain that project's required license/notice information as
well.

## Content providers

Parser/source support does not imply affiliation with, endorsement by, or ownership of the websites
or content providers that a parser can access. Users remain responsible for complying with the
applicable provider terms and local law.
