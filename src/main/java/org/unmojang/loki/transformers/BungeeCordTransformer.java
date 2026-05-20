package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;
import org.unmojang.loki.RequestInterceptor;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Map;

public class BungeeCordTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (!className.startsWith("net/md_5/bungee/")) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;
            Map<String, String> ygMap = RequestInterceptor.YGGDRASIL_MAP;

            if ("net/md_5/bungee/EncryptionUtil".equals(className)) {
                for (MethodNode mn : cn.methods) {
                    if ("<clinit>".equals(mn.name)) {
                        AbstractInsnNode ret = null;
                        for (AbstractInsnNode insn : mn.instructions.toArray())
                            if (insn.getOpcode() == Opcodes.RETURN) ret = insn;
                        if (ret != null) {
                            InsnList patch = new InsnList();
                            patch.add(new LdcInsnNode(Type.getType("Lnet/md_5/bungee/EncryptionUtil;")));
                            patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                    "org/unmojang/loki/hooks/Hooks",
                                    "replaceBungeeCordMojangKey",
                                    "(Ljava/lang/Class;)V",
                                    false));
                            mn.instructions.insertBefore(ret, patch);
                            Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                            changed = true;
                        }
                        break;
                    }
                }
            }

            for (MethodNode mn : cn.methods) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof String) {
                        // plain LDC string constants
                        LdcInsnNode ldc = (LdcInsnNode) insn;
                        String s = (String) ldc.cst;
                        for (String domain : ygMap.keySet()) {
                            String prefix = "https://" + domain;
                            if (s.startsWith(prefix)) {
                                ldc.cst = LokiUtil.normalizeUrl(ygMap.get(domain)) + s.substring(prefix.length());
                                Loki.log.debug("Patching Yggdrasil URL in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                                changed = true;
                                break;
                            }
                        }
                    } else if (insn instanceof InvokeDynamicInsnNode) {
                        // Java 9+ StringConcatFactory
                        InvokeDynamicInsnNode idn = (InvokeDynamicInsnNode) insn;
                        if (idn.bsmArgs != null && idn.bsmArgs.length > 0 && idn.bsmArgs[0] instanceof String) {
                            String recipe = (String) idn.bsmArgs[0];
                            for (String domain : ygMap.keySet()) {
                                String prefix = "https://" + domain;
                                if (recipe.contains(prefix)) {
                                    idn.bsmArgs[0] = recipe.replace(prefix, LokiUtil.normalizeUrl(ygMap.get(domain)));
                                    Loki.log.debug("Patching Yggdrasil URL in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                                    changed = true;
                                    break;
                                }
                            }
                        }
                    }
                }
                if (Arrays.asList("isValidName", "check").contains(mn.name)
                        && (mn.access & Opcodes.ACC_PUBLIC) != 0 && mn.desc.endsWith(")Z")) {
                    if ("check".equals(mn.name) && Loki.enforce_secure_profile) continue;
                    mn.instructions.clear();
                    mn.tryCatchBlocks.clear();
                    if (mn.localVariables != null) mn.localVariables.clear();
                    mn.instructions.add(new InsnNode(Opcodes.ICONST_1));
                    mn.instructions.add(new InsnNode(Opcodes.IRETURN));
                    Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                    changed = true;
                    break;
                }
            }

            if (!changed) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform " + className + "!", t);
            return null;
        }
    }
}
