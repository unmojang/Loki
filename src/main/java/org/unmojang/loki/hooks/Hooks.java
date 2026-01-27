package org.unmojang.loki.hooks;

import org.unmojang.loki.util.Base64;
import org.unmojang.loki.util.Json;
import org.unmojang.loki.util.logger.NilLogger;
import sun.misc.Unsafe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.*;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("unused")
public class Hooks {
    public static final Map<String, URLStreamHandler> DEFAULT_HANDLERS = new ConcurrentHashMap<String, URLStreamHandler>();
    private static final NilLogger log = NilLogger.get("Loki");
    public static String accessToken;

    // thanks yushijinhun!
    // https://github.com/yushijinhun/authlib-injector/blob/aff141877cccaec8c5ffe7a542efa139cc64bcde/src/main/java/moe/yushi/authlibinjector/transform/support/ConcatenateURLTransformUnit.java
    // https://github.com/yushijinhun/authlib-injector/issues/126
    public static URL concatenateURL(URL url, String query) {
        try {
            if (url.getQuery() != null && url.getQuery().length() != 0) {
                return new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile() + "&" + query);
            } else {
                return new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile() + "?" + query);
            }
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Could not concatenate given URL with GET arguments!", e);
        }
    }

    public static String[] transformMainArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (i + 1 > args.length) break;
            if ("--userType".equals(args[i]) && "mojang".equals(args[i + 1])) {
                args[i + 1] = "msa";
                log.info("Setting accountType to msa");
            }
            if ("--accessToken".equals(args[i])) {
                accessToken = args[i + 1];
            }
        }
        return args;
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

    public static void replaceMCAuthlibGameProfileSignature(Class<?> gameProfileClass) {
        try {
            PublicKey publicKey = getPublicKey();

            Field pubKeyField = gameProfileClass.getDeclaredField("SIGNATURE_KEY");
            pubKeyField.setAccessible(true);

            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) unsafeField.get(null);

            Object staticBase = unsafe.staticFieldBase(pubKeyField);
            long staticOffset = unsafe.staticFieldOffset(pubKeyField);
            unsafe.putObject(staticBase, staticOffset, publicKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch yggdrasil public key!", e);
        }
    }

    public static void replaceYggdrasilServicesKeyInfoSignature(Object target) {
        try {
            PublicKey publicKey = getPublicKey();

            Field pubKeyField = target.getClass().getDeclaredField("publicKey");
            pubKeyField.setAccessible(true);
            pubKeyField.set(target, publicKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch yggdrasil public key!", e);
        }
    }

    @SuppressWarnings("ExtractMethodRecommender")
    private static PublicKey getPublicKey() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        String baseUrl = System.getProperty("minecraft.api.services.host", "https://api.minecraftservices.com");
        URL url = new URL(baseUrl + "/publickeys");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setDoInput(true);

        String jsonText;
        InputStream is = null;
        try {
            is = conn.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[8192];
            int nRead;
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            jsonText = buffer.toString("UTF-8");
        } finally {
            if (is != null) is.close();
        }

        Json.JSONObject jsonObject = new Json.JSONObject(jsonText);
        Json.JSONArray profilePropertyKeys = jsonObject.getJSONArray("profilePropertyKeys");
        if (profilePropertyKeys == null || profilePropertyKeys.isEmpty()) {
            throw new IllegalStateException("profilePropertyKeys not found in response");
        }
        Object keyElement = profilePropertyKeys.getJSONObject(0).get("publicKey");
        if (keyElement == null) {
            throw new IllegalStateException("publicKey not found in response");
        }

        byte[] keyBytes = Base64.decode(keyElement.toString());
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePublic(spec);
    }

    public static synchronized void registerExternalFactory(URLStreamHandlerFactory factory) {
        if (factory == null) return;
        try {
            // Protocols that Loki needs to accept from external factories
            String[] protos = new String[] {"http", "https", "modjar"};
            for (String p : protos) {
                try {
                    URLStreamHandler h = factory.createURLStreamHandler(p);
                    if (h != null) {
                        DEFAULT_HANDLERS.put(p, h);
                        log.debug("Registered external handler for " + p + " from factory " + factory.getClass().getName());
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            log.error("registerExternalFactory failed!", t);
        }
    }
}
