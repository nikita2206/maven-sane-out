package io.github.mavensaneout;

import java.io.IOException;
import java.io.OutputStream;

public class SplittingOutputStream extends OutputStream {

    private final OutputStream out;
    private final OutputStream err;
    private final byte[] buf = new byte[8192];
    private int pos = 0;

    // Match "[ERROR]" literally (no ANSI)
    private static final byte[] MARKER_BRACKET = {'[', 'E', 'R', 'R', 'O', 'R', ']'};
    // Match "ERROR" after ANSI stripping
    private static final byte[] MARKER_ERROR = {'E', 'R', 'R', 'O', 'R'};

    public SplittingOutputStream(OutputStream out, OutputStream err) {
        this.out = out;
        this.err = err;
    }

    @Override
    public synchronized void write(int b) throws IOException {
        if (b == '\n') {
            flushLine();
        } else {
            if (pos < buf.length) {
                buf[pos++] = (byte) b;
            } else {
                out.write(buf, 0, pos);
                out.flush();
                pos = 0;
                buf[pos++] = (byte) b;
            }
        }
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
        for (int i = off; i < off + len; i++) {
            write(b[i]);
        }
    }

    @Override
    public synchronized void flush() throws IOException {
        // Don't send the line buffer here — the wrapping PrintStream calls
        // flush() after every write(byte[]) when autoFlush is enabled, which
        // would send partial lines to stdout before we see the newline.
        // Lines are flushed to the correct stream when '\n' is seen.
        out.flush();
        err.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        if (pos > 0) {
            out.write(buf, 0, pos);
            pos = 0;
        }
        out.flush();
        err.flush();
    }

    private void flushLine() throws IOException {
        OutputStream target = isErrorLine() ? err : out;
        target.write(buf, 0, pos);
        target.write('\n');
        target.flush();
        pos = 0;
    }

    private boolean isErrorLine() {
        int i = skipAnsi(0);

        // Case 1: plain "[ERROR]" (no ANSI colors, e.g. batch mode)
        if (matchAt(i, MARKER_BRACKET)) {
            return true;
        }

        // Case 2: ANSI-colored — Maven renders [ERROR] as:
        //   [ ESC[1;31m ERROR ESC[m ]
        // The "[" is literal, followed by ANSI color, then ERROR, then
        // ANSI reset, then "]".
        if (i < pos && buf[i] == '[') {
            int j = skipAnsi(i + 1);
            if (matchAt(j, MARKER_ERROR)) {
                j += MARKER_ERROR.length;
                j = skipAnsi(j);
                if (j < pos && buf[j] == ']') {
                    return true;
                }
            }
        }

        return false;
    }

    private int skipAnsi(int i) {
        while (i < pos && buf[i] == 0x1B) {
            i++;
            if (i < pos && buf[i] == '[') {
                i++;
                while (i < pos && buf[i] != 'm') {
                    i++;
                }
                if (i < pos) {
                    i++; // skip 'm'
                }
            }
        }
        return i;
    }

    private boolean matchAt(int offset, byte[] marker) {
        if (offset + marker.length > pos) {
            return false;
        }
        for (int j = 0; j < marker.length; j++) {
            if (buf[offset + j] != marker[j]) {
                return false;
            }
        }
        return true;
    }
}
