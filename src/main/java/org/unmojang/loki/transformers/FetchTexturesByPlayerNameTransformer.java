package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

public class FetchTexturesByPlayerNameTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (!"com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService".equals(className)) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

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

            if (!changed) return null;

            cn.methods.addAll(newMethods);

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform YggdrasilMinecraftSessionService!", t);
            return null;
        }
    }
}
