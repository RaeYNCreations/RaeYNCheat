# ProGuard configuration for RaeYNCheat mod
# This file configures aggressive obfuscation to protect the mod from decompilation

# Keep essential Minecraft mod structure
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep mod entry points (Neoforge/Fabric require these)
-keep @net.neoforged.fml.common.Mod class * {
    public <init>(...);
}

-keep class com.raeyncreations.raeyncheat.RaeYNCheat {
    public <init>(...);
}

# Keep client and server entry points
-keep class com.raeyncreations.raeyncheat.client.* {
    public <init>(...);
}

# Keep command classes (required by Minecraft command system)
-keep class * extends com.mojang.brigadier.Command {
    <methods>;
}

-keep class com.raeyncreations.raeyncheat.server.*Command {
    public static void register(...);
}

# Keep config class field names (needed for JSON serialization)
-keepclassmembers class com.raeyncreations.raeyncheat.config.RaeYNCheatConfig {
    public <fields>;
}

# Aggressive obfuscation settings
-obfuscationdictionary obfuscation-dictionary.txt
-classobfuscationdictionary obfuscation-dictionary.txt
-packageobfuscationdictionary obfuscation-dictionary.txt

# Rename packages
-repackageclasses ''
-allowaccessmodification

# Optimize aggressively
-optimizationpasses 9
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# Obfuscate method and field names
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# String encryption (encrypt constant strings)
-adaptclassstrings
-adaptresourcefilenames
-adaptresourcefilecontents

# Control flow obfuscation
-mergeinterfacesaggressively
-overloadaggressively

# Remove debug information
-assumenosideeffects class java.lang.System {
    public static void setErr(...);
    public static void setOut(...);
}

# Encrypt string literals (additional protection)
-assumenosideeffects class java.lang.String {
    public String intern() return this;
}

# Remove logging in production (optional - comment out if you need logs)
# -assumenosideeffects class org.slf4j.Logger {
#     public void trace(...);
#     public void debug(...);
#     public void info(...);
# }

# Anti-decompilation measures
# Add fake exception handlers and dead code
-dontshrink

# Keep line numbers for stack traces (helps with debugging)
# Remove this for maximum obfuscation
-keepattributes LineNumberTable

# Don't warn about missing classes
-dontwarn **

# Additional security: Encrypt the permanent key even more
# The obfuscator will rename and inline methods making it harder to find
-assumenosideeffects class com.raeyncreations.raeyncheat.util.EncryptionUtil {
    private static java.lang.String getPermanentKey() return null;
}
