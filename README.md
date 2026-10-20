# AeroFly

AeroFly is an EssentialsX addon. Players use /fly (from EssentialsX). AeroFly limits flight time. Players need essentials.fly.

**AeroFly** seamlessly layers a fair, real-time flight time-limit system onto EssentialsX. It features daily reset allowances based on LuckPerms permissions, permanent purchased flight time, temporary unlimited flight grants, offline player support, and SQLite persistence.

---
*Developed with assistance from Gemini Flash 3.8.*

## Requirements

- **Server Software:** Paper 1.21.11 (or compatible forks)
- **Java Runtime:** Java 21+
- **EssentialsX:** 2.21+ (required host plugin owning `/fly`)
- **LuckPerms:** 5.4+ (required dependency for ranks and permission handling)
- **PlaceholderAPI:** 2.11.6+ (optional soft-dependency for placeholders)

---

## Installation

1. Stop your Minecraft server.
2. Ensure you have installed **EssentialsX**, **LuckPerms** (and optionally **PlaceholderAPI**) into your server's `plugins/` directory.
3. Place `AeroFly-1.0-SNAPSHOT.jar` (or shaded release jar) into your server's `plugins/` directory.
4. Start the server. The plugin will automatically hook into EssentialsX and LuckPerms, generating its configuration file at `plugins/AeroFly/config.yml` and SQLite database at `plugins/AeroFly/aerofly.db`.

---

## Commands & Permissions

### Flight Command (EssentialsX)

Players toggle flight mode using EssentialsX's `/fly` command. AeroFly enforces time balances in the background.

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/fly` | `essentials.fly` | Toggles flight mode (provided by EssentialsX, time-managed by AeroFly). |
| `/fly [on\|off]` | `essentials.fly` | Explicitly enables or disables flight mode. |
| `/fly <player>` | `essentials.fly.others` | Toggles flight mode for another player. |
| `/fly <player> [on\|off]` | `essentials.fly.others` | Explicitly sets flight mode for another player. |

### Admin Commands

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/aerofly help` | `aerofly.admin` | Displays available commands matching sender permissions. |
| `/aerofly add <player> <time>` | `aerofly.admin.add` | Adds time to a player's purchased balance. |
| `/aerofly remove <player> <time>` | `aerofly.admin.remove` | Deducts time from purchased balance (clamped at 0). |
| `/aerofly set <player> <time>` | `aerofly.admin.set` | Sets purchased balance (use `0s` to reset to zero). |
| `/aerofly infinite all <time>` | `aerofly.admin.infinite` | Grants temporary unlimited flight to all players. |
| `/aerofly infinite player <player> <time>` | `aerofly.admin.infinite` | Grants temporary unlimited flight to a specific player. |
| `/aerofly infinite group <group> <time>` | `aerofly.admin.infinite` | Grants temporary unlimited flight to a LuckPerms group. |
| `/aerofly grants` | `aerofly.admin.infinite` | Lists all active unlimited grants with IDs and expiry. |
| `/aerofly revoke <id>` | `aerofly.admin.revoke` | Revokes a specific unlimited grant by ID. |
| `/aerofly check <player>` | `aerofly.admin.check` | Inspects player allowances, balances, and active grants. |
| `/aerofly reload` | `aerofly.admin.reload` | Reloads `config.yml` without touching balances or grants. |

> **Bypass Permission:** Players with `aerofly.bypass.time` have permanent unlimited flight and never consume daily or purchased flight balances.

---

## Duration Format Examples

AeroFly supports flexible duration inputs for admin commands:
- `30s` (30 seconds)
- `15m` (15 minutes)
- `2h` (2 hours)
- `1d` (1 day)
- `2h30m` (2 hours and 30 minutes)
- `1d12h30s` (combined days, hours, and seconds)
- `0s` (valid for `/aerofly set` to clear balance)

---

## Shop & Voucher Plugin Integration

You can easily automate flight rewards through shop, crate, or voucher plugins (such as PlayerPoints, EliteVouchers, ExcellentCrates, Tebex, etc.) by running console commands:

```text
aerofly add %player% 1h
aerofly add %player% 30m
aerofly infinite player %player% 1d
```

Console execution is always allowed and supports offline players safely.

---

## Configuration (`config.yml`)

```yaml
# Timezone used for daily reset calculations
timezone: "Asia/Kolkata"

# 24-hour time of day when daily allowances reset
daily-reset-time: "00:00"

# Rank definitions. Highest priority matching rank is chosen.
ranks:
  default:
    permission: "aerofly.rank.default"
    daily-time: "1h"
    priority: 1
  vip:
    permission: "aerofly.rank.vip"
    daily-time: "2h"
    priority: 2
  # mvp:
  #   permission: "aerofly.rank.mvp"
  #   daily-time: "3h"
  #   priority: 3

fallback-rank:
  name: "None"
  daily-time: "0s"

database:
  filename: "aerofly.db"
  autosave-interval-seconds: 300

flight:
  charge-only-when-airborne: true

warning-thresholds:
  - "5m"
  - "1m"
  - "30s"

excluded-worlds:
  - "example_world_nether"
  - "example_world_the_end"

placeholderapi:
  enabled: true

unlimited-text: "Unlimited"
```

---

## PlaceholderAPI Placeholders

When PlaceholderAPI is installed, the following placeholders are available:

| Placeholder | Description | Example Output |
| :--- | :--- | :--- |
| `%aerofly_daily_remaining%` | Formatted daily allowance remaining | `45m 12s` |
| `%aerofly_daily_remaining_seconds%` | Numeric seconds of daily allowance remaining | `2712` |
| `%aerofly_daily_allowance%` | Total configured daily allowance for player's rank | `1h` |
| `%aerofly_purchased_time%` | Formatted permanent purchased flight balance | `2h 30m` |
| `%aerofly_purchased_seconds%` | Numeric seconds of purchased flight balance | `9000` |
| `%aerofly_total_available%` | Combined time or `Unlimited` text | `3h 15m 12s` / `Unlimited` |
| `%aerofly_total_available_seconds%` | Numeric seconds combined or `-1` if unlimited | `11712` / `-1` |
| `%aerofly_is_flying%` | Whether player has AeroFly flight enabled | `true` / `false` |
| `%aerofly_has_unlimited%` | Whether player currently has unlimited flight active | `true` / `false` |
| `%aerofly_rank%` | Current applicable rank name | `vip` / `None` |
| `%aerofly_next_reset%` | Formatted time countdown until next daily reset | `6h 24m 50s` |

---

## Building from Source

To build the self-contained shadow JAR:

```powershell
.\gradlew.bat clean shadowJar
```

The resulting JAR will be generated inside `build\libs\AeroFly-1.0-SNAPSHOT.jar`.
