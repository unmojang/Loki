package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;
import org.unmojang.loki.RequestInterceptor;

public class OptiFineCapeTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return className.endsWith("CapeUtils") && !Loki.modded_capes && !RequestInterceptor.IS_MOJANG;
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("downloadCape")) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();

                mn.instructions.add(new InsnNode(Opcodes.RETURN));

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
                break;
            }
        }

        return changed;
    }
}
