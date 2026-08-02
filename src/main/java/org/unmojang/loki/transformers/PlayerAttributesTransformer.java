package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.util.Arrays;
import java.util.List;

// Decides whether to enable chat restrictions, snooper, etc.
// https://minecraft.wiki/w/Mojang_API#Query_player_attributes
public class PlayerAttributesTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return className.startsWith("com/mojang/authlib/");
    }

    protected boolean patch(ClassNode cn, String className) {
        List<String> snooperMethods = Arrays.asList("telemetryAllowed", "getTelemetry", "getOptionalTelemetry");
        List<String> chatMethods = Arrays.asList("chatAllowed", "getOnlineChat");

        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if ((mn.access & Opcodes.ACC_PUBLIC) != 0 && mn.desc.equals("()Z")) {
                int retVal = -1;
                if (!Loki.enable_snooper && snooperMethods.contains(mn.name)) {
                    retVal = Opcodes.ICONST_0; // disable telemetry
                } else if (!Loki.chat_restrictions && chatMethods.contains(mn.name)) {
                    retVal = Opcodes.ICONST_1; // enable online chat
                }
                if (retVal == -1) continue;

                mn.access &= ~Opcodes.ACC_ABSTRACT;
                mn.access &= ~Opcodes.ACC_NATIVE;

                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();

                mn.instructions.add(new InsnNode(retVal));
                mn.instructions.add(new InsnNode(Opcodes.IRETURN));

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
            }
        }

        return changed;
    }
}
