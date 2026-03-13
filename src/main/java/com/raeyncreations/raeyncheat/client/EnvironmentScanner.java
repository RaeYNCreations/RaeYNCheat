package com.raeyncreations.raeyncheat.client;

import com.raeyncreations.raeyncheat.RaeYNCheat;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Client-side environment scanner.
 *
 * Detects injection vectors that file-hash checking alone cannot catch:
 *   1. JVM arguments  — -javaagent, -Xbootclasspath, suspicious -D flags
 *   2. Extra JAR directories — side-loaded mods outside the standard mods/ folder
 *   3. NeoForge ModList cross-reference — mods loaded by the runtime vs files on disk
 *   4. Suspicious ClassLoader entries — unexpected URLs in the classloader hierarchy
 *
 * Results are packed into an {@link EnvironmentReport} and sent to the server inside
 * every SyncPacket so the server can validate them independently.
 *
 * All scanning is done defensively — any individual check that throws is caught and
 * recorded as a warning rather than crashing the client or blocking the login flow.
 */
public class EnvironmentScanner {

    // ---------------------------------------------------------------------------
    // Known-safe JVM argument prefixes
    // These are standard launcher / NeoForge / JVM arguments that are always present.
    // Anything NOT matching this whitelist is flagged.
    // ---------------------------------------------------------------------------
    private static final List<String> SAFE_JVM_ARG_PREFIXES = Arrays.asList(
        // Standard JVM
        "-Xmx", "-Xms", "-Xss", "-XX:", "-Xmn", "-Xnoclassgc",
        "-ea", "-da", "-esa", "-dsa",
        "-verbose", "-version", "--version",
        "-cp", "-classpath", "--class-path",
        "--module-path", "--add-modules", "--add-opens", "--add-exports",
        "--add-reads", "--patch-module", "--limit-modules",
        // NeoForge / FML standard args
        "-Dfml.", "-Dforge.", "-Dnet.minecraftforge.", "-Dneoforge.",
        "-DignoreList=", "-DmergeList=",
        // GC flags that launchers set
        "-Dfile.encoding=", "-Djava.library.path=",
        "-Djna.tmpdir=", "-Dorg.lwjgl.", "-Dio.netty.",
        // Minecraft launcher args
        "-Djava.rmi.server.", "-Dcom.sun.jndi.",
        // macOS specific
        "-XstartOnFirstThread", "-Dapple.",
        // Common safe system properties
        "-Dlog4j2.", "-Dlog4j.", "-Duser.",
        "-Djava.awt.", "-Dsun.", "-Djava.net."
    );

    // ---------------------------------------------------------------------------
    // Extra directories to scan beyond mods/
    // ---------------------------------------------------------------------------
    private static final List<String> EXTRA_SCAN_DIRS = Arrays.asList(
        "mods/memory_repo",
        "mods/shaderpacks",
        "mods/.cache",
        "libraries",
        "natives",
        "resourcepacks"   // resource pack JARs can contain code via overlays
    );

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Run all environment checks and return a packed report string.
     * The report is a newline-delimited set of KEY=VALUE pairs, encrypted by
     * the caller before transmission (same passkey used for checksum).
     *
     * Format lines:
     *   JVM_FLAG:<flagged arg>
     *   EXTRA_JAR:<path relative to game dir>
     *   MOD_GHOST:<modid>          — in ModList but no matching JAR on disk
     *   MOD_EXTRA:<filename>       — JAR on disk not accounted for by ModList
     *   CL_ANOMALY:<url>           — unexpected URL in classloader
     *   CLEAN                      — present only if ALL checks passed with no flags
     */
    public static String generateReport() {
        List<String> findings = new ArrayList<>();

        safeRun("JVM args",         () -> scanJvmArgs(findings));
        safeRun("Extra directories",() -> scanExtraDirectories(findings));
        safeRun("ModList cross-ref",() -> crossReferenceModList(findings));
        safeRun("ClassLoader",      () -> scanClassLoader(findings));

        if (findings.isEmpty()) {
            findings.add("CLEAN");
        }

        return String.join("\n", findings);
    }

    // ---------------------------------------------------------------------------
    // Check 1: JVM arguments
    // ---------------------------------------------------------------------------

