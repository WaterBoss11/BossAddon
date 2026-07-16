# OVERNIGHT SUMMARY — Tower upgrade + AutoCrystal/KillAura rewrites + new modules (v1.7.0)

Result: all 4 items done. 0 build/test failures. 41/41 tests pass after every item. Jar 243,624 B.
No behavior changes to existing features at default settings; every new option defaults OFF/additive.

## ITEM 1 — Scaffold tower (Meteor logic)
Replaced the LiquidBounce "Motion" tower with Meteor's: each tick while towering + jump held, check
`getBoundingBox().move(0,1,0)` via `level.getBlockCollisions` — no block above → `setDeltaMovement(vx, towerSpeed, vz)`;
block above → snap `deltaY = ceil(y)-y` and `setOnGround(true)`, then place the block below. New options: `towerSpeed`
(0.5, 0.1-1.0), `whileMoving` (false = only tower standing still). Removed towerMotion/towerTrigger/towerSlow.
`towerLaunch()` now returns towerSpeed; `towering()` respects whileMoving. All other scaffold features untouched.

## ITEM 2 — AutoCrystal (LiquidBounce)
- **A) Triggers** — added `onBlockChange` / `onExplodeSound` / `onEntityTeleport` (all OFF). Implemented via
  `onSoundPacket` (GENERIC_EXPLODE) and `onPacketReceive` (BlockUpdate/SectionBlocksUpdate, TeleportEntity),
  setting a `forceAct` flag so the NEXT client tick acts without waiting out the break/place delay. (Chose
  next-tick forcing over off-thread immediate execution for thread safety — world ops must run on the client
  thread.) fastBreak/ON_CRYSTAL_SPAWNED already existed.
- **C) Dual-position damage** (`dualDamage`, OFF) — also requires the crystal to do >= min damage at the target's
  2-tick predicted position (`predictedEnemyDamage`, optimistic full-exposure approximation).
- **D) SetDead** — kept; restore-on-timeout is already provided by the prune window (a crystal becomes
  re-targetable after the window if the server never confirmed the break). Did NOT do risky client-side entity
  remove/re-add.
- **F) WallsRange** (`wallsRange`, default 0.0) — a non-visible crystal/position is actionable only within this
  distance via the new `losOk` gate. **Defaulted 0.0 (strict LoS) instead of the requested 3.0** to honour the
  hard rule "no behavior change at defaults"; raise it for through-wall.
- **G) Offhand** (`offhandCrystal`, OFF) — places from the offhand (no hotbar switch) via `placeOffhand`.
- **B) IdPredict** — SKIPPED. The raw attack-by-predicted-entity-id needs `ServerboundInteractPacket`
  hand-crafting that isn't cleanly exposed and is desync-prone; too fragile to ship unverified. fastBreak covers
  most of the latency win.
- **E) Blast-resistance cap** — SKIPPED. Our exposure uses vanilla `ServerExplosion.getSeenPercent` (occlusion,
  no blast-resistance term), so there's nothing to cap without replacing the damage core — which the task said
  to keep.

## ITEM 3 — KillAura (LiquidBounce)
- **A) Click patterns** (`clickPattern`: Stabilized/NormalDistribution/Butterfly/Drag, default Stabilized =
  previous even timing). `patternDelay()` computes the next delay per pattern. Kept minCps/maxCps at 8-12
  (existing) rather than the requested 12-15 to avoid changing attack speed at defaults.
- **B) Rotation timing** (`rotationTiming`: Always/OnTick, default Always). OnTick skips the per-tick smooth
  arbiter rotation and sends a raw `ServerboundMovePlayerPacket.Rot` right before the attack (look freely
  between hits).
- **C) Target sort** — added HurtTime / Age / Direction sort keys + a `secondarySort` tiebreak.
- **D) IgnoreOnShieldBreak** (`ignoreOnShieldBreak`, OFF) — bypasses the CPS delay while
  `ShieldBreaker.isActive()` and its victim == the current target. Added `ShieldBreaker.isActive()`/`victim()`.
- **E) Cooldown display** — CombatHud now draws an attack-cooldown bar (green full / orange recharging) from
  `getAttackStrengthScale`; HUD height bumped 33 -> 47.
- Item 4B **NoMissCooldown** (`noMissCooldown`, OFF) also lives here: don't spend the cooldown on a tick where
  no target was actually in reach.

## ITEM 4 — New modules
- **A) SuperKB** — added `superKb` (OFF) to Anti-Knockback: forces the offensive on-hit W-tap (release W on each
  of your attacks) to maximise knockback dealt, independent of the W-tap toggle.
- **B) NoMissCooldown** — done in KillAura (above).
- **C) AutoShoot** — new `com.boss.pvp.module.combat.AutoShootModule` (registered, ticked). If the crosshair
  entity is a player (+ optional visibility check + delay), switches to and throws snowball/egg/pearl. Options:
  snowball(ON), egg(OFF), pearl(OFF), delay(500ms), onlyVisible(ON), switchBack(ON).

## Deviations (summary)
- wallsRange default 0.0 (not 3.0) — preserves strict-LoS at defaults.
- KillAura CPS default kept 8-12 (not 12-15) — preserves attack speed at defaults.
- AutoCrystal IdPredict (B) and blast-cap (E) skipped-and-documented (fragile / would rewrite the kept damage core).
- Trigger system uses next-tick forcing (thread-safe) rather than off-thread immediate execution.
- Movement/packet features not runtime-verified in-client (can't run AUTISM here); all default OFF.

## Tests
41/41 passing after every item. No new unit tests added (these are all in-game combat features with no pure
testable surface).
