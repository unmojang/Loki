package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class ServicesKeyInfoTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return "com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo".equals(className)
                || "com/mojang/authlib/services/MinecraftServicesKeyInfo".equals(className)
                || "com/mojang/authlib/services/MinecraftServicesDiscoveryService".equals(className);
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean isKeyInfo = "com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo".equals(className)
                || "com/mojang/authlib/services/MinecraftServicesKeyInfo".equals(className);
        boolean isDiscoveryService = "com/mojang/authlib/services/MinecraftServicesDiscoveryService".equals(className);

        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if (isKeyInfo && "<init>".equals(mn.name) && "(Ljava/security/PublicKey;)V".equals(mn.desc)) {
                AbstractInsnNode ret = null;
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn.getOpcode() == Opcodes.RETURN) {
                        ret = insn;
                    }
                }
                if (ret == null) throw new RuntimeException("could not find RETURN");

                InsnList insns = new InsnList();
                insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                insns.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/unmojang/loki/hooks/Hooks",
                        "replaceYggdrasilServicesKeyInfoSignature",
                        "(Ljava/lang/Object;)V",
                        false
                ));
                mn.instructions.insertBefore(ret, insns);

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
            } else if (isKeyInfo && "validateProperty".equals(mn.name) && "(Lcom/mojang/authlib/properties/Property;)Z".equals(mn.desc)) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();

                InsnList insns = new InsnList();
                insns.add(new InsnNode(Opcodes.ICONST_1));
                insns.add(new InsnNode(Opcodes.IRETURN));

                mn.instructions.add(insns);

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
            } else if (isKeyInfo && "signature".equals(mn.name) && "()Ljava/security/Signature;".equals(mn.desc)) {
                if (Loki.enforce_secure_profile) continue; // preserve signature

                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();

                InsnList insns = new InsnList();
                insns.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/unmojang/loki/hooks/Hooks",
                        "createDummySignature",
                        "()Ljava/security/Signature;",
                        false
                ));
                insns.add(new InsnNode(Opcodes.ARETURN));

                mn.instructions.add(insns);
                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
            } else if (isDiscoveryService && "getServicesKeySet".equals(mn.name)
                    && "()Lcom/mojang/authlib/services/ServicesKeySet;".equals(mn.desc)) {

                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();

                InsnList insns = new InsnList();
                insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false));
                insns.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/unmojang/loki/hooks/Hooks",
                        "buildServicesKeySet",
                        "(Ljava/lang/ClassLoader;)Ljava/lang/Object;",
                        false
                ));
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "com/mojang/authlib/services/ServicesKeySet"));
                insns.add(new InsnNode(Opcodes.ARETURN));

                mn.instructions.add(insns);

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
            }
        }

        return changed;
    }
}
