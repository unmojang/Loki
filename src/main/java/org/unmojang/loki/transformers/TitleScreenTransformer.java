package org.unmojang.loki.transformers;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class TitleScreenTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (LokiUtil.SERVER_NAME.length() == 0
                || !"net/minecraft/client/gui/screens/TitleScreen".equals(className)) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                for (AbstractInsnNode node : mn.instructions.toArray()) {
                    if (node.getOpcode() != Opcodes.INVOKEINTERFACE) continue;
                    MethodInsnNode min = (MethodInsnNode) node;
                    if (!"net/minecraft/WorldVersion".equals(min.owner) ||
                            !"name".equals(min.name) ||
                            !"()Ljava/lang/String;".equals(min.desc)) continue;

                    InsnList insns = new InsnList();
                    insns.add(new LdcInsnNode("/" + LokiUtil.SERVER_NAME));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                            "(Ljava/lang/String;)Ljava/lang/String;", false));
                    mn.instructions.insert(node, insns);

                    Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                    changed = true;
                }
            }

            if (!changed) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform " + className + "!", t);
            return null;
        }
    }
}
