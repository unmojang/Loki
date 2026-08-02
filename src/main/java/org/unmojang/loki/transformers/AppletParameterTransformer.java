package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;

import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class AppletParameterTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return "com/mojang/minecraft/MinecraftApplet".equals(className);
    }

    protected int writerFlags(String className) {
        return ClassWriter.COMPUTE_MAXS;
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if (!"init".equals(mn.name) || !"()V".equals(mn.desc)) continue;

            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode min = (MethodInsnNode) insn;

                if (min.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !"getParameter".equals(min.name)
                        || !"(Ljava/lang/String;)Ljava/lang/String;".equals(min.desc)) continue;

                AbstractInsnNode prev = min.getPrevious();
                AbstractInsnNode prev2 = (prev != null) ? prev.getPrevious() : null;
                if (prev2 == null) continue;

                if (prev2.getOpcode() == Opcodes.ALOAD && ((VarInsnNode) prev2).var == 0
                        && prev instanceof LdcInsnNode && "mppass".equals(((LdcInsnNode) prev).cst)) {
                    mn.instructions.remove(prev); // remove "mppass" string literal to appease stack
                    MethodInsnNode repl = new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "org/unmojang/loki/hooks/Hooks",
                            "getMpPass",
                            "(Ljava/lang/Object;)Ljava/lang/String;",
                            false
                    );
                    mn.instructions.set(min, repl);

                    Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                    changed = true;
                }
            }
        }

        return changed;
    }
}
