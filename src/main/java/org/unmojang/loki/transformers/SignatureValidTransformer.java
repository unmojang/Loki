package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class SignatureValidTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return "com/mojang/authlib/properties/Property".equals(className)
                || className.endsWith("/data/GameProfile$Property");
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("isSignatureValid") && mn.desc.equals("(Ljava/security/PublicKey;)Z")) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();

                mn.instructions.add(new InsnNode(Opcodes.ICONST_1));
                mn.instructions.add(new InsnNode(Opcodes.IRETURN));

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
                break;
            }
        }

        return changed;
    }
}
