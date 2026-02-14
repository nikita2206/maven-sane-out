package io.nikitwentytwo.mavensaneout;

import java.io.PrintStream;
import java.util.ArrayDeque;

/**
 * Called from the instrumented SimpleLogger.write() method.
 * Routes log output to stdout or stderr based on the log level
 * detected in the formatted message.
 *
 * Configuration via environment variables or system properties (-D):
 *   MAVEN_SANE_OUT_WARNINGS=1  / -Dsane.warnings    — also route [WARNING] to stderr
 *   MAVEN_SANE_OUT_EXCLUDE=p;p / -Dsane.exclude=p;p — lines matching any pattern stay
 *                                                       on stdout even if ERROR/WARNING
 *   MAVEN_SANE_OUT_QUIET=N     / -Dsane.quiet=N     — quiet mode: suppress non-error
 *                                                       output, show N context lines
 *                                                       before each error (per-thread)
 *   MAVEN_SANE_OUT_DISABLE     / -Dsane.disable      — disable the agent entirely
 *
 * System properties take precedence over environment variables.
 */
public class LogRouter {

    // Capture original streams early (in premain, before Maven wraps them).
    private static final PrintStream ORIGINAL_OUT = System.out;
    private static final PrintStream ORIGINAL_ERR = System.err;

    // Configuration is loaded lazily on first route() call because Maven
    // sets -D system properties after the JVM starts (they're program args,
    // not JVM args), so they aren't visible during premain/static-init.
    private static volatile boolean configLoaded;
    private static boolean routeWarnings;
    private static String[] excludePatterns = new String[0];
    private static boolean quietMode;
    private static int contextSize;

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

    private static void ensureConfigLoaded() {
        if (configLoaded) return;
        synchronized (LogRouter.class) {
            if (configLoaded) return;

            routeWarnings = config("MAVEN_SANE_OUT_WARNINGS", "sane.warnings") != null;

            String exclude = config("MAVEN_SANE_OUT_EXCLUDE", "sane.exclude");
            if (exclude != null && !exclude.isEmpty()) {
                excludePatterns = exclude.split(";");
            }

            String quiet = config("MAVEN_SANE_OUT_QUIET", "sane.quiet");
            if (quiet != null) {
                quietMode = true;
                int size = 0;
                try {
                    size = Integer.parseInt(quiet);
                } catch (NumberFormatException ignored) {
                }
                contextSize = Math.max(0, size);
            }

            configLoaded = true;
        }
    }

    /**
     * Returns the system property if set, otherwise falls back to the environment variable.
     */
    private static String config(String envVar, String sysProp) {
        String value = System.getProperty(sysProp);
        return value != null ? value : System.getenv(envVar);
    }

    /**
     * Replacement for SimpleLogger.write(StringBuilder, Throwable).
     * Routes the entire message (including throwable) to the right stream.
     */
    public static void route(Object logger, StringBuilder buf, Throwable t) {
        ensureConfigLoaded();
        String message = buf.toString();
        PrintStream target = chooseStream(message);

        if (quietMode) {
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
        if (contextSize == 0) {
            return;
        }
        ArrayDeque<BufferedEntry> buffer = CONTEXT_BUFFER.get();
        if (buffer.size() >= contextSize) {
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
        boolean isWarning = routeWarnings && startsWithLevel(message, "WARNING");

        if ((isError || isWarning) && !isExcluded(message)) {
            return ORIGINAL_ERR;
        }
        return ORIGINAL_OUT;
    }

    private static boolean isExcluded(String message) {
        // Strip ANSI codes for pattern matching so patterns work regardless
        // of whether Maven is running with colors
        String plain = stripAnsi(message);
        for (String pattern : excludePatterns) {
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
