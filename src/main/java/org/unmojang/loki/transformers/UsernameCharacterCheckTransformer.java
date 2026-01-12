package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class UsernameCharacterCheckTransformer implements ClassFileTransformer {

    public byte[] transform(final ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (Loki.username_validation || className.startsWith("java/") || className.startsWith("javax/")) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, ClassReader.EXPAND_FRAMES);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                if (mn.instructions == null) continue;

                AbstractInsnNode[] insns = mn.instructions.toArray();
                for (AbstractInsnNode insn : insns) {
                    if (insn instanceof FieldInsnNode) {
                        FieldInsnNode fi = (FieldInsnNode) insn;
                        if (fi.getOpcode() == Opcodes.GETFIELD
                                && "iKnowThisMayNotBeTheBestIdeaButPleaseDisableUsernameValidation".equals(fi.name)) {

                            InsnList patch = new InsnList();
                            patch.add(new InsnNode(Opcodes.POP));
                            patch.add(new InsnNode(Opcodes.ICONST_1));
                            mn.instructions.insertBefore(fi, patch);
                            mn.instructions.remove(fi);

                            Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc) + " (Paper username check)");
                            changed = true;
                        }
                    }

                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode mi = (MethodInsnNode) insn;
                        if (mi.getOpcode() == Opcodes.INVOKESTATIC &&
                                "org/apache/commons/lang3/Validate".equals(mi.owner) &&
                                "validState".equals(mi.name) &&
                                "(ZLjava/lang/String;[Ljava/lang/Object;)V".equals(mi.desc)) {

                            AbstractInsnNode scan = mi.getPrevious();
                            boolean found = false;
                            int safety = 0;
                            while (scan != null && safety++ < 50) {
                                if (scan instanceof LdcInsnNode) {
                                    LdcInsnNode ldc = (LdcInsnNode) scan;
                                    if ("Invalid characters in username".equals(ldc.cst)) {
                                        found = true;
                                    }
                                    break;
                                }
                                if (scan instanceof FrameNode || scan instanceof LabelNode || scan instanceof LineNumberNode) {
                                    scan = scan.getPrevious();
                                    continue;
                                }
                                if (++safety > 20) break;
                                scan = scan.getPrevious();
                            }

                            if (found) {
                                InsnNode pop1 = new InsnNode(Opcodes.POP);
                                InsnNode pop2 = new InsnNode(Opcodes.POP);
                                InsnNode pop3 = new InsnNode(Opcodes.POP);
                                mn.instructions.set(mi, pop1);
                                mn.instructions.insert(pop1, pop2);
                                mn.instructions.insert(pop2, pop3);

                                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc) + " (username check)");
                                changed = true;
                            }
                        }
                    }
                }
            }

            if (!changed) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    try {
                        ClassLoader cl = loader;
                        if (cl == null) cl = ClassLoader.getSystemClassLoader();
                        Class<?> c1 = Class.forName(type1.replace('/', '.'), false, cl);
                        Class<?> c2 = Class.forName(type2.replace('/', '.'), false, cl);
                        if (c1.isAssignableFrom(c2)) return type1;
                        if (c2.isAssignableFrom(c1)) return type2;
                        if (c1.isInterface() || c2.isInterface()) return "java/lang/Object";
                        while (!c1.isAssignableFrom(c2)) {
                            c1 = c1.getSuperclass();
                        }
                        return c1.getName().replace('.', '/');
                    } catch (Throwable t) {
                        return "java/lang/Object";
                    }
                }
            };
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform ServerLoginPacketListenerImpl!", t);
            return null;
        }
    }
}
