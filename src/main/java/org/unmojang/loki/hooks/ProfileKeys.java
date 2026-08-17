package org.unmojang.loki.hooks;

import org.unmojang.loki.util.Base64;
import org.unmojang.loki.util.HttpUtil;
import org.unmojang.loki.util.Json;
import org.unmojang.loki.util.logger.NilLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SuppressWarnings("unused")
public final class ProfileKeys {
    // Where LokiUtil leaves the keys it found in authlib-injector metadata
    public static final String PROP_SIGNATURE_KEYS = "Loki.signature_keys";

    private static final String MOJANG_KEY_RESOURCE = "/yggdrasil_session_pubkey.der";
    private static final String ALGORITHM = "SHA1withRSA";
    private static final int HTTP_TIMEOUT_MS = 5000;
    private static final long REFRESH_INTERVAL_MS = 3600000L;
    private static final long RETRY_INTERVAL_MS = 60000L;

    private static final NilLogger log = NilLogger.get("Loki");

    private static volatile List<PublicKey> serverKeys = Collections.emptyList();
    private static volatile long refreshDue = 0L;
    private static volatile PublicKey mojangKey;
    private static volatile boolean mojangKeyResolved;

    private ProfileKeys() {}

    private static boolean notEnforcing() {
        return !Boolean.getBoolean("Loki.verify_signatures");
    }

    // A verifier for player certificates, for ServicesKeyInfo.signature()
    public static Signature certificateSignature(Object owner) {
        if (notEnforcing()) return Hooks.createDummySignature();
        List<PublicKey> keys = trusted(owner);
        if (keys.isEmpty()) {
            log.debug("No trusted certificate keys to check against, accepting signatures");
            return Hooks.createDummySignature();
        }
        return new MultiKeySignature(keys);
    }

