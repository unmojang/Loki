package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;

// Decides whether to enable chat restrictions, snooper, etc.
// https://minecraft.wiki/w/Mojang_API#Query_player_attributes
public class PlayerAttributesTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (!className.startsWith("com/mojang/authlib/")) return null;

        List<String> snooperMethods = Arrays.asList("telemetryAllowed", "getTelemetry", "getOptionalTelemetry");
        List<String> chatMethods = Arrays.asList("chatAllowed", "getOnlineChat");

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

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

            if (!changed) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform player attributes!", t);
            return null;
        }
    }
}
