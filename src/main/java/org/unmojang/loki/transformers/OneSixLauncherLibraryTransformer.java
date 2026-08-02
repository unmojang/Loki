package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class OneSixLauncherLibraryTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return "net/minecraft/launcher/updater/Library".equals(className)
                || "net/minecraft/launcher/versions/Library".equals(className);
    }

    protected boolean patch(ClassNode cn, String className) {
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

        return changed;
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
