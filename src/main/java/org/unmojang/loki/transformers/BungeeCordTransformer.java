package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;
import java.util.Arrays;

public class BungeeCordTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return className.startsWith("net/md_5/bungee/");
    }

    protected int writerFlags(String className) {
        return ClassWriter.COMPUTE_MAXS;
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        if ("net/md_5/bungee/EncryptionUtil".equals(className)) {
            for (MethodNode mn : cn.methods) {
                if ("<clinit>".equals(mn.name)) {
                    AbstractInsnNode ret = null;
                    for (AbstractInsnNode insn : mn.instructions.toArray())
                        if (insn.getOpcode() == Opcodes.RETURN) ret = insn;
                    if (ret != null) {
                        InsnList patch = new InsnList();
                        patch.add(new LdcInsnNode(Type.getType("Lnet/md_5/bungee/EncryptionUtil;")));
                        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "org/unmojang/loki/hooks/Hooks",
                                "replaceBungeeCordMojangKey",
                                "(Ljava/lang/Class;)V",
                                false));
                        mn.instructions.insertBefore(ret, patch);
                        Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                        changed = true;
                    }
                    break;
                }
            }
        }

        for (MethodNode mn : cn.methods) {
            if (Arrays.asList("isValidName", "check").contains(mn.name)
                    && (mn.access & Opcodes.ACC_PUBLIC) != 0 && mn.desc.endsWith(")Z")) {
                if ("check".equals(mn.name) && Loki.enforce_secure_profile) continue;
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                mn.instructions.add(new InsnNode(Opcodes.ICONST_1));
                mn.instructions.add(new InsnNode(Opcodes.IRETURN));
                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
                break;
            }
        }

        return changed;
    }
}
