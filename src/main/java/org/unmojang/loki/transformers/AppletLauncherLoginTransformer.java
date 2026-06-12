package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class AppletLauncherLoginTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (!"net/minecraft/Util".equals(className)) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                if (("excutePost".equals(mn.name) || "executePost".equals(mn.name))
                        && "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;".equals(mn.desc)) {

                    InsnList guard = new InsnList();
                    LabelNode original = new LabelNode();
                    guard.add(new VarInsnNode(Opcodes.ALOAD, 0)); // targetURL
                    guard.add(new VarInsnNode(Opcodes.ALOAD, 1)); // urlParameters
                    guard.add(new InsnNode(Loki.launcher_trigger_update ? Opcodes.ICONST_1 : Opcodes.ICONST_0)); // triggerUpdate
                    guard.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "org/unmojang/loki/hooks/LauncherHooks",
                            "loginToAppletLauncher",
                            "(Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;",
                            false
                    ));
                    guard.add(new InsnNode(Opcodes.DUP));
                    guard.add(new JumpInsnNode(Opcodes.IFNULL, original)); // null -> run the original method
                    guard.add(new InsnNode(Opcodes.ARETURN));              // non-null -> return Loki's response
                    guard.add(original);
                    guard.add(new InsnNode(Opcodes.POP));                  // discard the null
                    mn.instructions.insert(guard);

                    Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                    changed = true;
                    break;
                }
            }

            if (!changed) return null;

            ClassWriter cw = new LoaderAwareClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform " + className + "!", t);
            return null;
        }
    }
}
