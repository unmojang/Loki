package org.unmojang.loki.transformers;

import org.objectweb.asm.*;
import org.unmojang.loki.Loki;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;

public class InetAddressTransformer implements ClassFileTransformer {

    private static final List<String> TARGET_HOSTS = new ArrayList<>();

    static {
        if (!Loki.modded_capes) {
            TARGET_HOSTS.add("s.optifine.net");
            TARGET_HOSTS.add("161.35.130.99"); // Cloaks+
            TARGET_HOSTS.add("api.rumblecapes.xyz");
        }
        if (!Loki.enable_snooper) {
            TARGET_HOSTS.add("snoop.minecraft.net");
        }
        if (Loki.disable_realms) {
            TARGET_HOSTS.add("java.frontendlegacy.realms.minecraft-services.net");
            TARGET_HOSTS.add("pc.realms.minecraft.net");
        }
    }

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {

        if (!"java/net/InetAddress".equals(className)) return null;

        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassVisitor(ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                boolean hookGetByName = "getByName".equals(name) && "(Ljava/lang/String;)Ljava/net/InetAddress;".equals(descriptor);
                boolean hookGetAllByName = "getAllByName".equals(name) && "(Ljava/lang/String;)[Ljava/net/InetAddress;".equals(descriptor);
                boolean hookGetAllByName0_1 = "getAllByName0".equals(name) && "(Ljava/lang/String;Z)[Ljava/net/InetAddress;".equals(descriptor);
                boolean hookGetAllByName0_2 = "getAllByName0".equals(name) && "(Ljava/lang/String;ZZ)[Ljava/net/InetAddress;".equals(descriptor);

                if (hookGetByName || hookGetAllByName || hookGetAllByName0_1 || hookGetAllByName0_2) {

                    return new MethodVisitor(ASM9, mv) {
                        @Override
                        public void visitCode() {
                            Label continueLabel = new Label();

                            Loki.log.debug("Patching " + name + " in " + className);

                            for (String target : TARGET_HOSTS) {
                                mv.visitVarInsn(ALOAD, 0);
                                mv.visitLdcInsn(target);
                                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equalsIgnoreCase",
                                        "(Ljava/lang/String;)Z", false);
                                Label nextLabel = new Label();
                                mv.visitJumpInsn(IFEQ, nextLabel);

                                if (hookGetByName) {
                                    mv.visitLdcInsn("0.0.0.0");
                                    mv.visitMethodInsn(INVOKESTATIC, "java/net/InetAddress",
                                            "getByName", "(Ljava/lang/String;)Ljava/net/InetAddress;", false);
                                    mv.visitInsn(ARETURN);
                                } else {
                                    mv.visitInsn(ICONST_1);
                                    mv.visitTypeInsn(ANEWARRAY, "java/net/InetAddress");
                                    mv.visitInsn(DUP);
                                    mv.visitInsn(ICONST_0);
                                    mv.visitLdcInsn("0.0.0.0");
                                    mv.visitMethodInsn(INVOKESTATIC, "java/net/InetAddress",
                                            "getByName", "(Ljava/lang/String;)Ljava/net/InetAddress;", false);
                                    mv.visitInsn(AASTORE);
                                    mv.visitInsn(ARETURN);
                                }

                                mv.visitLabel(nextLabel);
                            }

                            mv.visitLabel(continueLabel);
                            super.visitCode();
                        }
                    };
                }

                return mv;
            }
        };

        cr.accept(cv, 0);
        return cw.toByteArray();
    }
}
