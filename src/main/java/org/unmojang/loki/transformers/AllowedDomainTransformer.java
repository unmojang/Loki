package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;

public class AllowedDomainTransformer implements ClassFileTransformer {

    public byte[] transform(final ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        // Target MCAuthlib too (used in MojangFix and Ears mods, possibly more)
        if (!className.equals("com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService")
                && !className.equals("com/mojang/authlib/yggdrasil/TextureUrlChecker")
                && !className.endsWith("/data/GameProfile")) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                if ((mn.name.equals("isWhitelistedDomain") || mn.name.equals("isAllowedTextureDomain"))
                        && mn.desc.equals("(Ljava/lang/String;)Z")) {
                    mn.instructions.clear();
                    mn.tryCatchBlocks.clear();
                    if (mn.localVariables != null) mn.localVariables.clear();

                    List<String> skinDomains = LokiUtil.SERVER_TEXTURE_DOMAINS;
                    if (skinDomains.isEmpty()) { // allow any skin domain
                        mn.instructions.add(new InsnNode(Opcodes.ICONST_1));
                        mn.instructions.add(new InsnNode(Opcodes.IRETURN));
                    } else {
                        buildDomainCheck(mn, skinDomains);
                    }

                    Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                    changed = true;
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
            Loki.log.error("Failed to transform " + className + "!", t);
            return null;
        }
    }

    private static void buildDomainCheck(MethodNode mn, List<String> skinDomains) {
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode returnTrue = new LabelNode();

        mn.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handler, "java/lang/Exception"));

        InsnList il = mn.instructions;

        // host = new URL(url).getHost()
        il.add(tryStart);
        il.add(new TypeInsnNode(Opcodes.NEW, "java/net/URL"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/net/URL", "<init>", "(Ljava/lang/String;)V", false));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/URL", "getHost", "()Ljava/lang/String;", false));
        il.add(tryEnd);
        il.add(new VarInsnNode(Opcodes.ASTORE, 1));

        // Default domains: host.endsWith(".minecraft.net"), host.endsWith(".mojang.com")
        for (String d : new String[]{".minecraft.net", ".mojang.com"}) {
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new LdcInsnNode(d));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "endsWith", "(Ljava/lang/String;)Z", false));
            il.add(new JumpInsnNode(Opcodes.IFNE, returnTrue));
        }

        // Skin domains: host.endsWith(d)
        for (String d : skinDomains) {
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new LdcInsnNode(d));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "endsWith", "(Ljava/lang/String;)Z", false));
            il.add(new JumpInsnNode(Opcodes.IFNE, returnTrue));
        }

        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new InsnNode(Opcodes.IRETURN));

        il.add(returnTrue);
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.IRETURN));

        // Exception handler: return false
        il.add(handler);
        il.add(new InsnNode(Opcodes.POP));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new InsnNode(Opcodes.IRETURN));
    }
}
