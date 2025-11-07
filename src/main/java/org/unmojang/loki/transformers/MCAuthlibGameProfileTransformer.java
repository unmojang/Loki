package org.unmojang.loki.transformers;

import nilloader.api.lib.asm.Type;
import nilloader.api.lib.asm.tree.LabelNode;
import nilloader.api.lib.mini.MiniTransformer;
import nilloader.api.lib.mini.PatchContext;
import nilloader.api.lib.mini.annotation.Patch;
import org.unmojang.loki.Premain;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Patch.Class("com.github.steveice10.mc.auth.data.GameProfile")
public class MCAuthlibGameProfileTransformer extends MiniTransformer {
    @Patch.Method("<clinit>()V")
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
        Premain.log.info("Applying MCAuthLib SIGNATURE_KEY replacement");
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

        // pubKeyField = GameProfile.class.getDeclaredField("SIGNATURE_KEY")
        ctx.add(LDC(Type.getType("Lcom/github/steveice10/mc/auth/data/GameProfile;"))); // GameProfile.class
        ctx.add(LDC("SIGNATURE_KEY"));
        ctx.add(INVOKEVIRTUAL("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"));
        ctx.add(ASTORE(12)); // pubKeyField

        // pubKeyField.setAccessible(true)
        ctx.add(ALOAD(12));
        ctx.add(ICONST_1());
        ctx.add(INVOKEVIRTUAL("java/lang/reflect/Field", "setAccessible", "(Z)V"));

        // unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe")
        ctx.add(LDC(Type.getType("Lsun/misc/Unsafe;"))); // Unsafe.class
        ctx.add(LDC("theUnsafe"));
        ctx.add(INVOKEVIRTUAL("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"));
        ctx.add(ASTORE(13)); // unsafeField

        // unsafeField.setAccessible(true)
        ctx.add(ALOAD(13));
        ctx.add(ICONST_1());
        ctx.add(INVOKEVIRTUAL("java/lang/reflect/Field", "setAccessible", "(Z)V"));

        // unsafe = (sun.misc.Unsafe) unsafeField.get(null)
        ctx.add(ALOAD(13));
        ctx.add(ACONST_NULL());
        ctx.add(INVOKEVIRTUAL("java/lang/reflect/Field", "get", "(Ljava/lang/Object;)Ljava/lang/Object;"));
        ctx.add(CHECKCAST("sun/misc/Unsafe"));
        ctx.add(ASTORE(14)); // unsafe

        // staticBase = unsafe.staticFieldBase(pubKeyField)
        ctx.add(ALOAD(14));        // unsafe
        ctx.add(ALOAD(12));        // pubKeyField
        ctx.add(INVOKEVIRTUAL("sun/misc/Unsafe", "staticFieldBase", "(Ljava/lang/reflect/Field;)Ljava/lang/Object;"));
        ctx.add(ASTORE(15));       // staticBase

        // staticOffset = unsafe.staticFieldOffset(pubKeyField)
        ctx.add(ALOAD(14));        // unsafe
        ctx.add(ALOAD(12));        // pubKeyField
        ctx.add(INVOKEVIRTUAL("sun/misc/Unsafe", "staticFieldOffset", "(Ljava/lang/reflect/Field;)J"));
        ctx.add(LSTORE(16));       // staticOffset (long -> slots 16 & 17)

        // unsafe.putObject(staticBase, staticOffset, publicKey)
        ctx.add(ALOAD(14));        // unsafe
        ctx.add(ALOAD(15));        // staticBase
        ctx.add(LLOAD(16));        // staticOffset
        ctx.add(ALOAD(11));        // publicKey
        ctx.add(INVOKEVIRTUAL("sun/misc/Unsafe", "putObject", "(Ljava/lang/Object;JLjava/lang/Object;)V"));
    }
}
