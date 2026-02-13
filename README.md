# RaeYNCheat

A comprehensive mod verification and anti-cheat system for Minecraft 1.21.1 (Fabric & Neoforge) that uses encrypted checksums to verify client-side mods.

## Features

- **Client-side mod verification** with CRC32, SHA-256, and MD5 checksums
- **Two-part passkey system** combining a permanent key and player UUID
- **Encryption and obfuscation** to prevent tampering
- **Automatic check file generation** on each client launch and server connection
- **Server-side verification** comparing client mods against expected mods
- **Progressive punishment system** with configurable ban durations
- **Admin commands** for managing punishments

## Branches

- `fabric-1.21.1` - Fabric mod loader for Minecraft 1.21.1
- `neoforge-1.21.1` - Neoforge mod loader for Minecraft 1.21.1

Branch naming convention allows for future version ports (e.g., `fabric-1.21.8`, `neoforge-1.21.11`, etc.)

## How It Works

### Client Side

1. On game launch, the client generates a two-part passkey (permanent key + player UUID)
2. Scans all JAR files in the `mods` folder
3. Calculates CRC/hash/checksum for each JAR
4. Creates an aggregate checksum of all mod checksums
5. Obfuscates and encrypts the aggregate checksum
6. Stores the encrypted result in `config/RaeYNCheat/CheckSum`

### Server Side

1. On server boot, scans JAR files in `mods_client` folder (expected client mods)
2. Generates `CheckSum_init` file (obfuscated but not yet encrypted)
3. When a player connects:
   - Generates a unique two-part passkey for that player
   - Encrypts the `CheckSum_init` with the player's key
   - Compares the server-generated checksum with the client's checksum
   - Authenticates or denies based on comparison

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
  ]
}
```

### Punishment Steps
- Each step represents the ban duration in seconds
- `-1` indicates a permanent ban (also adds player to blacklist)
- `0` indicates a warning only
- Only positive integers, `0`, or `-1` are allowed
- Maximum of 30 steps can be configured
- Punishment escalates with each violation

## Admin Commands

### `/raeynpunish <player>`
Manually punish a player for mod verification failures.
- Requires operator permission level 2
- Records a violation and applies punishment based on violation count
- Progressive punishment according to configured steps

## Security Features

- **Two-part passkey**: Combines permanent embedded key with player UUID
- **Obfuscation**: XOR-based obfuscation to prevent simple reading
- **Encryption**: AES encryption using SHA-256 derived keys
- **Real-time generation**: Check files generated fresh on each connection
- **Tamper-proof**: Keys cannot be manipulated in real-time

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
