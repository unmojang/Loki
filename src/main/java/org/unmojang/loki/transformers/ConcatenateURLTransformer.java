package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;


public class ConcatenateURLTransformer implements ClassFileTransformer {
    /*
        thanks yushijinhun!
        https://github.com/yushijinhun/authlib-injector/blob/aff141877cccaec8c5ffe7a542efa139cc64bcde/src/main/java/moe/yushi/authlibinjector/transform/support/ConcatenateURLTransformUnit.java
        https://github.com/yushijinhun/authlib-injector/issues/126

        try {
            if (url.getQuery() != null && !url.getQuery().isEmpty()) {
                return new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile() + "&" + query);
            } else {
                return new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile() + "?" + query);
            }
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Could not concatenate given URL with GET arguments!", e);
        }
     */
    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {

        if (!"com/mojang/authlib/HttpAuthenticationService".equals(className)) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                if ("concatenateURL".equals(mn.name)
                        && "(Ljava/net/URL;Ljava/lang/String;)Ljava/net/URL;".equals(mn.desc)) {

                    AbstractInsnNode aret = null;
                    for (AbstractInsnNode insn : mn.instructions.toArray()) {
                        if (insn.getOpcode() == Opcodes.ARETURN) {
                            aret = insn;
                        }
                    }
                    if (aret == null) throw new RuntimeException("could not find ARETURN");

                    InsnList insns = new InsnList();

                    LabelNode EX_START_1 = new LabelNode();
                    LabelNode EX_END_1 = new LabelNode();
                    LabelNode EX_START_2 = new LabelNode();
                    LabelNode EX_END_2 = new LabelNode();
                    LabelNode EX_HANDLER_2 = new LabelNode();

                    // TRY region 1
                    insns.add(EX_START_1);

                    // if (url.getQuery() == null) goto EX_START_2
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/URL", "getQuery", "()Ljava/lang/String;", false));
                    insns.add(new JumpInsnNode(Opcodes.IFNULL, EX_START_2));

                    // if (url.getQuery().isEmpty()) goto EX_START_2
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/URL", "getQuery", "()Ljava/lang/String;", false));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false));
                    insns.add(new JumpInsnNode(Opcodes.IFNE, EX_START_2));

                    // construct new URL(..., file + "&" + query)
                    insns.add(new TypeInsnNode(Opcodes.NEW, "java/net/URL"));
                    insns.add(new InsnNode(Opcodes.DUP));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 0)); // url
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/URL", "getProtocol", "()Ljava/lang/String;", false));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/URL", "getHost", "()Ljava/lang/String;", false));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/URL", "getPort", "()I", false));

                    // file + "&" + query
                    insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
                    insns.add(new InsnNode(Opcodes.DUP));
                    insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/URL", "getFile", "()Ljava/lang/String;", false));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                    insns.add(new LdcInsnNode("&"));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 1)); // query param
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));

                    insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/net/URL", "<init>", "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V", false));

                    insns.add(EX_END_1);
                    insns.add(new InsnNode(Opcodes.ARETURN));

                    // TRY region 2
                    insns.add(EX_START_2);

                    // construct new URL(..., file + "?" + query)
                    insns.add(new TypeInsnNode(Opcodes.NEW, "java/net/URL"));
                    insns.add(new InsnNode(Opcodes.DUP));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/URL", "getProtocol", "()Ljava/lang/String;", false));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/URL", "getHost", "()Ljava/lang/String;", false));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/URL", "getPort", "()I", false));

                    // file + "?" + query
                    insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
                    insns.add(new InsnNode(Opcodes.DUP));
                    insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/URL", "getFile", "()Ljava/lang/String;", false));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                    insns.add(new LdcInsnNode("?"));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));

                    insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/net/URL", "<init>", "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V", false));

                    insns.add(EX_END_2);
                    insns.add(new InsnNode(Opcodes.ARETURN));

                    // Exception handler
                    insns.add(EX_HANDLER_2);
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 2));
                    insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalArgumentException"));
                    insns.add(new InsnNode(Opcodes.DUP));
                    insns.add(new LdcInsnNode("Could not concatenate given URL with GET arguments!"));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;Ljava/lang/Throwable;)V", false));
                    insns.add(new InsnNode(Opcodes.ATHROW));

                    // Register try/catch blocks
                    mn.tryCatchBlocks.add(new TryCatchBlockNode(EX_START_1, EX_END_1, EX_HANDLER_2, "java/net/MalformedURLException"));
                    mn.tryCatchBlocks.add(new TryCatchBlockNode(EX_START_2, EX_END_2, EX_HANDLER_2, "java/net/MalformedURLException"));

                    mn.instructions.insertBefore(aret, insns);

                    Loki.log.info("Patching " + mn.name + " in " + className);
                    changed = true;
                    break;
                }
            }

            if (!changed) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform concatenateURL!", t);
            return null;
        }
    }
}
