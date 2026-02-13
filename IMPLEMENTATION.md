# RaeYNCheat Implementation Details

## Overview

This document provides technical implementation details for the RaeYNCheat mod system.

## Architecture

### Two-Part Passkey System

The passkey system combines two elements:
1. **Permanent Key**: `"2003, December 15th"` - Date-based key embedded in the mod code
2. **Player UUID**: Unique Minecraft account UUID

Combined format: `"2003, December 15th:player-uuid-here"`

**Obfuscation of Permanent Key**:
- String is reversed
- Encoded with Base64
- Deobfuscated when needed for operations

This key is used for:
- Deriving AES encryption keys via SHA-256
- XOR obfuscation pattern
- Passkey validation between client and server

### Checksum Process

#### Client-Side Flow
1. **On Boot/Server Join**:
   ```
   Scan mods/*.jar → Calculate CRC32, SHA-256, MD5 for each
   → Aggregate all checksums → Calculate SHA-256 of aggregate
   → Obfuscate (XOR) → Encrypt (AES) → Write to CheckSum file
   ```

2. **CheckSum File Location**: `config/RaeYNCheat/CheckSum`

3. **Algorithms Used**:
   - **CRC32**: Fast integrity check
   - **SHA-256**: Cryptographic hash
   - **MD5**: Additional verification

#### Server-Side Flow
1. **On Boot**:
   ```
   Scan mods_client/*.jar → Calculate checksums
   → Aggregate → Calculate SHA-256 → Obfuscate (XOR)
   → Write to CheckSum_init file
   ```

2. **On Player Connection**:
   ```
   Validate client passkey → Generate player-specific passkey
   → Read CheckSum_init → Encrypt with player's key → Write to CheckSum
   → Compare with client's CheckSum → Analyze sensitivity
   → Apply punishment if needed
   ```
   ```

3. **Files**:
   - `CheckSum_init`: Pre-obfuscated, ready for encryption
   - `CheckSum`: Player-specific encrypted check file

### Encryption & Obfuscation

#### XOR Obfuscation
```java
for (int i = 0; i < bytes.length; i++) {
    obfuscated[i] = bytes[i] ^ pattern[i % pattern.length];
}
```
- Pattern: Bytes of permanent key
- Applied before encryption
- Prevents simple text analysis

#### AES Encryption
- Algorithm: AES-128
- Key derivation: SHA-256 hash of passkey → first 16 bytes
- Applied after obfuscation
- Prevents decryption without correct passkey

### Punishment System

#### Configuration
```json
{
  "enablePunishmentSystem": true,
  "punishmentSteps": [60, 300, 600, 1800, 3600, 7200, 14400, 28800, 86400, -1],
  "enablePasskeyPunishmentSystem": true,
  "passkeyPunishmentSteps": [300, 1800, 7200, 86400, -1],
  "enableSensitivityChecks": true,
  "sensitivityThresholdLow": 2,
  "sensitivityThresholdHigh": 10
}
```

#### Dual Violation Tracking
- **Checksum violations**: Tracked separately in `checksumViolations` map
- **Passkey violations**: Tracked separately in `passkeyViolations` map
- Each violation type has its own punishment escalation

#### Validation Rules
- Values must be: positive integers, 0, or -1
- Maximum 30 steps per punishment system
- `-1` = permanent ban + blacklist
- `0` = warning only

#### Escalation Logic
```java
// Checksum violations
checksumViolations.put(playerUUID, count + 1);
int duration = config.getPunishmentDuration(count);

// Passkey violations (more aggressive)
passkeyViolations.put(playerUUID, count + 1);
int duration = config.getPasskeyPunishmentDuration(count);
```

#### Admin Commands
```
/raeyn cheat checksum <player>  - Checksum violation punishment
/raeyn cheat passkey <player>   - Passkey violation punishment
```
- Both require OP level 2
- Record respective violation type
- Apply punishment based on violation count
- Permanent ban adds to vanilla blacklist

### Sensitivity Analysis

#### Detection Levels
```java
enum SensitivityLevel {
    NO_DIFFERENCE,      // 0 files different
    LOW_DIFFERENCE,     // 1-2 files (might be testing)
    MEDIUM_DIFFERENCE,  // 3-9 files (suspicious)
    HIGH_DIFFERENCE,    // 10+ files (might be wrong modpack)
    TOTAL_MISMATCH      // 80%+ files different
}
```

#### Analysis Process
```java
// Compare client and server mod lists
int added = clientMods - serverMods;
int removed = serverMods - clientMods;
int modified = sameNameDifferentChecksum;

