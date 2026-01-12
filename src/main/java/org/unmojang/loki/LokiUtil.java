package org.unmojang.loki;

import javax.net.ssl.*;
import java.io.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.*;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.jar.*;

public class LokiUtil {
    private static boolean OFFLINE_MODE = false;
    public static boolean FOUND_ALI = false;
    public static final Map<String, String> MANIFEST_ATTRS = new ConcurrentHashMap<String, String>();

    public static final int JAVA_MAJOR = getJavaVersion();

    public static void initManifestAttributes() {
        try {
            CodeSource codeSource = LokiUtil.class.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                JarFile jar = null;
                try {
                    jar = new JarFile(new File(codeSource.getLocation().toURI()));
                    for (Map.Entry<Object, Object> entry : jar.getManifest().getMainAttributes().entrySet()) {
                        MANIFEST_ATTRS.put(entry.getKey().toString(), entry.getValue().toString());
                    }
                } finally {
                    if (jar != null) jar.close();
                }
            }
        } catch (Throwable t) {
            Loki.log.error("Failed to read manifest attributes", t);
        }
    }

    private static boolean areWeOnline(final String host) {
        int timeoutMs = 2000;

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Boolean> future = executor.submit(new Callable<Boolean>() {
            public Boolean call() {
                try {
                    //noinspection ResultOfMethodCallIgnored
                    InetAddress.getByName(host);
                    return true;
                } catch (UnknownHostException e) {
                    return false;
                }
            }
        });

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return false;
        } catch (ExecutionException e) {
            return false;
        } catch (InterruptedException e) {
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
        if (OFFLINE_MODE || httpsUrl == null || httpsUrl.length() == 0 || httpsUrl.startsWith("http://")) return;
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
                if (path.length() == 0 || path.equals("/")) {
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
            String apiLocation = conn.getHeaderField("X-Authlib-Injector-Api-Location");
            if (server.startsWith("http://")) return apiLocation.replaceFirst("^https://", "http://");
            return apiLocation;
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

            JarFile jarFile = null;
            try {
                jarFile = new JarFile(jarPath);
                Enumeration<JarEntry> entries = jarFile.entries();

                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().startsWith("moe/yushi/authlibinjector/") ||
                            entry.getName().startsWith("org/to2mbn/authlibinjector/")) {

                        return agentArg;
                    }
                }
            } catch (Exception ignored) {} finally {
                if (jarFile != null) try { jarFile.close(); } catch (IOException ignored) {}
            }
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

    private static void hookFutureClassLoaders(Instrumentation inst, File jarFile) throws Exception {
        final URL jarUrl = jarFile.toURI().toURL();
        final Map<ClassLoader, Boolean> injectedLoaders = new ConcurrentHashMap<ClassLoader, Boolean>();
        ClassFileTransformer injector = new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader, String name, Class<?> c, ProtectionDomain pd, byte[] bytes) {
                if (loader == null || injectedLoaders.containsKey(loader)) return null;
                try {
                    if (loader instanceof URLClassLoader) {
                        Method m = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                        m.setAccessible(true);
                        m.invoke(loader, jarUrl);
                    } else {
                        Method fwd = loader.getClass().getMethod("addUrlFwd", URL.class);
                        fwd.setAccessible(true);
                        fwd.invoke(loader, jarUrl);
                    }
                } catch (Throwable ignored) {}
                injectedLoaders.put(loader, true);
                return null;
            }
        };

        addRetransformTransformer(injector, inst);
    }

    private static void appendHooksToClasspath(Instrumentation inst) {
        try {
            File agentJar = new File(LokiUtil.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!agentJar.exists()) throw new AssertionError("Agent JAR not found");

            File tmpJar = File.createTempFile("Loki-hooks", ".jar");
            tmpJar.deleteOnExit();

            JarInputStream jis = null;
            JarOutputStream jos = null;
            try {
                jis = new JarInputStream(new FileInputStream(agentJar));
                jos = new JarOutputStream(new FileOutputStream(tmpJar));
                JarEntry entry;
                while ((entry = jis.getNextJarEntry()) != null) {
                    String name = entry.getName();
                    if ((name.contains("/hooks/") || name.contains("/util/")) && name.endsWith(".class")) {
                        JarEntry newEntry = new JarEntry(name);
                        jos.putNextEntry(newEntry);

                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = jis.read(buffer)) != -1) {
                            jos.write(buffer, 0, read);
                        }
                        jos.closeEntry();
                    }
                }
            } finally {
                if (jis != null) jis.close();
                if (jos != null) jos.close();
            }

            //inst.appendToBootstrapClassLoaderSearch(new JarFile(tmpJar));
            try {
                Method m = Instrumentation.class.getMethod("appendToBootstrapClassLoaderSearch", JarFile.class);
                m.invoke(inst, new JarFile(tmpJar));
            } catch (NoSuchMethodException ignored) {}

            //inst.appendToSystemClassLoaderSearch(new JarFile(tmpJar));
            try {
                Method m = Instrumentation.class.getMethod("appendToSystemClassLoaderSearch", JarFile.class);
                m.invoke(inst, new JarFile(tmpJar));
            } catch (NoSuchMethodException ignored) {}
            hookFutureClassLoaders(inst, tmpJar);
            Loki.log.debug("Appended hooks to classpath");
        } catch (Exception e) {
            Loki.log.error("Failed to append hooks to classpath", e);
            System.exit(1);
        }
    }

    public static boolean isRetransformSupported(Instrumentation inst) {
        try {
            Method m = Instrumentation.class.getMethod("isRetransformClassesSupported");
            Object result = m.invoke(inst);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
        } catch (Throwable ignored) {}

        return false;
    }

    public static void earlyInit(String agentArgs, Instrumentation inst) {
        if (!isRetransformSupported(inst)) {
            Loki.log.warn("**** RETRANSFORMATION IS NOT SUPPORTED!");
            if (JAVA_MAJOR == 5) {
                Loki.log.warn("Class retransformation is not supported on Java 5.");
            } else {
                Loki.log.warn("Class retransformation is not supported in this environment.");
            }
            Loki.log.warn("Domain blocking, the Authlib-Injector killer, Chat signing (1.19+),");
            Loki.log.warn("and many other features will be unavailable!");
            if (JAVA_MAJOR == 5) Loki.log.warn("If possible, upgrade to at least Java 6 to restore this functionality!");
            Loki.log.warn("Forge 1.13+ support can be fixed by setting the JVM argument");
            Loki.log.warn("`-DLoki.disable_factory=true`");
            // fallback to fix Fabric
            System.setProperty("fabric.debug.disableClassPathIsolation", "true");
        }

        initManifestAttributes();

        // Authlib-Injector API
        String authlibInjectorURL = (System.getProperty("Loki.url") != null) // Prioritize Loki.url
                ? System.getProperty("Loki.url") : (agentArgs != null && agentArgs.length() != 0)
                ? agentArgs
                : MANIFEST_ATTRS.get("AuthlibInjectorAPIServer");
        if (authlibInjectorURL != null && authlibInjectorURL.length() != 0) {
            LokiUtil.tryOrDisableSSL(authlibInjectorURL);
            LokiUtil.initAuthlibInjectorAPI(authlibInjectorURL);
        } else {
            String sessionHost = System.getProperty("minecraft.api.session.host", MANIFEST_ATTRS.get("SessionHost"));
            LokiUtil.tryOrDisableSSL(sessionHost);
            System.setProperty("mojang.sessionserver", sessionHost + "/session/minecraft/hasJoined"); // Velocity
        }

        appendHooksToClasspath(inst);
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

    public static void addRetransformTransformer(ClassFileTransformer transformer, Instrumentation inst) {
        try {
            try {
                Method m = Instrumentation.class.getMethod("addTransformer", ClassFileTransformer.class, boolean.class);
                m.invoke(inst, transformer, Boolean.TRUE);
            } catch (NoSuchMethodException e) {
                inst.addTransformer(transformer); // fallback for Java 5
            }
        } catch (Throwable t) {
            Loki.log.error("Failed to add retransform transformer!", t);
        }
    }

    public static void retransformClass(String className, Instrumentation inst) {
        try {
            Class<?> targetClass = Class.forName(className);
            try {
                Method m = Instrumentation.class.getMethod("retransformClasses", Class[].class);
                m.invoke(inst, new Object[] { new Class<?>[] { targetClass } });
            } catch (NoSuchMethodException ignored) {}
        } catch (ClassNotFoundException ignored) {} catch (Throwable t) {
            Loki.log.error(String.format("Failed to retransform %s!", className), t);
        }
    }

    public static String getFqmn(String className, String name, String desc) {
        return className.replace('/', '.') + "::" + name + desc;
    }
}
