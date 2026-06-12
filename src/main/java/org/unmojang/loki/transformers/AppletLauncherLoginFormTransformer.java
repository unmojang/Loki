package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class AppletLauncherLoginFormTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (!"net/minecraft/LoginForm".equals(className)) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                if ("buildLoginPanel".equals(mn.name)) {
                    for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (insn.getOpcode() != Opcodes.ARETURN) continue;

                        InsnList patch = new InsnList();
                        patch.add(new InsnNode(Opcodes.DUP));                    // [..., panel, panel]
                        patch.add(new VarInsnNode(Opcodes.ALOAD, 0));    // [..., panel, panel, this]
                        patch.add(new InsnNode(Opcodes.SWAP));                   // [..., panel, this, panel]
                        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "org/unmojang/loki/hooks/LauncherHooks", "decorateLoginPanel",
                                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                        mn.instructions.insertBefore(insn, patch);
                        changed = true;
                    }
                    Loki.log.debug("Decorating login panel in " + LokiUtil.getFqmn(className, mn.name, mn.desc));

                } else if ("<init>".equals(mn.name)) {
                    for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (insn.getOpcode() != Opcodes.RETURN) continue;
                        InsnList patch = new InsnList();
                        patch.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
                        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "org/unmojang/loki/hooks/LauncherHooks", "resetLoginFields",
                                "(Ljava/lang/Object;)V", false));
                        mn.instructions.insertBefore(insn, patch);
                        changed = true;
                    }
                    Loki.log.debug("Clearing prefilled credentials in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
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
