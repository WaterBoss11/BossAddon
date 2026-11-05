# BossAddon v2-pre.2 — Item Render Cap

**Prerelease • November 5, 2026 • Minecraft 26.2 • Client 5.0 / API3 only**

## Download / compatibility

- Install **`bossaddon-2-pre.2+client5.0.jar`** with AUTISM Client **5.0 (API3)**, Fabric API **0.152.2+26.2**, and Java **25**.
- Install only one BossAddon jar and back up your configuration before replacing the old jar.
- **No API4 binary is included.** The matching 5.1/API4 local dependency was unavailable for verification. API4 users should retain the previous `v2-pre.1` API4 jar; it does not contain this optimization.
- The default source selector remains 5.1/API4. The verified API3 build uses explicit Gradle properties instead of temporarily editing the version catalog.

## Optimization port

Added **BossUtility → Item Render Cap**, ported from `WaterBoss11/boss-optimizations` revision `6707d67b15741fe9c36d94a1356e7c78ef9d9780` under its preserved MIT license.

- **Disabled by default.** Enable it manually in BossUtility. Existing module IDs, settings and enabled-state persistence are preserved.
- Default cap: **5 dropped items per 4-block grid cube**. Configure **0–128 items** and **0.5–128-block cell width**.
- Highest entity IDs win, favoring newer drops while IDs increase normally. The aimed-at item can add one extra render, including when the cap is zero.
- Selection/settings are fixed for an extraction frame. Disabling the module/utility half immediately stops capping; lifecycle changes and world replacement clear stale selection.
- Rejects excess items before render-state extraction. No entity mutation/removal, packets, ticking, picking, pickup, merging or despawn changes are introduced.
- When standalone mod **`bossoptimizations`** is installed, the addon cap is inert even if the standalone cap is disabled. It never reads or changes donor configuration, avoiding double-capping.
- Fixed an inherited donor bug where mirrored positive/negative grid cells shared a lossy hash and could hide an entire separate pile. Buckets now compare full coordinate tuples, not just hashes. The new regression failed before the fix and passes with it.
- Additional guards reject nonfinite selector widths and ignore crosshair IDs absent from the current frame.

BossAddon now registers **53 modules: 41 PvP + 12 utility**, plus the existing eight HUD providers. No combat behavior is changed in this release.

## Verification performed for this release

- Unchanged-source API3 baseline: **430 unit tests passed** and existing game-test sources compiled.
- Release API3 build: **463 unit tests passed**, **0 failures/errors/skips**, across **57 suites**; `build` and `compileGametestJava` succeeded under JDK **25.0.3**.
- Added **33 tests**: 22 donor/selector regressions and edge cases, 10 frame/lifecycle policy tests, and one utility-half restoration regression.
- Covered highest-ID/new-drop selection, static-scene stability, residual cell-boundary churn, crosshair exemptions, independent cells, reset/history, empty frames, negative coordinates, offer order, numeric validation, once-per-frame collection, changed settings, world/ID reuse, immediate inactive gates, donor precedence, and unrelated-half isolation.
- Packaged metadata/version, all entrypoint and mixin classes, Java 25 bytecode, complete license/notice texts, and dependency/test/config exclusions passed `scripts/verify-release.py`.
- Both new hook descriptors were checked against the actual Minecraft **26.2** classes. This is **not** a runtime mixin application test.
- The focused `item-render-cap` client-world adapter game-test batch was **compiled, not executed**. Historical v2-pre.1 gameplay evidence was not rerun or relabeled as a new result.

Build command:

```powershell
.\gradlew.bat --offline --no-daemon -PhostClientVersion=5.0-26.2-dev -PaddonVersion=2-pre.2+client5.0 build compileGametestJava
```

Artifact check (Python 3; optional `--minecraft-jar` also checks hook descriptors using `JAVA_HOME/bin/javap`):

```powershell
python scripts/verify-release.py build/libs/bossaddon-2-pre.2+client5.0.jar --version 2-pre.2+client5.0
```

## Limits / not claimed

- No new live-game, server pickup/despawn, Sodium/Iris, API4, anticheat or FPS benchmark result is claimed.
- The cap adds a per-frame item scan/ray test; benefit depends on the scene. A synthetic reduction in selected items is not an FPS measurement.
- Cells include offscreen items. Moving items crossing cell boundaries can change selection. ID wrap/reassignment can invalidate newest-first ordering.
- The donor's visual crosshair ray extends 32 blocks and ignores walls; it does not alter actual picking or allow interaction through walls.
- The pre-existing JOML `sun.misc.Unsafe` deprecation warning remains visible in unit-test output; it was not suppressed.
- No live Minecraft profile was modified, no host dependency is bundled, and the donor repository and unrelated `boss-client` working changes were left untouched.

GitHub provides source archives for the release tag. `SHA256SUMS` accompanies the verified jar.
