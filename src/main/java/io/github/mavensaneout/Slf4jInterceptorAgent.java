package io.github.mavensaneout;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.util.jar.JarFile;

public class Slf4jInterceptorAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        if (System.getenv("MAVEN_SANE_OUT_DISABLE") != null
                || System.getProperty("sane.disable") != null) {
            return;
        }

        // Add our JAR to the bootstrap classloader so that LogRouter is visible
        // to Maven's classloader when the instrumented SimpleLogger calls it.
        try {
            File agentJar = new File(
                    Slf4jInterceptorAgent.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
            inst.appendToBootstrapClassLoaderSearch(new JarFile(agentJar));
        } catch (Exception e) {
            System.err.println("[maven-sane-out] Failed to add agent to bootstrap classpath: " + e);
        }

        // Force LogRouter to initialize now (on the bootstrap classloader)
        // so it captures the real System.out/err before Maven wraps them.
        // Maven 4 wraps stderr and routes it back through SLF4J, which would
        // cause infinite recursion if LogRouter captured the wrapped streams.
        try {
            Class.forName("io.github.mavensaneout.LogRouter", true, null);
        } catch (ClassNotFoundException e) {
            System.err.println("[maven-sane-out] Failed to initialize LogRouter: " + e);
        }

        inst.addTransformer(new SimpleLoggerTransformer());
    }
}
