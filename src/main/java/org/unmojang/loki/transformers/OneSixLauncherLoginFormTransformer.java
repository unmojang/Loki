package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class OneSixLauncherLoginFormTransformer extends LokiTransformer {

    private static final String SIDEBAR = "net/minecraft/launcher/ui/sidebar/login/NotLoggedInForm";
    private static final String POPUP = "net/minecraft/launcher/ui/popups/login/LogInForm";

    protected boolean matches(String className) {
        return SIDEBAR.equals(className) || POPUP.equals(className);
    }

    protected int writerFlags(String className) {
        return ClassWriter.COMPUTE_MAXS;
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean isSidebar = SIDEBAR.equals(className);

        String hook = isSidebar ? "decorateSidebarLoginForm" : "decoratePopupLoginForm";

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

        return changed;
    }
}
