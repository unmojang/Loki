package org.unmojang.loki.transformers;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class TitleScreenTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return LokiUtil.SERVER_NAME.length() != 0
                && "net/minecraft/client/gui/screens/TitleScreen".equals(className);
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            for (AbstractInsnNode node : mn.instructions.toArray()) {
                if (node.getOpcode() != Opcodes.INVOKEINTERFACE) continue;
                MethodInsnNode min = (MethodInsnNode) node;
                if (!"net/minecraft/WorldVersion".equals(min.owner) ||
                        !"name".equals(min.name) ||
                        !"()Ljava/lang/String;".equals(min.desc)) continue;

                InsnList insns = new InsnList();
                insns.add(new LdcInsnNode("/" + LokiUtil.SERVER_NAME));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                        "(Ljava/lang/String;)Ljava/lang/String;", false));
                mn.instructions.insert(node, insns);

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
            }
        }

        return changed;
    }
}