// Apply thresholds
if (total <= lowThreshold) → LOW_DIFFERENCE
else if (total < highThreshold) → MEDIUM_DIFFERENCE
else if (total >= highThreshold) → HIGH_DIFFERENCE
```

#### Punishment Application
- **NO_DIFFERENCE**: No action
- **LOW_DIFFERENCE**: Warn only (unless strict mode)
- **MEDIUM_DIFFERENCE**: Always punish
- **HIGH_DIFFERENCE**: Warn (possible accident)
- **TOTAL_MISMATCH**: Warn (wrong installation)

## File Formats

### CheckSum File
```
Base64(AES_Encrypt(XOR_Obfuscate(SHA256_Hash)))
```

Example (after decryption/deobfuscation):
```
a3f5b2c1d4e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2
```

### Checksum List (temporary, aggregated before hashing)
```
modfile1.jar|12345678|sha256hash1|md5hash1
modfile2.jar|87654321|sha256hash2|md5hash2
...
```

### Config File (config/RaeYNCheat/config.json)
```json
{
  "enablePunishmentSystem": true,
  "punishmentSteps": [60, 300, 600, 1800, 3600, 7200, 14400, 28800, 86400, -1]
}
```

## Security Considerations

### Strengths
1. **Unique per player**: UUID prevents key sharing
2. **Multi-layer protection**: XOR + AES
3. **Real-time generation**: Files regenerated on each connection
4. **Tamper detection**: Any mod change = different checksum

### Limitations
1. **Client-side code**: Permanent key is in mod code (can be extracted)
2. **Memory manipulation**: Advanced cheats could modify runtime memory
3. **Network interception**: Theoretical MITM attack possible
4. **File replacement**: Client could be modified to send fake checksums

### Mitigation Strategies
1. **Obfuscated permanent key**: Makes extraction harder
2. **Real-time validation**: Server generates expected values
3. **Progressive punishment**: Deters repeated violations
4. **Blacklist integration**: Permanent bans persist

## Branch Structure

### Fabric Branch (`fabric-1.21.1`)
- Uses Fabric Loader API
- Entry points: `ModInitializer`, `ClientModInitializer`, `DedicatedServerModInitializer`
- Fabric API for events and commands
- Build tool: fabric-loom

### Neoforge Branch (`neoforge-1.21.1`)
- Uses Neoforge mod loader
- Entry point: `@Mod` annotation
- Event bus system for initialization
- Build tool: neoforge.gradle

### Common Code
- All utility classes (`util/` package)
- Configuration system (`config/` package)
- Server command logic (`server/` package)

## Future Enhancements

### Potential Improvements
1. **Network protocol**: Add custom packet for checksum exchange
2. **Hash algorithms**: Add newer algorithms (SHA-3, BLAKE3)
3. **Dynamic keys**: Server-generated session keys
4. **Database integration**: Store violations in database
5. **Automatic ban management**: Time-based ban expiration
6. **Webhook notifications**: Alert admins of violations
7. **Signature verification**: Code signing for mod authenticity

### Portability
Branch naming convention supports:
- `fabric-1.21.8` - Future Fabric versions
- `neoforge-1.21.11` - Future Neoforge versions
- `fabric-1.20.4` - Backports to older versions
- `forge-1.21.1` - Forge mod loader support

## Testing Checklist

### Client Testing
- [ ] CheckSum file generated on boot
- [ ] CheckSum regenerated on server join
- [ ] Correct UUID used in passkey
- [ ] Files created in config/RaeYNCheat/

### Server Testing  
- [ ] CheckSum_init generated on boot
- [ ] CheckSum generated per player connection
- [ ] mods_client folder scanned correctly
- [ ] Config file created with defaults

### Command Testing
- [ ] `/raeyn cheat checksum <player>` requires OP level 2
- [ ] `/raeyn cheat passkey <player>` requires OP level 2
- [ ] Violations tracked correctly
- [ ] Punishments escalate properly
- [ ] Permanent ban adds to blacklist

### Config Testing
- [ ] Config validates on load
- [ ] Invalid values corrected
- [ ] Maximum 30 steps enforced
- [ ] Only -1, 0, positive integers allowed

## Troubleshooting

### Common Issues

**CheckSum file not generated**
- Check config/RaeYNCheat/ folder exists
- Verify mods/ folder has JAR files
- Check logs for exceptions

**Server CheckSum_init missing**
- Verify mods_client/ folder exists
- Ensure mods_client/ contains JAR files
- Check server logs

**Command not working**
- Confirm player has OP level 2+
- Check command syntax: `/raeyn cheat checksum <playername>` or `/raeyn cheat passkey <playername>`
- Review server logs for errors

**Config not saving**
- Check file permissions
- Verify config/RaeYNCheat/ is writable
- Look for JSON parsing errors in logs

## Performance Notes

- **Checksum calculation**: O(n) where n = total size of all mod JARs
- **Typical overhead**: 100-500ms on client boot
- **Server overhead**: 50-200ms per player connection
- **Memory usage**: Minimal (~1MB for checksums)

## Compatibility

### Minecraft Versions
- **Current**: 1.21.1
- **Portable to**: Any version with Fabric/Neoforge support

### Mod Loaders
- **Implemented**: Fabric, Neoforge
- **Possible**: Forge (requires adaptation)

### Java Versions
- **Required**: Java 21+
- **Tested**: Java 21

## License

MIT License - See LICENSE file
