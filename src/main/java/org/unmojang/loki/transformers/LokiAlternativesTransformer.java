package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class LokiAlternativesTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (!className.equals("moe/yushi/authlibinjector/transform/ClassTransformer") &&
                !className.equals("org/to2mbn/authlibinjector/transform/ClassTransformer") &&
                !className.equals("moe/yushi/authlibinjector/Premain") &&
                !className.equals("moe/yushi/authlibinjector/javaagent/AuthlibInjectorPremain") &&
                !className.equals("org/to2mbn/authlibinjector/javaagent/AuthlibInjectorPremain") &&
                !className.startsWith("org/prismlauncher/legacy/fix/online/")) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) continue;
                if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;

                // Obliterate everything
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();

                switch (Type.getReturnType(mn.desc).getSort()) {
                    case Type.VOID:
                        mn.instructions.add(new InsnNode(Opcodes.RETURN));
                        break;
                    case Type.BOOLEAN:
                    case Type.BYTE:
                    case Type.CHAR:
                    case Type.SHORT:
                    case Type.INT:
                        mn.instructions.add(new InsnNode(Opcodes.ICONST_0));
                        mn.instructions.add(new InsnNode(Opcodes.IRETURN));
                        break;
                    case Type.LONG:
                        mn.instructions.add(new InsnNode(Opcodes.LCONST_0));
                        mn.instructions.add(new InsnNode(Opcodes.LRETURN));
                        break;
                    case Type.FLOAT:
                        mn.instructions.add(new InsnNode(Opcodes.FCONST_0));
                        mn.instructions.add(new InsnNode(Opcodes.FRETURN));
                        break;
                    case Type.DOUBLE:
                        mn.instructions.add(new InsnNode(Opcodes.DCONST_0));
                        mn.instructions.add(new InsnNode(Opcodes.DRETURN));
                        break;
                    default:
                        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                        mn.instructions.add(new InsnNode(Opcodes.ARETURN));
                }

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                if (!LokiUtil.FOUND_ALI && (className.startsWith("org/to2mbn/authlibinjector") ||
                        className.startsWith("moe/yushi/authlibinjector"))) {
                    LokiUtil.hijackALIAgentArgs();
                }
                changed = true;
            }

            if (!changed) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform Authlib-Injector!", t);
            return null;
        }
    }
}
