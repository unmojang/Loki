package org.unmojang.loki.transformers;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.unmojang.loki.Loki;

public class SetURLFactoryTransformer implements ClassFileTransformer {
    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!"net/minecraftforge/fml/loading/FMLLoader".equals(className) &&
                !"uk/betacraft/legacyfix/LegacyFixLauncher".equals(className)) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

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
                                "org/unmojang/loki/RequestInterceptor",
                                "registerExternalFactory",
                                "(Ljava/net/URLStreamHandlerFactory;)V",
                                false);
                        mn.instructions.set(min, replacement);

                        Loki.log.debug("Patching " + mn.name + " in " + className);
                        changed = true;
                    }
                }
            }

            if (!changed) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform URL.setURLStreamHandlerFactory!", t);
            return null;
        }
    }
}
