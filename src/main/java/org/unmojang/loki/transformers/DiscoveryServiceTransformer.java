package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class DiscoveryServiceTransformer extends LokiTransformer {

    private static final String OBJECT_MAPPER = "com/mojang/authlib/minecraft/client/ObjectMapper";

    protected boolean matches(String className) {
        return "com/mojang/authlib/services/MinecraftServicesDiscoveryService".equals(className);
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if ("createDiscoverySupplier".equals(mn.name)
                    && "(Ljava/net/Proxy;Lcom/mojang/authlib/Environment;)Ljava/util/function/Supplier;".equals(mn.desc)) {

                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();

                InsnList insns = new InsnList();
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OBJECT_MAPPER, "create",
                        "()L" + OBJECT_MAPPER + ";", false));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/unmojang/loki/hooks/Hooks",
                        "getDiscoveryJson", "()Ljava/lang/String;", false));
                insns.add(new LdcInsnNode(Type.getObjectType("com/mojang/authlib/services/response/discovery/DiscoveryResponse")));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OBJECT_MAPPER, "readValue",
                        "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/unmojang/loki/hooks/Hooks",
                        "constantSupplier", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/function/Supplier"));
                insns.add(new InsnNode(Opcodes.ARETURN));

                mn.instructions.add(insns);

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
            }
        }

        return changed;
    }
}
