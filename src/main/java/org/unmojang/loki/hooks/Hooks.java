package org.unmojang.loki.hooks;

import org.unmojang.loki.util.Base64;
import org.unmojang.loki.util.Json;
import org.unmojang.loki.util.logger.NilLogger;
import sun.misc.Unsafe;

import java.applet.Applet;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.*;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings({"unused", "CallToPrintStackTrace"})
public class Hooks {
    public static final Map<String, URLStreamHandler> DEFAULT_HANDLERS = new ConcurrentHashMap<String, URLStreamHandler>();
    private static final NilLogger log = NilLogger.get("Loki");
    public static boolean OFFLINE_MODE = false;
    private static final ConcurrentHashMap<String, String> nameToUUIDCache = new ConcurrentHashMap<String, String>();
    private static final ConcurrentHashMap<String, String[]> uuidToTexturesCache = new ConcurrentHashMap<String, String[]>();

    private static String readStream(InputStream in) throws IOException {
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

    public static String getMpPass(Applet applet) {
        if (applet == null) return null;
        String mppass = applet.getParameter("mppass"); // original mppass; returned if we are unable to fetch
        try {
            String sessionId = applet.getParameter("session");
            if (sessionId == null) sessionId = applet.getParameter("sessionid");
            String ip = applet.getParameter("server");
            String port = applet.getParameter("port");
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
            conn.setRequestProperty("User-Agent", "Loki/" + Hooks.class.getPackage().getImplementationVersion());
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            if (conn.getResponseCode() != 200) return mppass;
            InputStream is = null;
            try {
                is = conn.getInputStream();
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] data = new byte[8192];
                int nRead;
                while ((nRead = is.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }
                mppass = buffer.toString("UTF-8");
            } finally {
                if (is != null) is.close();
            }
        } catch (Exception ignored) {}
        log.debug("Fetched MpPass: " + mppass);
        return mppass;
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
        conn.setRequestProperty("User-Agent", "Loki/" + Hooks.class.getPackage().getImplementationVersion());
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

    public static void injectMCOSELanServerJvmArgs(List<String> command) {
        try {
            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (arg.startsWith("-javaagent:")) {
                    log.debug("Appending agent to LAN server: " + arg);
                    command.add(arg);
                }
            }
            Properties props = System.getProperties();
            Enumeration<?> names = props.propertyNames();
            while (names.hasMoreElements()) {
                String key = (String) names.nextElement();
                if (key.startsWith("Loki.") || key.startsWith("minecraft.api.")) {
                    String value = props.getProperty(key);
                    if (value == null) continue;
                    String jvmArg = "-D" + key + "=" + value;
                    log.debug("Appending JVM argument to LAN server: " + jvmArg);
                    command.add(jvmArg);
                }
            }
        } catch (Throwable t) {
            log.error("Failed to inject LAN server JVM args!", t);
        }
    }

    // TODO always keep in sync with Ygglib.getUUID
    private static String getUUID(String username) throws Exception {
        URL url = new URL(System.getProperty("minecraft.api.account.host", "https://api.mojang.com")
                + "/users/profiles/minecraft/" + URLEncoder.encode(username, "UTF-8"));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "Loki/" + Hooks.class.getPackage().getImplementationVersion());
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        if (conn.getResponseCode() == 200) {
            Json.JSONObject obj = new Json.JSONObject(readStream(conn.getInputStream()));
            return obj.getString("id");
        }

        // route not implemented? let's try the other one...
        url = new URL(System.getProperty("minecraft.api.account.host", "https://api.mojang.com")
                + "/minecraft/profile/lookup/name/" + URLEncoder.encode(username, "UTF-8"));
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "Loki/" + Hooks.class.getPackage().getImplementationVersion());
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        if (conn.getResponseCode() == 200) {
            Json.JSONObject obj = new Json.JSONObject(readStream(conn.getInputStream()));
            return obj.getString("id");
        }

        // prehistoric version of BlessingSkin only implements this route
        url = new URL(System.getProperty("minecraft.api.account.host", "https://api.mojang.com")
                + "/profiles/minecraft");
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "Loki/" + Hooks.class.getPackage().getImplementationVersion());
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        byte[] body = ("[\"" + username + "\"]").getBytes("UTF-8");
        conn.getOutputStream().write(body);
        conn.getOutputStream().close();

        if (conn.getResponseCode() == 200) {
            String jsonText = readStream(conn.getInputStream());
            Json.JSONArray arr = new Json.JSONArray(jsonText);
            return arr.getJSONObject(0).getString("id");
        }

        throw new IOException("No UUID lookup route succeeded for username: " + username);
    }

    private static String[] fetchTexturesData(String uuid) throws Exception {
        URL url = new URL(System.getProperty("minecraft.api.session.host", "https://sessionserver.mojang.com")
                + "/session/minecraft/profile/" + URLEncoder.encode(uuid, "UTF-8") + "?unsigned=false");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "Loki/" + Hooks.class.getPackage().getImplementationVersion());
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        if (conn.getResponseCode() != 200) return null;
        InputStream is = null;
        try {
            is = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] data = new byte[8192];
            int n;
            while ((n = is.read(data)) != -1) buf.write(data, 0, n);
            Json.JSONArray props = new Json.JSONObject(buf.toString("UTF-8")).getJSONArray("properties");
            for (int i = 0; i < props.length(); i++) {
                Json.JSONObject prop = props.getJSONObject(i);
                if ("textures".equals(prop.optString("name", ""))) {
                    return new String[]{ prop.getString("value"), prop.optString("signature", null) };
                }
            }
            return null;
        } finally {
            if (is != null) is.close();
        }
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

            String uuid = nameToUUIDCache.get(username);
            if (uuid == null) {
                try { uuid = getUUID(username); } catch (Exception e) { return null; }
                if (uuid == null) return null;
                nameToUUIDCache.putIfAbsent(username, uuid);
                uuid = nameToUUIDCache.get(username);
            }

            String[] texturesData = uuidToTexturesCache.get(uuid);
            if (texturesData == null) {
                try { texturesData = fetchTexturesData(uuid); } catch (Exception e) { return null; }
                if (texturesData == null) return null;
                uuidToTexturesCache.putIfAbsent(uuid, texturesData);
                texturesData = uuidToTexturesCache.get(uuid);
            }

            log.info("Successfully fetched missing textures for player " + username);
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
