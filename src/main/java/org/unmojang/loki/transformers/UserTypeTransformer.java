package org.unmojang.loki.transformers;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class UserTypeTransformer implements ClassFileTransformer {

    @Override
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

                LabelNode loopCheck = new LabelNode();
                LabelNode loopEnd = new LabelNode();

                // int i = 0;
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new VarInsnNode(Opcodes.ISTORE, 1)); // local var 1 = i

                insns.add(loopCheck);

                // if (i >= args.length) goto loopEnd
                insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
                insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
                insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));

                // if ("--userType".equals(args[i]))
                insns.add(new LdcInsnNode("--userType"));
                insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
                insns.add(new InsnNode(Opcodes.AALOAD));
                insns.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/String",
                        "equals",
                        "(Ljava/lang/Object;)Z",
                        false
                ));

                LabelNode continueLoop = new LabelNode();
                insns.add(new JumpInsnNode(Opcodes.IFEQ, continueLoop));

                // if (i+1 < args.length) continue
                insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
                insns.add(new InsnNode(Opcodes.ICONST_1));
                insns.add(new InsnNode(Opcodes.IADD));
                insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
                insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, continueLoop));

                // if (args[i+1].equals("mojang"))
                insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
                insns.add(new InsnNode(Opcodes.ICONST_1));
                insns.add(new InsnNode(Opcodes.IADD));
                insns.add(new InsnNode(Opcodes.AALOAD));
                insns.add(new LdcInsnNode("mojang"));
                insns.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/String",
                        "equals",
                        "(Ljava/lang/Object;)Z",
                        false
                ));

                LabelNode skipReplace = new LabelNode();
                insns.add(new JumpInsnNode(Opcodes.IFEQ, skipReplace));

                // args[i+1] = "msa"
                insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
                insns.add(new InsnNode(Opcodes.ICONST_1));
                insns.add(new InsnNode(Opcodes.IADD));
                insns.add(new LdcInsnNode("msa"));
                insns.add(new InsnNode(Opcodes.AASTORE));

                insns.add(skipReplace);
                insns.add(continueLoop);

                // i++
                insns.add(new IincInsnNode(1, 1));
                insns.add(new JumpInsnNode(Opcodes.GOTO, loopCheck));

                insns.add(loopEnd);

                mn.instructions.insert(insns);

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc) + " (userType)");
                changed = true;
            }

            if (!changed) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Throwable t) {
            Loki.log.error("Failed to transform userType!", t);
            return null;
        }
    }
}

