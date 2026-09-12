# Miyorare Sources — Attribution

This directory stages Miyorare-owned source packs and compatibility metadata. It does not claim
ownership of the third-party websites exposed by a parser.

## UMA / Tsuki parser code

The initial curated packs are built from source code in **InvalidDavid/UMA**, pinned to the exact
commit recorded in `packs.json`. UMA is distributed under **GNU GPL v3.0** and is itself derived
from other open-source parser work. Miyorare keeps the upstream license and source provenance when
adapting or distributing that code.

Upstream repository: `https://github.com/InvalidDavid/UMA`

The pack build workflow injects the upstream `LICENSE` file and `miyorare-pack.json` provenance
metadata into every staging JAR.

## Keiyoushi intake

M2 also verifies selected compatibility candidates against **keiyoushi/extensions-source**, pinned
to the exact commit recorded in `multi-upstream.json`. Keiyoushi's source repository is distributed
under **Apache License 2.0**.

Upstream repository: `https://github.com/keiyoushi/extensions-source`

The M2 intake check currently consumes module metadata (source name/id, language, base URL and path)
to prove cross-provider identity. It does not copy Keiyoushi parser source into the Miyorare JAR.
If a future Miyorare pack adapts Keiyoushi code directly, the Apache-2.0 license and required notices
must be retained alongside Miyorare's GPLv3 distribution obligations.

## Content providers

Parser/source support does not imply affiliation with, endorsement by, or ownership of the websites
or content providers that a parser can access. Users remain responsible for complying with the
applicable provider terms and local law.
