package com.unmojang.loki;

import nilloader.api.lib.nanojson.JsonParser;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class NetUtil { // TODO use authlib-injector functions
    public static void loadCacerts() {
        // Load cacert.pem from resources
        try (InputStream is = NetUtil.class.getResourceAsStream("/cacert.pem")) {
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
            e.printStackTrace();
            return;
        }
        Premain.log.info("Loaded CA certs from cacert.pem");
    }

    public static HttpURLConnection request(String method, String urlString, String body, String contentType) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method.toUpperCase());
            conn.setDoInput(true);

            if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
                conn.setDoOutput(true);
                if (contentType != null) {
                    conn.setRequestProperty("Content-Type", contentType);
                }
                if (body != null) {
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }

            conn.connect();
            return conn;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String getAuthlibInjectorApiLocation(String server) {
        try {
            HttpURLConnection conn = request("GET", server, null, null);
            if (conn == null) return null;
            return conn.getHeaderField("X-Authlib-Injector-Api-Location");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getAuthlibInjectorConfig(String server) {
        try {
            HttpURLConnection conn = request("GET", server, null, null);
            if (conn == null) return null;

            try (InputStream is = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

                String content = reader.lines().collect(Collectors.joining("\n"));
                Map<String, Object> obj = JsonParser.object().from(content); // parse JSON as Map

                Map<String, Object> meta = (Map<String, Object>) obj.get("meta");
                if (meta == null || !(Boolean) meta.getOrDefault("feature.enable_profile_key", false)) {
                    return null;
                }

                String implementationName = (String) meta.get("implementationName");
                String implementationVersion = (String) meta.get("implementationVersion");
                String serverName = (String) meta.get("serverName");

                String publicKey = ((String) obj.get("signaturePublickey"))
                        .replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "")
                        .replaceAll("\\s+", "");

                List<Object> skinDomainsList = (List<Object>) obj.get("skinDomains");
                String[] skinDomains = skinDomainsList.stream()
                        .map(Object::toString)
                        .toArray(String[]::new);

                Map<String, Object> result = new HashMap<>();
                result.put("implementationName", implementationName);
                result.put("implementationVersion", implementationVersion);
                result.put("serverName", serverName);
                result.put("publicKey", publicKey);
                result.put("skinDomains", skinDomains);

                return result;
            }

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
