package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.util.Arrays;

public class BungeeCordTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return className.startsWith("net/md_5/bungee/");
    }

    protected int writerFlags(String className) {
        return ClassWriter.COMPUTE_MAXS;
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        if ("net/md_5/bungee/EncryptionUtil".equals(className) && Loki.verify_signatures) {
            for (MethodNode mn : cn.methods) {
                if (!"check".equals(mn.name) || !mn.desc.matches(
                        "\\(Lnet/md_5/bungee/protocol/(data/)?PlayerPublicKey;(Ljava/util/UUID;)?\\)Z"
                )) continue;
                boolean hasUuid = mn.desc.contains("Ljava/util/UUID;");

                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();

                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); // the player's key
                if (hasUuid) mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1)); // their uuid, null pre-1.19.1
                else mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "org/unmojang/loki/hooks/ProfileKeys",
                        "isCertificateValid",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                        false));
                mn.instructions.add(new InsnNode(Opcodes.IRETURN));

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
            }
        }

        for (MethodNode mn : cn.methods) {
            if (Arrays.asList("isValidName", "check").contains(mn.name)
                    && (mn.access & Opcodes.ACC_PUBLIC) != 0 && mn.desc.endsWith(")Z")) {
                if ("check".equals(mn.name) && Loki.verify_signatures) continue;
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                mn.instructions.add(new InsnNode(Opcodes.ICONST_1));
                mn.instructions.add(new InsnNode(Opcodes.IRETURN));
                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
            }
        }

        return changed;
    }
}
