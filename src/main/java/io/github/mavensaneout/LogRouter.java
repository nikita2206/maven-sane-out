package io.github.mavensaneout;

import java.io.PrintStream;
import java.util.ArrayDeque;

/**
 * Called from the instrumented SimpleLogger.write() method.
 * Routes log output to stdout or stderr based on the log level
 * detected in the formatted message.
 *
 * Configuration via environment variables:
 *   MAVEN_SANE_OUT_WARNINGS=1    — also route [WARNING] to stderr (off by default)
 *   MAVEN_SANE_OUT_EXCLUDE=p1;p2 — lines matching any pattern stay on stdout
 *                                   even if they're ERROR/WARNING
 *   MAVEN_SANE_OUT_QUIET=N       — quiet mode: suppress non-error output, show N
 *                                   context lines before each error (per-thread)
 */
public class LogRouter {

    private static final PrintStream ORIGINAL_OUT = System.out;
    private static final PrintStream ORIGINAL_ERR = System.err;
    private static final boolean ROUTE_WARNINGS;
    private static final String[] EXCLUDE_PATTERNS;
    private static final boolean QUIET_MODE;
    private static final int CONTEXT_SIZE;

    private static final ThreadLocal<ArrayDeque<BufferedEntry>> CONTEXT_BUFFER =
            ThreadLocal.withInitial(ArrayDeque::new);

    private static class BufferedEntry {
        final String message;
        final Throwable throwable;

        BufferedEntry(String message, Throwable throwable) {
            this.message = message;
            this.throwable = throwable;
        }
    }

    static {
        ROUTE_WARNINGS = System.getenv("MAVEN_SANE_OUT_WARNINGS") != null;

        String exclude = System.getenv("MAVEN_SANE_OUT_EXCLUDE");
        if (exclude != null && !exclude.isEmpty()) {
            EXCLUDE_PATTERNS = exclude.split(";");
        } else {
            EXCLUDE_PATTERNS = new String[0];
        }

        String quiet = System.getenv("MAVEN_SANE_OUT_QUIET");
        if (quiet != null) {
            QUIET_MODE = true;
            int size = 0;
            try {
                size = Integer.parseInt(quiet);
            } catch (NumberFormatException ignored) {
            }
            CONTEXT_SIZE = Math.max(0, size);
        } else {
            QUIET_MODE = false;
            CONTEXT_SIZE = 0;
        }
    }

    /**
     * Replacement for SimpleLogger.write(StringBuilder, Throwable).
     * Routes the entire message (including throwable) to the right stream.
     */
    public static void route(Object logger, StringBuilder buf, Throwable t) {
        String message = buf.toString();
        PrintStream target = chooseStream(message);

        if (QUIET_MODE) {
            if (target == ORIGINAL_ERR) {
                flushContextBuffer(message);
                ORIGINAL_ERR.println(message);
                if (t != null) {
                    t.printStackTrace(ORIGINAL_ERR);
                }
                ORIGINAL_ERR.flush();
            } else {
                addToContextBuffer(message, t);
            }
            return;
        }

        target.println(message);
        if (t != null) {
            t.printStackTrace(target);
        }
        target.flush();
    }

    private static void addToContextBuffer(String message, Throwable t) {
        if (CONTEXT_SIZE == 0) {
            return;
        }
        ArrayDeque<BufferedEntry> buffer = CONTEXT_BUFFER.get();
        if (buffer.size() >= CONTEXT_SIZE) {
            buffer.pollFirst();
        }
        buffer.addLast(new BufferedEntry(message, t));
    }

    private static void flushContextBuffer(String errorMessage) {
        ArrayDeque<BufferedEntry> buffer = CONTEXT_BUFFER.get();
        if (buffer.isEmpty()) {
            return;
        }
        boolean useAnsi = errorMessage.indexOf('\u001B') >= 0;
        for (BufferedEntry entry : buffer) {
            if (useAnsi) {
                ORIGINAL_ERR.println("\u001B[0;37m" + entry.message + "\u001B[0m");
            } else {
                ORIGINAL_ERR.println(entry.message);
            }
            if (entry.throwable != null) {
                entry.throwable.printStackTrace(ORIGINAL_ERR);
            }
        }
        buffer.clear();
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
