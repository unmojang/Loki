package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class ReIndevGetSkinTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (!"net/minecraft/src/client/ThreadGetSkin".equals(className) || LokiUtil.JAVA_MAJOR <= 5) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                if (!"run".equals(mn.name) || !"()V".equals(mn.desc)) continue;

                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn.getOpcode() != Opcodes.ASTORE || insn.getPrevious() == null
                            || !(insn.getPrevious() instanceof MethodInsnNode)) continue;

                    MethodInsnNode prev = (MethodInsnNode) insn.getPrevious();

                    if ("<init>".equals(prev.name) && "java/lang/String".equals(prev.owner)) {
                        InsnList insns = new InsnList();
                        insns.add(new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode) insn).var));
                        insns.add(new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                "org/unmojang/loki/hooks/Hooks",
                                "transformProfileJson",
                                "(Ljava/lang/String;)Ljava/lang/String;",
                                false
                        ));
                        insns.add(new VarInsnNode(Opcodes.ASTORE, ((VarInsnNode) insn).var));

                        mn.instructions.insert(insn, insns);
                        Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                        changed = true;
                    }
                }
            }

            if (!changed) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform ThreadGetSkin!", t);
            return null;
        }
    }
}
