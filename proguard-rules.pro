# ProGuard configuration for RaeYNCheat mod
# This file configures obfuscation to protect the mod from decompilation

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

# Keep all classes in the mod package to prevent breakage
-keep class com.raeyncreations.raeyncheat.** { *; }

# Less aggressive obfuscation settings
# -obfuscationdictionary obfuscation-dictionary.txt
# -classobfuscationdictionary obfuscation-dictionary.txt
# -packageobfuscationdictionary obfuscation-dictionary.txt

# Don't repackage classes to avoid renaming issues
# -repackageclasses ''
-allowaccessmodification

# Optimize
-optimizationpasses 5

# Obfuscate method and field names
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Don't warn about missing classes
-dontwarn **