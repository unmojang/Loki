package org.unmojang.loki;

import org.unmojang.loki.hooks.Hooks;
import org.unmojang.loki.hooks.LauncherHooks;
import sun.misc.Unsafe;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
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
        String signalingHost = System.getProperty("minecraft.api.signaling.host");

        Map<String, String> tmp = new HashMap<String, String>();
        tmp.put("authserver.mojang.com", authHost != null ? authHost : LokiUtil.MANIFEST_ATTRS.get("AuthHost"));
        tmp.put("api.mojang.com", accountHost != null ? accountHost : LokiUtil.MANIFEST_ATTRS.get("AccountHost"));
        tmp.put("sessionserver.mojang.com", sessionHost != null ? sessionHost : LokiUtil.MANIFEST_ATTRS.get("SessionHost"));
        tmp.put("api.minecraftservices.com", servicesHost != null ? servicesHost : LokiUtil.MANIFEST_ATTRS.get("ServicesHost"));
        tmp.put("signaling-afd.franchise.minecraft-services.net", signalingHost != null ? signalingHost : LokiUtil.MANIFEST_ATTRS.get("SignalingHost"));
        YGGDRASIL_MAP = Collections.unmodifiableMap(tmp);
        IS_MOJANG = YGGDRASIL_MAP.get("api.mojang.com").equals("https://api.mojang.com")
                && YGGDRASIL_MAP.get("sessionserver.mojang.com").equals("https://sessionserver.mojang.com");

        INTERCEPTED_DOMAINS = new HashSet<String>(Arrays.asList(
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
                                        Ygglib.readStream(openWithParent(versionJsonURL, handler).getInputStream()));
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
                            Ygglib.readStream(openWithParent(originalUrl, handler).getInputStream()));
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
                if (Hooks.OFFLINE_MODE) {
                    return Ygglib.FakeURLConnection(originalUrl, originalConn, 200, (
                            "[{\"minecraft.net\":\"red\"},{\"login.minecraft.net\":\"red\"}," +
                                    "{\"session.minecraft.net\":\"red\"},{\"account.mojang.com\":\"red\"}," +
                                    "{\"auth.mojang.com\":\"red\"},{\"skins.minecraft.net\":\"red\"}," +
                                    "{\"authserver.mojang.com\":\"red\"},{\"sessionserver.mojang.com\":\"red\"}," +
                                    "{\"api.mojang.com\":\"red\"},{\"textures.minecraft.net\":\"red\"}," +
                                    "{\"mojang.com\":\"red\"}]\n"
                    ).getBytes("UTF-8"));
                } else {
                    return Ygglib.FakeURLConnection(originalUrl, originalConn, 200, (
                            "[{\"minecraft.net\":\"green\"},{\"login.minecraft.net\":\"green\"}," +
                                    "{\"session.minecraft.net\":\"green\"},{\"account.mojang.com\":\"green\"}," +
                                    "{\"auth.mojang.com\":\"green\"},{\"skins.minecraft.net\":\"green\"}," +
                                    "{\"authserver.mojang.com\":\"green\"},{\"sessionserver.mojang.com\":\"green\"}," +
                                    "{\"api.mojang.com\":\"green\"},{\"textures.minecraft.net\":\"green\"}," +
                                    "{\"mojang.com\":\"green\"}]\n"
                    ).getBytes("UTF-8"));
                }
            }

            // Misc
            if (host.equals("api.ashcon.app") && path.matches("^/mojang/[^/]+/user/.*")) {
                String username = Ygglib.getUsernameFromPath(originalUrl.getPath());
                Loki.log.info("Intercepting api.ashcon.app lookup for " + username);
                return Ygglib.getAshcon(originalUrl, originalConn, username);
            }

            if (host.equals("minotar.net") && (path.startsWith("/helm") || path.startsWith("/avatar"))) {
                String username = path.split("/")[2];
                int res = Integer.parseInt(path.split("/")[3].replaceFirst("\\..*$", ""));
                Loki.log.info(String.format("Intercepting minotar.net lookup for %s (%s px)", username, res));
                return Ygglib.getMinotar(originalUrl, originalConn, username, res);
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

    private static HttpURLConnection openMirroredConnection(URL targetUrl, HttpURLConnection httpConn) throws IOException {
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
            targetConn.setRequestProperty("User-Agent", "Loki/" + LokiUtil.class.getPackage().getImplementationVersion());
        }
        return targetConn;
    }

    public static HttpURLConnection mirrorHttpURLConnection(URL targetUrl, HttpURLConnection httpConn) throws IOException {
        final HttpURLConnection targetConn = openMirroredConnection(targetUrl, httpConn);

        // Mirror body if present
        if (httpConn.getDoOutput()) {
            targetConn.setDoOutput(true);
            InputStream is = null;
            OutputStream os = null;
            try {
                is = httpConn.getInputStream();
                os = targetConn.getOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
            } finally {
                if (is != null) is.close();
                if (os != null) os.close();
            }
        }
        return targetConn;
    }

    public static HttpURLConnection mirrorHttpURLConnectionWithETag(final URL targetUrl, HttpURLConnection httpConn) throws IOException {
        final HttpURLConnection targetConn = openMirroredConnection(targetUrl, httpConn);

        // Preload the response to compute an ETag
        byte[] data;
        try {
            InputStream is = targetConn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
            is.close();
            data = bos.toByteArray();
        } catch (IOException e) {
            targetConn.disconnect();
            throw e;
        }
        return byteServingConnection(data, targetUrl, targetConn);
    }

    // Construct fat JARs for applet launcher
    private static HttpURLConnection mergedJarConnection(List<String> urls, HttpURLConnection httpConn) throws IOException {
        ByteArrayOutputStream merged = new ByteArrayOutputStream();
        ZipOutputStream zout = new ZipOutputStream(merged);
        Set<String> seen = new HashSet<String>();
        byte[] buf = new byte[8192];
        for (String u : urls) {
            ZipInputStream zin = new ZipInputStream(openMirroredConnection(new URL(u), httpConn).getInputStream());
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

    private static String md5Etag(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            BigInteger bi = new BigInteger(1, md.digest(data));
            StringBuilder etag = new StringBuilder(bi.toString(16));
            while (etag.length() < 32) etag.insert(0, "0");
            return "\"" + etag + "\"";
        } catch (Exception e) {
            return null;
        }
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
}
