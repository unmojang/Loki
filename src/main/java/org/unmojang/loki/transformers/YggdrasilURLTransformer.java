package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;
import org.unmojang.loki.RequestInterceptor;

import java.util.Map;

public class YggdrasilURLTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return !className.startsWith("org/unmojang/loki/"); // let's not patch ourselves
    }

    protected int writerFlags(String className) {
        return ClassWriter.COMPUTE_MAXS;
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;
        Map<String, String> ygMap = RequestInterceptor.YGGDRASIL_MAP;

        for (MethodNode mn : cn.methods) {

            AbstractInsnNode insn = mn.instructions.getFirst();
            while (insn != null) {
                AbstractInsnNode nextInsn = insn.getNext();

                if (insn instanceof LdcInsnNode) {
                    LdcInsnNode ldc = (LdcInsnNode) insn;
                    if (ldc.cst instanceof String && ((String) ldc.cst).startsWith("https://")) {
                        String s = (String) ldc.cst;
                        for (String domain : ygMap.keySet()) {
                            String prefix = "https://" + domain;
                            if (s.startsWith(prefix)) {
                                ldc.cst = LokiUtil.normalizeUrl(ygMap.get(domain)) + s.substring(prefix.length());
                                Loki.log.debug("Patching static Yggdrasil URL in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                                changed = true;
                                break;
                            }
                        }
                    }
                }

                if (insn instanceof InvokeDynamicInsnNode) {
                    InvokeDynamicInsnNode idn = (InvokeDynamicInsnNode) insn;
                    if (idn.bsmArgs != null && idn.bsmArgs.length > 0 && idn.bsmArgs[0] instanceof String) {
                        String recipe = (String) idn.bsmArgs[0];
                        for (String domain : ygMap.keySet()) {
                            String prefix = "https://" + domain;
                            if (recipe.contains(prefix)) {
                                idn.bsmArgs[0] = recipe.replace(prefix, LokiUtil.normalizeUrl(ygMap.get(domain)));
                                Loki.log.debug("Patching dynamic Yggdrasil URL in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                                changed = true;
                                break;
                            }
                        }
                    }
                }

                insn = nextInsn;
            }
        }

        return changed;
    }
}
