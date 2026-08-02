package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class ConcatenateURLTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return "com/mojang/authlib/HttpAuthenticationService".equals(className);
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if ("concatenateURL".equals(mn.name)
                    && "(Ljava/net/URL;Ljava/lang/String;)Ljava/net/URL;".equals(mn.desc)) {

                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();

                InsnList insns = new InsnList();
                insns.add(new VarInsnNode(Opcodes.ALOAD, 0)); // url
                insns.add(new VarInsnNode(Opcodes.ALOAD, 1)); // query
                insns.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/unmojang/loki/hooks/Hooks",
                        "concatenateURL",
                        "(Ljava/net/URL;Ljava/lang/String;)Ljava/net/URL;",
                        false
                ));
                insns.add(new InsnNode(Opcodes.ARETURN));

                mn.instructions.add(insns);
                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
                break;
            }
        }

        return changed;
    }
}
