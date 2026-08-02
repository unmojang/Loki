package org.unmojang.loki;

import org.unmojang.loki.hooks.Hooks;
import org.unmojang.loki.hooks.LauncherHooks;
import org.unmojang.loki.util.HttpUtil;
import sun.misc.Unsafe;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.*;

public class RequestInterceptor {
    public static final Map<String, String> YGGDRASIL_MAP;
    private static final Set<String> INTERCEPTED_DOMAINS;
    public static final boolean IS_MOJANG;

    static {
        try {
            Hooks.DEFAULT_HANDLERS.put("http", getSystemURLHandler("http"));
            Hooks.DEFAULT_HANDLERS.put("https", getSystemURLHandler("https"));
        } catch (Exception e) {
            Loki.log.error("Failed to get system URL handler", e);
            Loki.disable_factory = true;
        }

        String accountHost = System.getProperty("minecraft.api.account.host",
                System.getProperty("minecraft.api.profiles.host"));
        String authHost = System.getProperty("minecraft.api.auth.host");
        String sessionHost = System.getProperty("minecraft.api.session.host");
        String servicesHost = System.getProperty("minecraft.api.services.host");

        Map<String, String> tmp = new ConcurrentHashMap<String, String>();
        putIfNotNull(tmp, "authserver.mojang.com", authHost != null ? authHost : LokiUtil.MANIFEST_ATTRS.get("AuthHost"));
        putIfNotNull(tmp, "api.mojang.com", accountHost != null ? accountHost : LokiUtil.MANIFEST_ATTRS.get("AccountHost"));
        putIfNotNull(tmp, "sessionserver.mojang.com", sessionHost != null ? sessionHost : LokiUtil.MANIFEST_ATTRS.get("SessionHost"));
        putIfNotNull(tmp, "api.minecraftservices.com", servicesHost != null ? servicesHost : LokiUtil.MANIFEST_ATTRS.get("ServicesHost"));

        YGGDRASIL_MAP = tmp;
        IS_MOJANG = "https://api.mojang.com".equals(YGGDRASIL_MAP.get("api.mojang.com"))
                && "https://sessionserver.mojang.com".equals(YGGDRASIL_MAP.get("sessionserver.mojang.com"));

        INTERCEPTED_DOMAINS = new HashSet<String>(Arrays.asList(
                "discovery.minecraftservices.com",
                "s3.amazonaws.com",
                "www.minecraft.net",
                "skins.minecraft.net",
                "session.minecraft.net",
                "launchermeta.mojang.com",
                "piston-meta.mojang.com",
                "resources.download.minecraft.net",
                "mcphackers.org",
                "vault.omniarchive.uk",
                "status.mojang.com",
                "betacraft.uk",
                "api.ashcon.app",
                "mineskin.eu",
                "minotar.net",
                "skinsystem.ely.by"
        ));
        if (!Loki.enable_snooper) {
            INTERCEPTED_DOMAINS.add("snoop.minecraft.net");
        }
        if (!Loki.modded_capes && !IS_MOJANG) {
            INTERCEPTED_DOMAINS.add("s.optifine.net");
            INTERCEPTED_DOMAINS.add("161.35.130.99"); // Cloaks+
            INTERCEPTED_DOMAINS.add("api.rumblecapes.xyz");
        }
    }

