# RaeYNCheat

A comprehensive mod verification and anti-cheat system for Minecraft 1.21.1 (NeoForge) that uses encrypted checksums, passkey validation, and real-time environment scanning to verify client-side mods and detect injection attacks.

## Features

- **Client-side mod verification** with CRC32, SHA-256, and MD5 checksums
- **Passkey authentication system** for secure client-server validation
- **Environment scanning** — detects JVM agent injection, side-loaded JARs, ghost mods, and ClassLoader anomalies
- **Encrypted environment reports** — scan results are encrypted before transmission; cannot be fabricated without the correct passkey
- **Periodic re-validation** — server continuously re-checks all online players at a configurable interval (not just on login)
- **Admin-triggered revalidation** — force an immediate re-check of one player or all online players via command
- **Triple violation tracking** — separate systems for checksum, passkey, and environment violations
- **Violation persistence** — violation records survive server restarts; expire after a configurable number of days
- **Multi-layer encryption and obfuscation** to prevent tampering (details withheld — see Code Protection)
- **Hardened key derivation** — [REDACTED] key pipeline baked into the JAR; key material is not stored in any config file or external location
- **Midnight key rollover** — keys rotate at midnight with a grace window to prevent false positives at the boundary
- **Automatic check file generation** on each client launch and server connection
- **Server-side verification** comparing client mods against expected mods
- **Progressive punishment systems** with configurable ban durations for all three violation types
- **Admin commands** for managing punishments, punishment step tuning, and manual revalidation
- **Comprehensive audit logging** — all validation events logged to `logs/cheat.log` with async I/O

## Branches

- `neoforge-1.21.1` — NeoForge mod loader for Minecraft 1.21.1

Branch naming convention allows for future version ports (e.g., `neoforge-1.21.4`, `neoforge-1.22.x`, etc.)

---

## How It Works

### Client Side

1. On game launch, the client initializes and prepares the mod scanner
2. On server connection:
   - Scans all JAR files in the `mods` folder
   - Calculates CRC32, SHA-256, and MD5 for each JAR
   - Generates an aggregate checksum of all individual checksums
   - Derives a two-part passkey [REDACTED — key derivation details withheld]
   - Runs the environment scanner (JVM args, extra directories, ModList, ClassLoader)
   - Encrypts both the aggregate checksum and the environment report
   - Sends all three to the server in a single packet
3. Responds to periodic revalidation requests from the server by repeating this process

### Server Side

1. On server boot:
   - Scans JAR files in `mods_client` folder (expected client mods)
   - Generates the server-side reference checksum
2. When a player connects (or revalidation is triggered):
   - **Phase 1 — Passkey**: Validates the client's passkey against the expected value for that UUID. Supports a short grace window around midnight to prevent false positives during key rollover.
   - **Phase 2 — Checksum**: Generates the expected encrypted checksum in memory using the validated passkey; compares against the client's submission. No shared files involved — each player's check is fully isolated.
   - **Phase 3 — Environment**: Decrypts and analyses the environment report. Flags JVM agents, unexpected JARs, runtime-injected mods, and ClassLoader anomalies based on configured policy.
3. Any failed phase records a violation, logs the event, and applies the configured punishment.

### What It Detects

- Modified or replaced JAR files in the client's `mods` folder
- Extra JAR files in side-load directories outside `mods/`
- Java agents injected via `-javaagent:` JVM argument
- JVM bootstrap classpath manipulation (`-Xbootclasspath`)
- Native JVMTI agents (`-agentlib:`, `-agentpath:`)
- Other non-whitelisted JVM arguments
- Mods present in the NeoForge ModList at runtime but absent from disk (ghost mods — injection indicator)
- Unexpected entries in the ClassLoader hierarchy pointing outside the game directory

### What It Does Not Detect

- Hypervisor or OS-level cheats operating below the JVM
- Hardware-level input injection (mouse/keyboard emulation at driver level)
- A sufficiently determined reverse engineer given time with the JAR (ProGuard raises the bar significantly but is not absolute)

---

## Installation

### Client
1. Place the mod JAR in your `mods` folder
2. Launch the game — the mod will initialize automatically on first run

### Server
1. Place the mod JAR in your `mods` folder
2. Create a `mods_client` folder in the server root directory (same level as `mods`)
3. Place all expected client mod JARs in `mods_client`
4. Launch the server — the reference checksum will be generated automatically

---

## Configuration

Configuration file: `config/RaeYNCheat/config.json`

