package org.unmojang.loki.util;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.BCSSLParameters;
import org.bouncycastle.jsse.BCSSLSocket;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.*;
import java.security.cert.X509Certificate;

// Thanks LegacyFix!
// https://github.com/betacraftuk/legacyfix/blob/9c8919e0b5455fec4cc3d5fca200cde3e426e243/src/main/java/uk/betacraft/legacyfix/util/BouncyCastleUtils.java
public class BouncyCastleUtils {
    public static void init() throws NoSuchAlgorithmException, NoSuchProviderException, KeyManagementException {
        System.setProperty("org.bouncycastle.jsse.client.assumeOriginalHostName", "true");
        Security.setProperty("ssl.SocketFactory.provider", "org.bouncycastle.jsse.provider.SSLSocketFactoryImpl");
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
        Security.insertProviderAt(new BouncyCastleJsseProvider(), 2);

        SSLContext sc = SSLContext.getInstance("TLSv1.2", "BCJSSE");
        sc.init(null, new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        }, new SecureRandom());
        final SSLSocketFactory factory = sc.getSocketFactory();

        SSLSocketFactory wrappedFactory = new SSLSocketFactory() {
            private BCSSLParameters configureBCSocket(BCSSLSocket sock) {
                BCSSLParameters params = sock.getParameters();
                params.setProtocols(new String[]{"TLSv1.2"});
                params.setCipherSuites(new String[]{
                        "TLS_RSA_WITH_AES_128_CBC_SHA",
                        "TLS_RSA_WITH_AES_256_CBC_SHA"
                });
                params.setNamedGroups(new String[]{"secp256r1","secp384r1"});
                return params;
            }

            private Socket wrap(Socket s) {
                if (s instanceof BCSSLSocket) {
                    BCSSLSocket bcSock = (BCSSLSocket) s;
                    bcSock.setParameters(configureBCSocket(bcSock));
                }
                return s;
            }

            @Override
            public String[] getDefaultCipherSuites() { return factory.getDefaultCipherSuites(); }
            @Override
            public String[] getSupportedCipherSuites() { return factory.getSupportedCipherSuites(); }

            @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException { return wrap(factory.createSocket(s, host, port, autoClose)); }
            @Override public Socket createSocket(String host, int port) throws IOException { return wrap(factory.createSocket(host, port)); }
            @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException { return wrap(factory.createSocket(host, port, localHost, localPort)); }
            @Override public Socket createSocket(InetAddress host, int port) throws IOException { return wrap(factory.createSocket(host, port)); }
            @Override public Socket createSocket(InetAddress host, int port, InetAddress localHost, int localPort) throws IOException { return wrap(factory.createSocket(host, port, localHost, localPort)); }
        };
        HttpsURLConnection.setDefaultSSLSocketFactory(wrappedFactory);

        HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
            public boolean verify(String hostname, SSLSession session) {
                return true;
            }
        });
    }
}


