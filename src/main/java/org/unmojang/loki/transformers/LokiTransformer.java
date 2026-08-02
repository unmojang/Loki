package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.unmojang.loki.Loki;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public abstract class LokiTransformer implements ClassFileTransformer {
    /** Return true if the class matches the target */
    protected abstract boolean matches(String className);

    /** Return true if anything was modified */
    protected abstract boolean patch(ClassNode cn, String className);

    /** LoaderAwareClassWriter is used if COMPUTE_FRAMES is set */
    protected int writerFlags(String className) {
        return ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS;
    }

    protected int readerFlags(String className) {
        return 0;
    }

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || !matches(className)) return null;
        try {
            ClassNode cn = new ClassNode();
            new ClassReader(classfileBuffer).accept(cn, readerFlags(className));

            if (!patch(cn, className)) return null;

            int flags = writerFlags(className);
            ClassWriter cw = (flags & ClassWriter.COMPUTE_FRAMES) != 0
                    ? new LoaderAwareClassWriter(flags, loader)
                    : new ClassWriter(flags);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Throwable t) {
            Loki.log.error("Failed to transform " + className + "!", t);
            return null;
        }
    }
}
