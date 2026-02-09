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

// Used in MojangFix and Ears mods, possibly more
public class MCAuthlibGameProfileTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (!className.endsWith("data/GameProfile") || LokiUtil.JAVA_MAJOR <= 5) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                if ("<clinit>".equals(mn.name) && "()V".equals(mn.desc)) {
                    AbstractInsnNode ret = null;
                    for (AbstractInsnNode insn : mn.instructions.toArray()) {
                        if (insn.getOpcode() == Opcodes.RETURN) {
                            ret = insn;
                        }
                    }
                    if (ret == null) throw new RuntimeException("could not find RETURN");

                    InsnList insns = new InsnList();
                    insns.add(new LdcInsnNode(Type.getType("L" + className + ";")));
                    insns.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "org/unmojang/loki/hooks/Hooks",
                            "replaceMCAuthlibGameProfileSignature",
                            "(Ljava/lang/Class;)V",
                            false
                    ));
                    mn.instructions.insertBefore(ret, insns);

                    Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                    changed = true;
                } else if ("validateProperty".equals(mn.name) && "(Lcom/mojang/authlib/properties/Property;)Z".equals(mn.desc)) {
                    mn.instructions.clear();
                    mn.tryCatchBlocks.clear();
                    if (mn.localVariables != null) mn.localVariables.clear();

                    InsnList insns = new InsnList();
                    insns.add(new InsnNode(Opcodes.ICONST_1));
                    insns.add(new InsnNode(Opcodes.IRETURN));

                    mn.instructions.add(insns);

                    Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                    changed = true;
                }
            }

            if (!changed) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform publicKey!", t);
            return null;
        }
    }
}
