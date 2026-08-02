package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class MCOSEMultiplayerTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return "net/minecraft/src/LocalLanServerManager".equals(className);
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof LdcInsnNode)) continue;
                if (!"-jar".equals(((LdcInsnNode) insn).cst)) continue;

                // Walk back past any metadata nodes to find the ALOAD that pushes 'command'
                AbstractInsnNode prev = insn.getPrevious();
                while (prev != null && !(prev instanceof VarInsnNode)) {
                    prev = prev.getPrevious();
                }
                if (prev == null || prev.getOpcode() != Opcodes.ALOAD) continue;

                int commandVar = ((VarInsnNode) prev).var;

                InsnList patch = new InsnList();
                patch.add(new VarInsnNode(Opcodes.ALOAD, commandVar));
                patch.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/unmojang/loki/hooks/Hooks",
                        "injectMCOSELanServerJvmArgs",
                        "(Ljava/util/List;)V",
                        false
                ));
                mn.instructions.insertBefore(prev, patch);

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
                break;
            }
            if (changed) break;
        }

        return changed;
    }
}
