package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;
import org.unmojang.loki.RequestInterceptor;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;

public class YggdrasilURLTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (className.startsWith("org/unmojang/loki/")) return null; // let's not patch ourselves

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;
            Map<String, String> ygMap = RequestInterceptor.YGGDRASIL_MAP;

            for (MethodNode mn : cn.methods) {

                AbstractInsnNode insn = mn.instructions.getFirst();
                while (insn != null) {
                    AbstractInsnNode nextInsn = insn.getNext();

                    if (insn instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode) insn;
                        if (ldc.cst instanceof String && ((String) ldc.cst).startsWith("https://")) {
                            String s = (String) ldc.cst;
                            for (String domain : ygMap.keySet()) {
                                String prefix = "https://" + domain;
                                if (s.startsWith(prefix)) {
                                    ldc.cst = LokiUtil.normalizeUrl(ygMap.get(domain)) + s.substring(prefix.length());
                                    Loki.log.debug("Patching static Yggdrasil URL in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                                    changed = true;
                                    break;
                                }
                            }
                        }
                    }

                    if (insn instanceof InvokeDynamicInsnNode) {
                        InvokeDynamicInsnNode idn = (InvokeDynamicInsnNode) insn;
                        if (idn.bsmArgs != null && idn.bsmArgs.length > 0 && idn.bsmArgs[0] instanceof String) {
                            String recipe = (String) idn.bsmArgs[0];
                            for (String domain : ygMap.keySet()) {
                                String prefix = "https://" + domain;
                                if (recipe.contains(prefix)) {
                                    idn.bsmArgs[0] = recipe.replace(prefix, LokiUtil.normalizeUrl(ygMap.get(domain)));
                                    Loki.log.debug("Patching dynamic Yggdrasil URL in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                                    changed = true;
                                    break;
                                }
                            }
                        }
                    }

                    insn = nextInsn;
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
