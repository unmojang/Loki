package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

public class AppletLauncherNativesTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (!"net/minecraft/GameUpdater".equals(className)) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                // The modern natives jars aren't signed with the bundled Mojang cert, so no-op the check.
                if ("validateCertificateChain".equals(mn.name)) {
                    mn.instructions.clear();
                    mn.tryCatchBlocks.clear();
                    if (mn.localVariables != null) mn.localVariables.clear();
                    mn.instructions.add(new InsnNode(Opcodes.RETURN));
                    Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                    changed = true;
                    continue;
                }

                // Force every read of lzmaSupported to false, so the launcher uses the plain (non-lzma) files.
                List<FieldInsnNode> reads = new ArrayList<FieldInsnNode>();
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.GETFIELD
                            && insn instanceof FieldInsnNode
                            && "lzmaSupported".equals(((FieldInsnNode) insn).name)) {
                        reads.add((FieldInsnNode) insn);
                    }
                }
                for (FieldInsnNode read : reads) {
                    InsnList repl = new InsnList();
                    repl.add(new InsnNode(Opcodes.POP));      // discard the `this` the GETFIELD would consume
                    repl.add(new InsnNode(Opcodes.ICONST_0)); // push false
                    mn.instructions.insertBefore(read, repl);
                    mn.instructions.remove(read);
                    changed = true;
                }
                if (!reads.isEmpty()) Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
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