    private static void putIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null) map.put(key, value);
    }

    // authlib-injector specific endpoints
    public static void registerAliHost(String host, String url) {
        if (host != null && url != null) YGGDRASIL_MAP.put(host, url);
    }

    public static void setURLFactory() {
        Loki.log.debug("Arrived in setURLFactory");
        Loki.log.trace("Classpath: " + System.getProperty("java.class.path"));
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
            return;
        }
        URL.setURLStreamHandlerFactory(new URLStreamHandlerFactory() {
            public URLStreamHandler createURLStreamHandler(String protocol) {
                URLStreamHandler delegate = Hooks.DEFAULT_HANDLERS.get(protocol);
                if (delegate == null) return null;
                return (protocol.equals("http") || protocol.equals("https"))
                        ? new URLStreamHandlerProxy(delegate)
                        : delegate;
            }
        });
    }

    public static HttpURLConnection openWithParent(URL url, URLStreamHandler handler) throws IOException {
        try {
            // Use the URL constructor with null context to avoid global wrapper
            URL delegated = new URL(null, url.toExternalForm(), handler);
            return (HttpURLConnection) delegated.openConnection();
        } catch (ClassCastException e) {
            throw new RuntimeException("Handler did not return HttpURLConnection", e);
        }
    }

    private static URLConnection wrapConnection(URL originalUrl, URLConnection originalConn) throws UnknownHostException, UnsupportedEncodingException {
        if (!(originalConn instanceof HttpURLConnection)) return originalConn;
        String host = originalUrl.getHost();
        String path = originalUrl.getPath();
        String query = originalUrl.getQuery();
        Loki.log.debug("Connection: " + ((HttpURLConnection) originalConn).getRequestMethod() + " " + originalUrl);
        boolean isAPIServer = false;
        for (Object v : YGGDRASIL_MAP.values())
            if (v != null && v.toString().contains(host)) { isAPIServer = true; break; }
        if (YGGDRASIL_MAP.containsKey(host) || isAPIServer) {
            if (path.endsWith("/events") && !Loki.enable_snooper) { // Snooper (1.18+): https://api.minecraftservices.com/events
                Loki.log.info("Intercepting snooper request");
                return Ygglib.FakeURLConnection(originalUrl, originalConn, 403, ("Nice try ;)").getBytes("UTF-8"));
            }
        }
        if (YGGDRASIL_MAP.containsKey(host)) { // yggdrasil
            try {
                final URL targetUrl = Ygglib.getYggdrasilUrl(originalUrl, originalConn);
                Loki.log.info("Intercepting " + host + " request");
                Loki.log.debug(originalUrl + " -> " + targetUrl);
                return mirrorHttpURLConnection(targetUrl, (HttpURLConnection) originalConn);
            } catch (Exception e) {
                Loki.log.error("Failed to intercept " + originalUrl, e);
                return originalConn;
            }
        } else if (INTERCEPTED_DOMAINS.contains(host)) {
            // Endpoint discovery (26.3+)
            if (host.equals("discovery.minecraftservices.com")) {
                try {
                    Loki.log.info("Intercepting discovery request");
                    return Ygglib.FakeURLConnection(originalUrl, originalConn, 200, Hooks.getDiscoveryJson().getBytes("UTF-8"));
                } catch (Exception e) {
                    Loki.log.error("Failed to serve discovery document", e);
                    return originalConn;
                }
            }

            // Authentication
            if (path.equals("/game/joinserver.jsp")) {
                Loki.log.info("Intercepting joinserver request");
                return Ygglib.joinServer(originalUrl, originalConn);
            } else if (path.equals("/game/checkserver.jsp")) {
                Loki.log.info("Intercepting checkserver request");
                return Ygglib.checkServer(originalUrl, originalConn);
            } else if (path.equals("/heartbeat.jsp")) {
                try {
                    final URL targetUrl = new URL(RequestInterceptor.YGGDRASIL_MAP.get("sessionserver.mojang.com") + "/heartbeat.jsp" + (query != null && query.length() != 0 ? "?" + query : ""));
                    Loki.log.debug("Intercepting heartbeat");
                    Loki.log.debug(originalUrl + " -> " + targetUrl);
                    return mirrorHttpURLConnection(targetUrl, (HttpURLConnection) originalConn);
                } catch (Exception e) {
                    Loki.log.error("Failed to intercept " + originalUrl, e);
                    return originalConn;
                }
            }

            // Textures
            if (path.startsWith("/MinecraftSkins") || path.startsWith("/skin")) {
                String username = Ygglib.getUsernameFromPath(path);
                Loki.log.info("Intercepting skin lookup for " + username);
                return Ygglib.getTexture(originalUrl, originalConn, username, "SKIN");
            } else if (path.startsWith("/MinecraftCloaks")) {
                String username = Ygglib.getUsernameFromPath(path);
                Loki.log.info("Intercepting cape lookup for " + username);
                return Ygglib.getTexture(originalUrl, originalConn, username, "CAPE");
            } else if (path.equals("/cloak/get.jsp")) {
                String username = Ygglib.queryStringParser(query).get("user");
                Loki.log.info("Intercepting cape lookup for " + username);
                return Ygglib.getTexture(originalUrl, originalConn, username, "CAPE");
            }

            // Snooper
            if (host.equals("snoop.minecraft.net")) {
                Loki.log.info("Intercepting snooper request");
                return Ygglib.FakeURLConnection(originalUrl, originalConn, 403, ("Nice try ;)").getBytes("UTF-8"));
            }

            // Launcher
            if (host.equals("s3.amazonaws.com") && (path.startsWith("/MinecraftDownload/")
                    || path.startsWith("/Minecraft.Download/"))) {
                try {
                    if (path.startsWith("/MinecraftDownload/minecraft.jar") && LokiUtil.LAUNCHER_VERSION_URL != null) { // game jar
                        return mirrorHttpURLConnectionWithETag(LokiUtil.LAUNCHER_VERSION_URL, (HttpURLConnection) originalConn);
                    }

                    if (path.startsWith("/MinecraftDownload/")) {
                        List<String> urls = LauncherHooks.getAppletLibraryUrls(path.substring("/MinecraftDownload/".length()));
                        if (urls != null && !urls.isEmpty()) {
                            if (urls.size() == 1) {
                                return mirrorHttpURLConnectionWithETag(new URL(urls.get(0)), (HttpURLConnection) originalConn);
                            }
                            return mergedJarConnection(urls, (HttpURLConnection) originalConn);
                        }
                    }

                    if (path.equals("/Minecraft.Download/versions/versions.json")) {
                        return byteServingConnection(Ygglib.getReclassifiedManifest(), originalUrl, null);
                    } else if (path.startsWith("/Minecraft.Download/versions/")) {
                        if (path.endsWith(".json")) {
                            String version = path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.json$", "");
                            URL versionJsonURL = Ygglib.getVersionJsonURL(version);
                            if (versionJsonURL != null) {
                                // Adapt the modern version JSON for older 1.6 launchers
                                URLStreamHandler handler = Hooks.DEFAULT_HANDLERS.get(versionJsonURL.getProtocol());
                                String rewritten = Ygglib.rewriteVersionJsonForLegacyLauncher(
                                        HttpUtil.readStream(openWithParent(versionJsonURL, handler).getInputStream()));
                                return Ygglib.FakeURLConnection(originalUrl, originalConn, 200, rewritten.getBytes("UTF-8"));
                            }
                        } else if (path.endsWith(".jar")) {
                            String version = path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.jar$", "");
                            URL versionJarURL = Ygglib.getVersionJarURL(version);
                            if (versionJarURL != null) return mirrorHttpURLConnectionWithETag(versionJarURL, (HttpURLConnection) originalConn);
                        }
                    } else if (path.startsWith("/Minecraft.Download/")) {
                        // Pre-1.0 1.6 launchers request /Minecraft.Download/<mavenPath>, without libraries prefix
                        String libPath = path.substring("/Minecraft.Download/".length());
                        if (libPath.startsWith("libraries/")) libPath = libPath.substring("libraries/".length());
                        String mappedUrl = LauncherHooks.libraryUrlMap.get(libPath);
                        URL libraryURL = mappedUrl != null ? new URL(mappedUrl) : new URL("https://libraries.minecraft.net/" + libPath);
                        return mirrorHttpURLConnectionWithETag(libraryURL, (HttpURLConnection) originalConn);
                    }
                } catch (Exception ignored) {}
            }

            // Replace version manifest with BetterJSONs
            if (host.equals("launchermeta.mojang.com") && path.equals("/mc/game/version_manifest.json")) {
                try {
                    return byteServingConnection(Ygglib.getReclassifiedManifest(), originalUrl, null);
                } catch (Exception e) {
                    Loki.log.error("Failed to serve BetterJSONs manifest", e);
                    return originalConn;
                }
            }

            // Rewrite to appease 1.6 launcher
            if (host.equals("mcphackers.org") && path.startsWith("/BetterJSONs/jsons/") && path.endsWith(".json")) {
                try {
                    URLStreamHandler handler = Hooks.DEFAULT_HANDLERS.get(originalUrl.getProtocol());
                    String rewritten = Ygglib.stripUnsupportedNatives(
                            HttpUtil.readStream(openWithParent(originalUrl, handler).getInputStream()));
                    return Ygglib.FakeURLConnection(originalUrl, originalConn, 200, rewritten.getBytes("UTF-8"));
                } catch (Exception e) {
                    Loki.log.error("Failed to rewrite BetterJSONs version JSON", e);
                    return originalConn;
                }
            }

            // Attach ETag to appease 1.6 launcher
            if (host.equals("resources.download.minecraft.net")
                    || host.equals("piston-meta.mojang.com")
                    || host.equals("launchermeta.mojang.com")
                    || host.equals("mcphackers.org")
                    || host.equals("vault.omniarchive.uk")) {
                try {
                    URL target = "http".equals(originalUrl.getProtocol())
                            ? new URL("https" + originalUrl.toExternalForm().substring(4))
                            : originalUrl;
                    return mirrorHttpURLConnectionWithETag(target, (HttpURLConnection) originalConn);
                } catch (Exception e) {
                    Loki.log.error("Failed to intercept " + originalUrl, e);
                    return originalConn;
                }
            }

            // Resources
            String resourcePrefix = null;
            if (host.equals("s3.amazonaws.com") && path.startsWith("/Minecraft.Resources")) resourcePrefix = "/Minecraft.Resources";
            else if (host.equals("s3.amazonaws.com") && path.startsWith("/MinecraftResources")) resourcePrefix = "/MinecraftResources";
            else if (host.equals("www.minecraft.net") && path.startsWith("/resources")) resourcePrefix = "/resources";
            if (resourcePrefix != null) {
                try {
                    String key = path.substring(resourcePrefix.length());
                    if (key.startsWith("/")) key = key.substring(1);
                    if (key.length() == 0) {
                        byte[] listing = host.equals("www.minecraft.net")
                                ? Ygglib.getLegacyResourcesListingPlain() // pre-a1.1.2_01
                                : Ygglib.getLegacyResourcesListing();     // a1.1.2_01-1.5.2
                        return Ygglib.FakeURLConnection(originalUrl, originalConn, 200, listing);
                    }
                    URL resourceURL = Ygglib.getLegacyResourceURL(key);
                    if (resourceURL != null) return mirrorHttpURLConnectionWithETag(resourceURL, (HttpURLConnection) originalConn);
                } catch (Exception ignored) {}
            }

            // Mojang Status
            if (host.equals("status.mojang.com") && path.equals("/check")) {
                String color = Hooks.OFFLINE_MODE ? "red" : "green";
                String[] services = {
                        "minecraft.net", "login.minecraft.net", "session.minecraft.net",
                        "account.mojang.com", "auth.mojang.com", "skins.minecraft.net",
                        "authserver.mojang.com", "sessionserver.mojang.com", "api.mojang.com",
                        "textures.minecraft.net", "mojang.com"
                };
                StringBuilder status = new StringBuilder("[");
                for (int i = 0; i < services.length; i++) {
                    if (i > 0) status.append(",");
                    status.append("{\"").append(services[i]).append("\":\"").append(color).append("\"}");
                }
                status.append("]\n");
                return Ygglib.FakeURLConnection(originalUrl, originalConn, 200, status.toString().getBytes("UTF-8"));
            }

            // Misc
            if (host.equals("api.ashcon.app") && path.matches("^/mojang/[^/]+/user/.*")) {
                String username = Ygglib.getUsernameFromPath(originalUrl.getPath());
                Loki.log.info("Intercepting api.ashcon.app lookup for " + username);
                return Ygglib.getAshcon(originalUrl, originalConn, username);
            }

            if (host.equals("minotar.net") && (path.startsWith("/helm") || path.startsWith("/avatar"))) {
                try {
                    String[] segments = path.split("/");
                    if (segments.length < 3 || segments[2].length() == 0) return originalConn;
                    String username = segments[2].replaceFirst("\\.[A-Za-z0-9]+$", "");
                    int res = 64;
                    if (segments.length > 3) {
                        try {
                            res = Integer.parseInt(segments[3].replaceFirst("\\..*$", ""));
                        } catch (NumberFormatException ignored) {}
                    }
                    Loki.log.info(String.format("Intercepting minotar.net lookup for %s (%s px)", username, res));
                    return Ygglib.getMinotar(originalUrl, originalConn, username, res);
                } catch (Exception e) {
                    Loki.log.error("Failed to intercept " + originalUrl, e);
                    return originalConn;
                }
            }

            if (host.equals("skinsystem.ely.by") && path.startsWith("/textures")) {
                String username = Ygglib.getUsernameFromPath(path);
                Loki.log.info("Intercepting ely.by lookup for " + username);
                return Ygglib.getElyBy(originalUrl, originalConn, username);
            }

            // Capes
            if (host.equals("s.optifine.net") && path.startsWith("/capes")) {
                Loki.log.info("Intercepting OptiFine cape lookup");
                return Ygglib.FakeURLConnection(originalUrl, originalConn, 403, ("Nice try ;)").getBytes("UTF-8"));
            }

            if (host.equals("161.35.130.99") && path.startsWith("/capes")) {
                Loki.log.info("Intercepting Cloaks+ cape lookup");
                return Ygglib.FakeURLConnection(originalUrl, originalConn, 403, ("Nice try ;)").getBytes("UTF-8"));
            }
        }

        return originalConn;
    }

    public static HttpURLConnection mirrorHttpURLConnection(URL targetUrl, HttpURLConnection httpConn) throws IOException {
        URLStreamHandler handler = Hooks.DEFAULT_HANDLERS.get(targetUrl.getProtocol());
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

        // Omniarchive rejects the Java user agent
        String targetHost = targetUrl.getHost();
        if (targetHost != null && targetHost.endsWith("omniarchive.uk")) {
            targetConn.setRequestProperty("User-Agent", "Loki/" + LokiUtil.getAgentVersion());
        }
        return targetConn;
    }

    public static HttpURLConnection mirrorHttpURLConnectionWithETag(final URL targetUrl, HttpURLConnection httpConn) throws IOException {
        final HttpURLConnection targetConn = mirrorHttpURLConnection(targetUrl, httpConn);

        // Spill to a temp file while hashing so a large jar isn't held in memory
        File spill = File.createTempFile("loki-mirror", ".tmp");
        spill.deleteOnExit();
        String etag;
        InputStream is = null;
        OutputStream os = null;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            is = targetConn.getInputStream();
            os = new FileOutputStream(spill);
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) {
                md.update(buf, 0, r);
                os.write(buf, 0, r);
            }
            etag = formatEtag(md.digest());
        } catch (Exception e) {
            if (!spill.delete()) spill.deleteOnExit();
            targetConn.disconnect();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        } finally {
            if (is != null) try { is.close(); } catch (IOException ignored) {}
            if (os != null) try { os.close(); } catch (IOException ignored) {}
        }
        return fileServingConnection(spill, etag, targetUrl, targetConn);
    }

    // Construct fat JARs for applet launcher
    private static HttpURLConnection mergedJarConnection(List<String> urls, HttpURLConnection httpConn) throws IOException {
        ByteArrayOutputStream merged = new ByteArrayOutputStream();
        ZipOutputStream zout = new ZipOutputStream(merged);
        Set<String> seen = new HashSet<String>();
        byte[] buf = new byte[8192];
        for (String u : urls) {
            ZipInputStream zin = new ZipInputStream(mirrorHttpURLConnection(new URL(u), httpConn).getInputStream());
            try {
                ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory() || name.startsWith("META-INF/") || !seen.add(name)) continue;
                    zout.putNextEntry(new ZipEntry(name));
                    int r;
                    while ((r = zin.read(buf)) != -1) zout.write(buf, 0, r);
                    zout.closeEntry();
                }
            } finally {
                zin.close();
            }
        }
        zout.close();
        return byteServingConnection(merged.toByteArray(), httpConn.getURL(), null);
    }

    // Serves a buffered byte[] as the connection body with a computed MD5 ETag
    private static HttpURLConnection byteServingConnection(final byte[] data, URL url, final HttpURLConnection backing) {
        return new HttpURLConnection(url) {
            @Override public void connect() {}
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(data); }
            @Override public String getHeaderField(String name) {
                if ("ETag".equalsIgnoreCase(name)) return md5Etag(data);
                return backing != null ? backing.getHeaderField(name) : null;
            }
            @Override public int getResponseCode() throws IOException {
                return backing != null ? backing.getResponseCode() : 200;
            }
            @Override public void disconnect() { if (backing != null) backing.disconnect(); }
            @Override public boolean usingProxy() { return backing != null && backing.usingProxy(); }
        };
    }

    // Serves a temp file as the connection body, deleting it once the caller disconnects
    private static HttpURLConnection fileServingConnection(final File file, final String etag, URL url, final HttpURLConnection backing) {
        return new HttpURLConnection(url) {
            @Override public void connect() {}
            @Override public InputStream getInputStream() throws IOException {
                return new BufferedInputStream(new FileInputStream(file));
            }
            @Override public String getHeaderField(String name) {
                if ("ETag".equalsIgnoreCase(name)) return etag;
                return backing != null ? backing.getHeaderField(name) : null;
            }
            @Override public int getResponseCode() throws IOException {
                return backing != null ? backing.getResponseCode() : 200;
            }
            @Override public void disconnect() {
                if (!file.delete()) file.deleteOnExit();
                if (backing != null) backing.disconnect();
            }
            @Override public boolean usingProxy() { return backing != null && backing.usingProxy(); }
        };
    }

    private static String md5Etag(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return formatEtag(md.digest(data));
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatEtag(byte[] digest) {
        BigInteger bi = new BigInteger(1, digest);
        StringBuilder etag = new StringBuilder(bi.toString(16));
        while (etag.length() < 32) etag.insert(0, "0");
        return "\"" + etag + "\"";
    }

    private static URLStreamHandler getSystemURLHandler(String protocol) throws Exception {
        URL url = new URL(protocol + ":"); // create a temporary URL
        Field handlerField = URL.class.getDeclaredField("handler");
        Unsafe unsafe = getUnsafe();
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
                Method m = URLStreamHandler.class.getDeclaredMethod("openConnection", URL.class, Proxy.class);
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
        int major, minor, patch;
        try {
            major = verParts.length > 0 ? Integer.parseInt(verParts[0]) : 0;
            minor = verParts.length > 1 ? Integer.parseInt(verParts[1]) : 0;
            patch = verParts.length > 2 ? Integer.parseInt(verParts[2]) : 0;
        } catch (NumberFormatException e) {
            Loki.log.warn("Could not parse FML version from " + filename);
            return;
        }

        // If major is 48, ensure we are running 48.0.31 or below
        if (major == 48 && (minor > 0 || (minor == 0 && patch > 31))) {
            Loki.log.error("LexForge version is not suported: " + fmlVersionStr);
            Loki.log.error("Please downgrade to LexForge 48.0.31, or use NeoForge!");
            Loki.log.error("Loki is terminating the game to prevent an imminent crash!");
            Loki.log.error("More details here: https://github.com/unmojang/Loki/issues/7");
            System.exit(1);
        }
    }
}
