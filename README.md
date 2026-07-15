# Boss's PVP

> An open-source PvP addon for AUTISM Client on Minecraft 26.2

[![License](https://img.shields.io/github/license/WaterBoss11/boss-pvp?color=blue)](LICENSE)
![Minecraft](https://img.shields.io/badge/Minecraft-26.2-brightgreen)
![AUTISM Client](https://img.shields.io/badge/AUTISM%20Client-3.4-orange)
[![Release](https://img.shields.io/github/v/release/WaterBoss11/boss-pvp?color=success&label=release)](https://github.com/WaterBoss11/boss-pvp/releases/latest)
[![Issues](https://img.shields.io/github/issues/WaterBoss11/boss-pvp)](https://github.com/WaterBoss11/boss-pvp/issues)

A combat addon for AUTISM Client. It adds 31 modules and 3 HUDs covering crystal PvP, melee, survival
automation, movement, and defense. Modules use real vanilla explosion-damage math, silent server-side
rotations, a ghost-safe rotate-before-act packet path, and a shared slot/rotation arbiter so they do not
conflict within a tick.

> [!WARNING]
> Intended for servers without anticheat. On servers that run anticheat or enforce rules, many modules will
> fail or get you banned. Use responsibly.

---

## Combat Modules

| Module | Description |
|--------|-------------|
| AutoCrystal | Places and detonates end crystals with vanilla damage math |
| AutoAnchor | Places, charges, and detonates respawn anchors |
| BedAura | Places and detonates beds in Nether/End |
| KillAura | Attacks nearby entities with smooth rotation |
| AimAssist | Aims toward targets (Linear/Sigmoid/Interpolation) |
| AutoWeapon | Switches to the best weapon before each hit |
| Criticals | Times attacks for critical hits |
| Surround | Places obsidian around you |
| HoleFiller | Fills holes around enemies |
| Trapper | Traps enemies in obsidian |
| ShieldBreaker | Breaks enemy shields with an axe |
| Reach | Extends attack and interact range |
| TriggerBot | Attacks the entity on your crosshair |
| AntiEntityPush | Prevents entities from pushing you |

## Automation Modules

| Module | Description |
|--------|-------------|
| AutoPot | Throws splash potions when health is low |
| AutoGap | Eats golden apples when health is low |
| AutoTotem | Keeps a totem of undying in the offhand |
| AutoArmor | Equips the best armor |
| AutoHook | Uses a fishing rod to pull enemies |
| AutoXP | Uses XP bottles |
| AutoClutch | Places blocks to prevent fall damage |
| Offhand | Manages offhand item cycling |
| InvManager | Manages the inventory |
| AutoLeave | Disconnects when health drops below a threshold |
| SelfDestruct | Clears logs, removes the addon jar, empties the recycle bin |
| FastPlace | Removes place delay |
| Hitbox | Expands entity hitboxes |

## Movement Modules

| Module | Description |
|--------|-------------|
| Scaffold | Places blocks beneath you while walking |
| Burrow (Beta) | Buries you in obsidian |
| AntiKnockback | Reduces or cancels knockback |
| NoSlowdown | Removes slowdown while eating/drinking/blocking |

## HUD

| Module | Description |
|--------|-------------|
| AimAssist FOV Circle | FOV ring for AimAssist |
| Combat HUD | Combat stats |
| Totem Pops | Totem pop counter |

---

## Team Check

Every combat module has an optional Team check toggle (default off). When on, a nearby player is treated as a
teammate and skipped by targeting, placement, and threat detection if they wear leather armor dyed the same
color as yours.

- Each armor slot where both you and the target wear dyed leather is compared by RGB.
- A slot matches when every channel is within ±15.
- At least 2 slots must match, so default brown leather does not cause false positives.
- If you wear no dyed leather, the check is disabled.

Wired into KillAura, AimAssist, AutoCrystal, AutoAnchor, BedAura, TriggerBot, ShieldBreaker, Trapper,
HoleFiller, and Surround.

---

## Requirements

- Minecraft 26.2
- AUTISM Client 3.4
- Fabric API 0.152.2+26.2
- Java 25+

---

## Build

```bash
# 1. Place the AUTISM Client 3.4 API jar in libs/ as autism-3.4.jar.
# 2. Point JAVA_HOME at a JDK 25 install, then build:
gradlew build          # Windows
./gradlew build        # macOS / Linux
# Output: build/libs/boss-pvp-1.0.0.jar
```

Match the versions in `gradle/libs.versions.toml` (minecraft, fabric, autism) to the AUTISM release you build
against.

---

## Installation

1. Download the latest release from the [Releases](https://github.com/WaterBoss11/boss-pvp/releases/latest) page.
2. Drop `boss-pvp-1.0.0.jar` into your `.minecraft/mods/` folder.
3. Launch with AUTISM Client 3.4 and Fabric API for Minecraft 26.2. The modules appear under the Boss's PVP
   category.

---

## License

GPL-3.0 — see [LICENSE](LICENSE).
