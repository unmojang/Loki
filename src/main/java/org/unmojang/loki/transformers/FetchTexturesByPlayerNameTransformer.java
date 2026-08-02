package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.util.ArrayList;
import java.util.List;

public class FetchTexturesByPlayerNameTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return !Loki.disable_profile_lookup
                && ("com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService".equals(className)
                || "com/mojang/authlib/services/MinecraftServicesSessionService".equals(className));
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;
        List<MethodNode> newMethods = new ArrayList<MethodNode>();

        for (MethodNode mn : cn.methods) {
            if ("getPackedTextures".equals(mn.name) &&
                "(Lcom/mojang/authlib/GameProfile;)Lcom/mojang/authlib/properties/Property;".equals(mn.desc)) {

                mn.name = "getPackedTextures$original";

                MethodNode hooked = new MethodNode(mn.access, "getPackedTextures", mn.desc, mn.signature,
                        mn.exceptions == null ? null : mn.exceptions.toArray(new String[0]));
                InsnList insns = new InsnList();
                insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "org/unmojang/loki/hooks/Hooks",
                        "getPackedTextures",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                        false));
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "com/mojang/authlib/properties/Property"));
                insns.add(new InsnNode(Opcodes.ARETURN));
                hooked.instructions = insns;
                hooked.maxLocals = 2;
                hooked.maxStack = 2;
                newMethods.add(hooked);

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, "getPackedTextures", mn.desc));
                changed = true;

            } else if ("getTextures".equals(mn.name) &&
                       "(Lcom/mojang/authlib/GameProfile;Z)Ljava/util/Map;".equals(mn.desc)) {

                mn.name = "getTextures$original";

                MethodNode hooked = new MethodNode(mn.access, "getTextures", mn.desc, mn.signature,
                        mn.exceptions == null ? null : mn.exceptions.toArray(new String[0]));
                InsnList insns = new InsnList();
                insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
                insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "org/unmojang/loki/hooks/Hooks",
                        "getTextures",
                        "(Ljava/lang/Object;Ljava/lang/Object;Z)Ljava/lang/Object;",
                        false));
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Map"));
                insns.add(new InsnNode(Opcodes.ARETURN));
                hooked.instructions = insns;
                hooked.maxLocals = 3;
                hooked.maxStack = 3;
                newMethods.add(hooked);

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, "getTextures", mn.desc));
                changed = true;
            }
        }

        cn.methods.addAll(newMethods);

        return changed;
    }
}
