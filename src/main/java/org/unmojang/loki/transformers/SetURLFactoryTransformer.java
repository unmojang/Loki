package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import org.objectweb.asm.ClassWriter;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class SetURLFactoryTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return ("net/minecraftforge/fml/loading/FMLLoader".equals(className) ||
                "uk/betacraft/legacyfix/LegacyFixLauncher".equals(className)) && LokiUtil.JAVA_MAJOR > 5;
    }

    protected int writerFlags(String className) {
        return ClassWriter.COMPUTE_MAXS;
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) continue;
            for (AbstractInsnNode ain : mn.instructions.toArray()) {
                if (!(ain instanceof MethodInsnNode)) continue;
                MethodInsnNode min = (MethodInsnNode) ain;
                if (min.getOpcode() == Opcodes.INVOKESTATIC
                        && "java/net/URL".equals(min.owner)
                        && "setURLStreamHandlerFactory".equals(min.name)
                        && "(Ljava/net/URLStreamHandlerFactory;)V".equals(min.desc)) {
                    MethodInsnNode replacement = new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "org/unmojang/loki/hooks/Hooks",
                            "registerExternalFactory",
                            "(Ljava/net/URLStreamHandlerFactory;)V",
                            false);
                    mn.instructions.set(min, replacement);

                    Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                    changed = true;
                }
            }
        }

        return changed;
    }
}
