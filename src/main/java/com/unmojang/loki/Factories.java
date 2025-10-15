package com.unmojang.loki;

import nilloader.api.lib.nanojson.JsonObject;
import nilloader.api.lib.nanojson.JsonParser;

import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static com.unmojang.loki.Ygglib.getYggdrasilUrl;

public class Factories {
    private static final Set<String> ALLOWED_DOMAINS;
    private static final Set<String> BLACKLISTED_PATHS;
    public static final Map<String, String> YGGDRASIL_MAP;

    static {
        ALLOWED_DOMAINS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                "s3.amazonaws.com",
                "www.minecraft.net",
                "skins.minecraft.net",
                "session.minecraft.net",
                "betacraft.uk"
        )));
        BLACKLISTED_PATHS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                "/MinecraftResources",
                "/baz"
        )));
        Map<String, String> tmp = new HashMap<>();
        tmp.put("authserver.mojang.com", System.getProperty("minecraft.api.auth.host", "authserver.mojang.com"));
        tmp.put("api.mojang.com", System.getProperty("minecraft.api.account.host", "api.mojang.com"));
        tmp.put("sessionserver.mojang.com", System.getProperty("minecraft.api.session.host", "sessionserver.mojang.com"));
        YGGDRASIL_MAP = Collections.unmodifiableMap(tmp);
    }

    public static void URLFactory() {
        Premain.log.info("Arrived in URLFactory");
        final URLStreamHandlerFactory ourFactory = protocol -> {
            try {
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
            URL.setURLStreamHandlerFactory(ourFactory);
            Premain.log.info("setURLStreamHandlerFactory succeeded");
            return;
        } catch (Error e) {
            Premain.log.info("setURLStreamHandlerFactory threw Error (already set). Will try reflective wrap.");
        } catch (Throwable t) {
            Premain.log.error("Unexpected error setting URLStreamHandlerFactory", t);
            return;
        }

        // If we got here, factory was already set. Try to wrap the existing factory reflectively.
        try {
            Field factoryField = URL.class.getDeclaredField("factory");
            factoryField.setAccessible(true);
            URLStreamHandlerFactory existingFactory = (URLStreamHandlerFactory) factoryField.get(null);

            if (existingFactory == null) {
                try {
                    URL.setURLStreamHandlerFactory(ourFactory);
                    Premain.log.info("setURLStreamHandlerFactory succeeded on second attempt");
                    return;
                } catch (Throwable t) {
                    Premain.log.warn("Second attempt to set factory failed", t);
                }
            }

            final URLStreamHandlerFactory delegateFactory = existingFactory;
            URLStreamHandlerFactory wrapper = new URLStreamHandlerFactory() {
                @Override
                public URLStreamHandler createURLStreamHandler(final String protocol) {
                    try {
                        // Ask the existing factory first (if present)
                        URLStreamHandler handler = null;
                        if (delegateFactory != null) {
                            try {
                                handler = delegateFactory.createURLStreamHandler(protocol);
                            } catch (Throwable t) {
                                Premain.log.info("existing factory threw for protocol " + protocol, t);
                            }
                        }
                        // If existing factory returned null, try system default
                        if (handler == null) handler = getDefaultHandler(protocol);
                        if (handler == null) return null;
                        return wrapHandler(handler);
                    } catch (Throwable t) {
                        Premain.log.warn("Failed to create wrapped handler for: " + protocol, t);
                        return null;
                    }
                }
            };

            // Replace the private factory field with our wrapper (dangerous but necessary if factory already set).
            factoryField.set(null, wrapper);
            Premain.log.info("Replaced URL.factory reflectively with wrapper factory.");

        } catch (Throwable t) {
            Premain.log.error("Failed to wrap existing URL.factory reflectively", t);
        }
    }

    private static URLStreamHandler wrapHandler(final URLStreamHandler delegate) {
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
                    return openDefault(delegate, u);
                }
                return wrapConnection(u, openDefault(delegate, u));
            }
        };
    }

    private static URLStreamHandler getDefaultHandler(String protocol) {
        try {
            // Use reflection to create the default handler
            Class<?> cls = Class.forName("sun.net.www.protocol." + protocol + ".Handler");
            return (URLStreamHandler) cls.newInstance();
        } catch (Exception e) {
            return null;
        }
    }

    private static URLConnection wrapConnection(URL originalUrl, URLConnection originalConn) throws MalformedURLException {
        String host = originalUrl.getHost();
        String path = originalUrl.getPath();
        String query = originalUrl.getQuery();
        if (YGGDRASIL_MAP.containsKey(host)) { // yggdrasil
            try {
                final URL targetUrl = getYggdrasilUrl(originalUrl, "api.mojang.com");
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
        } else if (ALLOWED_DOMAINS.contains(host) && BLACKLISTED_PATHS.stream().noneMatch(path::startsWith)) {
            Premain.log.info("Intercepting texture: " + originalUrl);
            if (path.startsWith("/MinecraftSkins") || path.startsWith("/skin")) {
                String username = Ygglib.getUsernameFromPath(path);
                return Ygglib.getTexture(username, "SKIN");
            } else if (path.startsWith("/MinecraftCloaks")) {
                String username = Ygglib.getUsernameFromPath(path);
                return Ygglib.getTexture(username, "CAPE");
            } else if (path.equals("/cloak/get.jsp")) {
                String username = query.substring(query.lastIndexOf('=') + 1);
                return Ygglib.getTexture(username, "CAPE");
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
