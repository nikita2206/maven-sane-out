package io.nikitwentytwo.mavensaneout;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Transforms SimpleLogger to intercept the write(StringBuilder, Throwable)
 * method. Instead of always writing to the configured output stream, we
 * inspect the formatted message to determine the log level and route
 * ERROR (and optionally WARNING) to stderr.
 *
 * This works at the SLF4J level, so:
 * - Multiline messages are handled as one unit
 * - Stack traces follow the same routing as their log line
 * - No text parsing of raw byte streams
 */
public class SimpleLoggerTransformer implements ClassFileTransformer {

    private static final String SIMPLE_LOGGER = "org/slf4j/impl/SimpleLogger";
    private static final String SIMPLE_LOGGER_ALT = "org/slf4j/simple/SimpleLogger";
    // Maven 4 copies SimpleLogger into its own class hierarchy
    private static final String MAVEN_SIMPLE_LOGGER = "org/apache/maven/slf4j/MavenSimpleLogger";

    @Override
    public byte[] transform(ClassLoader loader, String className,
            Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        if (!SIMPLE_LOGGER.equals(className)
                && !SIMPLE_LOGGER_ALT.equals(className)
                && !MAVEN_SIMPLE_LOGGER.equals(className)) {
            return null;
        }

        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    if ("write".equals(name)
                            && "(Ljava/lang/StringBuilder;Ljava/lang/Throwable;)V".equals(descriptor)) {
                        // Drop the original method — we'll generate a replacement in visitEnd
                        return null;
                    }
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }

                @Override
                public void visitEnd() {
                    // Generate replacement write(StringBuilder, Throwable)
                    MethodVisitor mv = cv.visitMethod(
                            Opcodes.ACC_PROTECTED, "write",
                            "(Ljava/lang/StringBuilder;Ljava/lang/Throwable;)V",
                            null, null);
                    mv.visitCode();

                    // LogRouter.route(this, buf, t)
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitVarInsn(Opcodes.ALOAD, 2);
                    mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "io/nikitwentytwo/mavensaneout/LogRouter",
                            "route",
                            "(Ljava/lang/Object;Ljava/lang/StringBuilder;Ljava/lang/Throwable;)V",
                            false);

                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(3, 3);
                    mv.visitEnd();

                    super.visitEnd();
                }
            }, 0);
            return cw.toByteArray();
        } catch (Exception e) {
            System.err.println("[maven-sane-out] Failed to transform " + className + ": " + e);
            return null;
        }
    }
}
