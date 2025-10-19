package com.unmojang.loki;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class RequestInterceptor {
    private static final Set<String> INTERCEPTED_DOMAINS;
    public static final Map<String, String> YGGDRASIL_MAP;

    static {
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
        Map<String, String> tmp = new HashMap<>();
        tmp.put("authserver.mojang.com", System.getProperty("minecraft.api.auth.host", "https://authserver.mojang.com"));
        tmp.put("api.mojang.com", System.getProperty("minecraft.api.account.host",
                // fallback to 1.21.9+ minecraft.api.profiles.host
                System.getProperty("minecraft.api.profiles.host", "https://api.mojang.com")));
        tmp.put("api.minecraftservices.com", System.getProperty("minecraft.api.services.host", "https://api.minecraftservices.com"));
        tmp.put("sessionserver.mojang.com", System.getProperty("minecraft.api.session.host", "https://sessionserver.mojang.com"));
        YGGDRASIL_MAP = Collections.unmodifiableMap(tmp);
    }

    public static void setURLFactory() {
        Premain.log.info("Arrived in setURLFactory");
        final URLStreamHandlerFactory factory = protocol -> {
            try {
                if (!"http".equals(protocol) && !"https".equals(protocol)) {
                    return null;
                }
                URLStreamHandler system = getDefaultHandler(protocol);
                if (system == null) {
                    return null; // let JVM handle it
                }
                return wrapHandler(system);
            } catch (Throwable t) {
                Premain.log.warn("failed to create/wrap handler for protocol: " + protocol, t);
                return null;
            }
        };

        try {
            URL.setURLStreamHandlerFactory(factory);
            Premain.log.info("setURLStreamHandlerFactory succeeded");
        } catch (Error e) {
            Premain.log.info("setURLStreamHandlerFactory threw Error (already set).");
        } catch (Throwable t) {
            Premain.log.error("Unexpected error setting URLStreamHandlerFactory", t);
        }
    }

    private static URLStreamHandler wrapHandler(final URLStreamHandler delegate) {
        Premain.log.info("Got into wrapHandler");
        return new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL u) throws IOException {
                String protocol = u.getProtocol();
                if (!"http".equals(protocol) && !"https".equals(protocol)) { // not a http(s) request; ignore
                    return openDefault(delegate, u);
                }
                return wrapConnection(u, openDefault(delegate, u));
            }

            @Override
            protected URLConnection openConnection(URL u, Proxy proxy) throws IOException {
                String protocol = u.getProtocol();
                if (!"http".equals(protocol) && !"https".equals(protocol)) {  // not a http(s) request; ignore
                    return openDefault(delegate, u, proxy);
                }
                return wrapConnection(u, openDefault(delegate, u, proxy));
            }
        };
    }

    private static URLStreamHandler getDefaultHandler(String protocol) {
        try {
            // Use reflection to create the default handler
            Class<?> cls = Class.forName("sun.net.www.protocol." + protocol + ".Handler");
            return (URLStreamHandler) cls.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return null;
        }
    }

    private static URLConnection wrapConnection(URL originalUrl, URLConnection originalConn) {
        String host = originalUrl.getHost();
        String path = originalUrl.getPath();
        String query = originalUrl.getQuery();
        if (YGGDRASIL_MAP.containsKey(host)) { // yggdrasil
            try {
                final URL targetUrl = Ygglib.getYggdrasilUrl(originalUrl, originalUrl.getHost());
                Premain.log.info("Redirecting " + originalUrl + " -> " + targetUrl);

                final HttpURLConnection targetConn = (HttpURLConnection) targetUrl.openConnection();

                // Mirror HTTP method
                if (originalConn instanceof HttpURLConnection) {
                    targetConn.setRequestMethod(((HttpURLConnection) originalConn).getRequestMethod());
                    targetConn.setDoOutput(originalConn.getDoOutput());
                    targetConn.setDoInput(true);
                    targetConn.setInstanceFollowRedirects(((HttpURLConnection) originalConn).getInstanceFollowRedirects());

                    // Mirror headers
                    Map<String, List<String>> headers = originalConn.getRequestProperties();
                    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                        String key = entry.getKey();
                        if (key == null) continue; // skip pseudo-headers
                        for (String val : entry.getValue()) {
                            targetConn.addRequestProperty(key, val);
                        }
                    }

                    // Mirror body if present
                    if (originalConn.getDoOutput()) {
                        targetConn.setDoOutput(true);
                        try (InputStream is = originalConn.getInputStream();
                             OutputStream os = targetConn.getOutputStream()) {
                            byte[] buf = new byte[8192];
                            int r;
                            while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
                        }
                    }
                }
                return targetConn;
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
            }

            // Snooper
            if (host.equals("snoop.minecraft.net")) {
                Premain.log.info("Snooper request intercepted: " + originalUrl);
                return new Ygglib.FakeURLConnection(originalUrl, 403, ("Nice try ;)").getBytes(StandardCharsets.UTF_8));
            }
        }

        return originalConn;
    }


    private static URLConnection openDefault(URLStreamHandler handler, URL url) throws IOException {
        try {
            java.lang.reflect.Method m = URLStreamHandler.class
                    .getDeclaredMethod("openConnection", URL.class);
            m.setAccessible(true);
            return (URLConnection) m.invoke(handler, url);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static URLConnection openDefault(URLStreamHandler handler, URL url, Proxy proxy) throws IOException {
        try {
            java.lang.reflect.Method m = URLStreamHandler.class
                    .getDeclaredMethod("openConnection", URL.class, Proxy.class);
            m.setAccessible(true);
            return (URLConnection) m.invoke(handler, url, proxy);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
