package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.RequestInterceptor;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class PatchyTransformer implements ClassFileTransformer {

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (!className.startsWith("com/mojang/patchy/")) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getType() == AbstractInsnNode.TYPE_INSN) {
                        TypeInsnNode tin = (TypeInsnNode) insn;
                        if (!"java/net/URL".equals(tin.desc)) continue;
                        AbstractInsnNode cur = tin.getNext();
                        while (cur != null) {
                            if (cur.getType() == AbstractInsnNode.METHOD_INSN) {
                                MethodInsnNode min = (MethodInsnNode) cur;
                                if (min.getOpcode() == Opcodes.INVOKESPECIAL
                                        && "java/net/URL".equals(min.owner)
                                        && "<init>".equals(min.name)) {
                                    break;
                                }
                            }

                            if (cur.getType() == AbstractInsnNode.LDC_INSN) {
                                assert cur instanceof LdcInsnNode;
                                LdcInsnNode ldc = (LdcInsnNode) cur;
                                Object cst = ldc.cst;
                                if (cst instanceof String) {
                                    if (cst.equals("https://sessionserver.mojang.com/blockedservers")) {
                                        ldc.cst = RequestInterceptor.YGGDRASIL_MAP.get("sessionserver.mojang.com")
                                                + "/blockedservers";
                                        Loki.log.debug("Patching " + mn.name + " in " + className);
                                        changed = true;
                                    }
                                }
                            }
                            cur = cur.getNext();
                        }
                    }
                }
            }

            if (!changed) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform patchy!", t);
            return null;
        }
    }
}
