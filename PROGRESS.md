# OVERNIGHT SUMMARY — FPS optimization + test infrastructure + code quality

Result: all items done. 0 failures / 0 rollbacks. Build + test green after every item.
Final jar: 232,374 B. Release: v1.5.1.

## ITEM 1 — FPS OPTIMIZATION
- **1A FovCircleHud**: the 128 ring segments used `pose.rotate(theta)` every frame (128 sin+cos/frame).
  Precomputed the 128 rotation matrices once in a static initializer (`SEG_ROT[]`) and switched the per-frame
  loop to `pose.mul(SEG_ROT[i])`. `pose.mul(R(theta)) == pose.rotate(theta)` exactly, so output is unchanged;
  all per-frame trig is gone. (Note: the angles depend only on SEGMENTS, not radius/thickness — the radius/
  thickness only affect the cheap axis-aligned fill rect, so the trig cache needs no radius/thickness key.)
- **1B CombatHud**: 4 full inventory scans + block scan + active-modules string were done per frame. Added a
  per-tick cache (recompute only when `level.getGameTime()` changes) storing the counts, the "T:.. C:.. G:.."
  string, block count, and the active-modules string. Render reads the cache; blink color stays per-frame.
- **1C TrajectoryModule**: the full projectile simulation ran every frame. Added a per-tick + held-item cache
  (`cachedPath`/`cachedTick`/`cachedItem`); re-simulates only when the tick advances or the held item changes.
  Deviation from the spec's 0.5-degree-tolerance key: used a per-tick key instead, because the trajectory origin
  is the player's eye position — caching purely on look angle would show a stale path while walking (a visible
  regression). Per-tick invalidation captures position, look, and blocks together and still collapses many
  frames to one simulation.
- **1D per-frame allocations**: the draw loop allocated a Vec3 per segment via `.subtract(camPos)`. Switched to
  AUTISM's `AutismWorldGeometry.line(pose, vc, double x1..z2, color, width)` overload with raw doubles — no
  per-frame Vec3 allocation. Simulation ArrayList/ClipContext/AABB allocations now happen once per tick (1C).
- **1E module tick dispatch**: already optimal. `BossPvpAddon.onTick` only calls `X.tick()` when `X.isEnabled()`,
  and `isEnabled()` is a plain boolean field read (`state.enabled`) — not a computation. An enabledModules cache
  would add toggle-sync state and risk for zero measurable gain, so none added (the task allowed "may already be
  optimal").

## ITEM 2 — TEST INFRASTRUCTURE
- **2A /bossautotest**: verified `BossPvpInit` registers the Fabric client command correctly for 26.2
  (`ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
  ClientCommands.literal("bossautotest").executes(...)))`). Registration is correct — no fix needed. `/bossautotest`
  (slash) works independent of AUTISM's command system.
- **2B server test mod** (test-server-mod, built + installed to test-server\mods\, 25,819 B):
  - testKillAura: wait 5s (was 3s); pass when zombie health < 19 (allows one missed hit).
  - AutoTest auto-join: added `fill -7 63 -7 7 63 7 minecraft:stone` after the teleport so Surround/Scaffold
    always have flat ground at 0 64 0.
  - testAutoTotem: verified `getOffhandItem()` is the correct 26.2 offhand accessor (already used) — no change.
  - testAutoArmor: verified `getItemBySlot(EquipmentSlot.HEAD)` is the correct helmet-slot check (already used).
  - testFriendsList: SKIPPED (documented). The friends list matches by username; zombie dummies have no
    username, and a fake ServerPlayer requires a live network connection. Not feasible server-side with dummies.
- **2C unit tests**: added 4 (37 -> 41):
  - CombatManagerTest: testResetClearsState, testPauseWithZeroTicks.
  - RotationMathTest: testAngleDifference180, testInterpolateQuarterWay.
  - PlayerSimulationTest.testCacheInvalidatesOnLevelChange: SKIPPED (documented). The cache lives in
    `simulateTrajectory(Player)` and needs a live Player + Level; plain JUnit can't construct those (no Fabric
    bootstrap), so it's infeasible without a game-test harness.

## ITEM 3 — CODE QUALITY
- **3A consistency**: added the standard `if (mc.player == null || mc.level == null) return;` guard to
  AutoWeapon.tick and SelfDestruct.tick (harmless — the global tick loop already guarantees non-null — but now
  consistent). AutoLeave already had it; NoSlowdown/NoHurtCam/AntiEntityPush are mixin-driven (no tick).
  Added TrajectoryModule.onDisable() to clear its cache + hit flags. Verified no module holds an Entity/Player
  across ticks without a use-time null check.
- **3B TrajectoryModule**: confirmed the `cam.getViewRotationMatrix(new Matrix4f())` + camera-relative-coords
  matrix path (mirrors AUTISM's own tracer flush). Added the no-throwable fallback (clears cache + renders
  nothing). Added a world-border stop in the simulation loop.
- **3C friends list**: `BossPvpAddon.friends()` and `KillAura.friends()` now return an unmodifiable view. The
  "Add target to friends" keybind is polled from `BossPvpAddon.onTick` unconditionally (via
  `killAura.pollFriendKey`), so it works even when KillAura is disabled, using the crosshair entity.

## TESTS
41/41 passing (37 prior + 4 new). 2 planned tests skipped-and-documented (infeasible without mocking / a live
game-test harness), consistent with the task's "if infeasible, document and skip".

## DEVIATIONS / NOTES
- Trajectory cache is per-tick, not 0.5-degree-tolerance (correctness — see 1C).
- Trajectory colors remain fixed constants (no ColorSetting) from v1.5.0 — unchanged this run.
- The addon was NOT redeployed to the Duping profile — deploy is not in this task's FINAL steps; the jar is in
  the v1.5.1 release. The server test mod WAS installed to test-server\mods\ (that step is in Item 2B).
- No behavior changes to existing modules; AutoCrystal damage math and KillAura rotation logic untouched.
