package org.unmojang.loki.hooks;

import org.unmojang.loki.util.Base64;
import org.unmojang.loki.util.Json;
import org.unmojang.loki.util.UuidBatcher;
import org.unmojang.loki.util.logger.NilLogger;
import sun.misc.Unsafe;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.*;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings({"unused", "CallToPrintStackTrace"})
public class Hooks {
    public static boolean OFFLINE_MODE = false;
    public static final Map<String, URLStreamHandler> DEFAULT_HANDLERS = new ConcurrentHashMap<String, URLStreamHandler>();

    private static final NilLogger log = NilLogger.get("Loki");
    private static final int UUID_CACHE_MAX = 256;
    private static final Map<String, String> nameToUUIDCache = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(16, 0.75f, true) {
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > UUID_CACHE_MAX;
                }
            });
    private static final ConcurrentHashMap<String, TextureEntry> uuidToTexturesCache = new ConcurrentHashMap<String, TextureEntry>();
    private static final long TEXTURE_CACHE_TTL_MS = 300000L; // 5 minutes
    private static volatile long textureRateLimitUntil = 0L;

    private static final ConcurrentHashMap<String, Long> negativeLookupCache = new ConcurrentHashMap<String, Long>();
    private static final long NEGATIVE_CACHE_TTL_MS = 60000L;
    private static final ConcurrentHashMap<String, Boolean> pendingLookups = new ConcurrentHashMap<String, Boolean>();
    private static final ExecutorService TEXTURE_FETCH_POOL = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private final AtomicInteger threadId = new AtomicInteger(1);
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "Loki-TextureFetch-" + threadId.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    private static final UuidBatcher uuidBatcher = new UuidBatcher("Loki-Uuid", new UuidBatcher.Resolver() {
        public Map<String, String> batchLookup(List<String> usernames) throws Exception {
            return batchLookupUUIDs(usernames);
        }
        public String singleLookup(String username) throws Exception {
            URL url = new URL(System.getProperty("minecraft.api.account.host", "https://api.mojang.com")
                    + "/users/profiles/minecraft/" + URLEncoder.encode(username, "UTF-8"));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int code = conn.getResponseCode();
            if (code == 200) {
                return new Json.JSONObject(readStream(conn.getInputStream())).getString("id");
            }
            if (code == 429) throw UuidBatcher.rateLimited(conn);

            url = new URL(System.getProperty("minecraft.api.account.host", "https://api.mojang.com")
                    + "/minecraft/profile/lookup/name/" + URLEncoder.encode(username, "UTF-8"));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            code = conn.getResponseCode();
            if (code == 200) {
                return new Json.JSONObject(readStream(conn.getInputStream())).getString("id");
            }
            if (code == 429) throw UuidBatcher.rateLimited(conn);

            return null;
        }
    });

    public static String readStream(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

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

    public static String[] transformMainArgs(String[] args, String serverName) {
        for (int i = 0; i < args.length; i++) {
            if (i + 1 > args.length) break;
            if ("--userType".equals(args[i]) && "mojang".equals(args[i + 1])) {
                args[i + 1] = "msa";
                log.info("Setting accountType to msa");
            }
            if ("--versionType".equals(args[i]) && serverName.length() != 0) {
                log.info("Setting versionType to server name: " + serverName);
                args[i + 1] = serverName;
            }
        }
        return args;
    }

    public static String transformProfileJson(String json) {
        try {
            Json.JSONObject profileObj = new Json.JSONObject(json);
            Json.JSONArray properties = profileObj.getJSONArray("properties");

            Iterator<Object> iter = properties.iterator();
            while (iter.hasNext()) {
                Object elem = iter.next();
                if (elem instanceof Json.JSONObject) {
                    String name = ((Json.JSONObject) elem).getString("name");
                    if (!"textures".equals(name)) {
                        iter.remove();
                    }
                }
            }

            return profileObj.toString();
        } catch (Exception e) {
            return json;
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

    public static void replaceMCAuthlibGameProfileSignature(Class<?> gameProfileClass) {
        try {
            log.debug("Replacing Mojang public key in MCAuthlib GameProfile");
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
            throw new RuntimeException("Failed to replace yggdrasil public key!", e);
        }
    }

    public static void replaceBungeeCordMojangKey(Class<?> encUtilClass) {
        try {
            log.debug("Replacing Mojang public key in BungeeCord");
            PublicKey publicKey = getPublicKey();

            Field keyField = encUtilClass.getDeclaredField("MOJANG_KEY");
            keyField.setAccessible(true);

            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) unsafeField.get(null);

            Object staticBase = unsafe.staticFieldBase(keyField);
            long staticOffset = unsafe.staticFieldOffset(keyField);
            unsafe.putObject(staticBase, staticOffset, publicKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to replace yggdrasil public key!", e);
        }
    }

    public static void replaceYggdrasilServicesKeyInfoSignature(Object target) {
        try {
            log.debug("Replacing Mojang public key in YggdrasilServicesKeyInfo");
            PublicKey publicKey = getPublicKey();

            Field pubKeyField = target.getClass().getDeclaredField("publicKey");
            pubKeyField.setAccessible(true);
            pubKeyField.set(target, publicKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to replace yggdrasil public key!", e);
        }
    }

    private static PublicKey getPublicKey() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        String baseUrl = System.getProperty("minecraft.api.services.host", "https://api.minecraftservices.com");
        URL url = new URL(baseUrl + "/publickeys");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setDoInput(true);

        Json.JSONObject jsonObject = new Json.JSONObject(readStream(conn.getInputStream()));
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

    public static String getMpPass(Object applet) {
        if (applet == null) return null;
        String mppass = null;
        try {
            Class<?> appletClass = Class.forName("java.applet.Applet");
            if (!appletClass.isInstance(applet)) return null;
            Method getParameter = appletClass.getMethod("getParameter", String.class);

            // original mppass; returned if we are unable to fetch
            mppass = (String) getParameter.invoke(applet, new Object[] { "mppass" });

            String sessionId = (String) getParameter.invoke(applet, new Object[] { "session" });
            if (sessionId == null) sessionId = (String) getParameter.invoke(applet, new Object[] { "sessionid" });
            String ip = (String) getParameter.invoke(applet, new Object[] { "server" });
            String port = (String) getParameter.invoke(applet, new Object[] { "port" });
            if (sessionId == null || ip == null || port == null)
                return mppass; // singleplayer?

            String accessToken;
            if (!sessionId.contains(":") && !sessionId.contains("%3A")) { // maybe it can be in the raw format here too?
                accessToken = sessionId;
            } else {
                String[] parts = sessionId.split(sessionId.contains(":") ? ":" : "%3A");
                if (parts.length < 3 || parts[1].length() == 0 || parts[2].length() == 0) {
                    log.error("could not parse session ID: " + sessionId);
                    return mppass;
                }

                accessToken = parts[1];
            }

            // Skip getting the mppass if we're offline
            if (OFFLINE_MODE) return mppass;

            URL url = new URL(System.getProperty("minecraft.api.session.host", "https://sessionserver.mojang.com")
                    + "/mppass?ip=" + URLEncoder.encode(ip, "UTF-8")
                    + "&port=" + port);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            if (conn.getResponseCode() != 200) return mppass;
            mppass = readStream(conn.getInputStream());
        } catch (Exception ignored) {}
        log.debug("Fetched MpPass: " + mppass);
        return mppass;
    }

    public static void injectMCOSELanServerJvmArgs(List<String> command) {
        command.addAll(Arrays.asList(LauncherHooks.getLokiJVMArgs()));
    }

    private static Map<String, String> batchLookupUUIDs(List<String> usernames) throws Exception {
        URL url = new URL(System.getProperty("minecraft.api.account.host", "https://api.mojang.com")
                + "/profiles/minecraft");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        StringBuilder body = new StringBuilder("[");
        for (int i = 0; i < usernames.size(); i++) {
            if (i > 0) body.append(",");
            body.append("\"").append(usernames.get(i)).append("\"");
        }
        body.append("]");
        conn.getOutputStream().write(body.toString().getBytes("UTF-8"));
        conn.getOutputStream().close();

        int code = conn.getResponseCode();
        if (code != 200) {
            if (code == 429) throw UuidBatcher.rateLimited(conn);
            if (code == 404 || code == 405 || code == 501) {
                throw new UuidBatcher.EndpointUnavailableException("Batch UUID endpoint returned " + code);
            }
            throw new IOException("Batch UUID endpoint returned " + code);
        }

        Json.JSONArray arr = new Json.JSONArray(readStream(conn.getInputStream()));
        Map<String, String> result = new HashMap<String, String>();
        for (int i = 0; i < arr.length(); i++) {
            Json.JSONObject obj = arr.getJSONObject(i);
            result.put(obj.getString("name").toLowerCase(Locale.ENGLISH), obj.getString("id"));
        }
        return result;
    }

    private static final class TextureEntry {
        final String[] data;
        final long expiry;
        TextureEntry(String[] data, long expiry) {
            this.data = data;
            this.expiry = expiry;
        }
    }

    private static String[] cachedTextures(String uuid) {
        TextureEntry entry = uuidToTexturesCache.get(uuid);
        if (entry == null) return null;
        if (System.currentTimeMillis() >= entry.expiry) {
            uuidToTexturesCache.remove(uuid, entry);
            return null;
        }
        return entry.data;
    }

    private static String[] fetchTexturesData(String uuid) throws Exception {
        URL url = new URL(System.getProperty("minecraft.api.session.host", "https://sessionserver.mojang.com")
                + "/session/minecraft/profile/" + URLEncoder.encode(uuid, "UTF-8") + "?unsigned=false");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        if (conn.getResponseCode() == 429) throw UuidBatcher.rateLimited(conn);
        if (conn.getResponseCode() != 200) return null;
        Json.JSONArray props = new Json.JSONObject(readStream(conn.getInputStream())).getJSONArray("properties");
        for (int i = 0; i < props.length(); i++) {
            Json.JSONObject prop = props.getJSONObject(i);
            if ("textures".equals(prop.optString("name", ""))) {
                return new String[]{ prop.getString("value"), prop.optString("signature", null) };
            }
        }
        return null;
    }

    private static void submitTextureFetch(final String username, final String uuid) {
        TEXTURE_FETCH_POOL.execute(new Runnable() {
            public void run() {
                try {
                    if (cachedTextures(uuid) == null) {
                        String[] texturesData = fetchTexturesData(uuid);
                        if (texturesData == null) {
                            negativeLookupCache.put(username, System.currentTimeMillis() + NEGATIVE_CACHE_TTL_MS);
                            return;
                        }
                        uuidToTexturesCache.put(uuid, new TextureEntry(texturesData, System.currentTimeMillis() + TEXTURE_CACHE_TTL_MS));
                        log.info("Successfully fetched missing textures for player " + username);
                    }
                } catch (UuidBatcher.RateLimitedException e) {
                    textureRateLimitUntil = System.currentTimeMillis() + e.retryAfterMs;
                } catch (Exception e) {
                    negativeLookupCache.put(username, System.currentTimeMillis() + NEGATIVE_CACHE_TTL_MS);
                } finally {
                    pendingLookups.remove(username);
                }
            }
        });
    }

    private static String[] resolveTextures(final String username) {
        String uuid = nameToUUIDCache.get(username);
        if (uuid != null) {
            String[] cached = cachedTextures(uuid);
            if (cached != null) return cached;
        }

        Long expiry = negativeLookupCache.get(username);
        if (expiry != null && System.currentTimeMillis() < expiry) return null;

        if (System.currentTimeMillis() < textureRateLimitUntil) return null; // back off after a 429

        if (pendingLookups.putIfAbsent(username, Boolean.TRUE) == null) {
            if (uuid != null) {
                submitTextureFetch(username, uuid);
            } else {
                uuidBatcher.resolve(username, new UuidBatcher.Callback() {
                    public void onResolved(String name, String resolvedUuid) {
                        if (resolvedUuid == null) {
                            negativeLookupCache.put(name, System.currentTimeMillis() + NEGATIVE_CACHE_TTL_MS);
                            pendingLookups.remove(name);
                            return;
                        }
                        nameToUUIDCache.put(name, resolvedUuid);
                        submitTextureFetch(name, resolvedUuid);
                    }
                });
            }
        }
        return null;
    }

    private static Object getMissingTexturesProperty(Object profile) {
        try {
            Object propertiesMap;
            try {
                propertiesMap = profile.getClass().getMethod("getProperties").invoke(profile); // ~<=1.21.1
            } catch (NoSuchMethodException e) {
                propertiesMap = profile.getClass().getMethod("properties").invoke(profile); // ~1.21.10+
            }
            Method containsKey = propertiesMap.getClass().getMethod("containsKey", Object.class);
            if (Boolean.TRUE.equals(containsKey.invoke(propertiesMap, "textures"))) return null;

            String username;
            try {
                username = (String) profile.getClass().getMethod("getName").invoke(profile); // ~<=1.21.1
            } catch (NoSuchMethodException e) {
                username = (String) profile.getClass().getMethod("name").invoke(profile); // ~1.21.10+
            }
            if (username == null || username.length() == 0) return null;

            String[] texturesData = resolveTextures(username);
            if (texturesData == null) return null;

            Class<?> propertyClass = profile.getClass().getClassLoader()
                    .loadClass("com.mojang.authlib.properties.Property");
            Constructor<?> ctor = propertyClass.getConstructor(String.class, String.class, String.class);
            return ctor.newInstance("textures", texturesData[0], texturesData[1]);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    public static Object getTextures(Object instance, Object profile, boolean requireSecure) {
        try {
            Object property = getMissingTexturesProperty(profile);
            if (property != null) {
                Object propertiesMap;
                try {
                    propertiesMap = profile.getClass().getMethod("getProperties").invoke(profile); // ~<=1.21.1
                } catch (NoSuchMethodException e) {
                    propertiesMap = profile.getClass().getMethod("properties").invoke(profile); // ~1.21.10+
                }
                Class<?> propertiesMapClass = propertiesMap.getClass();
                propertiesMapClass.getMethod("removeAll", Object.class).invoke(propertiesMap, "textures");
                propertiesMapClass.getMethod("put", Object.class, Object.class).invoke(propertiesMap, "textures", property);
            }
            Method original = instance.getClass().getDeclaredMethod("getTextures$original", profile.getClass(), boolean.class);
            original.setAccessible(true);
            return original.invoke(instance, profile, requireSecure);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    public static Object getPackedTextures(Object instance, Object profile) {
        try {
            Object property = getMissingTexturesProperty(profile);
            if (property != null) return property;
            Method original = instance.getClass().getDeclaredMethod("getPackedTextures$original", profile.getClass());
            original.setAccessible(true);
            return original.invoke(instance, profile);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
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
            t.printStackTrace();
        }
    }
}
