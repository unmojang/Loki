package org.unmojang.loki;

import org.unmojang.loki.util.HttpUtil;
import org.unmojang.loki.util.Json;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public class LokiUpdater {
    private static final String UPDATE_HOST = "api.github.com";
    private static final String RELEASES_URL = "https://" + UPDATE_HOST + "/repos/unmojang/Loki/releases/latest";

    private static File pendingBackup;
    private static Thread applierHook;

    public static boolean updateAndSwap(String agentArgs, Instrumentation inst) {
        if (Boolean.getBoolean("Loki.auto_update.done")) return false;

        Method newPremain;
        String version;
        File agentJar;
        try {
            String current = LokiUtil.getAgentVersion();
            if (current == null) return false;

            agentJar = new File(Loki.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!agentJar.isFile()) return false;
            File newJar = new File(agentJar.getPath() + ".new");
            File partial = new File(agentJar.getPath() + ".new.part");
            deleteQuietly(newJar);
            deleteQuietly(partial);
            deleteQuietly(new File(agentJar.getPath() + ".old")); // we're running fine, so a leftover backup is stale

            if (!LokiUtil.areWeOnline(UPDATE_HOST, 443)) {
                Loki.log.warn("Skipping update check, " + UPDATE_HOST + " is unreachable");
                return false;
            }

            Json.JSONObject release = fetchLatestRelease(current);
            if (release == null) return false;
            version = release.optString("tag_name", "").replaceFirst("^v", "");
            if (version.length() == 0 || compareVersions(version, current) <= 0) {
                Loki.log.info("Loki is up to date (" + current + ")");
                return false;
            }

            String downloadUrl = findJarAssetUrl(release);
            if (downloadUrl == null) return false;

            Loki.log.info("Updating Loki " + current + " -> " + version);
            download(downloadUrl, partial, current);
            if (readAgentJarVersion(partial) == null) {
                Loki.log.error("Downloaded update is not a valid Loki agent, ignoring it");
                deleteQuietly(partial);
                return false;
            }
            if (!partial.renameTo(newJar)) { // .new appears atomically for a concurrent applier
                Loki.log.error("Could not move the downloaded update to " + newJar);
                deleteQuietly(partial);
                return false;
            }
            newPremain = prepareSwap(agentJar, newJar);
        } catch (Throwable t) {
            Loki.log.error("Auto-update failed, continuing with current version", t);
            return false;
        }
        if (newPremain == null) return false;

        System.setProperty("Loki.auto_update.done", "true");
        Loki.log.debug("Swapping to updated Loki " + version);
        try {
            newPremain.invoke(null, agentArgs, inst);
        } catch (Throwable t) {
            Loki.log.error("The updated Loki " + version + " failed to initialize!");
            rollbackSwap(agentJar);
            Throwable cause = (t instanceof InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        }
        commitSwap();
        return true;
    }

    private static void commitSwap() {
        if (pendingBackup != null) {
            deleteQuietly(pendingBackup);
            pendingBackup = null;
        }
        applierHook = null;
    }

    private static void rollbackSwap(File agentJar) {
        if (applierHook != null) {
            try { Runtime.getRuntime().removeShutdownHook(applierHook); } catch (Throwable ignored) {}
            applierHook = null;
            deleteQuietly(new File(agentJar.getPath() + ".new"));
            Loki.log.error("The update has been discarded.");
            return;
        }
        File backup = pendingBackup;
        if (backup == null) return;
        pendingBackup = null;
        if (agentJar.delete() && backup.renameTo(agentJar)) {
            Loki.log.error("The previous version has been restored.");
        } else {
            Loki.log.error("Could not restore the previous version automatically, it remains at " + backup);
            Loki.log.error("Restore it manually over " + agentJar + " or disable automatic updates.");
        }
    }

    private static Json.JSONObject fetchLatestRelease(String currentVersion) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(RELEASES_URL).openConnection();
            conn.setRequestProperty("User-Agent", "Loki/" + currentVersion);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() != 200) {
                Loki.log.error("Update check returned HTTP " + conn.getResponseCode());
                conn.disconnect();
                return null;
            }
            return new Json.JSONObject(HttpUtil.readStream(conn.getInputStream()));
        } catch (Exception e) {
            Loki.log.error("Update check failed: " + e);
            return null;
        }
    }

    private static String findJarAssetUrl(Json.JSONObject release) {
        Json.JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            Json.JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            if (asset.optString("name", "").toLowerCase(Locale.ENGLISH).endsWith(".jar")) {
                return asset.optString("browser_download_url", null);
            }
        }
        return null;
    }

    private static void download(String url, File dest, String currentVersion) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "Loki/" + currentVersion);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(15000);
        InputStream in = null;
        OutputStream out = null;
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("Update download returned HTTP " + code);
            in = conn.getInputStream();
            out = new FileOutputStream(dest);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
            if (out != null) try { out.close(); } catch (IOException ignored) {}
        }
    }

    private static String readAgentJarVersion(File file) {
        if (!file.isFile()) return null;
        JarFile jar = null;
        try {
            jar = new JarFile(file);
            Manifest manifest = jar.getManifest();
            if (manifest == null) return null;
            if (!"org.unmojang.loki.Loki".equals(manifest.getMainAttributes().getValue("Premain-Class"))) return null;
            String version = manifest.getMainAttributes().getValue("Implementation-Version");
            if (version == null) return null;

            // A truncated download can keep a readable manifest; check every entry
            byte[] buffer = new byte[8192];
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                InputStream in = jar.getInputStream(entries.nextElement());
                try {
                    //noinspection StatementWithEmptyBody
                    while (in.read(buffer) != -1) {}
                } finally {
                    in.close();
                }
            }
            return version;
        } catch (Throwable t) {
            return null;
        } finally {
            if (jar != null) try { jar.close(); } catch (IOException ignored) {}
        }
    }

    private static Method prepareSwap(File agentJar, File newJar) {
        Method validated = loadNewPremain(newJar);
        if (validated == null) {
            deleteQuietly(newJar); // don't retry a broken jar every launch
            return null;
        }
        File target = replaceInPlace(agentJar, newJar);
        return newJar.equals(target) ? validated : loadNewPremain(target);
    }

    private static Method loadNewPremain(File jar) {
        try {
            URLClassLoader loader = new ChildFirstURLClassLoader(
                    new URL[]{jar.toURI().toURL()}, Loki.class.getClassLoader());
            Class<?> newLoki = loader.loadClass("org.unmojang.loki.Loki");
            if (newLoki.getClassLoader() != loader) {
                Loki.log.warn("Could not isolate the updated Loki, continuing with current version");
                return null;
            }
            return newLoki.getMethod("premain", String.class, Instrumentation.class);
        } catch (Throwable t) {
            Loki.log.error("Failed to load updated Loki, continuing with current version", t);
            return null;
        }
    }

    private static File replaceInPlace(File agentJar, File newJar) {
        File backup = new File(agentJar.getPath() + ".old");
        if (agentJar.renameTo(backup)) {
            if (newJar.renameTo(agentJar)) {
                pendingBackup = backup;
                return agentJar;
            }
            if (!backup.renameTo(agentJar)) {
                Loki.log.warn("Could not restore " + agentJar + " after failed update; it remains at " + backup);
            }
        }

        if (System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH).contains("win")) {
            scheduleWindowsReplaceOnExit(agentJar, newJar);
            Loki.log.info("Agent jar is locked by the JVM; the update will be applied after the game exits");
        } else {
            Loki.log.warn("Could not replace " + agentJar + "; the update was left at " + newJar);
        }
        return newJar;
    }

    private static void scheduleWindowsReplaceOnExit(final File agentJar, final File newJar) {
        final File java = findJavaBinary();
        final File applierDir = java == null ? null : extractApplier();
        if (applierDir == null) {
            Loki.log.warn("Could not set up the update applier; the update was left at " + newJar);
            return;
        }
        applierHook = new Thread("Loki-update-applier") {
            public void run() {
                try {
                    ProcessBuilder pb = new ProcessBuilder(java.getAbsolutePath(),
                            "-cp", applierDir.getAbsolutePath(),
                            "org.unmojang.loki.UpdateApplier",
                            newJar.getAbsolutePath(), agentJar.getAbsolutePath());
                    pb.environment().remove("_JAVA_OPTIONS");
                    pb.environment().remove("JAVA_TOOL_OPTIONS");
                    pb.environment().remove("JDK_JAVA_OPTIONS");
                    pb.environment().remove("IBM_JAVA_OPTIONS");
                    pb.environment().remove("CLASSPATH");
                    pb.start();
                } catch (Throwable ignored) {}
            }
        };
        Runtime.getRuntime().addShutdownHook(applierHook);
    }

    private static File extractApplier() {
        InputStream in = Loki.class.getResourceAsStream("/org/unmojang/loki/UpdateApplier.class");
        if (in == null) return null;
        OutputStream out = null;
        try {
            File dir = new File(System.getProperty("java.io.tmpdir"), "loki-update-applier");
            File pkgDir = new File(dir, "org" + File.separator + "unmojang" + File.separator + "loki");
            if (!pkgDir.isDirectory() && !pkgDir.mkdirs()) return null;
            out = new FileOutputStream(new File(pkgDir, "UpdateApplier.class"));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return dir;
        } catch (IOException e) {
            return null;
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            if (out != null) try { out.close(); } catch (IOException ignored) {}
        }
    }

    private static File findJavaBinary() {
        File bin = new File(System.getProperty("java.home"), "bin");
        File javaw = new File(bin, "javaw.exe");
        if (javaw.isFile()) return javaw;
        File javaExe = new File(bin, "java.exe");
        return javaExe.isFile() ? javaExe : null;
    }

    private static void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) {
            Loki.log.error("Could not delete " + file);
        }
    }

    private static int compareVersions(String a, String b) {
        String[] as = a.split("[^0-9]+");
        String[] bs = b.split("[^0-9]+");
        int len = Math.max(as.length, bs.length);
        for (int i = 0; i < len; i++) {
            int ai = i < as.length && as[i].length() != 0 ? Integer.parseInt(as[i]) : 0;
            int bi = i < bs.length && bs[i].length() != 0 ? Integer.parseInt(bs[i]) : 0;
            if (ai != bi) return ai < bi ? -1 : 1;
        }
        return 0;
    }

    private static class ChildFirstURLClassLoader extends URLClassLoader {
        ChildFirstURLClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> c = findLoadedClass(name);
            if (c == null && !name.startsWith("java.") && !name.startsWith("javax.")
                    && !name.startsWith("sun.") && !name.startsWith("jdk.")) {
                try {
                    c = findClass(name);
                } catch (ClassNotFoundException ignored) {}
            }
            if (c == null) return super.loadClass(name, resolve);
            if (resolve) resolveClass(c);
            return c;
        }
    }
}
