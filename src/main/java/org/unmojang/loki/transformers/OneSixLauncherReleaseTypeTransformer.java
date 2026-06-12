package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class OneSixLauncherReleaseTypeTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        boolean isProfile = "net/minecraft/launcher/profile/Profile".equals(className);
        boolean isReleaseType = "net/minecraft/launcher/versions/ReleaseType".equals(className);
        boolean isEnumAdapter = "net/minecraft/launcher/updater/LowerCaseEnumTypeAdapterFactory".equals(className);
        if (!isProfile && !isReleaseType && !isEnumAdapter) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = isProfile ? defaultOldVersionsOn(className, cn)
                    : isReleaseType ? underscoreReleaseTypeNames(className, cn)
                    : mapOldTypesToRelease(className, cn);
            if (!changed) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform " + className + "!", t);
            return null;
        }
    }

    // Default old_alpha/old_beta to enabled
    private static boolean defaultOldVersionsOn(String className, ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            if (!"<clinit>".equals(mn.name)) continue;

            FieldInsnNode put = null;
            String enumOwner = null;
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof FieldInsnNode)) continue;
                FieldInsnNode fin = (FieldInsnNode) insn;
                if (fin.getOpcode() == Opcodes.GETSTATIC && "RELEASE".equals(fin.name)) enumOwner = fin.owner;
                if (fin.getOpcode() == Opcodes.PUTSTATIC && "DEFAULT_RELEASE_TYPES".equals(fin.name)) put = fin;
            }
            if (put == null || enumOwner == null) continue;

            String typeDesc = "(Ljava/lang/String;)L" + enumOwner + ";";
            InsnList patch = new InsnList();
            for (String name : new String[]{"old_alpha", "old_beta"}) {
                patch.add(new FieldInsnNode(Opcodes.GETSTATIC, className, "DEFAULT_RELEASE_TYPES", "Ljava/util/Set;"));
                patch.add(new LdcInsnNode(name));
                patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, enumOwner, "getByName", typeDesc, false));
                patch.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true));
                patch.add(new InsnNode(Opcodes.POP));
            }
            patch.add(new FieldInsnNode(Opcodes.GETSTATIC, className, "DEFAULT_RELEASE_TYPES", "Ljava/util/Set;"));
            patch.add(new InsnNode(Opcodes.ACONST_NULL));
            patch.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "remove", "(Ljava/lang/Object;)Z", true));
            patch.add(new InsnNode(Opcodes.POP));
            mn.instructions.insert(put, patch);

            Loki.log.debug("Enabling old_alpha/old_beta by default in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
            return true;
        }
        return false;
    }

    // Rewrite early 1.6 launcher's old-alpha/old-beta to use underscores instead
    private static boolean underscoreReleaseTypeNames(String className, ClassNode cn) {
        boolean changed = false;
        for (MethodNode mn : cn.methods) {
            if (!"<clinit>".equals(mn.name)) continue;
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof LdcInsnNode)) continue;
                LdcInsnNode ldc = (LdcInsnNode) insn;
                if ("old-alpha".equals(ldc.cst)) { ldc.cst = "old_alpha"; changed = true; }
                else if ("old-beta".equals(ldc.cst)) { ldc.cst = "old_beta"; changed = true; }
            }
        }
        if (changed) Loki.log.debug("Rewriting old-alpha/old-beta release type names in " + className);
        return changed;
    }

    private static boolean mapOldTypesToRelease(String className, ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            if (!"create".equals(mn.name)) continue;

            int mapSlot = -1;
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.INVOKESPECIAL || !(insn instanceof MethodInsnNode)) continue;
                if (!"java/util/HashMap".equals(((MethodInsnNode) insn).owner)) continue;
                for (AbstractInsnNode n = insn.getNext(); n != null; n = n.getNext()) {
                    if (n instanceof VarInsnNode && n.getOpcode() == Opcodes.ASTORE) { mapSlot = ((VarInsnNode) n).var; break; }
                }
                break;
            }
            if (mapSlot < 0) continue;

            AbstractInsnNode ret = null;
            for (AbstractInsnNode insn = mn.instructions.getLast(); insn != null; insn = insn.getPrevious()) {
                if (insn.getOpcode() == Opcodes.ARETURN) { ret = insn; break; }
            }
            if (ret == null) continue;

            InsnList patch = new InsnList();
            patch.add(new VarInsnNode(Opcodes.ALOAD, mapSlot));
            patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/unmojang/loki/hooks/LauncherHooks",
                    "aliasOldReleaseTypes", "(Ljava/util/Map;)V", false));
            mn.instructions.insertBefore(ret, patch);

            Loki.log.debug("Aliasing old_alpha/old_beta to release in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
            return true;
        }
        return false;
    }
}
