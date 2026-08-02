package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class OneSixLauncherGameRunnerTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return "net/minecraft/launcher/game/MinecraftGameRunner".equals(className)   // new
                || "net/minecraft/launcher/GameLauncher".equals(className);          // old
    }

    protected int writerFlags(String className) {
        return ClassWriter.COMPUTE_MAXS;
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean isGameRunner = "net/minecraft/launcher/game/MinecraftGameRunner".equals(className);

        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if (!"launchGame".equals(mn.name) || !"()V".equals(mn.desc)) continue;
            changed |= isGameRunner ? patchGameRunner(className, mn) : patchGameLauncher(className, mn);
        }

        return changed;
    }

    private static boolean patchGameRunner(String className, MethodNode mn) {
        Integer processBuilderVar = null;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode min = (MethodInsnNode) insn;
            if (min.getOpcode() != Opcodes.INVOKESPECIAL
                    || !"com/mojang/launcher/game/process/GameProcessBuilder".equals(min.owner)
                    || !"<init>".equals(min.name)) continue;

            for (AbstractInsnNode next = insn.getNext(); next != null; next = next.getNext()) {
                if (next instanceof VarInsnNode && next.getOpcode() == Opcodes.ASTORE) {
                    processBuilderVar = ((VarInsnNode) next).var;
                    break;
                }
                if (next instanceof MethodInsnNode || next instanceof InsnNode) break; // not stored to a local
            }
            break;
        }

        if (processBuilderVar == null) {
            Loki.log.debug("Could not determine GameProcessBuilder local slot in " +
                    LokiUtil.getFqmn(className, mn.name, mn.desc));
            return false;
        }

        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode min = (MethodInsnNode) insn;
            if (!"createFeatureMatcher".equals(min.name)) continue;
            if (!"()Lnet/minecraft/launcher/CompatibilityRule$FeatureMatcher;".equals(min.desc)) continue;

            InsnList patch = new InsnList();
            patch.add(new VarInsnNode(Opcodes.ALOAD, processBuilderVar));
            patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "org/unmojang/loki/hooks/LauncherHooks", "getLokiJVMArgs", "()[Ljava/lang/String;", false));
            patch.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "com/mojang/launcher/game/process/GameProcessBuilder", "withArguments",
                    "([Ljava/lang/String;)Lcom/mojang/launcher/game/process/GameProcessBuilder;", false));
            patch.add(new InsnNode(Opcodes.POP));
            mn.instructions.insertBefore(insn, patch);

            Loki.log.debug("Patching JVM args into " + LokiUtil.getFqmn(className, mn.name, mn.desc));
            return true;
        }
        return false;
    }

    private static boolean patchGameLauncher(String className, MethodNode mn) {
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof LdcInsnNode)) continue;
            LdcInsnNode ldc = (LdcInsnNode) insn;
            if (!"-Djava.library.path=".equals(ldc.cst)) continue;

            VarInsnNode anchor = null;
            for (AbstractInsnNode prev = insn.getPrevious(); prev != null; prev = prev.getPrevious()) {
                if (prev instanceof VarInsnNode && prev.getOpcode() == Opcodes.ALOAD) {
                    anchor = (VarInsnNode) prev;
                    break;
                }
            }

            if (anchor == null) {
                Loki.log.debug("Could not determine processLauncher local slot in " +
                        LokiUtil.getFqmn(className, mn.name, mn.desc));
                return false;
            }

            InsnList patch = new InsnList();
            patch.add(new VarInsnNode(Opcodes.ALOAD, anchor.var));
            patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "org/unmojang/loki/hooks/LauncherHooks", "getLokiJVMArgs", "()[Ljava/lang/String;", false));
            patch.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/launcher/process/JavaProcessLauncher", "addCommands", "([Ljava/lang/String;)V", false));
            mn.instructions.insertBefore(anchor, patch);

            Loki.log.debug("Patching JVM args into " + LokiUtil.getFqmn(className, mn.name, mn.desc));
            return true;
        }
        return false;
    }
}
