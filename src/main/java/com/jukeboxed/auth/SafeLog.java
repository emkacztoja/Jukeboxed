package com.jukeboxed.auth;

import com.jukeboxed.JukeboxedMod;

/**
 * Centralized logger that scrubs Spotify tokens before they reach the Minecraft log.
 * <p>
 * Use this instead of {@link JukeboxedMod#LOGGER} anywhere near token-bearing data.
 * Implementation note: every method passes the formatted message through
 * {@link #scrub(String)} before delegating to SLF4J, so even string-built call sites
 * that accidentally inline a token string will have it masked to {@code [REDACTED]}.
 */
final class SafeLog {

    private SafeLog() {
    }

    static void info(String template, Object... args)  { log("info",  template, args); }
    static void warn(String template, Object... args)  { log("warn",  template, args); }
    static void error(String template, Object... args) { log("error", template, args); }

    private static void log(String level, String template, Object... args) {
        String rendered;
        try {
            rendered = template != null && args != null && args.length > 0
                    ? String.format(template, args)
                    : (template != null ? template : "");
        } catch (RuntimeException ex) {
            rendered = String.valueOf(template);
        }
        String safe = scrub(rendered);
        switch (level) {
            case "warn"  -> JukeboxedMod.LOGGER.warn(safe);
            case "error" -> JukeboxedMod.LOGGER.error(safe);
            default      -> JukeboxedMod.LOGGER.info(safe);
        }
    }

    /**
     * Best-effort token scrub. Catches the canonical Bearer-token shape plus any
     * 32+ char alnum run that smells like a refresh token. False-positives are
     * acceptable — we'd rather over-redact than leak.
     */
    static String scrub(String input) {
        if (input == null || input.isEmpty()) return input;
        String out = input;
        // Bearer <alnum and -_ . ~ + / = chars>
        out = out.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1[REDACTED]");
        // Stand-alone long alnum/dash/underscore runs (refresh tokens have ~70 chars)
        out = out.replaceAll("\\b[A-Za-z0-9_-]{32,}\\b", "[REDACTED]");
        return out;
    }
}
