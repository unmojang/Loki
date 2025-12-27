package org.unmojang.loki;

import javax.net.ssl.*;
import java.lang.instrument.Instrumentation;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LokiUtil {
    private static final ExecutorService ASYNC_EXECUTOR =
            Executors.newCachedThreadPool();

    @SuppressWarnings("unused")
    public static final int JAVA_MAJOR = getJavaVersion();

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
        ASYNC_EXECUTOR.submit(() -> doTryOrDisableSSL(httpsUrl));
    }

    private static void doTryOrDisableSSL(String httpsUrl) {
        if(httpsUrl == null || httpsUrl.isEmpty() || httpsUrl.startsWith("http://")) return;
        String url = normalizeUrl(httpsUrl.toLowerCase());
        try {
            try {
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
            } catch (UnknownHostException e) {
                Loki.log.error("Host lookup failed, are we offline? Disabling certificate validation!");
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
        Loki.log.info("Using authlib-injector API Server: " + server);
        String authlibInjectorApiLocation = getAuthlibInjectorApiLocation(server);
        if (authlibInjectorApiLocation == null) authlibInjectorApiLocation = server;
        System.setProperty("minecraft.api.env", "custom");
        System.setProperty("minecraft.api.account.host", authlibInjectorApiLocation + "/api");
        System.setProperty("minecraft.api.auth.host", authlibInjectorApiLocation + "/authserver");
        System.setProperty("minecraft.api.profiles.host", authlibInjectorApiLocation + "/api");
        System.setProperty("minecraft.api.session.host", authlibInjectorApiLocation + "/sessionserver");
        System.setProperty("minecraft.api.services.host", authlibInjectorApiLocation + "/minecraftservices");
    }

    public static void apply1219Fixes() {
        if (System.getProperty("minecraft.api.profiles.host") == null) {
            Loki.log.debug("Applying 1.21.9+ fixes");
            System.setProperty("minecraft.api.profiles.host", RequestInterceptor.YGGDRASIL_MAP.get("api.mojang.com"));
        } else if (System.getProperty("minecraft.api.account.host") == null) {
            Loki.log.debug("Applying <1.21.9 fixes");
            System.setProperty("minecraft.api.account.host", RequestInterceptor.YGGDRASIL_MAP.get("api.mojang.com"));
        }
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
        if(authlibInjectorURL != null) {
            LokiUtil.tryOrDisableSSL(authlibInjectorURL);
            LokiUtil.initAuthlibInjectorAPI(authlibInjectorURL);
        } else {
            LokiUtil.tryOrDisableSSL(System.getProperty("minecraft.api.session.host",
                    "https://sessionserver.mojang.com")); // fallback
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
}
