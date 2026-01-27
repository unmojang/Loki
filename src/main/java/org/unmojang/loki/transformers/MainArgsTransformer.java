package org.unmojang.loki.transformers;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class MainArgsTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (!"net/minecraft/client/main/Main".equals(className)) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                if (!mn.name.equals("main") || !mn.desc.equals("([Ljava/lang/String;)V")) continue;

                InsnList insns = new InsnList();
                insns.add(new VarInsnNode(Opcodes.ALOAD, 0)); // args
                insns.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/unmojang/loki/hooks/Hooks",
                        "transformMainArgs",
                        "([Ljava/lang/String;)[Ljava/lang/String;",
                        false
                ));
                insns.add(new VarInsnNode(Opcodes.ASTORE, 0));
                mn.instructions.insert(insns);

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
            }

            if (!changed) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform main args!", t);
            return null;
        }
    }
}