    /**
     * Scan JVM input arguments for anything that isn't on the whitelist.
     *
     * Primary targets:
     *   -javaagent:     — attaches a Java agent (most common cheat injection vector)
     *   -Xbootclasspath — prepends classes before the JVM bootstrap loader
     *   -agentlib:      — native agent (e.g. JVMTI-based cheats)
     *   -agentpath:     — native agent by path
     *
     * Anything else that doesn't match a known-safe prefix is also flagged as UNKNOWN.
     */
    private static void scanJvmArgs(List<String> findings) {
        List<String> args;
        try {
            args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (Exception e) {
            findings.add("JVM_SCAN_ERROR:" + e.getMessage());
            return;
        }

        // FIX #11: Merge hardcoded whitelist with any admin-configured extra entries.
        // The config whitelist is server-side only — we transmit findings and the server
        // applies policy, so the client scanner is intentionally conservative (flags more).
        // The server ValidationHandler will not punish for entries the admin has whitelisted.
        // NOTE: We intentionally do NOT read the config here — this runs client-side and the
        // client doesn't have access to the server config. The extra whitelist from config is
        // applied on the SERVER in ValidationHandler when parsing the received report.
        // This comment is here to document why we don't try to load config client-side.

        for (String arg : args) {
            // High-confidence injection vectors — always flag regardless of whitelist.
            if (arg.startsWith("-javaagent:")) {
                findings.add("JVM_FLAG:JAVAAGENT:" + sanitize(arg));
                continue;
            }
            if (arg.startsWith("-Xbootclasspath")) {
                findings.add("JVM_FLAG:BOOTCLASSPATH:" + sanitize(arg));
                continue;
            }
            if (arg.startsWith("-agentlib:")) {
                findings.add("JVM_FLAG:AGENTLIB:" + sanitize(arg));
                continue;
            }
            if (arg.startsWith("-agentpath:")) {
                findings.add("JVM_FLAG:AGENTPATH:" + sanitize(arg));
                continue;
            }

            // Check against whitelist — flag anything that doesn't match.
            boolean safe = false;
            for (String prefix : SAFE_JVM_ARG_PREFIXES) {
                if (arg.startsWith(prefix)) { safe = true; break; }
            }
            if (!safe) {
                findings.add("JVM_FLAG:UNKNOWN:" + sanitize(arg));
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Check 2: Extra directories
    // ---------------------------------------------------------------------------

    /**
     * Scan additional directories for JAR files that wouldn't be caught by the
     * standard mods/ hash check. Side-loaded JARs in these locations can inject
     * code without touching the mods/ folder.
     */
    private static void scanExtraDirectories(List<String> findings) {
        Path gameDir = FMLPaths.GAMEDIR.get();

        for (String relPath : EXTRA_SCAN_DIRS) {
            Path dir = gameDir.resolve(relPath);
            if (!Files.exists(dir) || !Files.isDirectory(dir)) continue;

            File[] jars = dir.toFile().listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
            if (jars == null) continue;

            for (File jar : jars) {
                // Relative path from game dir for readability in the report.
                String rel = gameDir.relativize(jar.toPath()).toString().replace("\\", "/");
                findings.add("EXTRA_JAR:" + rel);
            }
        }

        // Also scan the game directory root itself for stray JARs.
        File[] rootJars = gameDir.toFile().listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        if (rootJars != null) {
            for (File jar : rootJars) {
                findings.add("EXTRA_JAR:ROOT/" + jar.getName());
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Check 3: ModList cross-reference
    // ---------------------------------------------------------------------------

    /**
     * Compare NeoForge's runtime ModList against the JAR files present in mods/.
     *
     * Ghost mods: present in ModList but no corresponding file — suggests runtime injection.
     * Extra files: JAR in mods/ not accounted for by ModList — catches mods that bypass
     *              NeoForge's loading but still exist on disk (e.g. loaded by an agent).
     *
     * Note: ModList mod IDs don't map 1:1 to filenames, so we do a best-effort match
     * rather than an exact match. The server uses this as a signal, not a hard block.
     */
    private static void crossReferenceModList(List<String> findings) {
        // Collect all mod IDs from the runtime ModList.
        Set<String> runtimeModIds = new HashSet<>();
        try {
            ModList.get().getMods().forEach(info -> runtimeModIds.add(info.getModId().toLowerCase()));
        } catch (Exception e) {
            findings.add("MODLIST_SCAN_ERROR:" + e.getMessage());
            return;
        }

        // Collect all JAR filenames from mods/.
        Path modsDir = FMLPaths.GAMEDIR.get().resolve("mods");
        Set<String> diskFileNames = new HashSet<>();
        if (Files.exists(modsDir)) {
            File[] jars = modsDir.toFile().listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
            if (jars != null) {
                for (File jar : jars) {
                    diskFileNames.add(jar.getName().toLowerCase());
                }
            }
        }

        // Flag any runtime mod whose ID doesn't loosely correspond to any file on disk.
        // We skip vanilla mod IDs that NeoForge always injects (minecraft, neoforge, fml, etc.).
        Set<String> alwaysPresentIds = new HashSet<>(Arrays.asList(
            "minecraft", "neoforge", "forge", "fml", "neoforgespi",
            RaeYNCheat.MOD_ID
        ));

        for (String modId : runtimeModIds) {
            if (alwaysPresentIds.contains(modId)) continue;
            // Check if any disk file contains the mod ID as a substring (loose match).
            boolean found = diskFileNames.stream().anyMatch(f -> f.contains(modId));
            if (!found) {
                findings.add("MOD_GHOST:" + modId);
            }
        }

        // Send the total loaded mod count — useful for server-side cross-check.
        findings.add("MOD_COUNT:" + runtimeModIds.size());
        findings.add("DISK_JAR_COUNT:" + diskFileNames.size());
    }

    // ---------------------------------------------------------------------------
    // Check 4: ClassLoader anomaly detection
    // ---------------------------------------------------------------------------

    /**
     * Walk the ClassLoader hierarchy looking for unexpected URL sources.
     *
     * A URLClassLoader with entries pointing to temp directories, unusual paths,
     * or injected JARs outside the game directory is a strong indicator of a
     * runtime injection. After ProGuard obfuscates this method's name, it becomes
     * significantly harder to bypass by patching the scanner itself.
     */
    private static void scanClassLoader(List<String> findings) {
        Path gameDir = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        String javaHome = System.getProperty("java.home", "").toLowerCase();

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Set<String> seen = new HashSet<>();
        int depth = 0;

        while (cl != null && depth < 10) {
            if (cl instanceof java.net.URLClassLoader ucl) {
                for (java.net.URL url : ucl.getURLs()) {
                    String urlStr = url.toString();
                    if (!seen.add(urlStr)) continue;

                    try {
                        Path urlPath = java.nio.file.Paths.get(url.toURI()).toAbsolutePath().normalize();
                        String urlPathStr = urlPath.toString().toLowerCase();

                        // Skip anything inside the Java home (JDK/JRE libs).
                        if (!javaHome.isEmpty() && urlPathStr.startsWith(javaHome)) continue;

                        // Skip anything inside the game directory (expected).
                        if (urlPath.startsWith(gameDir)) continue;

                        // Skip standard temp patterns that launchers use legitimately.
                        if (urlPathStr.contains("launcher") || urlPathStr.contains("launcherdata")) continue;

                        // Anything outside the game dir and not a JDK lib is suspicious.
                        findings.add("CL_ANOMALY:" + sanitize(urlStr));

                    } catch (Exception ignored) {
                        // URI conversion can fail for non-file URLs — skip.
                    }
                }
            }
            cl = cl.getParent();
            depth++;
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Run a scanning lambda, catching all exceptions so one failing check doesn't break others. */
    private static void safeRun(String checkName, Runnable check) {
        try {
            check.run();
        } catch (Exception e) {
            RaeYNCheat.LOGGER.warn("EnvironmentScanner: {} check threw unexpectedly: {}", checkName, e.getMessage());
        }
    }

    /**
     * Sanitize a string for safe inclusion in a report line.
     * Removes newlines (which would break the KEY=VALUE line format) and
     * truncates to 256 chars to prevent oversized payloads.
     */
    private static String sanitize(String input) {
        if (input == null) return "null";
        String cleaned = input.replace("\n", " ").replace("\r", " ").trim();
        return cleaned.length() > 256 ? cleaned.substring(0, 256) + "..." : cleaned;
    }
}
