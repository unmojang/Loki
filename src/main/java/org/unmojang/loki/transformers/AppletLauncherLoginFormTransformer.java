package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class AppletLauncherLoginFormTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return "net/minecraft/LoginForm".equals(className);
    }

    protected int writerFlags(String className) {
        return ClassWriter.COMPUTE_MAXS;
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if ("buildLoginPanel".equals(mn.name)) {
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() != Opcodes.ARETURN) continue;

                    InsnList patch = new InsnList();
                    patch.add(new InsnNode(Opcodes.DUP));                    // [..., panel, panel]
                    patch.add(new VarInsnNode(Opcodes.ALOAD, 0));    // [..., panel, panel, this]
                    patch.add(new InsnNode(Opcodes.SWAP));                   // [..., panel, this, panel]
                    patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "org/unmojang/loki/hooks/LauncherHooks", "decorateLoginPanel",
                            "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                    mn.instructions.insertBefore(insn, patch);
                    changed = true;
                }
                Loki.log.debug("Decorating login panel in " + LokiUtil.getFqmn(className, mn.name, mn.desc));

            } else if ("<init>".equals(mn.name)) {
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() != Opcodes.RETURN) continue;
                    InsnList patch = new InsnList();
                    patch.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
                    patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "org/unmojang/loki/hooks/LauncherHooks", "resetLoginFields",
                            "(Ljava/lang/Object;)V", false));
                    mn.instructions.insertBefore(insn, patch);
                    changed = true;
                }
                Loki.log.debug("Clearing prefilled credentials in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
            }
        }

        return changed;
    }
}
