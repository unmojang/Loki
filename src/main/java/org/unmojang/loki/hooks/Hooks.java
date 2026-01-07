package org.unmojang.loki.hooks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@SuppressWarnings("unused")
public class Hooks {
    // thanks yushijinhun!
    // https://github.com/yushijinhun/authlib-injector/blob/aff141877cccaec8c5ffe7a542efa139cc64bcde/src/main/java/moe/yushi/authlibinjector/transform/support/ConcatenateURLTransformUnit.java
    // https://github.com/yushijinhun/authlib-injector/issues/126
    public static URL concatenateURL(URL url, String query) {
        try {
            if (url.getQuery() != null && !url.getQuery().isEmpty()) {
                return new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile() + "&" + query);
            } else {
                return new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile() + "?" + query);
            }
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Could not concatenate given URL with GET arguments!", e);
        }
    }

    // thanks yushijinhun!
    // https://github.com/yushijinhun/authlib-injector/blob/6425a2745264593da7e35896d12c6ea23638d679/src/main/java/moe/yushi/authlibinjector/transform/support/YggdrasilKeyTransformUnit.java#L116-L166
    public static Signature createDummySignature() {
        Signature sig = new Signature("dummy") {
            @Override
            protected boolean engineVerify(byte[] sigBytes) { return true; }
            @Override
            protected void engineUpdate(byte[] b, int off, int len) {}
            @Override
            protected void engineUpdate(byte b) {}
            @Override
            protected byte[] engineSign() { throw new UnsupportedOperationException(); }
            @Override @Deprecated
            protected void engineSetParameter(String param, Object value) {}
            @Override
            protected void engineInitVerify(PublicKey publicKey) {}
            @Override
            protected void engineInitSign(PrivateKey privateKey) { throw new UnsupportedOperationException(); }
            @Override @Deprecated
            protected Object engineGetParameter(String param) { return null; }
        };
        try { sig.initVerify((PublicKey)null); } catch (InvalidKeyException e) { throw new RuntimeException(e); }
        return sig;
    }

    public static void replaceKey(Object target) {
        try {
            String baseUrl = System.getProperty("minecraft.api.services.host", "https://api.minecraftservices.com");
            URL url = new URL(baseUrl + "/publickeys");
            String base64Key = getBase64Key(url);

            // Convert Base64 -> PublicKey
            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey publicKey = keyFactory.generatePublic(spec);

            Field pubKeyField = target.getClass().getDeclaredField("publicKey");
            pubKeyField.setAccessible(true);
            pubKeyField.set(target, publicKey);
        } catch (Exception e) {
            throw new AssertionError("Failed to fetch yggdrasil public key!", e);
        }
    }

    private static String getBase64Key(URL url) throws IOException {
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
        return jsonText.substring(start, end);
    }
}
