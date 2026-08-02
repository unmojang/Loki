package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class OneSixLauncherUpdateNagTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return "net/minecraft/launcher/ui/LauncherPanel".equals(className);
    }

    protected int writerFlags(String className) {
        return ClassWriter.COMPUTE_MAXS;
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if (!"createLauncherInterface".equals(mn.name) || !"()Ljavax/swing/JPanel;".equals(mn.desc)) continue;

            AbstractInsnNode jlabel = null;
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.NEW && "javax/swing/JLabel".equals(((TypeInsnNode) insn).desc)) {
                    jlabel = insn;
                    break;
                }
            }
            if (jlabel == null) continue;

            for (AbstractInsnNode insn = jlabel.getPrevious(); insn != null; insn = insn.getPrevious()) {
                if (insn.getOpcode() != Opcodes.IFEQ) continue;
                AbstractInsnNode load = insn.getPrevious();
                while ((load instanceof LabelNode || load instanceof LineNumberNode || load instanceof FrameNode)) {
                    load = load.getPrevious();
                }
                if (load != null && load.getOpcode() == Opcodes.ILOAD) {
                    mn.instructions.set(load, new InsnNode(Opcodes.ICONST_0)); // upgradableOS = false
                    Loki.log.debug("Patching update nag in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                    changed = true;
                }
                break;
            }
            break;
        }

        return changed;
    }
}
