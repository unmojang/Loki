package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;
import org.unmojang.loki.RequestInterceptor;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;

public class YggdrasilURLTransformer implements ClassFileTransformer {

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (!Loki.disable_factory) return null; // nothing to do
        if (className.startsWith("org/unmojang/loki")) return null; // let's not patch ourselves

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;
            Map<String, String> ygMap = RequestInterceptor.YGGDRASIL_MAP;

            for (MethodNode mn : cn.methods) {

                if ("<clinit>".equals(mn.name) && "com/mojang/authlib/yggdrasil/YggdrasilEnvironment".equals(className)) {
                    for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (insn instanceof LdcInsnNode) {
                            LdcInsnNode ldc = (LdcInsnNode) insn;
                            if (ldc.cst instanceof String) {
                                String s = (String) ldc.cst;
                                for (String domain : ygMap.keySet()) {
                                    if (s.contains(domain)) {
                                        ldc.cst = LokiUtil.normalizeUrl(ygMap.get(domain));
                                        Loki.log.debug("Patching YggdrasilEnvironment.PROD URL for " + domain + " in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                                        changed = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }

                AbstractInsnNode insn = mn.instructions.getFirst();
                while (insn != null) {
                    AbstractInsnNode nextInsn = insn.getNext();

                    if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode) {
                        TypeInsnNode tin = (TypeInsnNode) insn;
                        if ("java/lang/StringBuilder".equals(tin.desc)) {
                            AbstractInsnNode cur = tin.getNext();
                            while (cur != null) {
                                if (cur instanceof LdcInsnNode) {
                                    LdcInsnNode ldc = (LdcInsnNode) cur;
                                    if (ldc.cst instanceof String && ((String) ldc.cst).startsWith("https://")) {
                                        String s = (String) ldc.cst;
                                        for (String domain : ygMap.keySet()) {
                                            String prefix = "https://" + domain;
                                            if (s.startsWith(prefix)) {
                                                ldc.cst = LokiUtil.normalizeUrl(ygMap.get(domain)) + s.substring(prefix.length());
                                                Loki.log.debug("Patching dynamic Yggdrasil URL LDC in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                                                changed = true;
                                                break;
                                            }
                                        }
                                        break;
                                    }
                                }
                                if (cur.getType() == AbstractInsnNode.METHOD_INSN && cur instanceof MethodInsnNode) {
                                    MethodInsnNode min = (MethodInsnNode) cur;
                                    if (min.getOpcode() == Opcodes.INVOKESPECIAL
                                            && "java/lang/StringBuilder".equals(min.owner)
                                            && "<init>".equals(min.name)) {
                                        break;
                                    }
                                }
                                cur = cur.getNext();
                            }
                        }
                    }

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

                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode min = (MethodInsnNode) insn;
                        if ("com/mojang/authlib/HttpAuthenticationService".equals(min.owner)
                                && "constantURL".equals(min.name)
                                && min.desc != null && min.desc.startsWith("(Ljava/lang/String;)")) {

                            AbstractInsnNode prev = min.getPrevious();
                            if (prev instanceof LdcInsnNode) {
                                LdcInsnNode ldc = (LdcInsnNode) prev;
                                if (ldc.cst instanceof String && ((String) ldc.cst).startsWith("https://")) {
                                    String s = (String) ldc.cst;
                                    for (String domain : ygMap.keySet()) {
                                        String prefix = "https://" + domain;
                                        if (s.startsWith(prefix)) {
                                            ldc.cst = LokiUtil.normalizeUrl(ygMap.get(domain)) + s.substring(prefix.length());
                                            Loki.log.debug("Patching HttpAuthenticationService URL in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                                            changed = true;
                                            break;
                                        }
                                    }
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
            Loki.log.error("Failed to transform Yggdrasil URLs!", t);
            return null;
        }
    }
}
