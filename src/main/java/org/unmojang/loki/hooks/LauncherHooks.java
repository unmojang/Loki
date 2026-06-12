package org.unmojang.loki.hooks;

import org.unmojang.loki.util.Json;
import org.unmojang.loki.util.MicrosoftAuth;
import org.unmojang.loki.util.logger.NilLogger;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@SuppressWarnings({"unused", "CallToPrintStackTrace"})
public class LauncherHooks {
    public static final String VERSION_MANIFEST_URL = "https://mcphackers.org/BetterJSONs/version_manifest_v2.json";
    public static final Map<String, String> libraryUrlMap = new ConcurrentHashMap<String, String>();

    private static final NilLogger log = NilLogger.get("Loki");
    private static final ConcurrentHashMap<String, List<String>> appletLibraryUrlMap = new ConcurrentHashMap<String, List<String>>();

    public static volatile String currentVersionId;
    private static volatile String currentAssetIndexUrl;
    private static volatile String currentAssetIndexId;
    private static volatile String cachedAssetIndexUrl;
    private static volatile String resolvedVersionId;
    private static volatile Json.JSONObject assetIndexCache;
    private static volatile Json.JSONObject cachedManifestRoot;

    // Grab Loki-related JVM args and pass through to child process
    public static String[] getLokiJVMArgs() {
        try {
            List<String> args = new ArrayList<String>();

            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (arg != null && arg.startsWith("-javaagent:")) args.add(arg);
            }

            Properties props = System.getProperties();
            Enumeration<?> names = props.propertyNames();

            while (names.hasMoreElements()) {
                String key = (String) names.nextElement();

                if (key.startsWith("Loki.") || key.startsWith("minecraft.api.")) {
                    String value = props.getProperty(key);
                    if (value != null) args.add("-D" + key + "=" + value);
                }
            }

            return args.toArray(new String[0]);

        } catch (Throwable t) {
            t.printStackTrace();
            return new String[0];
        }
    }

    private static Json.JSONObject authenticateLegacy(String urlParameters) {
        String baseUrl = System.getProperty("minecraft.api.auth.host", "https://authserver.mojang.com");
        if ("https://authserver.mojang.com".equals(baseUrl)) {
            return MicrosoftAuth.authenticate();
        }

        try {
            String user = urlParameters.split("user=")[1].split("&")[0];
            String password = urlParameters.split("password=")[1].split("&")[0];

            URL url = new URL(baseUrl + "/authenticate");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            String payload =
                    "{"
                            + "\"agent\":{\"name\":\"Minecraft\",\"version\":1},"
                            + "\"username\":\"" + user + "\","
                            + "\"password\":\"" + password + "\""
                            + "}";
            OutputStream os = conn.getOutputStream();
            os.write(payload.getBytes("UTF-8"));
            os.flush();
            os.close();
            return new Json.JSONObject(Hooks.readStream(conn.getInputStream()));
        } catch (Exception ignored) {}
        return null;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean isMicrosoftAuth() {
        return "https://authserver.mojang.com".equals(
                System.getProperty("minecraft.api.auth.host", "https://authserver.mojang.com"));
    }

    // While using applet launcher, with Mojang API server: Disable the credential fields and add a Logout button
    public static void decorateLoginPanel(Object loginForm, Object panel) {
        if (!isMicrosoftAuth() || !(panel instanceof java.awt.Component)) return;
        try {
            disableField(loginForm, "userName");
            disableField(loginForm, "password");
            disableField(loginForm, "rememberBox");

            java.awt.Component link = findByText((java.awt.Component) panel, "Need account?");
            if (link == null) return;
            java.awt.Container parent = link.getParent();
            if (parent == null) return;

            Object button = createLogoutButton(loginForm);
            if (!(button instanceof java.awt.Component)) return;

            java.awt.LayoutManager layout = parent.getLayout();
            Object constraints = (layout instanceof java.awt.BorderLayout)
                    ? ((java.awt.BorderLayout) layout).getConstraints(link) : null;
            int index = -1;
            java.awt.Component[] siblings = parent.getComponents();
            for (int i = 0; i < siblings.length; i++) {
                if (siblings[i] == link) { index = i; break; }
            }

            parent.remove(link);
            if (constraints != null) parent.add((java.awt.Component) button, constraints, index);
            else parent.add((java.awt.Component) button, index);
            parent.validate();
            parent.repaint();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void disableField(Object owner, String fieldName) {
        try {
            Object value = readField(owner, fieldName);
            if (value instanceof java.awt.Component) ((java.awt.Component) value).setEnabled(false);
        } catch (Throwable ignored) {}
    }

    // Clear credential fields, so they're not displayed when switching to an MSA account
    public static void resetLoginFields(Object loginForm) {
        if (!isMicrosoftAuth()) return;
        clearText(loginForm, "userName");
        clearText(loginForm, "password");
        try {
            Object checkbox = readField(loginForm, "rememberBox");
            try {
                checkbox.getClass().getMethod("setSelected", boolean.class).invoke(checkbox, Boolean.FALSE);
            } catch (NoSuchMethodException awt) {
                checkbox.getClass().getMethod("setState", boolean.class).invoke(checkbox, Boolean.FALSE);
            }
        } catch (Throwable ignored) {}
    }

    private static void clearText(Object owner, String fieldName) {
        try {
            Object value = readField(owner, fieldName);
            value.getClass().getMethod("setText", String.class).invoke(value, "");
        } catch (Throwable ignored) {}
    }

    private static java.awt.Component findByText(java.awt.Component component, String text) {
        try {
            Object actual = component.getClass().getMethod("getText").invoke(component);
            if (text.equals(actual)) return component;
        } catch (Throwable ignored) {}
        if (component instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) component).getComponents()) {
                java.awt.Component found = findByText(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    // Logout button of the launcher's own Login-button class (AWT Button on Alpha, TransparentButton on Beta).
    private static Object createLogoutButton(final Object loginForm) throws Exception {
        Object launchButton = readField(loginForm, "launchButton");
        Class<?> buttonClass = launchButton.getClass();
        Object button = buttonClass.getConstructor(String.class).newInstance("Logout");
        java.awt.event.ActionListener listener = new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                synchronized (MicrosoftAuth.LOCK) {
                    MicrosoftAuth.loadAccounts();
                    MicrosoftAuth.accounts.clear();
                    MicrosoftAuth.saveAccounts();
                    log.info("Cleared stored Microsoft accounts");
                }
                try {
                    loginForm.getClass().getMethod("setError", String.class)
                            .invoke(loginForm, "Signed out. Press Login to choose an account.");
                } catch (Throwable ignored) {}
            }
        };
        buttonClass.getMethod("addActionListener", java.awt.event.ActionListener.class).invoke(button, listener);
        return button;
    }

    // Intercept authlib's Yggdrasil HTTP handler in 1.6 launcher to perform MSA auth
    public static String maybeYggdrasilAuth(URL url, String post) {
        try {
            if (!isMicrosoftAuth() || url == null) return null;
            String path = url.getPath();
            if (path == null) return null;
            if (path.endsWith("/authenticate")) {
                Json.JSONObject session;
                synchronized (MicrosoftAuth.LOCK) {
                    session = MicrosoftAuth.interactiveLogin();
                }
                return buildYggdrasilResponse(post, session);
            }
            if (path.endsWith("/refresh")) {
                String previous = "";
                try { previous = new Json.JSONObject(post).optString("accessToken", ""); } catch (Throwable ignored) {}
                Json.JSONObject session;
                synchronized (MicrosoftAuth.LOCK) {
                    String[] account = MicrosoftAuth.findByAccessToken(previous);
                    session = account == null ? null : MicrosoftAuth.refreshAccount(account);
                }
                return buildYggdrasilResponse(post, session);
            }
            if (path.endsWith("/validate")) {
                return "{\"error\":\"ForbiddenOperationException\",\"errorMessage\":\"Token requires refresh\"}";
            }
            if (path.endsWith("/invalidate") || path.endsWith("/signout")) {
                return "";
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return null;
    }

    private static String buildYggdrasilResponse(String post, Json.JSONObject session) {
        if (session == null) {
            Json.JSONObject err = new Json.JSONObject();
            err.put("error", "ForbiddenOperationException");
            err.put("errorMessage", "Microsoft sign-in was cancelled or unavailable");
            return err.toString();
        }

        // The launcher rejects the response unless its clientToken matches the request's, so echo it back.
        String clientToken = "";
        try { clientToken = new Json.JSONObject(post).optString("clientToken", ""); } catch (Throwable ignored) {}

        Json.JSONObject sp = session.getJSONObject("selectedProfile");
        Json.JSONObject profile = new Json.JSONObject();
        profile.put("id", sp.getString("id"));
        profile.put("name", sp.getString("name"));

        Json.JSONObject resp = new Json.JSONObject();
        resp.put("accessToken", session.getString("accessToken"));
        resp.put("clientToken", clientToken);
        resp.put("selectedProfile", profile);
        Json.JSONArray available = new Json.JSONArray();
        available.put(profile);
        resp.put("availableProfiles", available);
        return resp.toString();
    }

    // 1.6 Launcher (pre-1.1 UI): disable the credential fields
    public static void decorateSidebarLoginForm(Object form) {
        if (!isMicrosoftAuth()) return;
        try {
            clearText(form, "usernameField");
            clearText(form, "passwordField");
            disableField(form, "usernameField");
            disableField(form, "passwordField");
            // Force "remember me" on, so a logged-in Microsoft account plays on next
            // launch instead of prompting again.
            try {
                Object checkbox = readField(form, "rememberMeCheckbox");
                try {
                    checkbox.getClass().getMethod("setSelected", boolean.class).invoke(checkbox, Boolean.TRUE);
                } catch (NoSuchMethodException awt) {
                    checkbox.getClass().getMethod("setState", boolean.class).invoke(checkbox, Boolean.TRUE);
                }
                if (checkbox instanceof java.awt.Component) ((java.awt.Component) checkbox).setEnabled(false);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    // On the API server's logout, drop our stored account too.
    public static void onAuthLogOut(Object authentication) {
        if (!isMicrosoftAuth()) return;

        // Prefer the selected profile's UUID: it's restored from storage on restart, whereas the session
        // token only lives in memory right after a login (1.0's Legacy service doesn't persist it).
        String uuid = null;
        Object profile = invokeQuietly(authentication, "getSelectedProfile");
        if (profile != null) uuid = stringValue(invokeQuietly(profile, "getId"));

        String token = stringValue(invokeQuietly(authentication, "getAccessToken"));
        if (token == null) token = stringValue(invokeQuietly(authentication, "getAuthenticatedToken"));
        if (token == null) { // 0.7's OldAuthentication keeps the session id on its last response
            Object response = invokeQuietly(authentication, "getLastSuccessfulResponse");
            if (response != null) token = stringValue(invokeQuietly(response, "getSessionId"));
        }

        if (uuid == null && token == null) return;
        synchronized (MicrosoftAuth.LOCK) {
            MicrosoftAuth.loadAccounts();
            boolean removed = false;
            for (Iterator<String[]> it = MicrosoftAuth.accounts.iterator(); it.hasNext(); ) {
                String[] account = it.next();
                if ((account[0].equals(uuid)) || (account[1].equals(token))) {
                    it.remove();
                    removed = true;
                }
            }
            if (removed) {
                MicrosoftAuth.saveAccounts();
                log.info("Removed stored Microsoft account on logout");
            }
        }
    }

    private static final java.util.Set<String> autoRefreshAttempted =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    // 1.6 Launcher (pre-1.1 UI): trigger the launcher's tryLogIn when there's a
    // stored-but-offline token, else it sits on "Play Offline" with MS accounts
    public static void maybeAutoRefresh(Object notLoggedInForm) {
        if (!isMicrosoftAuth()) return;
        try {
            Object auth = invokeNoArg(invokeNoArg(invokeNoArg(invokeNoArg(
                    notLoggedInForm, "getLauncher"), "getProfileManager"), "getSelectedProfile"), "getAuthentication");
            if (!Boolean.TRUE.equals(invokeQuietly(auth, "isLoggedIn"))) return;   // nothing to refresh
            if (Boolean.TRUE.equals(invokeQuietly(auth, "canPlayOnline"))) return; // already online

            String token = stringValue(invokeQuietly(auth, "getAccessToken"));
            if (token == null) token = stringValue(invokeQuietly(auth, "getAuthenticatedToken"));
            if (token == null || !autoRefreshAttempted.add(token)) return;

            notLoggedInForm.getClass().getMethod("tryLogIn", boolean.class, boolean.class)
                    .invoke(notLoggedInForm, Boolean.FALSE, Boolean.FALSE);
        } catch (Throwable ignored) {}
    }

    private static Object invokeQuietly(Object target, String method) {
        try {
            return invokeNoArg(target, method);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String stringValue(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.length() == 0 ? null : s;
    }

    public static void onAccountRemoved(String uuid) {
        if (!isMicrosoftAuth() || uuid == null) return;
        synchronized (MicrosoftAuth.LOCK) {
            MicrosoftAuth.loadAccounts();
            if (MicrosoftAuth.removeAccountByUuid(uuid)) {
                MicrosoftAuth.saveAccounts();
                log.info("Removed stored Microsoft account " + uuid);
            }
        }
    }

    // On a non-Mojang API server, drop the token issued for an MSA account, else the launcher shows
    // a stale "logged in as <Microsoft account>"
    public static String filterStoredToken(String accessToken) {
        if (!isMicrosoftAuth() && accessToken != null && accessToken.length() != 0) {
            synchronized (MicrosoftAuth.LOCK) {
                if (MicrosoftAuth.findByAccessToken(accessToken) != null) return null;
            }
        }
        return accessToken;
    }

    // 1.6 Launcher: disable the credential fields
    public static void decoratePopupLoginForm(Object form) {
        if (!isMicrosoftAuth()) return;
        try {
            clearText(form, "usernameField");
            clearText(form, "passwordField");
            disableField(form, "usernameField");
            disableField(form, "passwordField");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    // Return the login response for the applet launcher's login endpoints
    public static String loginToAppletLauncher(String targetURL, String urlParameters, boolean triggerUpdate) {
        if (!"http://www.minecraft.net/game/getversion.jsp".equals(targetURL)
                && !"https://login.minecraft.net/".equals(targetURL)) return null;

        Json.JSONObject response = authenticateLegacy(urlParameters);
        if (response == null) return "";

        try {
            Json.JSONObject profile = response.getJSONObject("selectedProfile");
            String sessionId = response.getString("accessToken");
            String userName = profile.getString("name");

            String latestVersion = triggerUpdate ? String.valueOf(System.currentTimeMillis()) : "-1";
            String downloadTicket = "lokiRocks";
            return latestVersion + ":" + downloadTicket + ":" + userName + ":" + sessionId;
        } catch (Exception ignored) {}
        return "";
    }

    // Return the login response for 1.6 launcher's login endpoint
    public static String loginToOneSixLauncher(URL targetURL, String urlParameters) {
        if (!"login.minecraft.net".equals(targetURL.getHost())) return null;

        Json.JSONObject response = authenticateLegacy(urlParameters);
        if (response == null) return "";

        try {
            Json.JSONObject profile = response.getJSONObject("selectedProfile");
            String sessionId = response.getString("accessToken");
            String uuid = profile.getString("id");
            String userName = profile.getString("name");

            String latestVersion = "-1";
            String downloadTicket = "lokiRocks";
            return latestVersion + ":" + downloadTicket + ":" + userName + ":" + sessionId + ":" + uuid;
        } catch (Exception ignored) {}
        return "";
    }

    // Rewrite old_alpha/old_beta to "release" to show them in pre-1.2 versions of the 1.6 Launcher
    public static void aliasOldReleaseTypes(Map<Object, Object> typeMap) {
        if (typeMap == null) return;
        Object release = typeMap.get("release");
        if (release == null) return;
        if (!typeMap.containsKey("old_alpha")) typeMap.put("old_alpha", release);
        if (!typeMap.containsKey("old_beta")) typeMap.put("old_beta", release);
    }

    public static String getLibraryArtifactBaseDir(String name) {
        String[] parts = name.split(":");
        return parts[0].replaceAll("\\.", "/") + "/" + parts[1] + "/" + parts[2];
    }

    public static String getLibraryArtifactFilename(String name, String classifier) {
        String[] parts = name.split(":");
        if ((classifier == null || classifier.length() == 0) && parts.length > 3) classifier = parts[3];
        String suffix = (classifier != null && classifier.length() != 0) ? "-" + classifier : "";
        return (parts[1] + "-" + parts[2] + suffix + ".jar")
                .replace("${arch}", System.getProperty("os.arch", "").contains("64") ? "64" : "32");
    }

    public static List<String> getAppletLibraryUrls(String filename) {
        ensureVersionResolved();
        return appletLibraryUrlMap.get(filename);
    }

    public static void captureVersionId(Object completeVersion) {
        try {
            if (completeVersion == null) return;
            Object id = completeVersion.getClass().getMethod("getId").invoke(completeVersion);
            if (id != null) currentVersionId = id.toString();
        } catch (Throwable ignored) {}
    }

    public static void resolveVersionData(Json.JSONObject versionJson) {
        Json.JSONObject assetIndex = versionJson.optJSONObject("assetIndex");
        if (assetIndex != null) {
            String url = assetIndex.optString("url", "");
            String id = assetIndex.optString("id", versionJson.optString("assets", ""));
            if (url.length() != 0 && id.length() != 0) {
                currentAssetIndexUrl = url;
                currentAssetIndexId = id;
            }
        }

        Json.JSONArray libraries = versionJson.optJSONArray("libraries");
        if (libraries != null) {
            for (int i = 0; i < libraries.length(); i++) {
                Json.JSONObject library = libraries.getJSONObject(i);
                Json.JSONObject downloads = library.optJSONObject("downloads");
                if (downloads == null) continue;
                Json.JSONObject artifact = downloads.optJSONObject("artifact");
                mapDownload(artifact);
                Json.JSONObject classifiers = downloads.optJSONObject("classifiers");
                if (classifiers != null) {
                    java.util.Iterator<String> keys = classifiers.keys();
                    while (keys.hasNext()) mapDownload(classifiers.optJSONObject(keys.next()));
                }
                mapAppletLibrary(library.optString("name", ""), artifact, classifiers);
            }
        }

        String id = versionJson.optString("id", "");
        if (id.length() != 0) resolvedVersionId = id;
    }

    private static void mapDownload(Json.JSONObject download) {
        if (download == null) return;
        String path = download.optString("path", "");
        String url = download.optString("url", "");
        if (path.length() != 0 && url.length() != 0) libraryUrlMap.put(path, url);
    }

    private static void mapAppletLibrary(String name, Json.JSONObject artifact, Json.JSONObject classifiers) {
        if (name.length() == 0) return;
        String[] parts = name.split(":");
        if (parts.length < 2) return;
        String artifactId = parts[1];

        if (artifact != null) {
            String url = artifact.optString("url", "");
            if (url.length() != 0) {
                if ("lwjgl".equals(artifactId)) addAppletUrl("lwjgl.jar", url);
                else if ("lwjgl_util".equals(artifactId)) addAppletUrl("lwjgl_util.jar", url);
                else if ("jinput".equals(artifactId)) addAppletUrl("jinput.jar", url);
                else if ("jutils".equals(artifactId)) addAppletUrl("jinput.jar", url); // Plugins class jinput needs
            }
        }

        if (classifiers != null && ("lwjgl-platform".equals(artifactId) || "jinput-platform".equals(artifactId))) {
            addAppletNative("windows_natives.jar", classifiers.optJSONObject("natives-windows"));
            addAppletNative("linux_natives.jar", classifiers.optJSONObject("natives-linux"));
            addAppletNative("macosx_natives.jar", classifiers.optJSONObject("natives-osx"));
        }
    }

    private static void addAppletNative(String filename, Json.JSONObject classifier) {
        if (classifier == null) return;
        String url = classifier.optString("url", "");
        if (url.length() != 0) addAppletUrl(filename, url);
    }

    private static void addAppletUrl(String filename, String url) {
        List<String> urls = appletLibraryUrlMap.get(filename);
        if (urls == null) {
            urls = new CopyOnWriteArrayList<String>();
            List<String> prev = appletLibraryUrlMap.putIfAbsent(filename, urls);
            if (prev != null) urls = prev;
        }
        ((CopyOnWriteArrayList<String>) urls).addIfAbsent(url);
    }

    private static void ensureVersionResolved() {
        if (currentVersionId == null || currentVersionId.equals(resolvedVersionId)) return;
        try {
            Json.JSONArray versions = getManifestVersions();
            String versionUrl = null;
            for (int i = 0; i < versions.length(); i++) {
                Json.JSONObject entry = versions.getJSONObject(i);
                if (currentVersionId.equals(entry.getString("id"))) { versionUrl = entry.getString("url"); break; }
            }
            if (versionUrl == null) return;

            Json.JSONObject versionJson = new Json.JSONObject(Hooks.readStream(new URL(versionUrl).openConnection().getInputStream()));
            resolveVersionData(versionJson);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public static Json.JSONObject getManifestRoot() throws IOException {
        Json.JSONObject cached = cachedManifestRoot;
        if (cached != null) return cached;
        synchronized (LauncherHooks.class) {
            if (cachedManifestRoot != null) return cachedManifestRoot;
            HttpURLConnection conn = (HttpURLConnection) new URL(VERSION_MANIFEST_URL).openConnection();
            cachedManifestRoot = new Json.JSONObject(Hooks.readStream(conn.getInputStream()));
            return cachedManifestRoot;
        }
    }

    private static Json.JSONArray getManifestVersions() throws IOException {
        return getManifestRoot().getJSONArray("versions");
    }

    public static Json.JSONObject getAssetIndex() throws IOException {
        ensureVersionResolved();
        String url = currentAssetIndexUrl;
        if (url == null) throw new IOException("No asset index resolved (version: " + currentVersionId + ")");
        if (url.equals(cachedAssetIndexUrl) && assetIndexCache != null) return assetIndexCache;
        synchronized (LauncherHooks.class) {
            if (url.equals(cachedAssetIndexUrl) && assetIndexCache != null) return assetIndexCache;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            assetIndexCache = new Json.JSONObject(Hooks.readStream(conn.getInputStream()));
            cachedAssetIndexUrl = url;
            return assetIndexCache;
        }
    }

    // Modern resource/asset download backport
    public static Set<Object> buildModernResourceDownloadables(Object versionManager, Proxy proxy, File baseDirectory) {
        Set<Object> result = new HashSet<Object>();
        try {
            ensureVersionResolved();
            if (currentAssetIndexId == null) return result;

            Json.JSONObject objects = getAssetIndex().getJSONObject("objects");
            boolean flat = "pre-1.6".equals(currentAssetIndexId) || "legacy".equals(currentAssetIndexId);

            Class<?> downloadableClass = versionManager.getClass().getClassLoader()
                    .loadClass("net.minecraft.launcher.updater.download.Downloadable");
            Constructor<?> ctor = downloadableClass.getConstructor(Proxy.class, URL.class, File.class, boolean.class);
            Method setExpectedSize = null;
            try { setExpectedSize = downloadableClass.getMethod("setExpectedSize", long.class); } catch (NoSuchMethodException ignored) {}

            File assetsDir = new File(baseDirectory, "assets");
            // pre-1.6 assets live in the resources directory
            File resourcesDir = new File(baseDirectory, "resources");

            if (!flat) {
                File indexFile = new File(assetsDir, "indexes/" + currentAssetIndexId + ".json");
                if (!indexFile.isFile()) {
                    result.add(ctor.newInstance(proxy, new URL(currentAssetIndexUrl), indexFile, Boolean.FALSE));
                }
            }

            Iterator<String> keys = objects.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Json.JSONObject object = objects.getJSONObject(key);
                String hash = object.getString("hash");
                long size = object.getLong("size");
                String prefix = hash.substring(0, 2);

                File target = flat ? new File(resourcesDir, key) : new File(assetsDir, "objects/" + prefix + "/" + hash);
                if (target.isFile() && target.length() == size) continue;

                URL url = new URL("https://resources.download.minecraft.net/" + prefix + "/" + hash);
                Object downloadable = ctor.newInstance(proxy, url, target, Boolean.FALSE);
                if (setExpectedSize != null) setExpectedSize.invoke(downloadable, size);
                result.add(downloadable);
            }

            log.info("Queued " + result.size() + " resource download(s) for asset index " + currentAssetIndexId);
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return result;
    }

    public static void appendModernResourceDownloads(Object versionManager, Object job) {
        try {
            File baseDirectory = (File) invokeNoArg(readField(versionManager, "localVersionList"), "getBaseDirectory");
            Proxy proxy = (Proxy) invokeNoArg(readField(versionManager, "remoteVersionList"), "getProxy");

            Set<Object> downloadables = buildModernResourceDownloadables(versionManager, proxy, baseDirectory);
            if (downloadables.isEmpty()) return;
            job.getClass().getMethod("addDownloadables", Collection.class).invoke(job, downloadables);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static Object readField(Object obj, String name) throws Exception {
        Field field = obj.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(obj);
    }

    private static Object invokeNoArg(Object obj, String name) throws Exception {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(obj);
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(name);
    }

    public static String[] fillModernArgs(String[] args, Object version, File assetsDir, Object profile) {
        try {
            ensureVersionResolved();
            boolean flat = "pre-1.6".equals(currentAssetIndexId) || "legacy".equals(currentAssetIndexId);
            File assetsBase = flat ? new File(assetsDir.getParentFile(), "resources") : assetsDir;

            Map<String, String> replacements = new HashMap<String, String>();
            replacements.put("assets_root", assetsBase.getAbsolutePath());
            if (currentAssetIndexId != null) replacements.put("assets_index_name", currentAssetIndexId);

            Object auth = profile;
            try {
                Object a = profile.getClass().getMethod("getAuthentication").invoke(profile);
                if (a != null) auth = a;
            } catch (Throwable ignored) {}
            String accessToken = auth != null ? extractAccessToken(auth) : null;
            if (accessToken != null && accessToken.length() != 0) replacements.put("auth_access_token", accessToken);

            replacements.put("user_type", "msa");
            replacements.put("version_type", extractVersionType(version));
            replacements.put("user_properties", "{}");
            replacements.put("clientid", "");
            replacements.put("auth_xuid", "");

            for (int i = 0; i < args.length; i++) {
                for (Map.Entry<String, String> entry : replacements.entrySet()) {
                    args[i] = args[i].replace("${" + entry.getKey() + "}", entry.getValue());
                }
                if (flat) args[i] = args[i].replace(assetsDir.getAbsolutePath(), assetsBase.getAbsolutePath());
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return args;
    }

    private static String extractAccessToken(Object auth) {
        for (String method : new String[]{"getAuthenticatedToken", "getAccessToken"}) {
            try {
                Object token = auth.getClass().getMethod(method).invoke(auth);
                if (token != null && token.toString().length() != 0) return token.toString();
            } catch (Throwable ignored) {}
        }
        try {
            Object session = auth.getClass().getMethod("getSessionToken").invoke(auth);
            if (session != null) {
                String s = session.toString();
                if (s.startsWith("token:")) {
                    String[] parts = s.split(":");
                    if (parts.length >= 2) return parts[1];
                } else if (s.length() != 0) {
                    return s; // 1.0 LoadTesting/Legacy: getSessionToken is the raw access token
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String extractVersionType(Object version) {
        try {
            Object type = version.getClass().getMethod("getType").invoke(version);
            if (type != null) {
                try {
                    Object name = type.getClass().getMethod("getName").invoke(type);
                    if (name != null) return name.toString();
                } catch (NoSuchMethodException e) {
                    return type.toString().toLowerCase();
                }
            }
        } catch (Throwable ignored) {}
        return "release";
    }
}
