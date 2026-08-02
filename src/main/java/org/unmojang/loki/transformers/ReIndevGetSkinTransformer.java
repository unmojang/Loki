package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class ReIndevGetSkinTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return className.endsWith("ThreadGetSkin");
    }

    protected int writerFlags(String className) {
        return ClassWriter.COMPUTE_MAXS;
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if (!"run".equals(mn.name) || !"()V".equals(mn.desc)) continue;

            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.ASTORE || insn.getPrevious() == null
                        || !(insn.getPrevious() instanceof MethodInsnNode)) continue;

                MethodInsnNode prev = (MethodInsnNode) insn.getPrevious();

                if ("<init>".equals(prev.name) && "java/lang/String".equals(prev.owner)) {
                    InsnList insns = new InsnList();
                    insns.add(new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode) insn).var));
                    insns.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "org/unmojang/loki/hooks/Hooks",
                            "transformProfileJson",
                            "(Ljava/lang/String;)Ljava/lang/String;",
                            false
                    ));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, ((VarInsnNode) insn).var));

                    mn.instructions.insert(insn, insns);
                    Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                    changed = true;
                }
            }
        }

        return changed;
    }
}
