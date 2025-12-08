package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

@SuppressWarnings({"CommentedOutCode", "GrazieInspection"})
public class ServicesKeyInfoTransformer implements ClassFileTransformer {
    /*
        public void replaceKey() {
            try {
                String baseUrl = System.getProperty("minecraft.api.services.host", "https://api.minecraftservices.com");
                URL url = new URL(baseUrl + "/publickeys");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setDoInput(true);

                String jsonText;
                try (InputStream is = conn.getInputStream()) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] data = new byte[8192];
                    int nRead;
                    while ((nRead = is.read(data, 0, data.length)) != -1) {
                        buffer.write(data, 0, nRead);
                    }
                    jsonText = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
                }

                jsonText = jsonText.replaceAll("\\s+", "");

                // Expecting: "profilePropertyKeys":[{"publicKey":"..."}]
                int start = jsonText.indexOf("\"profilePropertyKeys\":[{\"publicKey\":\"");
                if (start == -1) throw new IllegalStateException("publicKey not found in response");
                start += "\"profilePropertyKeys\":[{\"publicKey\":\"".length();
                int end = jsonText.indexOf("\"", start);
                if (end == -1) throw new IllegalStateException("publicKey value not terminated");
                String base64Key = jsonText.substring(start, end);

                // Convert Base64 -> PublicKey
                byte[] keyBytes = Base64.getDecoder().decode(base64Key);
                X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                PublicKey publicKey = keyFactory.generatePublic(spec);

                Field pubKeyField = this.getClass().getDeclaredField("publicKey");
                pubKeyField.setAccessible(true);
                pubKeyField.set(this.getClass(), publicKey);

            } catch (Exception e) {
                throw new AssertionError("Failed to fetch services key!", e);
            }
        }
     */

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {

        if (!"com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo".equals(className)) return null;

        try {
            ClassNode cn = new ClassNode();
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(cn, 0);

            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                if ("<init>".equals(mn.name) && "(Ljava/security/PublicKey;)V".equals(mn.desc)) {
                    AbstractInsnNode ret = null;
                    for (AbstractInsnNode insn : mn.instructions.toArray()) {
                        if (insn.getOpcode() == Opcodes.RETURN) {
                            ret = insn;
                        }
                    }
                    if (ret == null) throw new RuntimeException("could not find RETURN");

                    InsnList insns = new InsnList();

                    // baseUrl = System.getProperty("minecraft.api.services.host", "https://api.minecraftservices.com")
                    insns.add(new LdcInsnNode("minecraft.api.services.host"));
                    insns.add(new LdcInsnNode("https://api.minecraftservices.com"));
                    insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty",
                            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 1));

                    // url = new URL(baseUrl + "/publickeys")
                    insns.add(new TypeInsnNode(Opcodes.NEW, "java/net/URL"));
                    insns.add(new InsnNode(Opcodes.DUP));
                    insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
                    insns.add(new InsnNode(Opcodes.DUP));
                    insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V"));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;"));
                    insns.add(new LdcInsnNode("/publickeys"));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;"));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;"));
                    insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/net/URL", "<init>", "(Ljava/lang/String;)V"));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 2));

                    // conn = (HttpURLConnection) url.openConnection()
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/URL", "openConnection", "()Ljava/net/URLConnection;"));
                    insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/net/HttpURLConnection"));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 3));

                    // conn.setRequestMethod("GET")
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
                    insns.add(new LdcInsnNode("GET"));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/HttpURLConnection", "setRequestMethod", "(Ljava/lang/String;)V"));

                    // conn.setDoInput(true)
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
                    insns.add(new InsnNode(Opcodes.ICONST_1));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/HttpURLConnection", "setDoInput", "(Z)V"));

                    // is = conn.getInputStream()
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/HttpURLConnection", "getInputStream", "()Ljava/io/InputStream;"));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 4));

                    // buffer = new ByteArrayOutputStream()
                    insns.add(new TypeInsnNode(Opcodes.NEW, "java/io/ByteArrayOutputStream"));
                    insns.add(new InsnNode(Opcodes.DUP));
                    insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/io/ByteArrayOutputStream", "<init>", "()V"));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 9));

                    // data = new byte[8192]
                    insns.add(new IntInsnNode(Opcodes.SIPUSH, 8192));
                    insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 8));

                    // read loop
                    LabelNode loopStart = new LabelNode();
                    LabelNode loopEnd = new LabelNode();
                    insns.add(loopStart);

                    insns.add(new VarInsnNode(Opcodes.ALOAD, 4)); // is
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 8)); // data
                    insns.add(new InsnNode(Opcodes.ICONST_0));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 8));
                    insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/InputStream", "read", "([BII)I"));
                    insns.add(new InsnNode(Opcodes.DUP));
                    insns.add(new VarInsnNode(Opcodes.ISTORE, 5)); // nRead
                    insns.add(new InsnNode(Opcodes.ICONST_M1));
                    insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, loopEnd));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 9)); // buffer
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 8)); // data
                    insns.add(new InsnNode(Opcodes.ICONST_0));
                    insns.add(new VarInsnNode(Opcodes.ILOAD, 5));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/ByteArrayOutputStream", "write", "([BII)V"));
                    insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
                    insns.add(loopEnd);

                    // jsonText = new String(buffer.toByteArray(), StandardCharsets.UTF_8)
                    insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
                    insns.add(new InsnNode(Opcodes.DUP));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 9));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/ByteArrayOutputStream", "toByteArray", "()[B"));
                    insns.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8", "Ljava/nio/charset/Charset;"));
                    insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V"));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 6));

                    // jsonText = jsonText.replaceAll("\\s+", "")
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
                    insns.add(new LdcInsnNode("\\s+"));
                    insns.add(new LdcInsnNode(""));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "replaceAll", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 6));

                    // idx = jsonText.indexOf("\"profilePropertyKeys\":[{\"publicKey\":\"")
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
                    insns.add(new LdcInsnNode("\"profilePropertyKeys\":[{\"publicKey\":\""));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;)I"));
                    insns.add(new VarInsnNode(Opcodes.ISTORE, 5));

                    // start = idx + "\"profilePropertyKeys\":[{\"publicKey\":\"".length()
                    insns.add(new VarInsnNode(Opcodes.ILOAD, 5));
                    insns.add(new LdcInsnNode("\"profilePropertyKeys\":[{\"publicKey\":\""));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I"));
                    insns.add(new InsnNode(Opcodes.IADD));
                    insns.add(new VarInsnNode(Opcodes.ISTORE, 5));

                    // end = jsonText.indexOf("\"", start)
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
                    insns.add(new LdcInsnNode("\""));
                    insns.add(new VarInsnNode(Opcodes.ILOAD, 5));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;I)I"));
                    insns.add(new VarInsnNode(Opcodes.ISTORE, 8));

                    // keyB64 = jsonText.substring(start, end)
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
                    insns.add(new VarInsnNode(Opcodes.ILOAD, 5));
                    insns.add(new VarInsnNode(Opcodes.ILOAD, 8));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;"));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 7));

                    // decode keyB64 -> decoded
                    insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Base64", "getDecoder", "()Ljava/util/Base64$Decoder;"));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 7));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Base64$Decoder", "decode", "(Ljava/lang/String;)[B"));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 8));

                    // spec = new X509EncodedKeySpec(decoded)
                    insns.add(new TypeInsnNode(Opcodes.NEW, "java/security/spec/X509EncodedKeySpec"));
                    insns.add(new InsnNode(Opcodes.DUP));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 8));
                    insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/security/spec/X509EncodedKeySpec", "<init>", "([B)V"));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 9));

                    // keyFactory = KeyFactory.getInstance("RSA")
                    insns.add(new LdcInsnNode("RSA"));
                    insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/security/KeyFactory", "getInstance", "(Ljava/lang/String;)Ljava/security/KeyFactory;"));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 10));

                    // publicKey = keyFactory.generatePublic(spec)
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 10));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 9));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/security/KeyFactory", "generatePublic", "(Ljava/security/spec/KeySpec;)Ljava/security/PublicKey;"));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 11));

                    // pubKeyField = this.getClass().getDeclaredField("publicKey")
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;"));
                    insns.add(new LdcInsnNode("publicKey"));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 12));

                    // pubKeyField.setAccessible(true)
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 12));
                    insns.add(new InsnNode(Opcodes.ICONST_1));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "setAccessible", "(Z)V"));

                    // pubKeyField.set(this, publicKey)
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 12));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 11));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "set", "(Ljava/lang/Object;Ljava/lang/Object;)V"));

                    mn.instructions.insertBefore(ret, insns);

                    Loki.log.debug("Patching " + mn.name + " in " + className);
                    changed = true;
                } else if ("validateProperty".equals(mn.name) && "(Lcom/mojang/authlib/properties/Property;)Z".equals(mn.desc)) {
                    mn.instructions.clear();
                    mn.tryCatchBlocks.clear();
                    if (mn.localVariables != null) mn.localVariables.clear();

                    InsnList insns = new InsnList();
                    insns.add(new InsnNode(Opcodes.ICONST_1));
                    insns.add(new InsnNode(Opcodes.IRETURN));

                    mn.instructions.add(insns);

                    Loki.log.debug("Patching " + mn.name + " in " + className);
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
