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

public class OneSixLauncherYggdrasilAuthTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        boolean isMojangHttp = "com/mojang/authlib/HttpAuthenticationService".equals(className);
        boolean isHopperUtil = "net/minecraft/hopper/Util".equals(className);
        boolean isNetYggdrasil = "net/minecraft/launcher/authentication/yggdrasil/YggdrasilAuthenticationService".equals(className);
        boolean isAuthService = isNetYggdrasil || "com/mojang/authlib/yggdrasil/YggdrasilUserAuthentication".equals(className);
        boolean isOldAuth = "net/minecraft/launcher/authentication/OldAuthentication".equals(className);              // ~0.7
        boolean isLegacyAuth = "net/minecraft/launcher/authentication/LegacyAuthenticationService".equals(className); // ~1.0
        boolean isAuthDb = "net/minecraft/launcher/profile/AuthenticationDatabase".equals(className)                  // ~1.6.93
                || "net/minecraft/launcher/authentication/AuthenticationDatabase".equals(className);                  // ~1.3
        if (!isMojangHttp && !isHopperUtil && !isAuthService && !isOldAuth && !isLegacyAuth && !isAuthDb) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                if (isMojangHttp && "performPostRequest".equals(mn.name)
                        && "(Ljava/net/URL;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;".equals(mn.desc)) {
                    injectAuthInterception(mn, 1, 2); // instance: url=1, post=2
                    changed = true;
                } else if (isHopperUtil && "performPost".equals(mn.name)
                        && "(Ljava/net/URL;Ljava/lang/String;Ljava/net/Proxy;Ljava/lang/String;Z)Ljava/lang/String;".equals(mn.desc)) {
                    injectAuthInterception(mn, 0, 1); // static: url=0, post=1
                    changed = true;
                } else if ((isAuthService && ("logIn".equals(mn.name) || "logInWithPassword".equals(mn.name)
                        || "logInWithToken".equals(mn.name)))
                        || (isLegacyAuth && "logIn".equals(mn.name))) {
                    changed |= neutralizeCredentialGuards(mn);
                } else if (isAuthService && "loadFromStorage".equals(mn.name)) {
                    changed |= filterStoredAccessToken(mn);
                } else if ((isNetYggdrasil && "logOut".equals(mn.name) && "()V".equals(mn.desc))
                        || (isLegacyAuth && "logOut".equals(mn.name) && "()V".equals(mn.desc))
                        || (isOldAuth && "clearLastSuccessfulResponse".equals(mn.name))) {

                    InsnList patch = new InsnList();
                    patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/unmojang/loki/hooks/LauncherHooks", "onAuthLogOut", "(Ljava/lang/Object;)V", false));
                    mn.instructions.insert(patch);
                    changed = true;
                } else if (isAuthDb && "removeUUID".equals(mn.name) && "(Ljava/lang/String;)V".equals(mn.desc)) {
                    InsnList patch = new InsnList();
                    patch.add(new VarInsnNode(Opcodes.ALOAD, 1)); // uuid
                    patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/unmojang/loki/hooks/LauncherHooks", "onAccountRemoved",
                            "(Ljava/lang/String;)V", false));
                    mn.instructions.insert(patch);
                    changed = true;
                } else {
                    continue;
                }
                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc) + " for Microsoft login");
            }

            if (!changed) return null;

            ClassWriter cw = (isMojangHttp || isHopperUtil)
                    ? new LoaderAwareClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader)
                    : new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform " + className + "!", t);
            return null;
        }
    }

    // String loki = maybeYggdrasilAuth(url, post); if (loki != null) return loki;
    private static void injectAuthInterception(MethodNode mn, int urlSlot, int postSlot) {
        InsnList patch = new InsnList();
        LabelNode original = new LabelNode();
        patch.add(new VarInsnNode(Opcodes.ALOAD, urlSlot));
        patch.add(new VarInsnNode(Opcodes.ALOAD, postSlot));
        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/unmojang/loki/hooks/LauncherHooks", "maybeYggdrasilAuth",
                "(Ljava/net/URL;Ljava/lang/String;)Ljava/lang/String;", false));
        patch.add(new InsnNode(Opcodes.DUP));
        patch.add(new JumpInsnNode(Opcodes.IFNULL, original)); // null -> fall through
        patch.add(new InsnNode(Opcodes.ARETURN));              // non-null -> return it
        patch.add(original);
        patch.add(new InsnNode(Opcodes.POP));                  // discard the null
        mn.instructions.insert(patch);
    }

    @SuppressWarnings("ExtractMethodRecommender")
    private static boolean neutralizeCredentialGuards(MethodNode mn) {
        List<MethodInsnNode> calls = new ArrayList<MethodInsnNode>();
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.INVOKESTATIC && insn instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) insn;
                if ("org/apache/commons/lang3/StringUtils".equals(mi.owner) && ("isBlank".equals(mi.name) || "isNotBlank".equals(mi.name))) {
                    calls.add(mi);
                }
            }
        }

        boolean changed = false;
        for (MethodInsnNode mi : calls) {
            InsnList add = new InsnList();
            add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/unmojang/loki/hooks/LauncherHooks", "isMicrosoftAuth", "()Z", false));
            if ("isBlank".equals(mi.name)) {
                add.add(new InsnNode(Opcodes.ICONST_1)); // result & !msa  -> guard skipped when msa
                add.add(new InsnNode(Opcodes.IXOR));
                add.add(new InsnNode(Opcodes.IAND));
            } else if (precededByPasswordGetter(mi)) {
                add.add(new InsnNode(Opcodes.IOR));      // result | msa    -> password treated as present
            } else {
                continue;
            }
            mn.instructions.insert(mi, add);
            changed = true;
        }
        return changed;
    }

    private static boolean filterStoredAccessToken(MethodNode mn) {
        boolean changed = false;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.PUTFIELD && insn instanceof FieldInsnNode
                    && "accessToken".equals(((FieldInsnNode) insn).name)) {
                mn.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, "org/unmojang/loki/hooks/LauncherHooks",
                        "filterStoredToken", "(Ljava/lang/String;)Ljava/lang/String;", false));
                changed = true;
            }
        }
        return changed;
    }

    private static boolean precededByPasswordGetter(MethodInsnNode isNotBlank) {
        for (AbstractInsnNode p = isNotBlank.getPrevious(); p != null; p = p.getPrevious()) {
            if (p instanceof LabelNode || p instanceof LineNumberNode || p instanceof FrameNode) continue;
            return p instanceof MethodInsnNode && "getPassword".equals(((MethodInsnNode) p).name);
        }
        return false;
    }
}
