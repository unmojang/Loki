package org.unmojang.loki;

import javax.net.ssl.*;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.concurrent.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class LokiUtil {
    private static boolean OFFLINE_MODE = false;
    public static boolean FOUND_ALI = false;

    @SuppressWarnings("unused")
    public static final int JAVA_MAJOR = getJavaVersion();

    private static boolean areWeOnline(String host) {
        int timeoutMs = 2000;

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Boolean> future = executor.submit(() -> {
            try {
                //noinspection ResultOfMethodCallIgnored
                InetAddress.getByName(host);
                return true;
            } catch (UnknownHostException e) {
                return false;
            }
        });

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return false;
        } catch (InterruptedException | ExecutionException e) {
            return false;
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean tryConnect(String url) throws UnknownHostException {
        try {
            HttpsURLConnection conn = (HttpsURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.connect();
            return true;
        } catch (javax.net.ssl.SSLHandshakeException ignored) {
            return false;
        } catch (UnknownHostException e) {
            throw e;
        } catch (Exception e) {
            Loki.log.error("Connection failed", e);
            throw new RuntimeException(e);
        }
    }

    public static String normalizeUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        return url;
    }

    public static void tryOrDisableSSL(String httpsUrl) {
        if (httpsUrl == null || httpsUrl.isEmpty() || httpsUrl.startsWith("http://")) return;
        String url = normalizeUrl(httpsUrl.toLowerCase());
        try {
            String host = new URL(url).getHost();
            if (!areWeOnline(host)) {
                Loki.log.warn(String.format("DNS lookup for %s timed out, are we offline? Disabling certificate validation!", host));
                OFFLINE_MODE = true;
            } else {
                boolean canConnect = tryConnect(url);
                if (canConnect) {
                    Loki.log.debug("Java's truststore is recent enough to connect to the API server");
                    return;
                } else {
                    Loki.log.warn("**** OUTDATED JAVA CERTIFICATE STORE DETECTED!");
                    Loki.log.warn("Certificate validation has been disabled to allow connections to the");
                    Loki.log.warn("API server. This allows Loki to function despite the old certificates,");
                    Loki.log.warn("but this is extremely insecure and may expose you to man-in-the-middle");
                    Loki.log.warn("attacks. You should upgrade to a more recent release of Java as soon");
                    Loki.log.warn("as possible to restore proper certificate validation.");
                }
            }

            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            }, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        } catch (Exception e) {
            Loki.log.error("Connection failed", e);
            throw new RuntimeException(e);
        }
    }

    public static String getAuthlibInjectorApiLocation(String server) {
        if (OFFLINE_MODE) {
            try {
                URL url = new URL(server);
                String path = url.getPath();
                if (path.isEmpty() || path.equals("/")) {
                    server = server.replaceAll("/$", "") + "/authlib-injector";
                    Loki.log.warn("Guessing Authlib-Injector API route: " + server);
                }
            } catch (MalformedURLException ignored) {}
            return server;
        }
        try {
            URL url = new URL(server);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setDoInput(true);
            conn.connect();
            return conn.getHeaderField("X-Authlib-Injector-Api-Location");
        } catch (Exception e) {
            Loki.log.error("Failed to get authlib-injector API location", e);
            return null;
        }
    }

    public static void initAuthlibInjectorAPI(String server) {
        server = normalizeUrl(server.toLowerCase());
        String authlibInjectorApiLocation = getAuthlibInjectorApiLocation(server);
        if (authlibInjectorApiLocation == null) authlibInjectorApiLocation = server;
        Loki.log.info("Using authlib-injector API Server: " + authlibInjectorApiLocation);
        System.setProperty("minecraft.api.env", "custom");
        System.setProperty("minecraft.api.account.host", authlibInjectorApiLocation + "/api");
        System.setProperty("minecraft.api.auth.host", authlibInjectorApiLocation + "/authserver");
        System.setProperty("minecraft.api.profiles.host", authlibInjectorApiLocation + "/api");
        System.setProperty("minecraft.api.session.host", authlibInjectorApiLocation + "/sessionserver");
        System.setProperty("minecraft.api.services.host", authlibInjectorApiLocation + "/minecraftservices");

        // Velocity
        System.setProperty("mojang.sessionserver", authlibInjectorApiLocation + "/sessionserver/session/minecraft/hasJoined");
    }

    public static void apply1219Fixes() {
        if (System.getProperty("minecraft.api.profiles.host") == null) {
            System.setProperty("minecraft.api.profiles.host", RequestInterceptor.YGGDRASIL_MAP.get("api.mojang.com"));
        } else if (System.getProperty("minecraft.api.account.host") == null) {
            System.setProperty("minecraft.api.account.host", RequestInterceptor.YGGDRASIL_MAP.get("api.mojang.com"));
        }
    }

    private static String getALIAgentArgsURL() {
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!arg.startsWith("-javaagent:") || arg.indexOf('=') == -1) continue;

            String jarPath = arg.substring("-javaagent:".length(), arg.indexOf('='));
            String agentArg = arg.substring(arg.indexOf('=') + 1);

            try (JarFile jarFile = new JarFile(jarPath)) {
                Enumeration<JarEntry> entries = jarFile.entries();

                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().startsWith("moe/yushi/authlibinjector/") ||
                            entry.getName().startsWith("org/to2mbn/authlibinjector/")) {

                        return agentArg;
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static void hijackALIAgentArgs() {
        Loki.log.warn("**** AUTHLIB-INJECTOR DETECTED!");
        Loki.log.warn("Authlib-Injector is in use, which could potentially break Loki!");
        Loki.log.warn("Loki has prevented Authlib-Injector from patching anything else, but");
        Loki.log.warn("it may have ran before Loki and already caused some problems! You");
        Loki.log.warn("are *strongly encouraged* to disable Authlib-Injector at this point!");

        String aliAgentArgsURL = getALIAgentArgsURL();

        if (aliAgentArgsURL != null) {
            LokiUtil.tryOrDisableSSL(aliAgentArgsURL);
            LokiUtil.initAuthlibInjectorAPI(aliAgentArgsURL);
        }
        FOUND_ALI = true;
    }

    public static void earlyInit(String agentArgs, Instrumentation inst) {
        // Ensure retransformation is supported
        if (!inst.isRetransformClassesSupported()) {
            Loki.log.error("Retransforming classes is not supported?!");
            throw new AssertionError();
        }

        // Authlib-Injector API
        String authlibInjectorURL = (System.getProperty("Loki.url") != null) // Prioritize Loki.url
                ? System.getProperty("Loki.url") : agentArgs;
        if (authlibInjectorURL != null) {
            LokiUtil.tryOrDisableSSL(authlibInjectorURL);
            LokiUtil.initAuthlibInjectorAPI(authlibInjectorURL);
        } else {
            String sessionHost = System.getProperty("minecraft.api.session.host",
                    "https://sessionserver.mojang.com");
            LokiUtil.tryOrDisableSSL(sessionHost);
            System.setProperty("mojang.sessionserver", sessionHost + "/session/minecraft/hasJoined"); // Velocity
        }
    }

    private static int getJavaVersion() {
        String version = System.getProperty("java.version");
        try {
            // Pre-Java 9
            if (version.startsWith("1.")) {
                return Integer.parseInt(version.substring(2, 3));
            } else {
                int dotIndex = version.indexOf('.');
                int dashIndex = version.indexOf('-');
                int endIndex = (dotIndex > 0) ? dotIndex : (dashIndex > 0 ? dashIndex : version.length());
                return Integer.parseInt(version.substring(0, endIndex));
            }
        } catch (Exception e) {
            return -1;
        }
    }

    public static void retransformClass(String className, Instrumentation inst) {
        try {
            Class<?> targetClass = Class.forName(className);
            inst.retransformClasses(targetClass);
        } catch (ClassNotFoundException ignored) {} catch (Throwable t) {
            Loki.log.error(String.format("Failed to retransform %s!", className), t);
        }
    }

    public static String getFqmn(String className, String name, String desc) {
        return className.replace('/', '.') + "::" + name + desc;
    }
}