```json
{
  "enablePunishmentSystem": true,
  "punishmentSteps": [0, 3600, 86400, -1],

  "enablePasskeyPunishmentSystem": true,
  "passkeyPunishmentSteps": [0, 3600, 86400, -1],

  "envPunishmentSteps": [0, 0, 3600, -1],

  "periodicRevalidationSeconds": 300,

  "enforceJvmArgCheck": true,
  "enforceExtraJarCheck": true,
  "enforceGhostModCheck": true,
  "enforceClassLoaderCheck": false,

  "violationExpiryDays": 30
}
```

### Punishment Steps
- Each entry is the ban duration in seconds for that violation count (index 0 = 1st offence)
- `-1` = permanent ban (also adds the player to Minecraft's ban list)
- `0` = kick without ban
- Positive integer = temporary ban in seconds
- Up to 30 steps can be configured; the last step repeats for all subsequent violations
- Changes take effect immediately and are saved to disk

### Environment Check Policy

| Field | Default | Description |
|---|---|---|
| `enforceJvmArgCheck` | `true` | Punish on flagged JVM arguments (agents, bootclasspath, etc.) |
| `enforceExtraJarCheck` | `true` | Punish on unexpected JARs outside `mods/` |
| `enforceGhostModCheck` | `true` | Punish on runtime mods with no corresponding file on disk |
| `enforceClassLoaderCheck` | `false` | Punish on ClassLoader anomalies (may have false positives with some launchers — enable after calibrating on your player base) |

### Periodic Revalidation

`periodicRevalidationSeconds` controls how often the server sends a revalidation request to each online player. Default 300 (every 5 minutes). Set to `0` to disable (login-only validation). Minimum enforced value is 60 seconds.

### Violation Expiry

`violationExpiryDays` controls how long violation records are retained. Set to `0` to disable expiry (violations persist indefinitely until manually cleared). Default is 30 days.

---

## Admin Commands

All commands require operator permission level 2.

### Checksum Commands

| Command | Description |
|---|---|
| `/raeyn cheat checksum <player>` | Manually apply a checksum violation to an online player |
| `/raeyn cheat checksum refresh` | Rebuild the server-side reference checksum (use after updating `mods_client`) |
| `/raeyn cheat checksum step` | List all current checksum punishment steps |
| `/raeyn cheat checksum step <index>` | Show the punishment step at a specific index |
| `/raeyn cheat checksum step <index> <duration>` | Set a checksum punishment step |

### Passkey Commands

| Command | Description |
|---|---|
| `/raeyn cheat passkey <player>` | Manually apply a passkey violation to an online player |
| `/raeyn cheat passkey step` | List all current passkey punishment steps |
| `/raeyn cheat passkey step <index>` | Show the punishment step at a specific index |
| `/raeyn cheat passkey step <index> <duration>` | Set a passkey punishment step |

### Environment Commands

| Command | Description |
|---|---|
| `/raeyn cheat env <player>` | Manually apply an environment violation to an online player |
| `/raeyn cheat env step` | List all current environment punishment steps |
| `/raeyn cheat env step <index>` | Show the punishment step at a specific index |
| `/raeyn cheat env step <index> <duration>` | Set an environment punishment step |

### Revalidation Commands

| Command | Description |
|---|---|
| `/raeyn cheat revalidate <player>` | Send an immediate revalidation request to one online player |
| `/raeyn cheat revalidate all` | Send an immediate revalidation request to all online players |

Results of manually triggered revalidations appear in `logs/cheat.log` within seconds.

---

## Audit Logging

All validation events are automatically logged to `logs/cheat.log`.

### Async Logging Architecture
The logger uses asynchronous I/O to prevent bottlenecks during player validation:
- Events are queued and written by a dedicated background thread
- Queue overflow is detected and recorded as a summary entry rather than silently dropped
- Automatic log rotation at 10 MB
- Graceful shutdown ensures all queued events are flushed before the server stops

### Logged Events
- Server start/stop lifecycle
- Validation results (passkey, checksum, environment) — success and failure
- Violation details (what was flagged, how many violations on record)
- Manual admin-triggered violations (includes admin username)
- Player connect/disconnect events
- Errors with stack traces
- Queue overflow warnings

### Log Format
```
--------------------------------------------------------------------------------
[2026-03-10 02:15:30.123] VALIDATION - FAILURE
Player: Steve (UUID: 12345678-1234-1234-1234-123456789abc)
Type: ENV_VIOLATION
Details: JVM_FLAG:JAVAAGENT, MOD_GHOST:suspiciousmod
```

### Security
- Passkeys are never written to the log in any form
- Log file access is thread-safe
- Session separators are written at each server start/stop for clear audit trail boundaries

---

## Security Architecture

- **[REDACTED] key derivation** — multiple private transformation stages baked into the JAR; no key material is stored externally or in config files
- **Date-based key rotation** — keys rotate daily, preventing replay attacks across sessions
- **Midnight grace window** — prevents false positives when a packet crosses the midnight boundary
- **AES/GCM authenticated encryption** — [REDACTED implementation details] — detects any in-transit tampering
- **PBKDF2 key stretching** — [REDACTED iteration count and parameters]
- **Per-encryption random IV** — prevents pattern analysis
- **Encrypted environment reports** — reports are encrypted with the player's passkey before transmission; a fabricated clean report requires breaking the passkey derivation first
- **Checksum generated in memory** — no shared files in the validation hot path; concurrent logins cannot interfere with each other
- **Violation persistence** — violations survive server restarts; players cannot reset their record by forcing a crash or restart
- **ProGuard obfuscation** — see Code Protection

---

## Code Protection

**Always distribute the obfuscated build.** The development build contains readable class and method names.

```bash
# Development build
./gradlew build
# Output: build/libs/raeyncheat-1.0.0.jar

# Production/distribution build (obfuscated)
./gradlew proguard
# Output: build/libs/raeyncheat-1.0.0-obfuscated.jar
```

ProGuard applies the following protections (specific configuration details withheld):
- Class, method, and field renaming to non-descriptive identifiers
- [REDACTED — obfuscation pass details withheld]
- Control flow obfuscation
- Aggressive optimization to obscure implementation logic

See `OBFUSCATION.md` for configuration details (not for public distribution).

---

## Project Structure

```
src/main/java/com/raeyncreations/raeyncheat/
├── RaeYNCheat.java                  # Main mod class — server init, periodic revalidation ticker
├── client/
│   ├── RaeYNCheatClient.java        # Client entry point — scan, encrypt, send, handle revalidation
│   └── EnvironmentScanner.java      # JVM args, extra dirs, ModList cross-ref, ClassLoader scan
├── config/
│   └── RaeYNCheatConfig.java        # Config handling, violation persistence, punishment steps
├── server/
│   ├── PlayerConnectionHandler.java # Login/logout event handlers
│   ├── ValidationHandler.java       # Three-phase validation (passkey, checksum, environment)
│   └── RaeYNCommand.java            # Admin commands
├── network/
│   ├── NetworkHandler.java          # Packet registration
│   ├── SyncPacket.java              # Client→Server: passkey + checksum + encrypted env report
│   └── RevalidatePacket.java        # Server→Client: trigger a fresh scan and SyncPacket
└── util/
    ├── ChecksumUtil.java            # Per-JAR checksum calculation (CRC32, SHA-256, MD5)
    ├── EncryptionUtil.java          # [REDACTED — encryption/key derivation implementation]
    ├── CheckFileManager.java        # Check file generation, in-memory comparison
    └── PasskeyLogger.java           # Async audit logging with queue overflow protection
```

---

## Building

### Requirements
- Java 21
- Gradle 8.x

### Commands
```bash
./gradlew build       # Development build
./gradlew proguard    # Obfuscated production build
```

---

## Troubleshooting

### Mod Verification Disabled on Server

```
[WARN] mods_client directory does not exist. Mod verification is DISABLED.
```

**Solution**: Create the `mods_client` folder in the server root directory and populate it with the expected client mod JARs, then restart the server. Use `/raeyn cheat checksum refresh` after any changes to `mods_client` without a full restart.

### Mod Verification Disabled on Client

```
[WARN] mods directory does not exist. Client check file generation is DISABLED.
```

**Solution**: This indicates a corrupted Minecraft installation. Verify your installation and ensure the `mods` folder exists with correct permissions.

### False Positives on Environment Check

If legitimate players are being flagged by the environment check (common with Prism Launcher, MultiMC, or GDLauncher due to non-standard ClassLoader entries):

1. Leave `enforceClassLoaderCheck` as `false` (default) until you have reviewed `cheat.log` entries from your actual player base
2. Review `JVM_FLAG:UNKNOWN:` entries in `cheat.log` — if a specific flag appears consistently from clean players, it can be added to the whitelist in `EnvironmentScanner.java` before the obfuscated build
3. The environment punishment steps default to two kicks before escalating, giving legitimate players a chance to be reviewed before a ban is applied

### Players Repeatedly Failing Validation at Midnight

Key rollover happens at 00:00:00 server time. If players are consistently failing at midnight:
- The grace window handles packets that cross the boundary
- Ensure server and client clocks are not severely out of sync (>30 seconds drift can cause issues)

### Missing Mods / Registry Sync Errors

Errors like `Failed to handle registry sync from server` or `Channel failed to connect: missing on server side` are **not caused by RaeYNCheat**. They indicate a mod mismatch between client and server. Ensure both sides have identical mod lists and versions.

---

## License

MIT License — see LICENSE file for details.
