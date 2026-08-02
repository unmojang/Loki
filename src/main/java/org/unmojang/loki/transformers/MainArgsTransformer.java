package org.unmojang.loki.transformers;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class MainArgsTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return "net/minecraft/client/main/Main".equals(className);
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals("main") || !mn.desc.equals("([Ljava/lang/String;)V")) continue;

            InsnList insns = new InsnList();
            insns.add(new VarInsnNode(Opcodes.ALOAD, 0)); // args
            insns.add(new LdcInsnNode(LokiUtil.SERVER_NAME));
            insns.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "org/unmojang/loki/hooks/Hooks",
                    "transformMainArgs",
                    "([Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;",
                    false
            ));
            insns.add(new VarInsnNode(Opcodes.ASTORE, 0));
            mn.instructions.insert(insns);

            Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
            changed = true;
        }

        return changed;
    }
}
