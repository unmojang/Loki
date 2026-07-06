package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class DiscoveryServiceTransformer implements ClassFileTransformer {

    private static final String OBJECT_MAPPER = "com/mojang/authlib/minecraft/client/ObjectMapper";

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (!"com/mojang/authlib/services/MinecraftServicesDiscoveryService".equals(className)) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

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

            if (!changed) return null;

            ClassWriter cw = new LoaderAwareClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform " + className + "!", t);
            return null;
        }
    }
}
