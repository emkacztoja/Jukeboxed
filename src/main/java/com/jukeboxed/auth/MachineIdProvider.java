package com.jukeboxed.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves a per-machine identifier across Windows, macOS, and Linux.
 * <p>
 * Used as the password input to PBKDF2 when deriving the AES key for token
 * encryption. The deliberate threat-model boundary: this stops a casual scraper
 * from reading {@code .minecraft/config/jukeboxed/tokens.bin}, but cannot stop
 * a dedicated reverse-engineer with full local access.
 * <p>
 * Results are cached on first successful read; call {@link #invalidateCache()}
 * from tests that need to retake the lookup.
 */
final class MachineIdProvider {

    private static final AtomicReference<String> CACHED = new AtomicReference<>();
    private static volatile String testOverride;

    private MachineIdProvider() {
    }

    static String get() {
        String overridden = testOverride;
        if (overridden != null) {
            return overridden;
        }
        String cached = CACHED.get();
        if (cached != null) {
            return cached;
        }
        String detected = detect();
        if (detected == null || detected.isEmpty()) {
            throw new MachineIdUnavailableException("No machine UUID available on this OS");
        }
        CACHED.set(detected);
        return detected;
    }

    /** Test hook: pin a specific UUID without invoking the OS lookup. */
    static void setOverride(String value) {
        testOverride = value;
        CACHED.set(null);
    }

    static void invalidateCache() {
        CACHED.set(null);
    }

    private static String detect() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return detectWindows();
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return detectMac();
        }
        return detectLinux();
    }

    private static String detectWindows() {
        // Legacy `wmic` is being phased out — try it first since it's the lightest call.
        String legacy = run(new String[]{"wmic", "csproduct", "get", "uuid"});
        if (legacy != null) {
            return legacy;
        }
        // PowerShell fall-back. Two well-known command shapes — try each.
        String ps1 = run(new String[]{
                "powershell", "-NoProfile", "-Command",
                "(Get-CimInstance Win32_ComputerSystemProduct).UUID"
        });
        if (ps1 != null) {
            return ps1;
        }
        return run(new String[]{
                "powershell", "-NoProfile", "-Command",
                "Get-CimInstance Win32_ComputerSystemProduct | Select-Object -ExpandProperty UUID"
        });
    }

    private static String detectMac() {
        // ioreg lives at /usr/sbin on most installs, but check /usr/bin as well.
        for (String path : new String[]{"/usr/sbin/ioreg", "/usr/bin/ioreg"}) {
            String output = run(new String[]{path, "-rd1", "-c", "IOPlatformExpertDevice"});
            if (output == null) {
                continue;
            }
            for (String line : output.split("\n")) {
                if (!line.contains("IOPlatformUUID")) {
                    continue;
                }
                int firstQuote = line.indexOf('"');
                int lastQuote = line.lastIndexOf('"');
                if (firstQuote >= 0 && lastQuote > firstQuote) {
                    String candidate = line.substring(firstQuote + 1, lastQuote).trim();
                    if (!candidate.isEmpty()) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static String detectLinux() {
        for (String path : new String[]{"/var/lib/dbus/machine-id", "/etc/machine-id"}) {
            try {
                String content = Files.readString(Paths.get(path), StandardCharsets.UTF_8).trim();
                if (!content.isEmpty()) {
                    return content;
                }
            } catch (IOException ex) {
                // try next path
            }
        }
        return null;
    }

    /**
     * Run a command and return the first non-empty, non-header line.
     * Lines are stripped of surrounding quotes and whitespace.
     */
    private static String run(String[] command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    // Skip label rows (e.g. wmic prints "UUID" before the value).
                    if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("uuid")) {
                        continue;
                    }
                    // Strip surrounding quotes ("…") — some tools double-wrap.
                    if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                        trimmed = trimmed.substring(1, trimmed.length() - 1);
                    }
                    if (!trimmed.isEmpty()) {
                        return trimmed;
                    }
                }
            }
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ex) {
            // fall through to null
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return null;
    }
}
