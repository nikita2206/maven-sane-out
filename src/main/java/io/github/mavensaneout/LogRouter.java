package io.github.mavensaneout;

import java.io.PrintStream;

/**
 * Called from the instrumented SimpleLogger.write() method.
 * Routes log output to stdout or stderr based on the log level
 * detected in the formatted message.
 *
 * Configuration via environment variables:
 *   MAVEN_SANE_OUT_WARNINGS=1    — also route [WARNING] to stderr (off by default)
 *   MAVEN_SANE_OUT_EXCLUDE=p1;p2 — lines matching any pattern stay on stdout
 *                                   even if they're ERROR/WARNING
 */
public class LogRouter {

    private static final PrintStream ORIGINAL_OUT = System.out;
    private static final PrintStream ORIGINAL_ERR = System.err;
    private static final boolean ROUTE_WARNINGS;
    private static final String[] EXCLUDE_PATTERNS;

    static {
        ROUTE_WARNINGS = System.getenv("MAVEN_SANE_OUT_WARNINGS") != null;

        String exclude = System.getenv("MAVEN_SANE_OUT_EXCLUDE");
        if (exclude != null && !exclude.isEmpty()) {
            EXCLUDE_PATTERNS = exclude.split(";");
        } else {
            EXCLUDE_PATTERNS = new String[0];
        }
    }

    /**
     * Replacement for SimpleLogger.write(StringBuilder, Throwable).
     * Routes the entire message (including throwable) to the right stream.
     */
    public static void route(Object logger, StringBuilder buf, Throwable t) {
        String message = buf.toString();
        PrintStream target = chooseStream(message);

        target.println(message);
        if (t != null) {
            t.printStackTrace(target);
        }
        target.flush();
    }

    private static PrintStream chooseStream(String message) {
        boolean isError = startsWithLevel(message, "ERROR");
        boolean isWarning = ROUTE_WARNINGS && startsWithLevel(message, "WARNING");

        if ((isError || isWarning) && !isExcluded(message)) {
            return ORIGINAL_ERR;
        }
        return ORIGINAL_OUT;
    }

    private static boolean isExcluded(String message) {
        // Strip ANSI codes for pattern matching so patterns work regardless
        // of whether Maven is running with colors
        String plain = stripAnsi(message);
        for (String pattern : EXCLUDE_PATTERNS) {
            if (!pattern.isEmpty() && plain.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static String stripAnsi(String s) {
        // Fast path: no ESC character
        if (s.indexOf('\u001B') < 0) return s;

        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == '\u001B' && i + 1 < s.length() && s.charAt(i + 1) == '[') {
                i += 2;
                while (i < s.length() && s.charAt(i) != 'm') i++;
                if (i < s.length()) i++; // skip 'm'
            } else {
                out.append(s.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Checks if the message starts with [LEVEL] accounting for ANSI codes.
     * Maven formats: "[ERROR] msg" or "[ ESC[1;31m ERROR ESC[m ] msg"
     */
    private static boolean startsWithLevel(String message, String level) {
        int i = 0;
        int len = message.length();

        i = skipAnsi(message, i);
        if (i >= len || message.charAt(i) != '[') return false;
        i++;

        i = skipAnsi(message, i);
        if (i + level.length() > len) return false;
        if (!message.regionMatches(i, level, 0, level.length())) return false;
        i += level.length();

        i = skipAnsi(message, i);
        return i < len && message.charAt(i) == ']';
    }

    private static int skipAnsi(String s, int i) {
        while (i < s.length() && s.charAt(i) == '\u001B') {
            i++;
            if (i < s.length() && s.charAt(i) == '[') {
                i++;
                while (i < s.length() && s.charAt(i) != 'm') i++;
                if (i < s.length()) i++;
            }
        }
        return i;
    }
}
