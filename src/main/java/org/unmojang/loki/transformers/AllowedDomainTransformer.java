package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;


public class AllowedDomainTransformer implements ClassFileTransformer {
    private static final Set<String> TARGET_CLASSES = new HashSet<>(Arrays.asList(
            "com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService",
            "com/mojang/authlib/yggdrasil/TextureUrlChecker",
            "com/github/steveice10/mc/auth/data/GameProfile"
    ));
    private static final String[] METHODS = {
            "isWhitelistedDomain(Ljava/lang/String;)Z",
            "isAllowedTextureDomain(Ljava/lang/String;)Z"
    };

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {

        if (!TARGET_CLASSES.contains(className)) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                String methodDesc = mn.name + mn.desc;
                for (String targetMethod : METHODS) {
                    if (methodDesc.equals(targetMethod)) {
                        AbstractInsnNode iret = null;
                        for (AbstractInsnNode insn : mn.instructions.toArray()) {
                            if (insn.getOpcode() == Opcodes.IRETURN) {
                                iret = insn;
                            }
                        }
                        if (iret == null) throw new RuntimeException("could not find IRETURN");

                        InsnList insns = new InsnList();

                        insns.add(new InsnNode(Opcodes.ICONST_1));
                        insns.add(new InsnNode(Opcodes.IRETURN));

                        mn.instructions.insertBefore(iret, insns);

                        Loki.log.debug("Patching " + mn.name + " in " + className);
                        changed = true;
                        break;
                    }
                }
            }

            if (!changed) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    try {
                        Class<?> c1 = Class.forName(type1.replace('/', '.'), false, loader);
                        Class<?> c2 = Class.forName(type2.replace('/', '.'), false, loader);
                        if (c1.isAssignableFrom(c2)) return type1;
                        if (c2.isAssignableFrom(c1)) return type2;
                        if (c1.isInterface() || c2.isInterface()) return "java/lang/Object";
                        do {
                            c1 = c1.getSuperclass();
                        } while (!c1.isAssignableFrom(c2));
                        return c1.getName().replace('.', '/');
                    } catch (ClassNotFoundException e) {
                        return "java/lang/Object"; // fallback
                    }
                }
            };
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform isAllowedTextureDomain!", t);
            return null;
        }
    }
}