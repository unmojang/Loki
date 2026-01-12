package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class UsernameConstantTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (className.startsWith("java/") || className.startsWith("javax/")) return null;
        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                if (mn.instructions == null) continue;

                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode) insn;
                        if ("Grumm".equals(ldc.cst) || "Notch".equals(ldc.cst)) {
                            Loki.log.debug("Replacing \"" + ldc.cst + "\" constant in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                            ldc.cst = "cat";
                            changed = true;
                        }
                    }
                }
            }

            if (!changed) return null;

            ClassWriter cw = new ClassWriter(0);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform username constant!", t);
            return null;
        }
    }
}
