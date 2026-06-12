package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class OneSixLauncherModernAssetsTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        boolean isVersionManager = "net/minecraft/launcher/updater/VersionManager".equals(className);
        boolean isGameLauncher = "net/minecraft/launcher/GameLauncher".equals(className);
        boolean isDownloadable = "net/minecraft/launcher/updater/download/Downloadable".equals(className);
        boolean isPreHashed = "net/minecraft/launcher/updater/PreHashedDownloadable".equals(className);
        if (!isVersionManager && !isGameLauncher && !isDownloadable && !isPreHashed) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                if (isDownloadable
                        && "download".equals(mn.name)
                        && "()Ljava/lang/String;".equals(mn.desc)) {

                    InsnList pre = new InsnList();
                    LabelNode proceed = new LabelNode();
                    pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    pre.add(new FieldInsnNode(Opcodes.GETFIELD, className, "forceDownload", "Z"));
                    pre.add(new JumpInsnNode(Opcodes.IFNE, proceed)); // forced -> download
                    pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    pre.add(new FieldInsnNode(Opcodes.GETFIELD, className, "target", "Ljava/io/File;"));
                    pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/File", "isFile", "()Z", false));
                    pre.add(new JumpInsnNode(Opcodes.IFEQ, proceed)); // no local copy -> download
                    pre.add(new LdcInsnNode("Using existing local copy"));
                    pre.add(new InsnNode(Opcodes.ARETURN));
                    pre.add(proceed);
                    mn.instructions.insertBefore(mn.instructions.getFirst(), pre);

                    Loki.log.debug("Skipping re-download of existing files in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                    changed = true;
                } else if (isPreHashed
                        && "download".equals(mn.name)
                        && "()Ljava/lang/String;".equals(mn.desc)) {

                    InsnList pre = new InsnList();
                    LabelNode proceed = new LabelNode();
                    pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, className, "shouldIgnoreLocal", "()Z", false));
                    pre.add(new JumpInsnNode(Opcodes.IFNE, proceed)); // forced -> download
                    pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, className, "getTarget", "()Ljava/io/File;", false));
                    pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/File", "isFile", "()Z", false));
                    pre.add(new JumpInsnNode(Opcodes.IFEQ, proceed)); // no local copy -> download
                    pre.add(new LdcInsnNode("Using existing local copy"));
                    pre.add(new InsnNode(Opcodes.ARETURN));
                    pre.add(proceed);
                    mn.instructions.insertBefore(mn.instructions.getFirst(), pre);

                    Loki.log.debug("Skipping hash check for existing local copy in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                    changed = true;
                } else if (isVersionManager
                        && "getResourceFiles".equals(mn.name)
                        && "(Ljava/net/Proxy;Ljava/io/File;)Ljava/util/Set;".equals(mn.desc)) {

                    mn.instructions.clear();
                    mn.tryCatchBlocks.clear();
                    if (mn.localVariables != null) mn.localVariables.clear();

                    InsnList insns = new InsnList();
                    insns.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashSet"));
                    insns.add(new InsnNode(Opcodes.DUP));
                    insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "()V", false));
                    insns.add(new InsnNode(Opcodes.ARETURN));

                    mn.instructions.add(insns);
                    Loki.log.debug("Disabling startup resource scrape in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                    changed = true;
                } else if (isVersionManager
                        && "downloadVersion".equals(mn.name)
                        && Type.getArgumentTypes(mn.desc).length == 2) {

                    AbstractInsnNode lastReturn = null;
                    for (AbstractInsnNode insn = mn.instructions.getLast(); insn != null; insn = insn.getPrevious()) {
                        if (insn.getOpcode() == Opcodes.ARETURN) { lastReturn = insn; break; }
                    }
                    if (lastReturn != null) {
                        InsnList patch = new InsnList();
                        patch.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
                        patch.add(new VarInsnNode(Opcodes.ALOAD, 2)); // job
                        patch.add(new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                "org/unmojang/loki/hooks/LauncherHooks",
                                "appendModernResourceDownloads",
                                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                                false
                        ));
                        mn.instructions.insertBefore(lastReturn, patch);
                        Loki.log.debug("Appending modern asset downloads in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                        changed = true;
                    }
                } else if (isVersionManager
                        && "getLatestCompleteVersion".equals(mn.name)
                        && Type.getReturnType(mn.desc).getInternalName().equals("net/minecraft/launcher/versions/CompleteVersion")) {

                    for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (insn.getOpcode() != Opcodes.ARETURN) continue;
                        InsnList capture = new InsnList();
                        capture.add(new InsnNode(Opcodes.DUP));
                        capture.add(new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                "org/unmojang/loki/hooks/LauncherHooks",
                                "captureVersionId",
                                "(Ljava/lang/Object;)V",
                                false
                        ));
                        mn.instructions.insertBefore(insn, capture);
                    }
                    Loki.log.debug("Capturing launched version in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                    changed = true;
                } else if (isGameLauncher
                        && "getMinecraftArguments".equals(mn.name)
                        && Type.getReturnType(mn.desc).equals(Type.getType("[Ljava/lang/String;"))) {

                    int versionSlot = -1, profileSlot = -1, assetsSlot = -1;
                    int slot = 1; // instance method: slot 0 is `this`
                    for (Type arg : Type.getArgumentTypes(mn.desc)) {
                        String name = arg.getInternalName();
                        if ("net/minecraft/launcher/versions/CompleteVersion".equals(name) && versionSlot < 0) versionSlot = slot;
                        else if ("net/minecraft/launcher/profile/Profile".equals(name)) profileSlot = slot;
                        else if ("java/io/File".equals(name)) assetsSlot = slot; // last File is assetsDirectory
                        slot += arg.getSize();
                    }
                    if (versionSlot < 0 || profileSlot < 0 || assetsSlot < 0) {
                        Loki.log.debug("Could not resolve parameter slots in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                        continue;
                    }

                    AbstractInsnNode lastReturn = null;
                    for (AbstractInsnNode insn = mn.instructions.getLast(); insn != null; insn = insn.getPrevious()) {
                        if (insn.getOpcode() == Opcodes.ARETURN) { lastReturn = insn; break; }
                    }
                    if (lastReturn == null) {
                        Loki.log.debug("No array return found in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                        continue;
                    }

                    InsnList patch = new InsnList();
                    patch.add(new VarInsnNode(Opcodes.ALOAD, versionSlot)); // version
                    patch.add(new VarInsnNode(Opcodes.ALOAD, assetsSlot));  // assetsDirectory
                    patch.add(new VarInsnNode(Opcodes.ALOAD, profileSlot)); // selectedProfile
                    patch.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "org/unmojang/loki/hooks/LauncherHooks",
                            "fillModernArgs",
                            "([Ljava/lang/String;Ljava/lang/Object;Ljava/io/File;Ljava/lang/Object;)[Ljava/lang/String;",
                            false
                    ));
                    mn.instructions.insertBefore(lastReturn, patch);

                    Loki.log.debug("Filling modern game arguments in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
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
