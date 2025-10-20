package org.unmojang.loki.transformers;

import org.unmojang.loki.Premain;
import nilloader.api.lib.asm.tree.LabelNode;
import nilloader.api.lib.mini.MiniTransformer;
import nilloader.api.lib.mini.PatchContext;
import nilloader.api.lib.mini.annotation.Patch;

@Patch.Class("com.mojang.authlib.yggdrasil.YggdrasilServicesKeyInfo")
public class ServicesKeyInfoTransformer extends MiniTransformer {
    @Patch.Method("<init>(Ljava/security/PublicKey;)V")
    @Patch.Method.AffectsControlFlow
    @Patch.Method.Optional
    public void replaceKey(PatchContext ctx) {
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
                jsonText.indexOf("\"profilePropertyKeys\":[{\"publicKey\":\"");
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
        Premain.log.info("Applying 1.19-1.19.2 publicKey replacement");
        ctx.jumpToLastReturn();

        // baseUrl = System.getProperty("minecraft.api.services.host", "https://api.minecraftservices.com")
        ctx.add(LDC("minecraft.api.services.host"));
        ctx.add(LDC("https://api.minecraftservices.com"));
        ctx.add(INVOKESTATIC("java/lang/System", "getProperty",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"));
        ctx.add(ASTORE(1));

        // url = new URL(baseUrl + "/publickeys")
        ctx.add(NEW("java/net/URL"));
        ctx.add(DUP());
        ctx.add(NEW("java/lang/StringBuilder"));
        ctx.add(DUP());
        ctx.add(INVOKESPECIAL("java/lang/StringBuilder", "<init>", "()V"));
        ctx.add(ALOAD(1));
        ctx.add(INVOKEVIRTUAL("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;"));
        ctx.add(LDC("/publickeys"));
        ctx.add(INVOKEVIRTUAL("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;"));
        ctx.add(INVOKEVIRTUAL("java/lang/StringBuilder", "toString", "()Ljava/lang/String;"));
        ctx.add(INVOKESPECIAL("java/net/URL", "<init>", "(Ljava/lang/String;)V"));
        ctx.add(ASTORE(2));

        // conn = (HttpURLConnection) url.openConnection()
        ctx.add(ALOAD(2));
        ctx.add(INVOKEVIRTUAL("java/net/URL", "openConnection", "()Ljava/net/URLConnection;"));
        ctx.add(CHECKCAST("java/net/HttpURLConnection"));
        ctx.add(ASTORE(3));

        // conn.setRequestMethod("GET")
        ctx.add(ALOAD(3));
        ctx.add(LDC("GET"));
        ctx.add(INVOKEVIRTUAL("java/net/HttpURLConnection", "setRequestMethod", "(Ljava/lang/String;)V"));

        // conn.setDoInput(true)
        ctx.add(ALOAD(3));
        ctx.add(ICONST_1());
        ctx.add(INVOKEVIRTUAL("java/net/HttpURLConnection", "setDoInput", "(Z)V"));

        // is = conn.getInputStream()
        ctx.add(ALOAD(3));
        ctx.add(INVOKEVIRTUAL("java/net/HttpURLConnection", "getInputStream", "()Ljava/io/InputStream;"));
        ctx.add(ASTORE(4));

        // buffer = new ByteArrayOutputStream()
        ctx.add(NEW("java/io/ByteArrayOutputStream"));
        ctx.add(DUP());
        ctx.add(INVOKESPECIAL("java/io/ByteArrayOutputStream", "<init>", "()V"));
        ctx.add(ASTORE(9));

        // data = new byte[8192]
        ctx.add(SIPUSH(8192));
        ctx.add(NEWARRAY(T_BYTE));
        ctx.add(ASTORE(8));

        // read loop
        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        ctx.add(loopStart);
        ctx.add(ALOAD(4)); // is
        ctx.add(ALOAD(8)); // data
        ctx.add(ICONST_0());
        ctx.add(ALOAD(8));
        ctx.add(ARRAYLENGTH());
        ctx.add(INVOKEVIRTUAL("java/io/InputStream", "read", "([BII)I"));
        ctx.add(DUP());
        ctx.add(ISTORE(5)); // nRead
        ctx.add(ICONST_M1());
        ctx.add(IF_ICMPEQ(loopEnd));
        ctx.add(ALOAD(9)); // buffer
        ctx.add(ALOAD(8)); // data
        ctx.add(ICONST_0());
        ctx.add(ILOAD(5));
        ctx.add(INVOKEVIRTUAL("java/io/ByteArrayOutputStream", "write", "([BII)V"));
        ctx.add(GOTO(loopStart));
        ctx.add(loopEnd);

        // jsonText = new String(buffer.toByteArray(), StandardCharsets.UTF_8)
        ctx.add(NEW("java/lang/String"));
        ctx.add(DUP());
        ctx.add(ALOAD(9));
        ctx.add(INVOKEVIRTUAL("java/io/ByteArrayOutputStream", "toByteArray", "()[B"));
        ctx.add(GETSTATIC("java/nio/charset/StandardCharsets", "UTF_8", "Ljava/nio/charset/Charset;"));
        ctx.add(INVOKESPECIAL("java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V"));
        ctx.add(ASTORE(6));

        // jsonText = jsonText.replaceAll("\\s+", "")
        ctx.add(ALOAD(6));
        ctx.add(LDC("\\s+"));
        ctx.add(LDC(""));
        ctx.add(INVOKEVIRTUAL("java/lang/String", "replaceAll", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"));
        ctx.add(ASTORE(6));

        // idx = jsonText.indexOf("\"profilePropertyKeys\":[{\"publicKey\":\"")
        ctx.add(ALOAD(6));
        ctx.add(LDC("\"profilePropertyKeys\":[{\"publicKey\":\""));
        ctx.add(INVOKEVIRTUAL("java/lang/String", "indexOf", "(Ljava/lang/String;)I"));
        ctx.add(ISTORE(5));

        // start = idx + "\"profilePropertyKeys\":[{\"publicKey\":\"".length()
        ctx.add(ILOAD(5));
        ctx.add(LDC("\"profilePropertyKeys\":[{\"publicKey\":\""));
        ctx.add(INVOKEVIRTUAL("java/lang/String", "length", "()I"));
        ctx.add(IADD());
        ctx.add(ISTORE(5));

        // end = jsonText.indexOf("\"", start)
        ctx.add(ALOAD(6));
        ctx.add(LDC("\""));
        ctx.add(ILOAD(5));
        ctx.add(INVOKEVIRTUAL("java/lang/String", "indexOf", "(Ljava/lang/String;I)I"));
        ctx.add(ISTORE(8));

        // keyB64 = jsonText.substring(start, end)
        ctx.add(ALOAD(6));
        ctx.add(ILOAD(5));
        ctx.add(ILOAD(8));
        ctx.add(INVOKEVIRTUAL("java/lang/String", "substring", "(II)Ljava/lang/String;"));
        ctx.add(ASTORE(7));

        // decode keyB64 -> decoded
        ctx.add(INVOKESTATIC("java/util/Base64", "getDecoder", "()Ljava/util/Base64$Decoder;"));
        ctx.add(ALOAD(7));
        ctx.add(INVOKEVIRTUAL("java/util/Base64$Decoder", "decode", "(Ljava/lang/String;)[B"));
        ctx.add(ASTORE(8));

        // spec = new X509EncodedKeySpec(decoded)
        ctx.add(NEW("java/security/spec/X509EncodedKeySpec"));
        ctx.add(DUP());
        ctx.add(ALOAD(8));
        ctx.add(INVOKESPECIAL("java/security/spec/X509EncodedKeySpec", "<init>", "([B)V"));
        ctx.add(ASTORE(9));

        // keyFactory = KeyFactory.getInstance("RSA")
        ctx.add(LDC("RSA"));
        ctx.add(INVOKESTATIC("java/security/KeyFactory", "getInstance", "(Ljava/lang/String;)Ljava/security/KeyFactory;"));
        ctx.add(ASTORE(10));

        // publicKey = keyFactory.generatePublic(spec)
        ctx.add(ALOAD(10));
        ctx.add(ALOAD(9));
        ctx.add(INVOKEVIRTUAL("java/security/KeyFactory", "generatePublic", "(Ljava/security/spec/KeySpec;)Ljava/security/PublicKey;"));
        ctx.add(ASTORE(11));

        // pubKeyField = this.getClass().getDeclaredField("publicKey")
        ctx.add(ALOAD(0));
        ctx.add(INVOKEVIRTUAL("java/lang/Object", "getClass", "()Ljava/lang/Class;"));
        ctx.add(LDC("publicKey"));
        ctx.add(INVOKEVIRTUAL("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"));
        ctx.add(ASTORE(12));

        // pubKeyField.setAccessible(true)
        ctx.add(ALOAD(12));
        ctx.add(ICONST_1());
        ctx.add(INVOKEVIRTUAL("java/lang/reflect/Field", "setAccessible", "(Z)V"));

        // pubKeyField.set(this, publicKey)
        ctx.add(ALOAD(12));
        ctx.add(ALOAD(0));
        ctx.add(ALOAD(11));
        ctx.add(INVOKEVIRTUAL("java/lang/reflect/Field", "set", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
    }

    @Patch.Method("validateProperty(Lcom/mojang/authlib/properties/Property;)Z")
    @Patch.Method.AffectsControlFlow
    @Patch.Method.Optional
    public void patchValidateProperty(PatchContext ctx) {
        Premain.log.info("Setting validateProperty to true in YggdrasilServicesKeyInfo");
        ctx.jumpToStart();   // HEAD
        ctx.add(ICONST_1()); // push 1 (true)
        ctx.add(IRETURN());  // return it
    }
}
