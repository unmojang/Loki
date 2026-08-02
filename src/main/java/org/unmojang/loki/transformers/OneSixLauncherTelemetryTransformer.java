package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class OneSixLauncherTelemetryTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return "net/minecraft/launcher/profile/Profile".equals(className) && !Loki.enable_snooper;
    }

    protected int writerFlags(String className) {
        return ClassWriter.COMPUTE_MAXS;
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if ("getUseHopperCrashService".equals(mn.name) && "()Z".equals(mn.desc)) {
                // return Boolean.TRUE.equals(this.useHopperCrashService);
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                InsnList insns = new InsnList();
                insns.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;"));
                insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                insns.add(new FieldInsnNode(Opcodes.GETFIELD, className, "useHopperCrashService", "Ljava/lang/Boolean;"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "equals", "(Ljava/lang/Object;)Z", false));
                insns.add(new InsnNode(Opcodes.IRETURN));
                mn.instructions.add(insns);
                Loki.log.debug("Disabling telemetry by default in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
            } else if ("setUseHopperCrashService".equals(mn.name) && "(Z)V".equals(mn.desc)) {
                // this.useHopperCrashService = Boolean.valueOf(useHopperCrashService);
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                InsnList insns = new InsnList();
                insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
                insns.add(new FieldInsnNode(Opcodes.PUTFIELD, className, "useHopperCrashService", "Ljava/lang/Boolean;"));
                insns.add(new InsnNode(Opcodes.RETURN));
                mn.instructions.add(insns);
                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
            }
        }

        return changed;
    }
}
