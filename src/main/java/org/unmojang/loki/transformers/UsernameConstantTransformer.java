package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class UsernameConstantTransformer implements ClassFileTransformer {

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        try {
            ClassNode cn = new ClassNode();
            new ClassReader(classfileBuffer).accept(cn, ClassReader.EXPAND_FRAMES);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                if (mn.instructions == null) continue;

                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode) insn;
                        if ("Dinnerbone".equals(ldc.cst)) {
                            ldc.cst = "cat";
                            Loki.log.debug("Replacing \"Dinnerbone\" constant in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                            changed = true;
                        } else if ("Notch".equals(ldc.cst)) {
                            ldc.cst = "cat";
                            Loki.log.debug("Replacing \"Notch\" constant in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
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
