package org.unmojang.loki;

import org.unmojang.loki.hooks.Hooks;
import org.unmojang.loki.util.Base64;
import org.unmojang.loki.util.Json;

import javax.imageio.ImageIO;
import javax.net.ssl.HttpsURLConnection;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.security.cert.Certificate;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class Ygglib {
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

    public static String getUUID(String username) throws UnknownHostException {
        try {
            URL skinUrl = new URL("https://api.mojang.com/users/profiles/minecraft/" + URLEncoder.encode(username, "UTF-8"));
            skinUrl = getYggdrasilUrl(skinUrl, skinUrl.getHost());
            URLStreamHandler handler = Hooks.DEFAULT_HANDLERS.get(skinUrl.getProtocol());
            HttpURLConnection conn = RequestInterceptor.openWithParent(skinUrl, handler);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            String jsonText = readStream(conn.getInputStream());
            Json.JSONObject obj = new Json.JSONObject(jsonText);
            return obj.getString("id");
        } catch (UnknownHostException e) {
            throw e;
        } catch (Exception e) {
            Loki.log.error("Failed to get UUID for " + username, e);
            return null;
        }
    }

    public static String getTexturesProperty(String uuid, boolean returnProfileJson) {
        try {
            URL textureUrl = new URL("https://sessionserver.mojang.com/session/minecraft/profile/" + URLEncoder.encode(uuid, "UTF-8") + "?unsigned=false");
            textureUrl = getYggdrasilUrl(textureUrl, textureUrl.getHost());
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
            Loki.log.error("Failed to get textures property for " + uuid, e);
            return null;
        }
    }

    // Credit: Prism Launcher legacy fixes
    // https://github.com/PrismLauncher/PrismLauncher/blob/develop/libraries/launcher/legacy/org/prismlauncher/legacy/fix/online/SkinFix.java
    public static URLConnection getTexture(URL originalUrl, URLConnection originalConn, String username, String type) throws UnknownHostException {
        try {
            String uuid = getUUID(username);
            if (uuid == null) throw new RuntimeException("Couldn't find UUID of " + username);
            Loki.log.debug("UUID of " + username + ": " + uuid);

            String texturesProperty = getTexturesProperty(uuid, false);
            if (texturesProperty == null) throw new RuntimeException("textures property was null");
            Json.JSONObject texturePayloadObj = new Json.JSONObject(texturesProperty);
            Json.JSONObject skinOrCape = texturePayloadObj.getJSONObject("textures").getJSONObject(type);
            String textureUrl = skinOrCape.getString("url");
            if (textureUrl == null) return FakeURLConnection(originalUrl, originalConn, 204, null);
            if (RequestInterceptor.YGGDRASIL_MAP.get("sessionserver.mojang.com").startsWith("http://")) {
                textureUrl = textureUrl.replaceFirst("^https://", "http://");
            }

            if (type.equals("SKIN")) {
                boolean isSlim = false;
                if (skinOrCape.has("metadata")) {
                    Json.JSONObject metadata = skinOrCape.getJSONObject("metadata");
                    if ("slim".equals(metadata.getString("model"))) {
                        isSlim = true;
                    }
                }
                URLConnection connection = new URL(textureUrl).openConnection();
                InputStream in = null;
                try {
                    // thank you ahnewark!
                    // this is heavily based on
                    // https://github.com/ahnewark/MineOnline/blob/4f4f86f9d051e0a6fd7ff0b95b2a05f7437683d7/src/main/java/gg/codie/mineonline/gui/textures/TextureHelper.java#L17
                    in = connection.getInputStream();
                    BufferedImage image = ImageIO.read(in);
                    Graphics2D graphics = image.createGraphics();
                    graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));

                    BufferedImage subimage;

                    if (image.getHeight() > 32) {
                        // flatten second layers
                        subimage = image.getSubimage(0, 32, 56, 16);
                        graphics.drawImage(subimage, 0, 16, null);
                    }

                    if (isSlim) {
                        // convert slim to classic
                        subimage = image.getSubimage(45, 16, 9, 16);
                        graphics.drawImage(subimage, 46, 16, null);

                        subimage = image.getSubimage(49, 16, 2, 4);
                        graphics.drawImage(subimage, 50, 16, null);

                        subimage = image.getSubimage(53, 20, 2, 12);
                        graphics.drawImage(subimage, 54, 20, null);
                    }

                    graphics.dispose();

                    // crop the image - old versions disregard all secondary layers besides the hat
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    image = image.getSubimage(0, 0, 64, 32);
                    ImageIO.write(image, "png", out);

                    return FakeURLConnection(originalUrl, originalConn, 200, out.toByteArray());
                } finally {
                    if (in != null) in.close();
                }
            } else if (type.equals("CAPE")) {
                return new URL(textureUrl).openConnection();
            }
            throw new RuntimeException("Unexpected texture type. How did we get here?");
        } catch (UnknownHostException e) {
            throw e;
        } catch (Exception e) {
            Loki.log.error("getTexture failed", e);
            throw new RuntimeException(e);
        }
    }

    // Credit: Fjord Launcher legacy fixes
    // https://github.com/unmojang/FjordLauncher/blob/develop/libraries/launcher/legacy/org/prismlauncher/legacy/fix/online/OnlineModeFix.java
    public static URLConnection joinServer(URL originalUrl, URLConnection originalConn) {
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
            if (!sessionId.contains(":") && !sessionId.contains("%3A")) {
                throw new RuntimeException("invalid sessionId");
            }
            String[] parts = sessionId.split(sessionId.contains(":") ? ":" : "%3A");
            if (parts.length < 3 || parts[1].length() == 0 || parts[2].length() == 0) {
                throw new RuntimeException("invalid sessionId");
            }

            String accessToken = parts[1];
            String uuid = parts[2];
            Loki.log.debug("UUID of " + username + ": " + uuid);

            URL url = new URL("https://sessionserver.mojang.com/session/minecraft/join");
            url = getYggdrasilUrl(url, url.getHost());
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
            return FakeURLConnection(originalUrl, originalConn, 200, "Bad login".getBytes("UTF-8"));
        } catch (Exception e) {
            Loki.log.error("joinServer failed", e);
            throw new RuntimeException(e);
        }
    }

    // Credit: OnlineModeFix
    // https://github.com/craftycodie/OnlineModeFix/blob/main/src/gg/codie/mineonline/protocol/CheckServerURLConnection.java
    // https://github.com/craftycodie/OnlineModeFix/blob/main/src/gg/codie/minecraft/api/SessionServer.java
    public static URLConnection checkServer(URL originalUrl, URLConnection originalConn) {
        try {
            Map<String, String> params = queryStringParser(originalUrl.getQuery());
            String user = params.get("user");
            if (user == null) throw new RuntimeException("missing user");
            String serverId = params.get("serverId");
            if (serverId == null) throw new RuntimeException("missing serverId");
            String ip = params.get("ip");

            URL url = new URL("https://sessionserver.mojang.com/session/minecraft/hasJoined?username=" + user + "&serverId=" + serverId + (ip != null ? "&ip=" + ip : ""));
            url = getYggdrasilUrl(url, url.getHost());
            URLStreamHandler handler = Hooks.DEFAULT_HANDLERS.get(url.getProtocol());
            HttpURLConnection conn = RequestInterceptor.openWithParent(url, handler);
            conn.setDoInput(true);
            conn.setDoOutput(false);
            conn.setRequestMethod("GET");
            conn.connect();

            if (conn.getResponseCode() == 200) {
                return FakeURLConnection(originalUrl, originalConn, 200, "YES".getBytes("UTF-8"));
            }
            return FakeURLConnection(originalUrl, originalConn, 200, "Bad login".getBytes("UTF-8"));
        } catch (Exception e) {
            Loki.log.error("checkServer failed", e);
            throw new RuntimeException(e);
        }
    }

    public static URL getYggdrasilUrl(URL originalUrl, String server) throws MalformedURLException {
        String replacement = RequestInterceptor.YGGDRASIL_MAP.get(server);
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

    public static URLConnection getSessionProfile(URL originalUrl, URLConnection originalConn) {
        try {
            HttpURLConnection conn = RequestInterceptor.mirrorHttpURLConnection(originalUrl, (HttpURLConnection) originalConn);
            String profileJson = readStream(conn.getInputStream());
            Json.JSONObject profileObj = new Json.JSONObject(profileJson);
            Json.JSONArray properties = profileObj.getJSONArray("properties");
            if (properties == null) throw new RuntimeException("properties was null");

            // Use iterator to safely remove elements
            Iterator<Object> iter = properties.iterator();
            while (iter.hasNext()) {
                Object elem = iter.next();
                if (elem instanceof Json.JSONObject) {
                    Json.JSONObject prop = (Json.JSONObject) elem;
                    String name = prop.getString("name");
                    if (!"textures".equals(name)) {
                        iter.remove(); // remove uploadableTextures entry
                    }
                } else {
                    throw new RuntimeException("elem was not an instance of JsonObject");
                }
            }
            profileJson = profileObj.toString();
            return FakeURLConnection(originalUrl, originalConn, 200, profileJson.getBytes("UTF-8"));
        } catch (Exception e) {
            Loki.log.error("getSessionProfile failed", e);
            throw new RuntimeException(e);
        }
    }

    public static URLConnection getAshcon(URL originalUrl, URLConnection originalConn, String username) throws UnknownHostException {
        try {
            String uuid = getUUID(username);
            if (uuid == null) throw new RuntimeException("Couldn't find UUID of " + username);
            Loki.log.debug("UUID of " + username + ": " + uuid);

            String profileJson = getTexturesProperty(uuid, true);
            if (profileJson == null) throw new RuntimeException("profile JSON was null");
            Json.JSONObject profileObj = new Json.JSONObject(profileJson);
            Json.JSONObject properties = profileObj.getJSONArray("properties").getJSONObject(0);
            String texturesBase64 = properties.getString("value");
            String signaturesBase64 = properties.getString("signature");
            String texturePayload = new String(Base64.decode(texturesBase64), "UTF-8");
            Json.JSONObject texturePayloadObj = new Json.JSONObject(texturePayload);
            Json.JSONObject skinObj = texturePayloadObj.getJSONObject("textures").getJSONObject("SKIN");
            boolean isSlim = false;
            if (skinObj.has("metadata")) {
                Json.JSONObject metadata = skinObj.getJSONObject("metadata");
                if ("slim".equals(metadata.getString("model"))) {
                    isSlim = true;
                }
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
            Loki.log.error("getAshcon failed", e);
            throw new RuntimeException(e);
        }
    }

    public static URLConnection getMinotar(URL originalUrl, URLConnection originalConn, String username, int res) throws UnknownHostException {
        try {
            String uuid = getUUID(username);
            if (uuid == null) throw new RuntimeException("Couldn't find UUID of " + username);
            Loki.log.debug("UUID of " + username + ": " + uuid);

            String texturesProperty = getTexturesProperty(uuid, false);
            if (texturesProperty == null) throw new RuntimeException("textures property was null");
            Json.JSONObject texturePayloadObj = new Json.JSONObject(texturesProperty);
            String textureUrl = texturePayloadObj.getJSONObject("textures").getJSONObject("SKIN").getString("url");
            if (textureUrl == null) return FakeURLConnection(originalUrl, originalConn, 204, null);
            if (RequestInterceptor.YGGDRASIL_MAP.get("sessionserver.mojang.com").startsWith("http://")) {
                textureUrl = textureUrl.replaceFirst("^https://", "http://");
            }
            URLConnection connection = new URL(textureUrl).openConnection();

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
            Loki.log.error("getMinotar failed", e);
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
