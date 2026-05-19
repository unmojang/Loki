package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;
import org.unmojang.loki.RequestInterceptor;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class SignalingServiceClientTransformer implements ClassFileTransformer {

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (!"net/minecraft/client/multiplayer/p2p/client/SignalingServiceClient$Environment".equals(className)) return null;

        String signalingHost = RequestInterceptor.YGGDRASIL_MAP.get("signaling-afd.franchise.minecraft-services.net");
        if (signalingHost == null || signalingHost.length() == 0) return null;

        try {
            ClassNode cn = new ClassNode();
            new ClassReader(classfileBuffer).accept(cn, 0);

            boolean changed = false;
            for (MethodNode mn : cn.methods) {
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode) insn;
                        if ("https://signaling-afd.franchise.minecraft-services.net".equals(ldc.cst)) {
                            ldc.cst = LokiUtil.normalizeUrl(signalingHost);
                            Loki.log.debug("Patching signaling service URL in " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                            changed = true;
                        }
                    }
                }
            }

            if (!changed) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Throwable t) {
            Loki.log.error("Failed to transform SignalingServiceClient!", t);
            return null;
        }
    }
}
