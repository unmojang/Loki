package org.unmojang.loki;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public class LokiUtil {
    public static void loadCacerts() {
        // Load cacert.pem from resources
        try (InputStream is = LokiUtil.class.getResourceAsStream("/cacert.pem")) {
            if (is == null) throw new RuntimeException("cacert.pem not found in resources");

            CertificateFactory cf = CertificateFactory.getInstance("X.509");

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);

            int certIndex = 0;
            for (Certificate cert : cf.generateCertificates(is)) {
                if (cert instanceof X509Certificate) {
                    ks.setCertificateEntry("cert" + certIndex++, cert);
                }
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);

            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, tmf.getTrustManagers(), null);
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

            // Disable hostname verification
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            Premain.log.error("Failed to load CA certs from cacert.pem", e);
            return;
        }
        Premain.log.info("Loaded CA certs from cacert.pem");
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
            Premain.log.error("Failed to get authlib-injector API location", e);
            return null;
        }
    }

    public static void initAuthlibInjectorAPI(String server) {
        if (!(server.startsWith("http://") || server.startsWith("https://"))) return;
        String authlibInjectorApiLocation = getAuthlibInjectorApiLocation(server);
        if (authlibInjectorApiLocation == null) authlibInjectorApiLocation = server;
        Premain.log.info("Using authlib-injector API");

        System.setProperty("minecraft.api.env", "custom");
        System.setProperty("minecraft.api.account.host", authlibInjectorApiLocation + "/api");
        System.setProperty("minecraft.api.auth.host", authlibInjectorApiLocation + "/authserver");
        System.setProperty("minecraft.api.profiles.host", authlibInjectorApiLocation + "/api");
        System.setProperty("minecraft.api.session.host", authlibInjectorApiLocation + "/sessionserver");
        System.setProperty("minecraft.api.services.host", authlibInjectorApiLocation + "/minecraftservices");
    }

    public static void apply1219Fixes() {
        if (System.getProperty("minecraft.api.profiles.host") == null) {
            Premain.log.info("Applying 1.21.9+ fixes");
            System.setProperty("minecraft.api.profiles.host", RequestInterceptor.YGGDRASIL_MAP.get("api.mojang.com"));
        } else if (System.getProperty("minecraft.api.account.host") == null) {
            Premain.log.info("Applying <1.21.9 fixes");
            System.setProperty("minecraft.api.account.host", RequestInterceptor.YGGDRASIL_MAP.get("api.mojang.com"));
        }
    }

    public static void unsetVanillaEnv() {
        Premain.log.info("Clearing minecraft.api.*.host parameters");

        System.clearProperty("minecraft.api.env");
        System.clearProperty("minecraft.api.account.host");
        System.clearProperty("minecraft.api.profiles.host");
        System.clearProperty("minecraft.api.auth.host");
        System.clearProperty("minecraft.api.session.host");
        System.clearProperty("minecraft.api.services.host");
    }
}
