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

    static {
        try {
            DEFAULT_HANDLERS.put("http", getSystemURLHandler("http"));
            DEFAULT_HANDLERS.put("https", getSystemURLHandler("https"));
        } catch (Exception e) {
            Loki.log.error("Failed to get system URL handler", e);
            Loki.disable_factory = true;
        }
        INTERCEPTED_DOMAINS = new HashSet<>(Arrays.asList(
                "s3.amazonaws.com",
                "www.minecraft.net",
                "skins.minecraft.net",
                "session.minecraft.net",
                "betacraft.uk",
                "api.ashcon.app",
                "mineskin.eu",
                "minotar.net"
        ));
        if (Loki.disable_realms) {
            INTERCEPTED_DOMAINS.add("java.frontendlegacy.realms.minecraft-services.net");
            INTERCEPTED_DOMAINS.add("pc.realms.minecraft.net");
        }
        if (!Loki.enable_snooper) {
            INTERCEPTED_DOMAINS.add("snoop.minecraft.net");
        }
        if (!Loki.modded_capes) {
            INTERCEPTED_DOMAINS.add("s.optifine.net");
            INTERCEPTED_DOMAINS.add("161.35.130.99"); // Cloaks+
            INTERCEPTED_DOMAINS.add("api.rumblecapes.xyz");
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
        Loki.log.debug("Arrived in setURLFactory");
        if (Loki.disable_factory) {
            Loki.log.warn("Loki's URL factory is disabled :(");
            Loki.log.warn("Your API server may potentially not be queried by mods that utilize");
            Loki.log.warn("the Mojang API! If you are running a pre-Yggdrasil version without a");
            Loki.log.warn("Mojang API fixer mod, expect total catastrophic breakage!");
            return;
        }
        if (isModernForge()) {
            Loki.log.warn("This Forge environment does not support Loki's URL factory :(");
            Loki.log.warn("Your API server may potentially not be queried by mods that utilize");
            Loki.log.warn("the Mojang API!");
            Loki.disable_factory = true;
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

    private static URLConnection wrapConnection(java.net.URL originalUrl, java.net.URLConnection originalConn) throws UnknownHostException {
        if (!(originalConn instanceof HttpURLConnection)) return originalConn;
        String host = originalUrl.getHost();
        String path = originalUrl.getPath();
        String query = originalUrl.getQuery();
        HttpURLConnection httpConn = (HttpURLConnection) originalConn;
        Loki.log.debug("Connection: " + httpConn.getRequestMethod() + " " + originalUrl);
        if (YGGDRASIL_MAP.containsKey(host)) { // yggdrasil
            try {
                final URL targetUrl = Ygglib.getYggdrasilUrl(originalUrl, originalUrl.getHost());
                if (path.equals("/events") && !(Loki.enable_snooper)) { // Snooper (1.18+): https://api.minecraftservices.com/events
                    Loki.log.info("Intercepting snooper request");
                    return new Ygglib.FakeURLConnection(originalUrl, 403, ("Nice try ;)").getBytes(StandardCharsets.UTF_8));
                }
                Loki.log.info("Intercepting " + host + " request");
                Loki.log.debug(originalUrl + " -> " + targetUrl);
                if (path.startsWith("/session/minecraft/profile/")) { // ReIndev fix
                    return Ygglib.getSessionProfile(targetUrl, httpConn);
                }
                return mirrorHttpURLConnection(targetUrl, httpConn);
            } catch (Exception e) {
                Loki.log.error("Failed to intercept " + originalUrl, e);
                return originalConn;
            }
        } else if (INTERCEPTED_DOMAINS.contains(host)) {
            // Authentication
            if (path.equals("/game/joinserver.jsp")) {
                Loki.log.info("Intercepting joinserver request");
                return Ygglib.joinServer(originalUrl);
            } else if (path.equals("/game/checkserver.jsp")) {
                Loki.log.info("Intercepting checkserver request");
                return Ygglib.checkServer(originalUrl);
            }

            // Textures
            if (path.startsWith("/MinecraftSkins") || path.startsWith("/skin")) {
                String username = Ygglib.getUsernameFromPath(path);
                Loki.log.info("Intercepting skin lookup for " + username);
                return Ygglib.getTexture(originalUrl, username, "SKIN");
            } else if (path.startsWith("/MinecraftCloaks")) {
                String username = Ygglib.getUsernameFromPath(path);
                Loki.log.info("Intercepting cape lookup for " + username);
                return Ygglib.getTexture(originalUrl, username, "CAPE");
            } else if (path.equals("/cloak/get.jsp")) {
                String username = Ygglib.queryStringParser(query).get("user");
                Loki.log.info("Intercepting cape lookup for " + username);
                return Ygglib.getTexture(originalUrl, username, "CAPE");
            }

            // Snooper
            if (host.equals("snoop.minecraft.net")) {
                Loki.log.info("Intercepting snooper request");
                return new Ygglib.FakeURLConnection(originalUrl, 403, ("Nice try ;)").getBytes(StandardCharsets.UTF_8));
            }

            // Realms
            if (host.equals("java.frontendlegacy.realms.minecraft-services.net") || host.equals("pc.realms.minecraft.net")) {
                Loki.log.info("Intercepting realms request");
                return new Ygglib.FakeURLConnection(originalUrl, 403, ("Nice try ;)").getBytes(StandardCharsets.UTF_8));
            }

            // Misc
            if (host.equals("api.ashcon.app") && path.matches("^/mojang/[^/]+/user/.*")) {
                String username = Ygglib.getUsernameFromPath(originalUrl.getPath());
                Loki.log.info("Intercepting api.ashcon.app lookup for " + username);
                return Ygglib.getAshcon(originalUrl, username);
            }

            if (host.equals("minotar.net") && path.startsWith("/helm")) {
                String username = path.split("/")[2];
                int res = Integer.parseInt(path.split("/")[3].replaceFirst("\\..*$", ""));
                Loki.log.info(String.format("Intercepting minotar.net lookup for %s (%s px)", username, res));
                return Ygglib.getMinotar(originalUrl, username, res);
            }

            // Capes
            if (host.equals("s.optifine.net") && path.startsWith("/capes")) {
                Loki.log.info("Intercepting OptiFine cape lookup");
                return new Ygglib.FakeURLConnection(originalUrl, 403, ("Nice try ;)").getBytes(StandardCharsets.UTF_8));
            }

            if (host.equals("161.35.130.99") && path.startsWith("/capes")) {
                Loki.log.info("Intercepting Cloaks+ cape lookup");
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
        sun.misc.Unsafe unsafe = getUnsafe();
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
        Loki.log.debug("Classpath: " + cp);
        if (cp.equals(".")) {
            Loki.log.debug("Empty classpath, perhaps we are running from a 1.17+ Forge server? Not setting URL factory!");
            return true;
        }
        for (String entry : cp.split(File.pathSeparator)) {
            String fileName = new File(entry).getName();
            if (fileName.startsWith("securejarhandler-") && fileName.endsWith(".jar")) {
                Loki.log.debug("Found " + fileName + ", we must be on 1.17-1.20.2 LexForge or <1.21.9 NeoForge. Not setting URL factory!");
                return true;
            } else if (fileName.startsWith("fmlloader-")) {
                checkFMLVersion(fileName);
            }
        }
        Loki.log.debug("We don't seem to be on 1.17-1.20.2 LexForge or <1.21.9 NeoForge, continuing to set URL factory.");
        return false;
    }

    public static void checkFMLVersion(String filename) {
        if (!filename.endsWith(".jar")) return; // malformed

        String[] parts = filename.substring(0, filename.length() - 4).split("-");
        if (parts.length < 2) return; // malformed

        String fmlVersionStr = parts[parts.length - 1];
        String[] verParts = fmlVersionStr.split("\\.");
        int major = verParts.length > 0 ? Integer.parseInt(verParts[0]) : 0;
        int minor = verParts.length > 1 ? Integer.parseInt(verParts[1]) : 0;
        int patch = verParts.length > 2 ? Integer.parseInt(verParts[2]) : 0;

        // If major is 48, ensure we are running 48.0.31 or below
        if (major == 48 && (minor > 0 || (minor == 0 && patch > 31))) {
            Loki.log.error("LexForge version is not suported: " + fmlVersionStr);
            Loki.log.error("Please downgrade to LexForge 48.0.31, or use NeoForge!");
            Loki.log.error("Loki is terminating the game to prevent an imminent crash!");
            Loki.log.error("More details here: https://github.com/unmojang/Loki/issues/7");
            System.exit(1);
        }
    }

    @SuppressWarnings("unused")
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
                        Loki.log.debug("Registered external handler for " + p + " from factory " + factory.getClass().getName());
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Loki.log.error("registerExternalFactory failed!", t);
        }
    }
}
