# Miyorare Sources — Attribution

This directory stages Miyorare-owned source packs and compatibility metadata. It does not claim
ownership of the third-party websites exposed by a parser.

## UMA / Tsuki parser code

The curated base of each Miyorare-ID / Miyorare-EN pack is built from source code in
**InvalidDavid/UMA**, pinned to the exact commit recorded in `packs.json`. UMA is distributed under
**GNU GPL v3.0**.

Upstream repository: `https://github.com/InvalidDavid/UMA`

## Gekkoushi parser code

The ID/EN expansion imports parser code from **Gekkoushi/plugin-source**, also pinned to the exact
commit recorded in `packs.json`. Gekkoushi/plugin-source is distributed under **GNU GPL v3.0** and
is itself derived from open-source Kotatsu/Tsuki parser work.

Upstream repository: `https://github.com/Gekkoushi/plugin-source`

For each language, Miyorare keeps its existing curated UMA runtime source keys and adds every
Gekkoushi runtime source key not already present. Duplicate filtering is performed on the
`@MangaSourceParser` runtime key, not on a display name.

The source-pack build embeds both upstream `LICENSE` files and `miyorare-pack.json` provenance
metadata in every staging JAR. The provenance records the exact commits, final runtime source list,
added Gekkoushi sources, and skipped duplicate keys.

## Keiyoushi intake

M2 also verifies selected compatibility candidates against **keiyoushi/extensions-source**, pinned
to the exact commit recorded in `multi-upstream.json`. Keiyoushi's source repository is distributed
under **Apache License 2.0**.

Upstream repository: `https://github.com/keiyoushi/extensions-source`

The M2 intake consumes module metadata to prove cross-provider identity. It does not copy Keiyoushi
parser source into the Miyorare JAR. If Miyorare later adapts Keiyoushi code directly, the
Apache-2.0 license and required notices must be retained alongside Miyorare's GPL obligations.

## Content providers

Parser/source support does not imply affiliation with, endorsement by, or ownership of the websites
or content providers that a parser can access. Users remain responsible for complying with
applicable provider terms and local law.
