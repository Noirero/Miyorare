# VoraToon first-party candidate

Derived from InvalidDavid/UMA at `2462a1f88f277f83c20758e682b7c8882f5dad9f`, GPL-3.0-only. This checked-in snapshot is intended for Miyorare maintenance, not upstream auto-sync. Parser key `VORATOON`, locale `id` and relative manga/chapter URL formats are retained. Source-local helpers avoid shared helper collisions. The old shipped default `v2.voratoon.com` is treated as a legacy alias of the new default.

This draft does not change pack membership or release configuration. Compile and exercise this snapshot against Tsuki 1.0.5 before Global integration. The Global release must preserve old ID references, exclude duplicate exposure, and validate live access and Android data continuity.
