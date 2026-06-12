package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class OneSixLauncherLibraryTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (!"net/minecraft/launcher/updater/Library".equals(className)
                && !"net/minecraft/launcher/versions/Library".equals(className)) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                if ("getArtifactBaseDir".equals(mn.name) && "()Ljava/lang/String;".equals(mn.desc)) {
                    replaceWithHook(mn, className, "getLibraryArtifactBaseDir", "(Ljava/lang/String;)Ljava/lang/String;", false);
                    Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                    changed = true;
                } else if ("getArtifactFilename".equals(mn.name) && "(Ljava/lang/String;)Ljava/lang/String;".equals(mn.desc)) {
                    replaceWithHook(mn, className, "getLibraryArtifactFilename", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", true);
                    Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                    changed = true;
                } else if ("getArtifactFilename".equals(mn.name) && "()Ljava/lang/String;".equals(mn.desc)) {
                    replaceWithHook(mn, className, "getLibraryArtifactFilename", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
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

    private static void replaceWithHook(MethodNode mn, String owner, String hookName, String hookDesc, boolean hasClassifierParam) {
        mn.instructions.clear();
        mn.tryCatchBlocks.clear();
        if (mn.localVariables != null) mn.localVariables.clear();

        InsnList insns = new InsnList();
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "name", "Ljava/lang/String;"));
        if ("getLibraryArtifactFilename".equals(hookName)) {
            insns.add(hasClassifierParam ? new VarInsnNode(Opcodes.ALOAD, 1) : new InsnNode(Opcodes.ACONST_NULL));
        }
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/unmojang/loki/hooks/LauncherHooks", hookName, hookDesc, false));
        insns.add(new InsnNode(Opcodes.ARETURN));

        mn.instructions.add(insns);
    }
}
