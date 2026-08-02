package org.unmojang.loki.transformers;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class ClassicUsernameLengthTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return !Loki.username_validation && className.startsWith("com/mojang/minecraft/server");
    }

    protected int writerFlags(String className) {
        return ClassWriter.COMPUTE_MAXS;
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) continue;

            AbstractInsnNode[] insns = mn.instructions.toArray();
            for (int i = 0; i < insns.length - 2; i++) {
                // ALOAD 0, LDC "Illegal name.", INVOKE*
                AbstractInsnNode a = insns[i];
                AbstractInsnNode b = insns[i + 1];
                AbstractInsnNode c = insns[i + 2];

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

        return changed;
    }
}
