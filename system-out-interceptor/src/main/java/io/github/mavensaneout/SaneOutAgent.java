package io.github.mavensaneout;

import java.io.PrintStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public class SaneOutAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        if (System.getenv("MAVEN_SANE_OUT_DISABLE") != null) {
            return;
        }

        PrintStream originalErr = System.err;

        // Wrap System.out immediately (handles the case where Jansi isn't present)
        final PrintStream[] currentWrapper = { installWrapper(System.out, originalErr) };

        // Jansi (used by Maven 3.9+) replaces System.out with its own
        // AnsiPrintStream that writes directly to FileDescriptor.out,
        // discarding our wrapper. Use a class transformer to detect when
        // this happens and re-wrap.
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className,
                    Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                    byte[] classfileBuffer) {
                PrintStream current = System.out;
                if (current != currentWrapper[0]) {
                    currentWrapper[0] = installWrapper(current, originalErr);
                }
                return null;
            }
        });
    }

    private static PrintStream installWrapper(PrintStream target, PrintStream err) {
        SplittingOutputStream splitter = new SplittingOutputStream(target, err);
        PrintStream wrapper = new PrintStream(splitter, false);
        System.setOut(wrapper);
        return wrapper;
    }
}
