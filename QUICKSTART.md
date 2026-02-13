# Quick Start Guide

## For Server Administrators

### Installation
1. Download the appropriate mod JAR for your mod loader:
   - `raeyncheat-fabric-1.0.0.jar` for Fabric servers
   - `raeyncheat-neoforge-1.0.0.jar` for Neoforge servers

2. Place the JAR in your server's `mods/` folder

3. Create a `mods_client/` folder in the server root directory

4. Copy all required client-side mods into `mods_client/`
   - These are the mods you expect players to have
   - Example: OptiFine, minimap mods, utility mods, etc.

5. Start your server
   - The mod will automatically generate `config/RaeYNCheat/CheckSum_init`
   - This file contains the expected client mod checksums

### Configuration
On first run, a config file is created at `config/RaeYNCheat/config.json`:

```json
{
  "enablePunishmentSystem": true,
  "punishmentSteps": [
    60,      // 1st violation: 60 seconds
    300,     // 2nd violation: 5 minutes
    600,     // 3rd violation: 10 minutes
    1800,    // 4th violation: 30 minutes
    3600,    // 5th violation: 1 hour
    7200,    // 6th violation: 2 hours
    14400,   // 7th violation: 4 hours
    28800,   // 8th violation: 8 hours
    86400,   // 9th violation: 24 hours
    -1       // 10th violation: PERMANENT BAN
  ]
}
```

#### Customizing Punishments
- **Disable system**: Set `enablePunishmentSystem` to `false`
- **Change durations**: Modify values in `punishmentSteps` array
- **Add steps**: Add more values (max 30)
- **Remove steps**: Remove values from array
- **Warning only**: Use `0` for a step
- **Permanent ban**: Use `-1` for a step

#### Valid Values
- `-1`: Permanent ban + blacklist
- `0`: Warning message only
- Positive integers: Ban duration in seconds

### Admin Commands

#### Punish a Player
```
/raeynpunish <playername>
```
- Requires OP level 2
- Records a violation for the player
- Applies punishment based on their violation count
- Progressive escalation per config

#### Examples
```
/raeynpunish Steve
/raeynpunish Alex
```

### Monitoring
Check server logs for:
```
[RaeYNCheat] Player connecting: <uuid>
[RaeYNCheat] Server check file generated for player: <uuid>
[RaeYNCheat] Player <uuid> has X violations. Punishment duration: Y seconds
```

## For Players

### Installation
1. Download the mod JAR matching your client:
   - `raeyncheat-fabric-1.0.0.jar` for Fabric
   - `raeyncheat-neoforge-1.0.0.jar` for Neoforge

2. Place the JAR in your `mods/` folder

3. Launch Minecraft
   - The mod automatically scans your mods
   - Creates a check file at `config/RaeYNCheat/CheckSum`

4. Join a server
   - The mod regenerates your check file
   - Server verifies your mods match expected mods

### What Happens
- ✅ **Mods match**: You connect normally
- ❌ **Mods don't match**: Violation recorded, punishment applied

### Avoiding Violations
- Only use mods allowed by the server
- Keep mods in the `mods/` folder (don't hide them)
- Don't modify mod JAR files
- Don't use modified clients

### If You're Punished
1. **Check your mods**: Ensure you only have allowed mods
2. **Remove unauthorized mods**: Delete any mods not permitted
3. **Wait for ban to expire**: If temporary ban
4. **Contact admin**: If you believe it's an error

## Troubleshooting

### Server Issues

**CheckSum_init file not created**
- Verify `mods_client/` folder exists
- Ensure `mods_client/` contains JAR files
- Check server logs for errors

**Command not working**
- Confirm you have OP level 2 or higher
- Check command syntax: `/raeynpunish playername`
- Review logs for error messages

**Players always fail verification**
- Ensure `mods_client/` contains the EXACT mods players should have
- Verify mod versions match
- Check if `CheckSum_init` was generated

### Client Issues

**CheckSum file not created**
- Check if `config/RaeYNCheat/` folder exists
- Verify `mods/` folder contains JAR files
- Look for errors in game logs

**Always kicked from server**
- Your mods don't match server expectations
- Contact server admin for list of allowed mods
- Ensure you have exact versions required

**Mod not loading**
- Verify you have the correct version (Fabric vs Neoforge)
- Check Minecraft version is 1.21.1
- Ensure Java 21 or higher is installed

## File Locations

### Server
```
server/
├── mods/
│   └── raeyncheat-*.jar
├── mods_client/                    # You create this
│   ├── allowedmod1.jar
│   ├── allowedmod2.jar
│   └── ...
└── config/
    └── RaeYNCheat/
        ├── config.json             # Auto-generated
        ├── CheckSum_init           # Auto-generated on boot
        └── CheckSum                # Auto-generated per player
```

### Client
```
.minecraft/
├── mods/
│   └── raeyncheat-*.jar
└── config/
    └── RaeYNCheat/
        └── CheckSum                # Auto-generated
```

## Advanced Configuration

### Lenient Punishment
For a more forgiving setup:
```json
{
  "enablePunishmentSystem": true,
  "punishmentSteps": [
    0,       // Warning only
    0,       // Warning only
    60,      // 1 minute
    300,     // 5 minutes
    -1       // Permanent
  ]
}
```

### Strict Punishment
For zero tolerance:
```json
{
  "enablePunishmentSystem": true,
  "punishmentSteps": [
    -1       // Immediate permanent ban
  ]
}
```

### Disabled Punishment (Monitoring Only)
```json
{
  "enablePunishmentSystem": false,
  "punishmentSteps": []
}
```

## Security Notes

### For Server Admins
- Keep `mods_client/` up to date with allowed mods
- Restart server after changing mods in `mods_client/`
- Monitor logs for violation patterns
- Review punishment config periodically

### For Players
- This mod verifies you have approved mods
- Modified or additional mods will be detected
- Hiding mods won't work - all JARs are scanned
- Using a clean client installation is recommended

## Support

### Getting Help
1. Check this guide first
2. Review server/client logs for error messages
3. Verify configuration is correct
4. Contact server administrator
5. Check GitHub issues: https://github.com/RaeYNCreations/RaeYNCheat/issues

### Reporting Bugs
Include:
- Mod version
- Minecraft version
- Mod loader (Fabric/Neoforge) and version
- Error logs
- Steps to reproduce

## License
MIT License - see LICENSE file
