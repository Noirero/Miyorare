# Reader Journey — Phase 10 Full Validation

Status: validation contract for the Phase 10 candidate branch. This document deliberately separates
automated evidence from device/manual evidence so a green JVM suite is never mistaken for full
production validation.

## Source of truth

Phase 10 follows the consolidated Reader Journey Rank Rewards V2 contract:

- all 12 Rank Themes;
- Light / Dark / OLED;
- small/narrow and modern-phone behavior;
- Indonesian / long labels / large text;
- animation disabled / Reduce Motion / Minimal Cosmetic expectations;
- wallpaper ON/OFF;
- Reader, Library, Reader Journey, Profile, Achievements and Settings;
- cold start, first Library render, scroll, Reader movement, theme switch, wallpaper decode and
  memory pressure;
- representative golden visual regression;
- process recreation/death safety;
- system bars / edge-to-edge / IME integrity;
- selected state must not be color-only.

## Automated gate in this change

The dedicated `Reader Journey Phase 10 Full Validation` workflow is exact-head pinned.

### Deterministic JVM matrix

It validates:

- exactly 12 semantic theme definitions;
- all three variants for every theme;
- OLED background/surface contract;
- reserved semantic status colors remain distinct from rank identity;
- stable representative golden theme IDs:
  - First Page;
  - Neon Archive;
  - Golden Manuscript;
  - Eternal Library;
- representative visual specs exist for every golden theme;
- Developer Theme Gallery includes Light/Dark/OLED selection, wallpaper ON/OFF, Indonesian long
  copy and non-color-only selected-state treatment;
- Reader Content Isolation;
- centralized theme-source precedence;
- Compose and legacy View consume the same Reader Journey theme runtime;
- system-bar / edge-to-edge wiring remains present;
- atomic cosmetic snapshot, policy sanitization and Collection regressions.

### Android 15 persistence probe

The Android job re-instantiates `ReaderProfileStore` across the persisted
`CosmeticLoadoutV2` boundary and verifies:

- a Full Set restores as one coherent state;
- badge/wallpaper/card/progress IDs remain matched to the selected theme;
- auto-equip preference survives;
- an old/corrupt snapshot fails closed to a safe state;
- no invalid partial cosmetic selection is restored.

This is the deterministic persistence boundary relevant to process recreation. It does not claim to
be a literal OS kill/relaunch benchmark.

## Performance evidence

The existing Reader Journey APK size gate already records exact base-vs-head Preview APK bytes.
Phase 9 ended at 49,031,574 bytes. This Phase 10 validation-only change intentionally adds tests,
workflow configuration and documentation rather than runtime assets.

A complete cold-start / render / scroll / memory performance verdict must use comparable
BEFORE-vs-AFTER measurements from the same build type, device class, Android version, dataset,
theme state and wallpaper state. No universal threshold is invented here.

## Representative visual regression

The project already has canonical Android screenshot-evidence infrastructure for Favourites.
Phase 10 keeps the Rank Theme golden matrix representative rather than attempting
12 themes × every screen × every device × every mode.

Reference themes remain frozen to:

1. First Page
2. Neon Archive
3. Golden Manuscript
4. Eternal Library

The deterministic gate protects their semantic/visual definitions now. Pixel screenshot evidence
for the full minimum screen set remains a device-render validation item rather than being falsely
reported as covered by JVM tests.

## Device/manual evidence still required before declaring Phase 10 fully PASS

These checks require actual rendered/device evidence:

- small/narrow phone;
- modern phone;
- large system font scaling;
- Reader interaction and page movement;
- Library/Favourites long-list scrolling;
- Reader Journey / Profile / Achievements / Settings rendering;
- status/navigation bars, gesture navigation, display cutout and IME transitions;
- wallpaper crop/decode behavior;
- animation disabled and Reduce Motion behavior;
- Minimal Cosmetic behavior once the production control is available;
- cold/warm start, first render, scroll, theme switch latency and memory pressure.

If any of these finds a regression, fix it on a branch from the latest `beta`, rerun exact-head
gates, confirm `behind=0`, then merge.

## Important finding

The current Beta has a general `VisualEffectLevel` control and a debug Gallery wallpaper toggle,
but the master contract's explicit Rank Theme controls for Reduce Motion, Reduce Glow and Minimal
Cosmetic Mode are not yet represented as complete production controls. Phase 10 must therefore not
claim those rows as PASS until that gap is implemented or explicitly resolved.

Likewise, production use of every badge/frame/wallpaper/card/progress primitive must be verified
surface-by-surface; availability in the visual registry or Developer Gallery alone is not proof of
production integration.
