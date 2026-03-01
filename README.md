# SimpleFactionsRaiding

A comprehensive raiding plugin for SimpleFactions with custom TNT types, core chunk systems, and height-based reward scaling.

## Features

### 1. Raid Time Windows
- **Cycle Duration**: 8 hours raid active + 16 hours protected (24 hour total cycle)
- **Per-Faction**: Each faction has independent raid windows
- **Status Commands**: `/raid status <faction>` to check current window status
- **Admin Control**: `/raid start` and `/raid stop` for manual overrides

### 2. Custom TNT Types
Three specialized TNT variants for diverse raiding strategies:

| Type | Damage | Radius | Special |
|------|--------|--------|---------|
| **LETHAL** | 2.0x | 20 blocks | Maximum destruction |
| **GIGANTIC** | 1.5x | 40 blocks | Massive radius |
| **LUCKY** | 1.0x | 30 blocks | Normal with random ores |

### 3. Core Chunk System
- **Core Designation**: First faction claim becomes the core chunk (permanent, can't delete)
- **Core Points**: Factions start with 100 points in their core
- **Raiding**: Players steal points by hitting core chunks during raid windows
- **Wild Claims**: Regular claims outside core auto-delete after 24 hours
- **Warzone Claims**: Claims in warzone areas are permanent

### 4. Height-Based Reward Scaling
Reward percentage scales linearly with Y-coordinate:
- **Y = 319+**: 75% of core points stolen
- **Y = 160**: ~40% of core points stolen  
- **Y = 1**: 5% of core points stolen

**Formula**: Linear interpolation between Y=1 (5%) and Y=319 (75%)

### 5. Raid Mechanics
- **Protection**: Factions in protected window receive raid block notifications
- **Active Raids**: During raid windows, successful TNT hits steal core points
- **Notifications**: Faction members see real-time raid alerts with:
  - TNT type used (LETHAL/GIGANTIC/LUCKY)
  - Points stolen
  - Height bonus percentage
- **Cannoning**: Full support for piston cannons and TNT launching

### 6. Claim Auto-Deletion
- **Wild Claims**: Automatically deleted after 24 hours
- **Core Chunks**: Never deleted (permanent)
- **Warzone Claims**: Never deleted (permanent)
- **Timer Tracking**: Per-claim creation time tracking

## Commands

### Player Commands
```
/raid status <faction>       - View faction's raid window status
/raid info <faction>         - See core chunk location and current points
/raid points <faction>       - Check current core points
```

### Admin Commands
```
/raid start <faction>        - Manually activate raid window
/raid stop <faction>         - Manually disable raid window
```

## Integration

- **SimpleFactions**: Core faction data and territory system
- **Paper/Spigot**: Compatible with 1.21.10+
- **WorldGuard/Warzone**: Respects warzone claims (permanent)

## Configuration

Currently uses hardcoded values:
- Raid window: 8 hours active, 16 hours protected
- Core points: 100 per faction
- Height scaling: Y=1 (5%) to Y=319 (75%)
- Wild claim duration: 24 hours

**Future Enhancement**: Configurable values via `config.yml`

## Installation

1. Build SimpleFactions first: `gradle build`
2. Build SimpleFactionsRaiding: `gradle build`
3. Place both JARs in server `plugins/` folder
4. Restart server
5. Verify messages appear:
   ```
   === SimpleFactionsRaiding Plugin Enabled ===
   Raiding managers initialized successfully
   ```

## Technical Details

### Core Classes
- **RaidingManager**: Handles raid windows and height-based reward calculation
- **CustomTNTManager**: Manages TNT type registration and properties
- **CoreChunkManager**: Tracks core chunks, points, and claim timers
- **RaidListener**: Processes TNT explosions and core chunk raiding
- **RaidCommand**: Handles `/raid` command structure

### Data Structures
- TNT types tracked in `WeakHashMap<TNTPrimed, CustomTNTType>`
- Raid windows tracked per faction with millisecond precision
- Claim creation times stored for 24-hour auto-deletion
- Core chunks stored with coordinates, world, and point values

## Future Enhancements

- [ ] YAML configuration file
- [ ] Custom reward scaling curves
- [ ] Raid notifications with sound effects
- [ ] Core chunk upgrade system (increase defense)
- [ ] Raid history logging
- [ ] Leaderboard system (most points stolen)
- [ ] Lucky TNT special effect (randomize nearby ore drops)
- [ ] Explosions respecting terrain (less damage to more hardened blocks)