    // BungeeCord's EncryptionUtil.check over a set; uuid is null for 1.19.0's format
    public static boolean isCertificateValid(Object playerPublicKey, Object uuid) {
        if (notEnforcing()) return true;
        try {
            long expiry = (Long) invoke(playerPublicKey, "getExpiry");
            byte[] declaredKey = (byte[]) invoke(playerPublicKey, "getKey");
            byte[] signature = (byte[]) invoke(playerPublicKey, "getSignature");
            if (declaredKey == null || signature == null) return false;

            byte[] encoded = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(declaredKey)).getEncoded();
            byte[] signed = uuid != null
                    ? certificatePayload(uuid, expiry, encoded)
                    : legacyCertificatePayload(expiry, encoded);

            List<PublicKey> keys = trusted(playerPublicKey);
            if (keys.isEmpty()) {
                log.debug("No trusted certificate keys to check against, accepting the certificate");
                return true;
            }
            for (PublicKey key : keys) {
                if (verify(key, signed, signature)) return true;
            }
            log.warn("Player key certificate matched none of the " + keys.size() + " trusted keys");
            return false;
        } catch (Throwable t) {
            log.error("Could not check a player key certificate", t);
            return false;
        }
    }

    // 1.19.1 and later: the UUID, the expiry and the key, big endian, back to back
    private static byte[] certificatePayload(Object uuid, long expiry, byte[] encoded) throws Exception {
        long most = (Long) invoke(uuid, "getMostSignificantBits");
        long least = (Long) invoke(uuid, "getLeastSignificantBits");
        ByteBuffer buffer = ByteBuffer.allocate(24 + encoded.length).order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(most).putLong(least).putLong(expiry).put(encoded);
        return buffer.array();
    }

    // 1.19.0: the expiry followed by the key as PEM, as ASCII
    private static byte[] legacyCertificatePayload(long expiry, byte[] encoded) throws Exception {
        String pem = expiry + "-----BEGIN RSA PUBLIC KEY-----\n"
                + Base64.encodeMime(encoded) + "\n-----END RSA PUBLIC KEY-----\n";
        return pem.getBytes("US-ASCII");
    }

    private static Object invoke(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    private static boolean verify(PublicKey key, byte[] signed, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(signed);
            return verifier.verify(signature);
        } catch (Exception e) {
            return false; // wrong key, wrong algorithm for this key, or a corrupt signature
        }
    }

    private static List<PublicKey> trusted(Object owner) {
        List<PublicKey> keys = new ArrayList<PublicKey>(refreshServerKeys());
        // Mojang's key is always trusted: fallback-proxied data carries its signature
        PublicKey mojang = mojangKey(owner);
        if (mojang != null) keys.add(mojang);
        return keys;
    }

    // A failed refresh keeps the previous set: a stale key beats none
    private static List<PublicKey> refreshServerKeys() {
        long now = System.currentTimeMillis();
        List<PublicKey> current = serverKeys;
        if (now < refreshDue) return current;

        List<PublicKey> fetched = fetchServerKeys();
        if (fetched.isEmpty() && !current.isEmpty()) {
            refreshDue = now + RETRY_INTERVAL_MS;
            log.debug("Keeping the previous signing keys, the API server did not answer");
            return current;
        }
        refreshDue = now + (fetched.isEmpty() ? RETRY_INTERVAL_MS : REFRESH_INTERVAL_MS);
        serverKeys = fetched;
        return fetched;
    }

    // https://github.com/yushijinhun/authlib-injector/pull/279 has not been merged, and even when it is older Drasl
    // versions will not support it, so the flow is /publickeys first with a fallback to authlib-injector metadata
    private static List<PublicKey> fetchServerKeys() {
        String base = System.getProperty("minecraft.api.services.host", "https://api.minecraftservices.com");
        try {
            Json.JSONObject published = new Json.JSONObject(readDocument(base + "/publickeys"));
            Json.JSONArray keys = published.optJSONArray("playerCertificateKeys");
            if (keys != null) {
                String[] encoded = new String[keys.length()];
                for (int i = 0; i < encoded.length; i++) {
                    encoded[i] = keys.getJSONObject(i).optString("publicKey", "");
                }
                List<PublicKey> fetched = parseAll(encoded, base + "/publickeys");
                if (!fetched.isEmpty()) return fetched;
            }
        } catch (Exception e) {
            log.debug("No signing keys at " + base + "/publickeys (" + e + ")");
        }

        String declared = System.getProperty(PROP_SIGNATURE_KEYS, "");
        if (declared.length() != 0) {
            return parseAll(declared.split(","), "authlib-injector metadata");
        }
        return Collections.emptyList();
    }

    private static String readDocument(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(HTTP_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_TIMEOUT_MS);
        if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
        return HttpUtil.readStream(conn.getInputStream());
    }

    private static List<PublicKey> parseAll(String[] encoded, String source) {
        List<PublicKey> parsed = new ArrayList<PublicKey>();
        for (String s : encoded) {
            String trimmed = s == null ? "" : s.trim();
            if (trimmed.length() == 0) continue;
            try {
                byte[] der = Base64.decode(trimmed);
                parsed.add(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der)));
            } catch (Exception e) {
                log.warn("Ignoring an unreadable signing key");
            }
        }
        if (!parsed.isEmpty()) log.info("Trusting " + parsed.size() + " signing key(s) from " + source);
        return parsed;
    }

    // Read from authlib itself (bundled 1.5.6-3.18.38) so Loki cannot disagree with the game.
    private static PublicKey mojangKey(Object owner) {
        if (!mojangKeyResolved) {
            mojangKey = readMojangKey(owner);
            mojangKeyResolved = true;
        }
        return mojangKey;
    }

    private static PublicKey readMojangKey(Object owner) {
        if (owner == null) return null;
        InputStream in = owner.getClass().getResourceAsStream(MOJANG_KEY_RESOURCE);
        if (in == null) {
            log.debug("authlib does not bundle " + MOJANG_KEY_RESOURCE + ", no Mojang key to fall back on");
            return null;
        }
        try {
            byte[] der = HttpUtil.readAllBytes(in);
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            log.debug("Also trusting Mojang's own key, as bundled in authlib");
            return key;
        } catch (Exception e) {
            log.warn("Could not read Mojang's bundled key", e);
            return null;
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    // Buffers updates and replays them against each trusted key when verify is called
    private static final class MultiKeySignature extends Signature {
        private final List<PublicKey> keys;
        private ByteArrayOutputStream buffered = new ByteArrayOutputStream();

        MultiKeySignature(List<PublicKey> keys) {
            super(ALGORITHM);
            this.keys = keys;
            try {
                initVerify((PublicKey) null);
            } catch (Exception e) {
                throw new RuntimeException("Could not put a verifier into the verify state", e);
            }
        }

        protected void engineInitVerify(PublicKey ignored) {
            buffered = new ByteArrayOutputStream();
        }

        protected void engineUpdate(byte b) {
            buffered.write(b);
        }

        protected void engineUpdate(byte[] b, int off, int len) {
            buffered.write(b, off, len);
        }

        protected boolean engineVerify(byte[] signature) {
            byte[] signed = buffered.toByteArray();
            buffered = new ByteArrayOutputStream(); // a Signature resets after verifying
            for (PublicKey key : keys) {
                if (ProfileKeys.verify(key, signed, signature)) return true;
            }
            log.warn("Signature matched none of the " + keys.size() + " trusted certificate keys");
            return false;
        }

        protected void engineInitSign(PrivateKey privateKey) {
            throw new UnsupportedOperationException("Loki only verifies");
        }

        protected byte[] engineSign() {
            throw new UnsupportedOperationException("Loki only verifies");
        }

        @Deprecated
        protected void engineSetParameter(String param, Object value) {}

        @Deprecated
        protected Object engineGetParameter(String param) {
            return null;
        }
    }
}
