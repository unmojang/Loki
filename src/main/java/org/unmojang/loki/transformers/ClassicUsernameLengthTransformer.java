package org.unmojang.loki.transformers;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class ClassicUsernameLengthTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (Loki.username_validation || !className.startsWith("com/mojang/minecraft/server")) return null;

        try {
            ClassNode cn = new ClassNode();
            new ClassReader(classfileBuffer).accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                if (mn.instructions == null) continue;

                for (int i = 0; i < mn.instructions.size() - 2; i++) {
                    // ALOAD 0, LDC "Illegal name.", INVOKE*
                    AbstractInsnNode a = mn.instructions.get(i);
                    AbstractInsnNode b = mn.instructions.get(i + 1);
                    AbstractInsnNode c = mn.instructions.get(i + 2);

                    if (a instanceof VarInsnNode && b instanceof LdcInsnNode && c instanceof MethodInsnNode) {
                        if (a.getOpcode() == Opcodes.ALOAD && ((VarInsnNode) a).var == 0
                                && "Illegal name.".equals(((LdcInsnNode) b).cst)) {

                            mn.instructions.remove(a);
                            mn.instructions.remove(b);
                            mn.instructions.remove(c);

                            Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                            changed = true;
                            break;
                        }
                    }
                }
            }

            if (!changed) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform readPacket");
            return null;
        }
    }
}
