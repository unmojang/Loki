package com.unmojang.loki;

import nilloader.api.lib.nanojson.JsonObject;
import nilloader.api.lib.nanojson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static com.unmojang.loki.RequestInterceptor.YGGDRASIL_MAP;

public class Ygglib {
    public static String getUsernameFromPath(String path) {
        return path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.png$", "");
    }

    public static Map<String, String> queryStringParser(String query) {
        Map<String, String> params = new HashMap<>();
        String[] entries = query.split("&");
        for (String entry : entries) {
            String[] pair = entry.split("=");
            if (pair.length == 2) {
                params.put(pair[0], pair[1]);
            }
        }
        return params;
    }

    public static String getUUID(String username) {
        try {
            URL skinUrl = new URL("https://api.mojang.com/users/profiles/minecraft/" + URLEncoder.encode(username, "UTF-8"));
            skinUrl = getYggdrasilUrl(skinUrl, skinUrl.getHost());
            HttpURLConnection conn = (HttpURLConnection) skinUrl.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String jsonText = reader.lines().collect(Collectors.joining());
            JsonObject obj = JsonParser.object().from(jsonText);
            return obj.getString("id");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String getTexturePayload(String uuid) {
        try {
            URL textureUrl = new URL("https://sessionserver.mojang.com/session/minecraft/profile/" + URLEncoder.encode(uuid, "UTF-8"));
            textureUrl = getYggdrasilUrl(textureUrl, textureUrl.getHost());
            HttpURLConnection conn = (HttpURLConnection) textureUrl.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String profileJson = reader.lines().collect(Collectors.joining());
            JsonObject profileObj = JsonParser.object().from(profileJson);
            String texturesBase64 = profileObj.getArray("properties").getObject(0).getString("value");
            return new String(Base64.getDecoder().decode(texturesBase64), StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // Credit: Prism Launcher legacy fixes
    // https://github.com/PrismLauncher/PrismLauncher/blob/develop/libraries/launcher/legacy/org/prismlauncher/legacy/fix/online/SkinFix.java
    public static HttpURLConnection getTexture(URL originalUrl, String username, String type) {
        try {
            String uuid = Ygglib.getUUID(username);
            String texturePayload = Ygglib.getTexturePayload(uuid);
            if (texturePayload == null) return null;
            JsonObject texturePayloadObj = JsonParser.object().from(texturePayload);
            JsonObject skinOrCape = texturePayloadObj.getObject("textures").getObject(type);
            String textureUrl = skinOrCape.getString("url");
            if(textureUrl == null) return new FakeURLConnection(originalUrl, 204, null);

            if(type.equals("SKIN")) {
                boolean isSlim = false;
                if (skinOrCape.has("metadata")) {
                    JsonObject metadata = skinOrCape.getObject("metadata");
                    if ("slim".equals(metadata.getString("model", ""))) {
                        isSlim = true;
                    }
                }
                URLConnection connection = new URL(textureUrl).openConnection();
                try (InputStream in = connection.getInputStream()) {
                    // thank you ahnewark!
                    // this is heavily based on
                    // https://github.com/ahnewark/MineOnline/blob/4f4f86f9d051e0a6fd7ff0b95b2a05f7437683d7/src/main/java/gg/codie/mineonline/gui/textures/TextureHelper.java#L17
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

                    return new FakeURLConnection(originalUrl, 200, out.toByteArray());
                }
            } else if (type.equals("CAPE")) {
                return (HttpURLConnection) new URL(textureUrl).openConnection();
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    // Credit: Fjord Launcher legacy fixes
    // https://github.com/unmojang/FjordLauncher/blob/develop/libraries/launcher/legacy/org/prismlauncher/legacy/fix/online/OnlineModeFix.java
    public static HttpURLConnection joinServer(URL originalUrl) {
        try {
            Map<String, String> params = queryStringParser(originalUrl.getQuery());
            String user = params.get("user");
            if (user == null) {
                throw new AssertionError("missing user");
            }
            String serverId = params.get("serverId");
            String sessionId = params.getOrDefault("sessionId", params.get("session"));
            if (sessionId == null) {
                throw new AssertionError("missing session/sessionId");
            }

            // sessionId has the form:
            // token:<accessToken>:<player UUID>
            // or, as of Minecraft release 1.3.1, it may be URL encoded:
            // token%3A<accessToken>%3A<player UUID>
            String accessToken;
            if (sessionId.contains(":")) {
                accessToken = sessionId.split(":")[1];
            } else if (sessionId.contains("%3A")) {
                accessToken = sessionId.split("%3A")[1];
            } else {
                throw new AssertionError("invalid sessionId");
            }

            String uuid;
            uuid = getUUID(user);
            if (uuid == null) {
                return new FakeURLConnection(originalUrl, 200, ("Couldn't find UUID of " + user).getBytes(StandardCharsets.UTF_8));
            }

            URL url = new URL( "https://sessionserver.mojang.com/session/minecraft/join");
            url = getYggdrasilUrl(url, url.getHost());
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            try (OutputStream os = connection.getOutputStream()) {
                StringBuilder payload = new StringBuilder();
                payload.append("{")
                        .append("\"accessToken\": \"").append(accessToken).append("\",")
                        .append("\"selectedProfile\": \"").append(uuid).append("\"");

                if (serverId != null) {
                    payload.append(",\"serverId\": \"").append(serverId).append("\"");
                }
                payload.append("}");
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            int responseCode = connection.getResponseCode();

            if (responseCode == 204) {
                return new FakeURLConnection(originalUrl, 200, "OK".getBytes(StandardCharsets.UTF_8));
            } else {
                return new FakeURLConnection(originalUrl, 200, "Bad login".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            throw new AssertionError("An error occurred");
        }
    }

    // Credit: OnlineModeFix
    // https://github.com/craftycodie/OnlineModeFix/blob/main/src/gg/codie/mineonline/protocol/CheckServerURLConnection.java
    // https://github.com/craftycodie/OnlineModeFix/blob/main/src/gg/codie/minecraft/api/SessionServer.java
    public static HttpURLConnection checkServer(URL originalUrl) {
        try {
            Map<String, String> params = queryStringParser(originalUrl.getQuery());
            String user = params.get("user");
            if (user == null) {
                throw new AssertionError("missing user");
            }
            String serverId = params.get("serverId");
            if (serverId == null) {
                throw new AssertionError("missing serverId");
            }
            String ip = params.get("ip");

            URL url = new URL( "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=" + user + "&serverId=" + serverId + (ip != null ? "&ip=" + ip : ""));
            url = getYggdrasilUrl(url, url.getHost());
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.setDoOutput(false);
            connection.setRequestMethod("GET");
            connection.connect();
            int responseCode = connection.getResponseCode();

            if (responseCode == 200) {
                return new FakeURLConnection(originalUrl, 200, "YES".getBytes(StandardCharsets.UTF_8));
            } else {
                return new FakeURLConnection(originalUrl, 200, "Bad login".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            throw new AssertionError("An error occurred");
        }
    }

    public static URL getYggdrasilUrl(URL originalUrl, String server) throws MalformedURLException {
        String replacement = YGGDRASIL_MAP.get(server);
        URL replacementUrl = new URL(replacement.startsWith("http") ? replacement
                : originalUrl.getProtocol() + "://" + replacement);

        String newPath = replacementUrl.getPath();
        if (!newPath.endsWith("/") && !originalUrl.getPath().isEmpty()) newPath += "/";
        newPath += originalUrl.getPath().startsWith("/") ? originalUrl.getPath().substring(1) : originalUrl.getPath();

        String finalUrlStr = replacementUrl.getProtocol() + "://" + replacementUrl.getHost();
        if (replacementUrl.getPort() != -1) finalUrlStr += ":" + replacementUrl.getPort();
        finalUrlStr += newPath;
        if (originalUrl.getQuery() != null) finalUrlStr += "?" + originalUrl.getQuery();

        return new URL(finalUrlStr);
    }

    public static class FakeURLConnection extends HttpURLConnection {

        private final byte[] data;
        private final int code;

        public FakeURLConnection(URL url, int code, byte[] data) {
            super(url);
            this.code = code;
            this.data = data;
        }

        @Override
        public void connect() {
        }

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
        public void disconnect() {

        }

        @Override
        public boolean usingProxy() {
            return false;
        }
    }
}
