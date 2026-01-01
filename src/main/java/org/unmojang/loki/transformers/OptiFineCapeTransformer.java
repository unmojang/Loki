package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class OptiFineCapeTransformer implements ClassFileTransformer {

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (Loki.modded_capes || !className.endsWith("CapeUtils")) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                if (mn.name.equals("downloadCape")) {
                    mn.instructions.clear();
                    mn.tryCatchBlocks.clear();
                    if (mn.localVariables != null) mn.localVariables.clear();

                    mn.instructions.add(new InsnNode(Opcodes.RETURN));

                    Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                    changed = true;
                    break;
                }
            }

            if (!changed) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform downloadCape!", t);
            return null;
        }
    }
}
