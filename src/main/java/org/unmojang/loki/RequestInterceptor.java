package org.unmojang.loki;

import sun.misc.Unsafe;

import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RequestInterceptor {
    public static final Map<String, String> YGGDRASIL_MAP;
    public static final Map<String, URLStreamHandler> DEFAULT_HANDLERS = new ConcurrentHashMap<>();
    private static final Set<String> INTERCEPTED_DOMAINS;
    private static final sun.misc.Unsafe unsafe = getUnsafe();

    static {
        try {
            DEFAULT_HANDLERS.put("http", getSystemURLHandler("http"));
            DEFAULT_HANDLERS.put("https", getSystemURLHandler("https"));
        } catch (Exception e) {
            Loki.log.error("Failed to get system URL handler", e);
        }
        INTERCEPTED_DOMAINS = new HashSet<>(Arrays.asList(
                "s3.amazonaws.com",
                "www.minecraft.net",
                "skins.minecraft.net",
                "session.minecraft.net",
                "betacraft.uk",
                "api.ashcon.app",
                "mineskin.eu"
        ));
        if (!Loki.enable_realms) {
            INTERCEPTED_DOMAINS.add("java.frontendlegacy.realms.minecraft-services.net");
            INTERCEPTED_DOMAINS.add("pc.realms.minecraft.net");
        }
        if (!Loki.enable_snooper) {
            INTERCEPTED_DOMAINS.add("snoop.minecraft.net");
        }
        if (!Loki.modded_capes) {
            INTERCEPTED_DOMAINS.add("api.betterthanadventure.net");
        }

        String accountHost = System.getProperty("minecraft.api.account.host",
                System.getProperty("minecraft.api.profiles.host"));
        String authHost = System.getProperty("minecraft.api.auth.host");
        String sessionHost = System.getProperty("minecraft.api.session.host");
        String servicesHost = System.getProperty("minecraft.api.services.host");

        Map<String, String> tmp = new HashMap<>();
        tmp.put("authserver.mojang.com", authHost != null ? authHost : "https://authserver.mojang.com");
        tmp.put("api.mojang.com", accountHost != null ? accountHost : "https://api.mojang.com");
        tmp.put("api.minecraftservices.com", servicesHost != null ? servicesHost : "https://api.minecraftservices.com");
        tmp.put("sessionserver.mojang.com", sessionHost != null ? sessionHost : "https://sessionserver.mojang.com");
        YGGDRASIL_MAP = Collections.unmodifiableMap(tmp);
    }

    public static void setURLFactory() {
        Loki.log.info("Arrived in setURLFactory");
        if (isModernForge()) {
            return;
        }
        URL.setURLStreamHandlerFactory(protocol -> {
            URLStreamHandler delegate = DEFAULT_HANDLERS.get(protocol);
            if (delegate == null) return null;
            return (protocol.equals("http") || protocol.equals("https"))
                    ? new URLStreamHandlerProxy(delegate)
                    : delegate;
        });
    }

    public static HttpURLConnection openWithParent(URL url, URLStreamHandler handler) throws IOException {
        try {
            // Use the URL constructor with null context to avoid global wrapper
            URL delegated = new URL(null, url.toExternalForm(), handler);
            return (HttpURLConnection) delegated.openConnection();
        } catch (ClassCastException e) {
            throw new IOException("Handler did not return HttpURLConnection", e);
        }
    }

    private static URLConnection wrapConnection(java.net.URL originalUrl, java.net.URLConnection originalConn) {
        if (!(originalConn instanceof HttpURLConnection)) return originalConn;
        String host = originalUrl.getHost();
        String path = originalUrl.getPath();
        String query = originalUrl.getQuery();
        HttpURLConnection httpConn = (HttpURLConnection) originalConn;
        if (Loki.debug) {
            Loki.log.info("Connection: " + httpConn.getRequestMethod() + " " + originalUrl);
        }
        if (YGGDRASIL_MAP.containsKey(host)) { // yggdrasil
            try {
                final URL targetUrl = Ygglib.getYggdrasilUrl(originalUrl, originalUrl.getHost());
                Loki.log.info("Intercepting: " + originalUrl + " -> " + targetUrl);
                if (path.startsWith("/session/minecraft/profile/")) { // ReIndev fix
                    return Ygglib.getSessionProfile(targetUrl, httpConn);
                }
                if (path.equals("/events") && !(Loki.enable_snooper)) { // Snooper (1.18+): https://api.minecraftservices.com/events
                    Loki.log.info("Snooper request intercepted: " + originalUrl);
                    return new Ygglib.FakeURLConnection(originalUrl, 403, ("Nice try ;)").getBytes(StandardCharsets.UTF_8));
                }
                return mirrorHttpURLConnection(targetUrl, httpConn);
            } catch (Exception e) {
                Loki.log.error("Failed to intercept " + originalUrl, e);
                return originalConn;
            }
        } else if (INTERCEPTED_DOMAINS.contains(host)) {
            // Authentication
            if (path.equals("/game/joinserver.jsp")) {
                Loki.log.info("Intercepting joinServer: " + originalUrl);
                return Ygglib.joinServer(originalUrl);
            } else if (path.equals("/game/checkserver.jsp")) {
                Loki.log.info("Intercepting checkServer: " + originalUrl);
                return Ygglib.checkServer(originalUrl);
            }

            // Textures
            if (path.startsWith("/MinecraftSkins") || path.startsWith("/skin")) {
                Loki.log.info("Intercepting skin texture: " + originalUrl);
                String username = Ygglib.getUsernameFromPath(path);
                return Ygglib.getTexture(originalUrl, username, "SKIN");
            } else if (path.startsWith("/MinecraftCloaks")) {
                Loki.log.info("Intercepting cape texture: " + originalUrl);
                String username = Ygglib.getUsernameFromPath(path);
                return Ygglib.getTexture(originalUrl, username, "CAPE");
            } else if (path.equals("/cloak/get.jsp")) {
                Loki.log.info("Intercepting cape texture: " + originalUrl);
                String username = Ygglib.queryStringParser(query).get("user");
                return Ygglib.getTexture(originalUrl, username, "CAPE");
            }

            // Snooper
            if (host.equals("snoop.minecraft.net")) {
                Loki.log.info("Snooper request intercepted: " + originalUrl);
                return new Ygglib.FakeURLConnection(originalUrl, 403, ("Nice try ;)").getBytes(StandardCharsets.UTF_8));
            }

            // Realms
            if (host.equals("java.frontendlegacy.realms.minecraft-services.net") || host.equals("pc.realms.minecraft.net")) {
                Loki.log.info("Realms request intercepted: " + originalUrl);
                return new Ygglib.FakeURLConnection(originalUrl, 403, ("Nice try ;)").getBytes(StandardCharsets.UTF_8));
            }

            // Misc
            if (host.equals("api.ashcon.app") && path.matches("^/mojang/[^/]+/user/.*")) {
                Loki.log.info("Intercepting api.ashcon.app: " + originalUrl);
                String username = Ygglib.getUsernameFromPath(originalUrl.getPath());
                return Ygglib.getAshcon(originalUrl, username);
            }

            if (host.equals("api.betterthanadventure.net") && path.endsWith("/capes")) {
                Loki.log.info("Intercepting api.betterthanadventure.net: " + originalUrl);
                return new Ygglib.FakeURLConnection(originalUrl, 403, ("Nice try ;)").getBytes(StandardCharsets.UTF_8));
            }
        }

        return originalConn;
    }

    public static HttpURLConnection mirrorHttpURLConnection(URL targetUrl, HttpURLConnection httpConn) throws IOException {
        URLStreamHandler handler = DEFAULT_HANDLERS.get(targetUrl.getProtocol());
        final HttpURLConnection targetConn = openWithParent(targetUrl, handler);

        // Mirror HTTP method
        targetConn.setRequestMethod(httpConn.getRequestMethod());
        targetConn.setDoOutput(httpConn.getDoOutput());
        targetConn.setDoInput(true);
        targetConn.setInstanceFollowRedirects(httpConn.getInstanceFollowRedirects());

        // Mirror headers
        Map<String, List<String>> headers = httpConn.getRequestProperties();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (key == null) continue; // skip pseudo-headers
            for (String val : entry.getValue()) {
                targetConn.addRequestProperty(key, val);
            }
        }

        // Mirror body if present
        if (httpConn.getDoOutput()) {
            targetConn.setDoOutput(true);
            try (InputStream is = httpConn.getInputStream();
                 OutputStream os = targetConn.getOutputStream()) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
            }
        }
        return targetConn;
    }

    private static URLStreamHandler getSystemURLHandler(String protocol) throws Exception {
        URL url = new URL(protocol + ":"); // create a temporary URL
        Field handlerField = URL.class.getDeclaredField("handler");
        long offset = unsafe.objectFieldOffset(handlerField);
        return (URLStreamHandler) unsafe.getObject(url, offset);
    }

    private static Unsafe getUnsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class URLStreamHandlerProxy extends URLStreamHandler {
        private final URLStreamHandler parent;

        public URLStreamHandlerProxy(URLStreamHandler parent) {
            this.parent = parent;
        }

        @Override
        protected URLConnection openConnection(URL url) throws IOException {
            // Use public constructor to delegate to parent handler
            URL delegated = new URL(null, url.toExternalForm(), parent);
            URLConnection conn = delegated.openConnection();
            return wrapConnection(url, conn);
        }

        @Override
        protected URLConnection openConnection(URL url, Proxy proxy) throws IOException {
            // Java URL constructor does not support Proxy directly, need fallback
            try {
                java.lang.reflect.Method m = URLStreamHandler.class
                        .getDeclaredMethod("openConnection", URL.class, Proxy.class);
                m.setAccessible(true); // will fail on Java 9+, avoid if possible
                URLConnection conn = (URLConnection) m.invoke(parent, url, proxy);
                return wrapConnection(url, conn);
            } catch (Exception e) {
                // Fallback: open URL without proxy
                URL delegated = new URL(null, url.toExternalForm(), parent);
                return wrapConnection(url, delegated.openConnection());
            }
        }
    }

    public static boolean isModernForge() {
        String cp = System.getProperty("java.class.path");
        if (cp == null) return false;
        if (cp.equals(".")) {
            Loki.log.info("Empty classpath, perhaps we are running from a 1.17+ Forge server? Not setting URL factory!");
            return true;
        }
        for (String entry : cp.split(File.pathSeparator)) {
            String fileName = new File(entry).getName();
            if (fileName.startsWith("securejarhandler-") && fileName.endsWith(".jar")) {
                Loki.log.info("Found SecureJarHandler, we must be on 1.17+ Forge, not setting URL factory!");
                return true;
            }
        }
        return false;
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
                        Loki.log.info("Registered external handler for " + p + " from factory " + factory.getClass().getName());
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Loki.log.error("registerExternalFactory failed: " + t);
        }
    }
}
