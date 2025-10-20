package org.unmojang.loki;

import sun.misc.Unsafe;

import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class RequestInterceptor {
    private static final Set<String> INTERCEPTED_DOMAINS;
    public static final Map<String, String> YGGDRASIL_MAP;
    private static final sun.misc.Unsafe unsafe = getUnsafe();
    public static final Map<String, URLStreamHandler> DEFAULT_HANDLERS = new HashMap<>();

    static {
        try {
            DEFAULT_HANDLERS.put("http", getSystemHandler("http"));
            DEFAULT_HANDLERS.put("https", getSystemHandler("https"));
        } catch (Exception e) {
            e.printStackTrace();
        }
        INTERCEPTED_DOMAINS = new HashSet<>(Arrays.asList(
                "s3.amazonaws.com",
                "www.minecraft.net",
                "skins.minecraft.net",
                "session.minecraft.net",
                "betacraft.uk",
                "snoop.minecraft.net"
        ));
        if (System.getProperty("Loki.enable_snooper", "false").equalsIgnoreCase("true")) {
            INTERCEPTED_DOMAINS.remove("snoop.minecraft.net");
        }
        if (System.getProperty("Loki.enable_realms", "true").equalsIgnoreCase("false")) {
            INTERCEPTED_DOMAINS.add("java.frontendlegacy.realms.minecraft-services.net");
            INTERCEPTED_DOMAINS.add("pc.realms.minecraft.net");
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
        Premain.log.info("Arrived in setURLFactory");
        URL.setURLStreamHandlerFactory(protocol -> {
            if (!"http".equals(protocol) && !"https".equals(protocol)) return null;
            URLStreamHandler delegate = DEFAULT_HANDLERS.get(protocol);
            if (delegate == null) return null;
            return new URLStreamHandlerProxy(delegate);
        });
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
        if (Objects.equals(System.getProperty("Loki.debug", "false"), "true")) {
            Premain.log.info("Connection: " + ((HttpURLConnection) originalConn).getRequestMethod() + " " + originalUrl);
        }
        if (!(originalConn instanceof HttpURLConnection)) return originalConn;
        String host = originalUrl.getHost();
        String path = originalUrl.getPath();
        String query = originalUrl.getQuery();
        HttpURLConnection httpConn = (HttpURLConnection) originalConn;
        if (YGGDRASIL_MAP.containsKey(host)) { // yggdrasil
            Premain.log.info("Intercepting: " + originalUrl);
            try {
                final URL targetUrl = Ygglib.getYggdrasilUrl(originalUrl, originalUrl.getHost());
                Premain.log.info(" -> " + targetUrl);
                if (path.startsWith("/session/minecraft/profile/")) { // ReIndev fix
                    return Ygglib.getSessionProfile(originalUrl);
                }
                if (path.equals("/events")) { // Snooper (1.18+): https://api.minecraftservices.com/events
                    Premain.log.info("Snooper request intercepted: " + originalUrl);
                    return new Ygglib.FakeURLConnection(originalUrl, 403, ("Nice try ;)").getBytes(StandardCharsets.UTF_8));
                }
                return mirrorHttpURLConnection(targetUrl, httpConn);
            } catch (Exception e) {
                Premain.log.warn("Failed to redirect " + originalUrl + ": " + e);
                return originalConn;
            }
        } else if (INTERCEPTED_DOMAINS.contains(host)) {
            // Authentication
            if (path.equals("/game/joinserver.jsp")) {
                Premain.log.info("Intercepting joinServer: " + originalUrl);
                return Ygglib.joinServer(originalUrl);
            } else if (path.equals("/game/checkserver.jsp")) {
                Premain.log.info("Intercepting checkServer: " + originalUrl);
                return Ygglib.checkServer(originalUrl);
            }

            // Textures
            if (path.startsWith("/MinecraftSkins") || path.startsWith("/skin")) {
                Premain.log.info("Intercepting skin texture: " + originalUrl);
                String username = Ygglib.getUsernameFromPath(path);
                return Ygglib.getTexture(originalUrl, username, "SKIN");
            } else if (path.startsWith("/MinecraftCloaks")) {
                Premain.log.info("Intercepting cape texture: " + originalUrl);
                String username = Ygglib.getUsernameFromPath(path);
                return Ygglib.getTexture(originalUrl, username, "CAPE");
            } else if (path.equals("/cloak/get.jsp")) {
                Premain.log.info("Intercepting cape texture: " + originalUrl);
                String username = Ygglib.queryStringParser(query).get("user");
                return Ygglib.getTexture(originalUrl, username, "CAPE");
            }

            // Resources
            if (path.equals("/MinecraftResources/")) {
                try (InputStream is = RequestInterceptor.class.getResourceAsStream("/MinecraftResources.xml")) {
                    assert is != null;
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    reader.close();
                    return new Ygglib.FakeURLConnection(originalUrl, 200, sb.toString().getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    return new Ygglib.FakeURLConnection(originalUrl, 500, "\0".getBytes(StandardCharsets.UTF_8));
                }
            } else if (path.startsWith("/MinecraftResources/")) { // resource fetch attempt
                return new Ygglib.FakeURLConnection(originalUrl, 500, "\0".getBytes(StandardCharsets.UTF_8));
            }

            // Snooper
            if (host.equals("snoop.minecraft.net")) {
                Premain.log.info("Snooper request intercepted: " + originalUrl);
                return new Ygglib.FakeURLConnection(originalUrl, 403, ("Nice try ;)").getBytes(StandardCharsets.UTF_8));
            }

            // Realms
            if (host.equals("java.frontendlegacy.realms.minecraft-services.net") || host.equals("pc.realms.minecraft.net")) {
                Premain.log.info("Realms request intercepted: " + originalUrl);
                return new Ygglib.FakeURLConnection(originalUrl, 403, ("Nice try ;)").getBytes(StandardCharsets.UTF_8));
            }
        }

        return originalConn;
    }

    private static HttpURLConnection mirrorHttpURLConnection(URL targetUrl, HttpURLConnection httpConn) throws IOException {
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

    private static URLStreamHandler getSystemHandler(String protocol) throws Exception {
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
}
