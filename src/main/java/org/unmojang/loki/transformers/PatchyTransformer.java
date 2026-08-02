package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class PatchyTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return className.startsWith("com/mojang/patchy/") && !Loki.enable_patchy;
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            // Disable server blocking (isBlockedServer = false)
            if ((mn.access & Opcodes.ACC_PUBLIC) != 0 && mn.desc.equals("(Ljava/lang/String;)Z")) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();

                mn.instructions.add(new InsnNode(Opcodes.ICONST_0));
                mn.instructions.add(new InsnNode(Opcodes.IRETURN));

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
            }

        }

        return changed;
    }
}
