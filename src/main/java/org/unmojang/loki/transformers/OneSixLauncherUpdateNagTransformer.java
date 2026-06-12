package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class OneSixLauncherUpdateNagTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (!"net/minecraft/launcher/ui/LauncherPanel".equals(className)) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                if (!"createLauncherInterface".equals(mn.name) || !"()Ljavax/swing/JPanel;".equals(mn.desc)) continue;

                AbstractInsnNode jlabel = null;
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.NEW && "javax/swing/JLabel".equals(((TypeInsnNode) insn).desc)) {
                        jlabel = insn;
                        break;
                    }
                }
                if (jlabel == null) continue;

                for (AbstractInsnNode insn = jlabel.getPrevious(); insn != null; insn = insn.getPrevious()) {
                    if (insn.getOpcode() != Opcodes.IFEQ) continue;
                    AbstractInsnNode load = insn.getPrevious();
                    while ((load instanceof LabelNode || load instanceof LineNumberNode || load instanceof FrameNode)) {
                        load = load.getPrevious();
                    }
                    if (load != null && load.getOpcode() == Opcodes.ILOAD) {
                        mn.instructions.set(load, new InsnNode(Opcodes.ICONST_0)); // upgradableOS = false
                        Loki.log.debug("Patching update nag in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                        changed = true;
                    }
                    break;
                }
                break;
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
