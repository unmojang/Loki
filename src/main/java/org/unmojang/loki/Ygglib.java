package org.unmojang.loki;

import org.unmojang.loki.hooks.Hooks;
import org.unmojang.loki.hooks.LauncherHooks;
import org.unmojang.loki.util.Base64;
import org.unmojang.loki.util.Json;

import javax.imageio.ImageIO;
import javax.net.ssl.HttpsURLConnection;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("HttpUrlsUsage")
public class Ygglib {
    private static volatile byte[] reclassifiedManifestCache;
    private static final String[] LEGACY_OS_NAMES = {"windows", "linux", "osx"};

    public static String readStream(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    public static String getUsernameFromPath(String path) {
        return path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.png$", "");
    }

    public static Map<String, String> queryStringParser(String query) {
        Map<String, String> params = new HashMap<String, String>();
        String[] entries = query.split("&");
        for (String entry : entries) {
            String[] pair = entry.split("=");
            if (pair.length == 2) {
                params.put(pair[0], pair[1]);
            }
        }
        return params;
    }

    public static String getUUID(String username) throws Exception {
        try {
            URL skinUrl = new URL("https://api.mojang.com/users/profiles/minecraft/" + URLEncoder.encode(username, "UTF-8"));
            skinUrl = getYggdrasilUrl(skinUrl, null);
            URLStreamHandler handler = Hooks.DEFAULT_HANDLERS.get(skinUrl.getProtocol());
            HttpURLConnection conn = RequestInterceptor.openWithParent(skinUrl, handler);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                Json.JSONObject obj = new Json.JSONObject(readStream(conn.getInputStream()));
                return obj.getString("id");
            }

            // route not implemented? let's try the other one...
            skinUrl = new URL("https://api.mojang.com/minecraft/profile/lookup/name/" + URLEncoder.encode(username, "UTF-8"));
            skinUrl = getYggdrasilUrl(skinUrl, null);
            handler = Hooks.DEFAULT_HANDLERS.get(skinUrl.getProtocol());
            conn = RequestInterceptor.openWithParent(skinUrl, handler);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                Json.JSONObject obj = new Json.JSONObject(readStream(conn.getInputStream()));
                return obj.getString("id");
            }

            // prehistoric version of BlessingSkin only implements this route
            skinUrl = new URL("https://api.mojang.com/profiles/minecraft");
            skinUrl = getYggdrasilUrl(skinUrl, null);
            handler = Hooks.DEFAULT_HANDLERS.get(skinUrl.getProtocol());
            conn = RequestInterceptor.openWithParent(skinUrl, handler);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            byte[] body = ("[\"" + username + "\"]").getBytes("UTF-8");
            conn.getOutputStream().write(body);
            conn.getOutputStream().close();

            if (conn.getResponseCode() == 200) {
                String jsonText = readStream(conn.getInputStream());
                Json.JSONArray arr = new Json.JSONArray(jsonText);
                return arr.getJSONObject(0).getString("id");
            }

            throw new IOException("No UUID lookup route succeeded for username: " + username);
        } catch (UnknownHostException e) {
            throw e;
        } catch (Exception e) {
            Loki.log.error("Failed to get UUID for " + username);
            throw e;
        }
    }

    public static String getTexturesProperty(String uuid, boolean returnProfileJson) throws Exception {
        try {
            URL textureUrl = new URL("https://sessionserver.mojang.com/session/minecraft/profile/" + URLEncoder.encode(uuid, "UTF-8") + "?unsigned=false");
            textureUrl = getYggdrasilUrl(textureUrl, null);
            URLStreamHandler handler = Hooks.DEFAULT_HANDLERS.get(textureUrl.getProtocol());
            HttpURLConnection conn = RequestInterceptor.openWithParent(textureUrl, handler);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            String profileJson = readStream(conn.getInputStream());
            if (returnProfileJson) return profileJson;
            Json.JSONObject profileObj = new Json.JSONObject(profileJson);
            String texturesBase64 = profileObj.getJSONArray("properties").getJSONObject(0).getString("value");
            return new String(Base64.decode(texturesBase64), "UTF-8");
        } catch (Exception e) {
            Loki.log.error("Failed to get textures property for " + uuid);
            throw e;
        }
    }

    // Credit: Prism Launcher legacy fixes
    // https://github.com/PrismLauncher/PrismLauncher/blob/develop/libraries/launcher/legacy/org/prismlauncher/legacy/fix/online/SkinFix.java
    public static URLConnection getTexture(URL originalUrl, URLConnection originalConn, String username, String type) throws UnknownHostException {
        try {
            String uuid = getUUID(username);
            Loki.log.debug("UUID of " + username + ": " + uuid);

            String texturesProperty = getTexturesProperty(uuid, false);
            Json.JSONObject texturePayloadObj = new Json.JSONObject(texturesProperty);
            Json.JSONObject texturesObj = texturePayloadObj.getJSONObject("textures");
            if (!texturesObj.has(type)) return FakeURLConnection(originalUrl, originalConn, 204, null);
            Json.JSONObject skinOrCape = texturesObj.getJSONObject(type);
            String textureUrl = skinOrCape.getString("url");
            if (textureUrl == null) return FakeURLConnection(originalUrl, originalConn, 204, null);
            if (RequestInterceptor.YGGDRASIL_MAP.get("sessionserver.mojang.com").startsWith("http://")) {
                textureUrl = textureUrl.replaceFirst("^https://", "http://");
            }

            if (type.equals("SKIN")) {
                boolean isSlim = false;
                if (skinOrCape.has("metadata")) {
                    Json.JSONObject metadata = skinOrCape.getJSONObject("metadata");
                    if ("slim".equals(metadata.optString("model", ""))) isSlim = true;
                }
                URL parsedTextureUrl = new URL(textureUrl);
                URLStreamHandler handler = Hooks.DEFAULT_HANDLERS.get(parsedTextureUrl.getProtocol());
                URLConnection connection = RequestInterceptor.openWithParent(parsedTextureUrl, handler);
                InputStream in = null;
                try {
                    // thank you ahnewark!
                    // this is heavily based on
                    // https://github.com/ahnewark/MineOnline/blob/4f4f86f9d051e0a6fd7ff0b95b2a05f7437683d7/src/main/java/gg/codie/mineonline/gui/textures/TextureHelper.java#L17
                    in = connection.getInputStream();
                    BufferedImage image = ImageIO.read(in);
                    int s = Math.max(1, image.getWidth() / 64);
                    Graphics2D graphics = image.createGraphics();
                    graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));

                    BufferedImage subimage;

                    if (image.getHeight() > 32 * s) {
                        // flatten second layers
                        subimage = image.getSubimage(0, 32 * s, 56 * s, 16 * s);
                        graphics.drawImage(subimage, 0, 16 * s, null);
                    }

                    if (isSlim) {
                        // convert slim to classic
                        subimage = image.getSubimage(45 * s, 16 * s, 9 * s, 16 * s);
                        graphics.drawImage(subimage, 46 * s, 16 * s, null);

                        subimage = image.getSubimage(49 * s, 16 * s, 2 * s, 4 * s);
                        graphics.drawImage(subimage, 50 * s, 16 * s, null);

                        subimage = image.getSubimage(53 * s, 20 * s, 2 * s, 12 * s);
                        graphics.drawImage(subimage, 54 * s, 20 * s, null);
                    }

                    graphics.dispose();

                    // crop the image - old versions disregard all secondary layers besides the hat
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    image = image.getSubimage(0, 0, 64 * s, 32 * s);
                    ImageIO.write(image, "png", out);

                    return FakeURLConnection(originalUrl, originalConn, 200, out.toByteArray());
                } finally {
                    if (in != null) in.close();
                }
            } else if (type.equals("CAPE")) {
                URL parsedTextureUrl = new URL(textureUrl);
                URLStreamHandler handler = Hooks.DEFAULT_HANDLERS.get(parsedTextureUrl.getProtocol());
                return RequestInterceptor.openWithParent(parsedTextureUrl, handler);
            }
            throw new RuntimeException("Unexpected texture type. How did we get here?");
        } catch (UnknownHostException e) {
            throw e;
        } catch (Exception e) {
            Loki.log.error("getTexture failed");
            throw new RuntimeException(e);
        }
    }

    // Credit: Fjord Launcher legacy fixes
    // https://github.com/unmojang/FjordLauncher/blob/develop/libraries/launcher/legacy/org/prismlauncher/legacy/fix/online/OnlineModeFix.java
    public static URLConnection joinServer(URL originalUrl, URLConnection originalConn) throws UnsupportedEncodingException {
        try {
            Map<String, String> params = queryStringParser(originalUrl.getQuery());
            String username = params.get("user");
            if (username == null) throw new RuntimeException("missing user");
            String serverId = params.get("serverId");
            String sessionId = params.get("sessionId");
            if (sessionId == null) sessionId = params.get("session");
            if (sessionId == null || sessionId.length() == 0) throw new RuntimeException("missing session/sessionId");

            // sessionId has the form:
            // token:<accessToken>:<player UUID>
            // or, as of Minecraft release 1.3.1, it may be URL encoded:
            // token%3A<accessToken>%3A<player UUID>
            String accessToken;
            String uuid;
            if (!sessionId.contains(":") && !sessionId.contains("%3A")) { // apparently this is valid too?
                accessToken = sessionId;
                uuid = getUUID(username);
            } else {
                String[] parts = sessionId.split(sessionId.contains(":") ? ":" : "%3A");
                if (parts.length < 3 || parts[1].length() == 0 || parts[2].length() == 0) {
                    throw new RuntimeException("invalid sessionId");
                }

                accessToken = parts[1];
                uuid = parts[2];
            }
            Loki.log.debug("UUID of " + username + ": " + uuid);

            URL url = new URL("https://sessionserver.mojang.com/session/minecraft/join");
            url = getYggdrasilUrl(url, null);
            URLStreamHandler handler = Hooks.DEFAULT_HANDLERS.get(url.getProtocol());
            HttpURLConnection conn = RequestInterceptor.openWithParent(url, handler);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            OutputStream os = null;
            try {
                os = conn.getOutputStream();
                StringBuilder payload = new StringBuilder();
                payload.append("{")
                        .append("\"accessToken\": \"").append(accessToken).append("\",")
                        .append("\"selectedProfile\": \"").append(uuid).append("\"");

                if (serverId != null) {
                    payload.append(",\"serverId\": \"").append(serverId).append("\"");
                }
                payload.append("}");
                os.write(payload.toString().getBytes("UTF-8"));
            } finally {
                if (os != null) os.close();
            }

            if (conn.getResponseCode() == 204) {
                return FakeURLConnection(originalUrl, originalConn, 200, "OK".getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Loki.log.error("joinServer failed", e);
            return FakeURLConnection(originalUrl, originalConn, 200, e.getMessage().getBytes("UTF-8"));
        }
        return FakeURLConnection(originalUrl, originalConn, 200, "Bad login".getBytes("UTF-8"));
    }

    // Credit: OnlineModeFix
    // https://github.com/craftycodie/OnlineModeFix/blob/main/src/gg/codie/mineonline/protocol/CheckServerURLConnection.java
    // https://github.com/craftycodie/OnlineModeFix/blob/main/src/gg/codie/minecraft/api/SessionServer.java
    public static URLConnection checkServer(URL originalUrl, URLConnection originalConn) throws UnsupportedEncodingException {
        try {
            Map<String, String> params = queryStringParser(originalUrl.getQuery());
            String user = params.get("user");
            if (user == null) throw new RuntimeException("missing user");
            String serverId = params.get("serverId");
            if (serverId == null) throw new RuntimeException("missing serverId");
            String ip = params.get("ip");

            URL url = new URL("https://sessionserver.mojang.com/session/minecraft/hasJoined?username=" + user + "&serverId=" + serverId + (ip != null ? "&ip=" + ip : ""));
            url = getYggdrasilUrl(url, null);
            URLStreamHandler handler = Hooks.DEFAULT_HANDLERS.get(url.getProtocol());
            HttpURLConnection conn = RequestInterceptor.openWithParent(url, handler);
            conn.setDoInput(true);
            conn.setDoOutput(false);
            conn.setRequestMethod("GET");
            conn.connect();

            if (conn.getResponseCode() == 200) {
                return FakeURLConnection(originalUrl, originalConn, 200, "YES".getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Loki.log.error("checkServer failed", e);
            return FakeURLConnection(originalUrl, originalConn, 200, e.getMessage().getBytes("UTF-8"));
        }
        return FakeURLConnection(originalUrl, originalConn, 200, "NO".getBytes("UTF-8"));
    }

    @SuppressWarnings("ExtractMethodRecommender")
    public static URL getYggdrasilUrl(URL originalUrl, URLConnection originalConn) throws MalformedURLException {
        String replacement = RequestInterceptor.YGGDRASIL_MAP.get(originalUrl.getHost());
        if (originalConn instanceof HttpsURLConnection && replacement.startsWith("http://")) {
            Loki.log.warn("Cannot downgrade HttpsURLConnection to HTTP, forcing HTTPS!");
            replacement = replacement.replaceFirst("^http://", "https://");
        }
        URL replacementUrl = new URL(replacement.startsWith("http") ? replacement
                : originalUrl.getProtocol() + "://" + replacement);

        String newPath = replacementUrl.getPath();
        if (!newPath.endsWith("/") && originalUrl.getPath().length() != 0) newPath += "/";
        newPath += originalUrl.getPath().startsWith("/") ? originalUrl.getPath().substring(1) : originalUrl.getPath();

        String finalUrlStr = replacementUrl.getProtocol() + "://" + replacementUrl.getHost();
        if (replacementUrl.getPort() != -1) finalUrlStr += ":" + replacementUrl.getPort();
        finalUrlStr += newPath;
        if (originalUrl.getQuery() != null) finalUrlStr += "?" + originalUrl.getQuery();

        return new URL(finalUrlStr);
    }

    private static Json.JSONArray getManifestVersions() throws IOException {
        return LauncherHooks.getManifestRoot().getJSONArray("versions");
    }

    // Drop BetterJSONs lwjgl3 ports and construct the release type from the id
    public static byte[] getReclassifiedManifest() throws IOException {
        byte[] cached = reclassifiedManifestCache;
        if (cached != null) return cached;
        synchronized (Ygglib.class) {
            if (reclassifiedManifestCache != null) return reclassifiedManifestCache;
            Json.JSONObject root = new Json.JSONObject(LauncherHooks.getManifestRoot().toString());
            Json.JSONArray versions = root.optJSONArray("versions");
            if (versions != null) {
                Json.JSONArray kept = new Json.JSONArray();
                for (int i = 0; i < versions.length(); i++) {
                    Json.JSONObject version = versions.getJSONObject(i);
                    if (version.optString("id", "").endsWith("-lwjgl3")) continue;
                    version.put("type", classifyType(version.optString("id", ""), version.optString("type", "release")));
                    kept.put(version);
                }
                root.put("versions", kept);
            }
            reclassifiedManifestCache = root.toString().getBytes("UTF-8");
            return reclassifiedManifestCache;
        }
    }

    private static String classifyType(String id, String existing) {
        if (id.startsWith("b1.")) return "old_beta";
        if (id.startsWith("a1.") || id.startsWith("a0.") || id.startsWith("c0.")
                || id.startsWith("inf-") || id.startsWith("in-") || id.startsWith("rd-") || id.startsWith("pc-")) {
            return "old_alpha";
        }
        if ("april-fools".equals(existing) || "special".equals(existing)) return "snapshot";
        String lower = id.toLowerCase();
        if (lower.startsWith("combat") || lower.contains("shareware") || lower.contains("-unobf")
                || id.matches("\\d\\dw\\d\\d.*") || lower.contains("-pre") || lower.contains("-rc")
                || lower.matches(".*-(snap|exp)\\d.*")) {
            return "snapshot";
        }
        return existing;
    }

    public static URL getVersionJsonURL(String version) {
        try {
            Json.JSONArray versions = getManifestVersions();

            for (int i = 0; i < versions.length(); i++) {
                Json.JSONObject entry = versions.getJSONObject(i);

                if (version.equals(entry.getString("id"))) {
                    return new URL(entry.getString("url"));
                }
            }
            Loki.log.error("Minecraft version not found in manifest: " + version);
        } catch (Exception e) {
            Loki.log.error("Failed to resolve version JSON URL for " + version, e);
        }
        return null;
    }

    public static URL getVersionJarURL(String version) {
        try {
            URL versionJsonURL = getVersionJsonURL(version);
            if (versionJsonURL == null) return null;

            Json.JSONObject versionJson = new Json.JSONObject(Ygglib.readStream(versionJsonURL.openConnection().getInputStream()));
            URL versionURL = new URL(versionJson.getJSONObject("downloads").getJSONObject("client").getString("url"));
            Loki.log.debug("Grabbed Minecraft " + version + " URL: " + versionURL);
            return versionURL;
        } catch (Exception e) {
            Loki.log.error("Failed to resolve client jar URL for " + version, e);
        }
        return null;
    }

    private static void appendResourceContents(StringBuilder xml, Json.JSONObject objects) {
        if (objects == null) return;
        java.util.Iterator<String> keys = objects.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.indexOf('/') < 0) continue; // skip non-resource files
            Json.JSONObject object = objects.getJSONObject(key);
            // In-game ThreadDownloadResources reads only <Key> and <Size>, skip <ETag>
            String escapedKey = key.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            xml.append("<Contents><Key>").append(escapedKey).append("</Key>")
                    .append("<Size>").append(object.getLong("size")).append("</Size></Contents>");
        }
    }

    // Serve pre-1.6 XML resources list
    public static byte[] getLegacyResourcesListing() {
        try {
            Json.JSONObject index = LauncherHooks.getAssetIndex();
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult>");
            appendResourceContents(xml, index.optJSONObject("objects"));
            appendResourceContents(xml, index.optJSONObject("custom"));
            xml.append("</ListBucketResult>");
            return xml.toString().getBytes("UTF-8");
        } catch (Exception e) {
            Loki.log.error("Failed to build legacy resources listing", e);
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult></ListBucketResult>".getBytes();
        }
    }

    // Pre-a1.1.2_01 plaintext resources list
    public static byte[] getLegacyResourcesListingPlain() {
        try {
            Json.JSONObject index = LauncherHooks.getAssetIndex();
            StringBuilder sb = new StringBuilder();
            appendResourceLines(sb, index.optJSONObject("objects"));
            appendResourceLines(sb, index.optJSONObject("custom"));
            return sb.toString().getBytes("UTF-8");
        } catch (Exception e) {
            Loki.log.error("Failed to build legacy resources listing", e);
            return new byte[0];
        }
    }

    private static void appendResourceLines(StringBuilder sb, Json.JSONObject objects) {
        if (objects == null) return;
        java.util.Iterator<String> keys = objects.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.indexOf('/') < 0) continue; // skip non-resource files
            Json.JSONObject object = objects.getJSONObject(key);
            sb.append(key).append(',').append(object.getLong("size")).append(",0\n");
        }
    }

    public static URL getLegacyResourceURL(String key) {
        try {
            Json.JSONObject index = LauncherHooks.getAssetIndex();
            Json.JSONObject object = null;
            Json.JSONObject objects = index.optJSONObject("objects");
            Json.JSONObject custom = index.optJSONObject("custom");
            if (objects != null && objects.has(key)) object = objects.getJSONObject(key);
            else if (custom != null && custom.has(key)) object = custom.getJSONObject(key);
            if (object == null) return null;

            String url = object.optString("url", "");
            if (url.length() != 0) return new URL(url);

            String hash = object.getString("hash");
            return new URL("https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash);
        } catch (Exception e) {
            Loki.log.error("Failed to resolve legacy resource " + key, e);
            return null;
        }
    }

    public static String rewriteVersionJsonForLegacyLauncher(String versionJson) {
        try {
            Json.JSONObject root = new Json.JSONObject(versionJson);
            root.put("minimumLauncherVersion", 0); // remove restriction
            // keep installed alphas/betas consistent with the manifest
            root.put("type", classifyType(root.optString("id", ""), root.optString("type", "release")));
            LauncherHooks.resolveVersionData(root);

            // Construct minecraftArguments if necessary
            if (!root.has("minecraftArguments")) {
                Json.JSONObject arguments = root.optJSONObject("arguments");
                Json.JSONArray game = arguments != null ? arguments.optJSONArray("game") : null;
                if (game != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < game.length(); i++) {
                        Object token = game.get(i);
                        if (token instanceof String) {
                            if (sb.length() > 0) sb.append(" ");
                            sb.append((String) token);
                        }
                    }
                    root.put("minecraftArguments", sb.toString());
                }
            }

            Json.JSONArray libraries = root.optJSONArray("libraries");
            if (libraries != null) {
                for (int i = 0; i < libraries.length(); i++) {
                    Json.JSONObject library = libraries.getJSONObject(i);
                    stripUnsupportedNatives(library);
                    Json.JSONArray rules = library.optJSONArray("rules");
                    if (rules == null) continue;

                    Json.JSONArray allowed = new Json.JSONArray();
                    for (String os : LEGACY_OS_NAMES) {
                        if (rulesAllowOnOS(rules, os)) allowed.put(os);
                    }

                    if (allowed.length() < LEGACY_OS_NAMES.length) {
                        library.put("os", allowed);
                    }
                }
            }

            String arch = System.getProperty("os.arch", "").contains("64") ? "64" : "32";
            return root.toString().replace("${arch}", arch);
        } catch (Exception e) {
            Loki.log.error("Failed to rewrite version JSON", e);
            return versionJson;
        }
    }

    private static boolean rulesAllowOnOS(Json.JSONArray rules, String osName) {
        String lastAction = "disallow";
        for (int i = 0; i < rules.length(); i++) {
            Json.JSONObject rule = rules.getJSONObject(i);
            Json.JSONObject os = rule.optJSONObject("os");
            if (os != null) {
                String ruleOs = os.optString("name", "");
                if (ruleOs.length() != 0 && !ruleOs.equals(osName)) continue;
            }
            lastAction = rule.optString("action", "allow");
        }
        return "allow".equals(lastAction);
    }

    public static String stripUnsupportedNatives(String versionJson) {
        try {
            Json.JSONObject root = new Json.JSONObject(versionJson);
            root.put("type", classifyType(root.optString("id", ""), root.optString("type", "release")));
            Json.JSONArray libraries = root.optJSONArray("libraries");
            if (libraries != null) {
                for (int i = 0; i < libraries.length(); i++) {
                    stripUnsupportedNatives(libraries.getJSONObject(i));
                }
            }
            return root.toString();
        } catch (Exception e) {
            Loki.log.error("Failed to strip unsupported natives", e);
            return versionJson;
        }
    }

    private static void stripUnsupportedNatives(Json.JSONObject library) {
        Json.JSONObject natives = library.optJSONObject("natives");
        if (natives == null) return;
        java.util.List<String> remove = new java.util.ArrayList<String>();
        java.util.Iterator<String> keys = natives.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!Arrays.asList(LEGACY_OS_NAMES).contains(key)) remove.add(key);
        }
        for (String key : remove) natives.remove(key);
    }

    public static URLConnection getAshcon(URL originalUrl, URLConnection originalConn, String username) throws UnknownHostException {
        try {
            String uuid = getUUID(username);
            Loki.log.debug("UUID of " + username + ": " + uuid);

            String profileJson = getTexturesProperty(uuid, true);
            Json.JSONObject profileObj = new Json.JSONObject(profileJson);
            Json.JSONObject properties = profileObj.getJSONArray("properties").getJSONObject(0);
            String texturesBase64 = properties.getString("value");
            String signaturesBase64 = properties.getString("signature");
            String texturePayload = new String(Base64.decode(texturesBase64), "UTF-8");
            Json.JSONObject texturePayloadObj = new Json.JSONObject(texturePayload);
            Json.JSONObject texturesObj = texturePayloadObj.getJSONObject("textures");
            if (!texturesObj.has("SKIN")) return FakeURLConnection(originalUrl, originalConn, 204, null);
            Json.JSONObject skinObj = texturesObj.getJSONObject("SKIN");
            boolean isSlim = false;
            if (skinObj.has("metadata")) {
                Json.JSONObject metadata = skinObj.getJSONObject("metadata");
                if ("slim".equals(metadata.optString("model", ""))) isSlim = true;
            }

            String responseJson = "{\n" +
                    "  \"uuid\": \"" + uuid + "\",\n" +
                    "  \"username\": \"" + username + "\",\n" +
                    "  \"username_history\": [\n" +
                    "    {\n" +
                    "      \"username\": \"" + username + "\"\n" +
                    "    }\n" +
                    "  ],\n" +
                    "  \"textures\": {\n" +
                    "    \"custom\": true,\n" +
                    "    \"slim\": " + isSlim + ",\n" +
                    "    \"raw\": {\n" +
                    "      \"value\": \"" + texturesBase64 + "\",\n" +
                    "      \"signature\": \"" + signaturesBase64 + "\"\n" +
                    "    }\n" +
                    "  },\n" +
                    "  \"created_at\": null\n" +
                    "}";
            return FakeURLConnection(originalUrl, originalConn, 200, (responseJson).getBytes("UTF-8"));
        } catch (UnknownHostException e) {
            throw e;
        } catch (Exception e) {
            Loki.log.error("getAshcon failed");
            throw new RuntimeException(e);
        }
    }

    public static URLConnection getElyBy(URL originalUrl, URLConnection originalConn, String username) {
        try {
            String uuid = getUUID(username);
            Loki.log.debug("UUID of " + username + ": " + uuid);

            String texturesProperty = getTexturesProperty(uuid, false);
            Json.JSONObject texturePayloadObj = new Json.JSONObject(texturesProperty);
            if (!texturePayloadObj.has("textures")) throw new RuntimeException("textures object was null");
            Json.JSONObject texturesObj = texturePayloadObj.getJSONObject("textures");

            if (RequestInterceptor.YGGDRASIL_MAP.get("sessionserver.mojang.com").startsWith("http://")) {
                if (texturesObj.has("SKIN")) {
                    Json.JSONObject skinObj = texturesObj.getJSONObject("SKIN");
                    String skinUrl = skinObj.optString("url", null);
                    if (skinUrl != null) {
                        skinUrl = skinUrl.replaceFirst("^https://", "http://");
                        skinObj.put("url", skinUrl);
                    }
                }

                if (texturesObj.has("CAPE")) {
                    Json.JSONObject capeObj = texturesObj.getJSONObject("CAPE");
                    String capeUrl = capeObj.optString("url", null);
                    if (capeUrl != null) {
                        capeUrl = capeUrl.replaceFirst("^https://", "http://");
                        capeObj.put("url", capeUrl);
                    }
                }
            }

            return FakeURLConnection(originalUrl, originalConn, 200, texturesObj.toString().getBytes("UTF-8"));
        } catch (Exception e) {
            Loki.log.error("getElyBy failed");
            throw new RuntimeException(e);
        }
    }

    public static URLConnection getMinotar(URL originalUrl, URLConnection originalConn, String username, int res) throws UnknownHostException {
        try {
            String uuid = getUUID(username);
            Loki.log.debug("UUID of " + username + ": " + uuid);

            String texturesProperty = getTexturesProperty(uuid, false);
            Json.JSONObject texturePayloadObj = new Json.JSONObject(texturesProperty);
            Json.JSONObject texturesObj = texturePayloadObj.getJSONObject("textures");
            if (!texturesObj.has("SKIN")) return FakeURLConnection(originalUrl, originalConn, 204, null);
            String skinUrl = texturesObj.getJSONObject("SKIN").optString("url", null);
            if (skinUrl == null) return FakeURLConnection(originalUrl, originalConn, 204, null);
            if (RequestInterceptor.YGGDRASIL_MAP.get("sessionserver.mojang.com").startsWith("http://")) {
                skinUrl = skinUrl.replaceFirst("^https://", "http://");
            }
            URL parsedSkinUrl = new URL(skinUrl);
            URLStreamHandler handler = Hooks.DEFAULT_HANDLERS.get(parsedSkinUrl.getProtocol());
            URLConnection connection = RequestInterceptor.openWithParent(parsedSkinUrl, handler);

            InputStream in = null;
            try {
                in = connection.getInputStream();
                BufferedImage image = ImageIO.read(in);
                int headPx = Math.max(1, image.getWidth() / 8);
                BufferedImage i1 = image.getSubimage(headPx, headPx, headPx, headPx);
                BufferedImage headWithOverlay = new BufferedImage(headPx, headPx, BufferedImage.TYPE_INT_ARGB);
                Graphics2D gHead = headWithOverlay.createGraphics();
                gHead.setComposite(AlphaComposite.SrcOver);
                gHead.drawImage(i1, 0, 0, null);
                BufferedImage overlay = image.getSubimage(5 * headPx, headPx, headPx, headPx);
                gHead.drawImage(overlay, 0, 0, null);
                gHead.dispose();
                BufferedImage i2 = new BufferedImage(res, res, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = i2.createGraphics();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2.drawImage(headWithOverlay, 0, 0, res, res, null);
                g2.dispose();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(i2, "png", out);
                return FakeURLConnection(originalUrl, originalConn, 200, out.toByteArray());
            } finally {
                if (in != null) in.close();
            }
        } catch (UnknownHostException e) {
            throw e;
        } catch (Exception e) {
            Loki.log.error("getMinotar failed");
            throw new RuntimeException(e);
        }
    }

    public static URLConnection FakeURLConnection(URL url, URLConnection originalConn, int code, byte[] data) {
        return (originalConn instanceof HttpsURLConnection)
                ? new FakeHttpsURLConnection(url, code, data)
                : new FakeHttpURLConnection(url, code, data);
    }

    public static class FakeHttpURLConnection extends HttpURLConnection {
        private final byte[] data;
        private final int code;

        public FakeHttpURLConnection(URL url, int code, byte[] data) {
            super(url);
            this.code = code;
            this.data = data;
        }

        @Override
        public void connect() {}

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(data);
        }

        @Override
        public int getContentLength() {
            return data.length;
        }

        @Override
        public int getResponseCode() {
            return code;
        }

        @Override
        public void disconnect() {}

        @Override
        public boolean usingProxy() {
            return false;
        }
    }

    public static class FakeHttpsURLConnection extends HttpsURLConnection {
        private final byte[] data;
        private final int code;

        public FakeHttpsURLConnection(URL url, int code, byte[] data) {
            super(url);
            this.code = code;
            this.data = data;
        }

        @Override
        public void connect() {}

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(data);
        }

        @Override
        public int getContentLength() {
            return data.length;
        }

        @Override
        public int getResponseCode() {
            return code;
        }

        @Override
        public void disconnect() {}

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public String getCipherSuite() {
            return "";
        }

        @Override
        public Certificate[] getLocalCertificates() {
            return new Certificate[0];
        }

        @Override
        public Certificate[] getServerCertificates() {
            return new Certificate[0];
        }
    }
}
