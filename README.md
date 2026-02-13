# RaeYNCheat

A comprehensive mod verification and anti-cheat system for Minecraft 1.21.1 (Fabric & Neoforge) that uses encrypted checksums and passkey validation to verify client-side mods.

## Features

- **Client-side mod verification** with CRC32, SHA-256, and MD5 checksums
- **Two-part passkey system** with date-based permanent key ("2003, December 15th") and player UUID
- **Dual violation tracking** - separate systems for checksum and passkey violations
- **Encryption and obfuscation** to prevent tampering
- **Automatic check file generation** on each client launch and server connection
- **Server-side verification** comparing client mods against expected mods
- **Passkey synchronization** - client passkeys validated against server
- **Progressive punishment systems** with configurable ban durations for both violations
- **Sensitivity analysis** - detects potential false positives (1-2 file changes vs entire modlist)
- **Admin commands** for managing both checksum and passkey punishments

## Branches

- `fabric-1.21.1` - Fabric mod loader for Minecraft 1.21.1
- `neoforge-1.21.1` - Neoforge mod loader for Minecraft 1.21.1

Branch naming convention allows for future version ports (e.g., `fabric-1.21.8`, `neoforge-1.21.11`, etc.)

## How It Works

### Client Side

1. On game launch, the client generates a two-part passkey (date-based permanent key + player UUID)
2. Scans all JAR files in the `mods` folder
3. Calculates CRC/hash/checksum for each JAR
4. Creates an aggregate checksum of all mod checksums
5. Obfuscates and encrypts the aggregate checksum
6. Stores the encrypted result in `config/RaeYNCheat/CheckSum`
7. Sends passkey to server for validation

### Server Side

1. On server boot, scans JAR files in `mods_client` folder (expected client mods)
2. Generates `CheckSum_init` file (obfuscated but not yet encrypted)
3. When a player connects:
   - Validates the client's passkey against expected passkey
   - Generates a unique two-part passkey for that player
   - Encrypts the `CheckSum_init` with the player's key
   - Compares the server-generated checksum with the client's checksum
   - Analyzes differences for sensitivity (false positive detection)
   - Authenticates or denies based on comparison and sensitivity analysis

## Installation

### Client
1. Place the mod JAR in your `mods` folder
2. Launch the game - the mod will automatically generate check files

### Server
1. Place the mod JAR in your `mods` folder
2. Create a `mods_client` folder in the server root directory
3. Place all expected client mods in `mods_client`
4. Launch the server - the mod will generate the initial check file

## Configuration

Configuration file: `config/RaeYNCheat/config.json`

```json
{
  "enablePunishmentSystem": true,
  "punishmentSteps": [
    60,      // 1 minute
    300,     // 5 minutes
    600,     // 10 minutes
    1800,    // 30 minutes
    3600,    // 1 hour
    7200,    // 2 hours
    14400,   // 4 hours
    28800,   // 8 hours
    86400,   // 24 hours
    -1       // Permanent ban
  ],
  "enablePasskeyPunishmentSystem": true,
  "passkeyPunishmentSteps": [
    300,     // 5 minutes
    1800,    // 30 minutes
    7200,    // 2 hours
    86400,   // 24 hours
    -1       // Permanent ban
  ],
  "enableSensitivityChecks": true,
  "sensitivityThresholdLow": 2,
  "sensitivityThresholdHigh": 10
}
```

### Punishment Steps
- Each step represents the ban duration in seconds
- `-1` indicates a permanent ban (also adds player to blacklist)
- `0` indicates a warning only
- Only positive integers, `0`, or `-1` are allowed
- Maximum of 30 steps can be configured
- Punishment escalates with each violation

### Dual Punishment Systems
- **Checksum violations** - for mod list mismatches
- **Passkey violations** - for passkey validation failures (more aggressive by default)

### Sensitivity Settings
- **sensitivityThresholdLow** (default: 2) - 1-2 files different may indicate intentional testing
- **sensitivityThresholdHigh** (default: 10) - 10+ files different may indicate wrong modpack (accident)
- **enableSensitivityChecks** - enables false positive detection

## Admin Commands

### `/raeynpunish <player>`
Manually punish a player for checksum verification failures.
- Requires operator permission level 2
- Records a checksum violation and applies punishment based on violation count
- Progressive punishment according to configured checksum punishment steps

### `/raeynpasskeyban <player>`
Manually punish a player for passkey verification failures.
- Requires operator permission level 2
- Records a passkey violation and applies punishment based on passkey violation count
- Progressive punishment according to configured passkey punishment steps
- More aggressive by default since passkey failures are more suspicious

## Security Features

- **Two-part passkey**: Date-based permanent key ("2003, December 15th") + player UUID
- **Passkey obfuscation**: Base64 encoding and string reversal to hide permanent key
- **Passkey validation**: Server validates client passkeys on connection
- **XOR obfuscation**: Prevents simple reading of encrypted data
- **AES encryption**: Using SHA-256 derived keys
- **Real-time generation**: Check files generated fresh on each connection
- **Tamper-proof**: Keys cannot be manipulated in real-time
- **Dual violation tracking**: Separate tracking for checksum and passkey violations
- **Sensitivity analysis**: Distinguishes between intentional changes and accidents

## Building

### Fabric
```bash
./gradlew build
```

The compiled JAR will be in `build/libs/`

## Development

### Requirements
- Java 21
- Gradle 8.x

### Project Structure
```
src/main/java/com/raeyncreations/raeyncheat/
├── RaeYNCheat.java                 # Main mod class
├── RaeYNCheatClient.java           # Client-side entry point
├── RaeYNCheatServer.java           # Server-side entry point
├── config/
│   └── RaeYNCheatConfig.java       # Configuration handling
├── server/
│   └── PunishCommand.java          # Admin punishment command
└── util/
    ├── ChecksumUtil.java           # Checksum calculation
    ├── EncryptionUtil.java         # Encryption/obfuscation
    └── CheckFileManager.java       # Check file generation/comparison
```

## License

MIT License - see LICENSE file for details
