package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

@SuppressWarnings({"CommentedOutCode", "GrazieInspection"})
public class MCAuthlibGameProfileTransformer implements ClassFileTransformer {
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

                Field pubKeyField = GameProfile.class.getDeclaredField("SIGNATURE_KEY");
                pubKeyField.setAccessible(true);
                unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                unsafe = (sun.misc.Unsafe) unsafeField.get(null);
                staticBase = unsafe.staticFieldBase(pubKeyField);
                staticOffset = unsafe.staticFieldOffset(pubKeyField);
                unsafe.putObject(staticBase, staticOffset, publicKey);
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

        if (!"com/github/steveice10/mc/auth/data/GameProfile".equals(className)) return null;

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

                    insns.add(new LdcInsnNode("minecraft.api.services.host"));
                    insns.add(new LdcInsnNode("https://api.minecraftservices.com"));
                    insns.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "java/lang/System",
                            "getProperty",
                            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                            false));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 1)); // baseUrl (slot 1)

                    insns.add(new TypeInsnNode(Opcodes.NEW, "java/net/URL"));
                    insns.add(new InsnNode(Opcodes.DUP));
                    insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
                    insns.add(new InsnNode(Opcodes.DUP));
                    insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 1)); // baseUrl
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                    insns.add(new LdcInsnNode("/publickeys"));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
                    insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/net/URL", "<init>", "(Ljava/lang/String;)V", false));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 2)); // url (slot 2)

                    insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/URL", "openConnection", "()Ljava/net/URLConnection;", false));
                    insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/net/HttpURLConnection"));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 3)); // conn

                    insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
                    insns.add(new LdcInsnNode("GET"));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/HttpURLConnection", "setRequestMethod", "(Ljava/lang/String;)V", false));

                    insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
                    insns.add(new InsnNode(Opcodes.ICONST_1));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/HttpURLConnection", "setDoInput", "(Z)V", false));

                    insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/HttpURLConnection", "getInputStream", "()Ljava/io/InputStream;", false));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 4)); // is

                    insns.add(new TypeInsnNode(Opcodes.NEW, "java/io/ByteArrayOutputStream"));
                    insns.add(new InsnNode(Opcodes.DUP));
                    insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/io/ByteArrayOutputStream", "<init>", "()V", false));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 9)); // buffer

                    insns.add(new IntInsnNode(Opcodes.SIPUSH, 8192));
                    insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 8)); // data ([B)

                    LabelNode loopStart = new LabelNode();
                    LabelNode loopEnd = new LabelNode();
                    insns.add(loopStart);

                    insns.add(new VarInsnNode(Opcodes.ALOAD, 4));          // InputStream is
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 8));          // byte[] data
                    insns.add(new InsnNode(Opcodes.ICONST_0));             // 0
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 8));          // data
                    insns.add(new InsnNode(Opcodes.ARRAYLENGTH));         // data.length
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/InputStream", "read", "([BII)I", false));
                    insns.add(new InsnNode(Opcodes.DUP));
                    insns.add(new VarInsnNode(Opcodes.ISTORE, 5));         // nRead (int slot 5)
                    insns.add(new InsnNode(Opcodes.ICONST_M1));
                    insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, loopEnd));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 9));          // buffer
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 8));          // data
                    insns.add(new InsnNode(Opcodes.ICONST_0));
                    insns.add(new VarInsnNode(Opcodes.ILOAD, 5));          // nRead
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/ByteArrayOutputStream", "write", "([BII)V", false));
                    insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
                    insns.add(loopEnd);

                    insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
                    insns.add(new InsnNode(Opcodes.DUP));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 9));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/ByteArrayOutputStream", "toByteArray", "()[B", false));
                    insns.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8", "Ljava/nio/charset/Charset;"));
                    insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V", false));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 6)); // jsonText

                    insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
                    insns.add(new LdcInsnNode("\\s+"));
                    insns.add(new LdcInsnNode(""));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "replaceAll", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 6));

                    insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
                    insns.add(new LdcInsnNode("\"profilePropertyKeys\":[{\"publicKey\":\""));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;)I", false));
                    insns.add(new VarInsnNode(Opcodes.ISTORE, 5)); // idx

                    insns.add(new VarInsnNode(Opcodes.ILOAD, 5));
                    insns.add(new LdcInsnNode("\"profilePropertyKeys\":[{\"publicKey\":\""));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false));
                    insns.add(new InsnNode(Opcodes.IADD));
                    insns.add(new VarInsnNode(Opcodes.ISTORE, 5)); // start

                    insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
                    insns.add(new LdcInsnNode("\""));
                    insns.add(new VarInsnNode(Opcodes.ILOAD, 5));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;I)I", false));
                    insns.add(new VarInsnNode(Opcodes.ISTORE, 8)); // NOTE: store as int in slot 8

                    insns.add(new VarInsnNode(Opcodes.ALOAD, 6));
                    insns.add(new VarInsnNode(Opcodes.ILOAD, 5));
                    insns.add(new VarInsnNode(Opcodes.ILOAD, 8));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 7)); // keyB64

                    insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Base64", "getDecoder", "()Ljava/util/Base64$Decoder;", false));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 7));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Base64$Decoder", "decode", "(Ljava/lang/String;)[B", false));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 8)); // decoded bytes ([B) Opcodes.IADDslot 8 reused for byte[]; ok because previous int stored elsewhere

                    insns.add(new TypeInsnNode(Opcodes.NEW, "java/security/spec/X509EncodedKeySpec"));
                    insns.add(new InsnNode(Opcodes.DUP));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 8));
                    insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/security/spec/X509EncodedKeySpec", "<init>", "([B)V", false));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 9)); // spec

                    insns.add(new LdcInsnNode("RSA"));
                    insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/security/KeyFactory", "getInstance", "(Ljava/lang/String;)Ljava/security/KeyFactory;", false));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 10)); // keyFactory

                    insns.add(new VarInsnNode(Opcodes.ALOAD, 10));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 9));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/security/KeyFactory", "generatePublic", "(Ljava/security/spec/KeySpec;)Ljava/security/PublicKey;", false));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 11)); // publicKey

                    insns.add(new LdcInsnNode(Type.getType("Lcom/github/steveice10/mc/auth/data/GameProfile;")));
                    insns.add(new LdcInsnNode("SIGNATURE_KEY"));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 12)); // pubKeyField

                    insns.add(new VarInsnNode(Opcodes.ALOAD, 12));
                    insns.add(new InsnNode(Opcodes.ICONST_1));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "setAccessible", "(Z)V", false));

                    insns.add(new LdcInsnNode(Type.getType("Lsun/misc/Unsafe;")));
                    insns.add(new LdcInsnNode("theUnsafe"));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 13)); // unsafeField

                    insns.add(new VarInsnNode(Opcodes.ALOAD, 13));
                    insns.add(new InsnNode(Opcodes.ICONST_1));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "setAccessible", "(Z)V", false));

                    insns.add(new VarInsnNode(Opcodes.ALOAD, 13));
                    insns.add(new InsnNode(Opcodes.ACONST_NULL));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
                    insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "sun/misc/Unsafe"));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 14)); // unsafe

                    insns.add(new VarInsnNode(Opcodes.ALOAD, 14));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 12));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "sun/misc/Unsafe", "staticFieldBase", "(Ljava/lang/reflect/Field;)Ljava/lang/Object;", false));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, 15)); // staticBase

                    insns.add(new VarInsnNode(Opcodes.ALOAD, 14));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 12));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "sun/misc/Unsafe", "staticFieldOffset", "(Ljava/lang/reflect/Field;)J", false));
                    insns.add(new VarInsnNode(Opcodes.LSTORE, 16)); // staticOffset (long -> slots 16 & 17)

                    insns.add(new VarInsnNode(Opcodes.ALOAD, 14));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 15));
                    insns.add(new VarInsnNode(Opcodes.LLOAD, 16));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 11));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "sun/misc/Unsafe", "putObject", "(Ljava/lang/Object;JLjava/lang/Object;)V", false));

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
