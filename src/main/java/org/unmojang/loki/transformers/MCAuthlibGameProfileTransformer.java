package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

// Used in MojangFix and Ears mods, possibly more
public class MCAuthlibGameProfileTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return className.endsWith("/data/GameProfile");
    }

    protected boolean patch(ClassNode cn, String className) {
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

        return changed;
    }
}
