package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class AppletParameterTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (!"com/mojang/minecraft/MinecraftApplet".equals(className)) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                if (!"init".equals(mn.name) || !"()V".equals(mn.desc)) continue;

                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (!(insn instanceof MethodInsnNode)) continue;
                    MethodInsnNode min = (MethodInsnNode) insn;

                    if (min.getOpcode() != Opcodes.INVOKEVIRTUAL
                            || !"getParameter".equals(min.name)
                            || !"(Ljava/lang/String;)Ljava/lang/String;".equals(min.desc)) continue;

                    AbstractInsnNode prev = min.getPrevious();
                    AbstractInsnNode prev2 = (prev != null) ? prev.getPrevious() : null;
                    if (prev2 == null) continue;

                    if (prev2.getOpcode() == Opcodes.ALOAD && ((VarInsnNode) prev2).var == 0
                            && prev instanceof LdcInsnNode && "mppass".equals(((LdcInsnNode) prev).cst)) {
                        mn.instructions.remove(prev); // remove "mppass" string literal to appease stack
                        MethodInsnNode repl = new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                "org/unmojang/loki/hooks/Hooks",
                                "getMpPass",
                                "(Ljava/applet/Applet;)Ljava/lang/String;",
                                false
                        );
                        mn.instructions.set(min, repl);

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
            Loki.log.error("Failed to transform MinecraftApplet!", t);
            return null;
        }
    }
}
