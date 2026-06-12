package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class OneSixLauncherLoginFormTransformer implements ClassFileTransformer {

    private static final String SIDEBAR = "net/minecraft/launcher/ui/sidebar/login/NotLoggedInForm";
    private static final String POPUP = "net/minecraft/launcher/ui/popups/login/LogInForm";

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        boolean isSidebar = SIDEBAR.equals(className);
        boolean isPopup = POPUP.equals(className);
        if (!isSidebar && !isPopup) return null;

        String hook = isSidebar ? "decorateSidebarLoginForm" : "decoratePopupLoginForm";

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                boolean atStart = "tryLogIn".equals(mn.name);
                boolean atReturn = isSidebar
                        ? ("<init>".equals(mn.name) || "onProfilesRefreshed".equals(mn.name))
                        : "createInterface".equals(mn.name);
                if (!atStart && !atReturn) continue;

                if (atStart) {
                    InsnList decorate = new InsnList();
                    decorate.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    decorate.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "org/unmojang/loki/hooks/LauncherHooks", hook, "(Ljava/lang/Object;)V", false));
                    mn.instructions.insert(decorate);
                } else {
                    if (isSidebar && "onProfilesRefreshed".equals(mn.name)) {
                        InsnList p = new InsnList();
                        p.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        p.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "org/unmojang/loki/hooks/LauncherHooks", "maybeAutoRefresh",
                                "(Ljava/lang/Object;)V", false));
                        mn.instructions.insert(p);
                    }
                    for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (insn.getOpcode() == Opcodes.RETURN) {
                            InsnList decorate = new InsnList();
                            decorate.add(new VarInsnNode(Opcodes.ALOAD, 0));
                            decorate.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                    "org/unmojang/loki/hooks/LauncherHooks", hook, "(Ljava/lang/Object;)V", false));
                            mn.instructions.insertBefore(insn, decorate);
                        }
                    }
                }
                Loki.log.debug("Decorating MSA login form in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
            }

            if (!changed) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform " + className + "!", t);
            return null;
        }
    }
}
