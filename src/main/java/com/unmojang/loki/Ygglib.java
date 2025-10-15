package com.unmojang.loki;

import nilloader.api.lib.nanojson.JsonObject;
import nilloader.api.lib.nanojson.JsonParser;
import nilloader.api.lib.nanojson.JsonParserException;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Collectors;

import static com.unmojang.loki.Factories.YGGDRASIL_MAP;

public class Ygglib {
    public static String getUsernameFromPath(String path) {
        return path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.png$", "");
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

    public static HttpURLConnection getTexture(String username, String type) {
        try {
            String uuid = Ygglib.getUUID(username);
            String texturePayload = Ygglib.getTexturePayload(uuid);
            if (texturePayload == null) return null;
            JsonObject texturePayloadObj = JsonParser.object().from(texturePayload);
            JsonObject skinOrCape = texturePayloadObj.getObject("textures").getObject(type);
            String textureUrl = skinOrCape.getString("url");
            if(textureUrl == null) return null;

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

                    return new FakeURLConnection(new URL(textureUrl), out.toByteArray());
                }
            } else if (type.equals("CAPE")) {
                return (HttpURLConnection) new URL(textureUrl).openConnection();
            }
        } catch (Exception e) {
            return null;
        }
        return null;
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

        public FakeURLConnection(URL url, byte[] data) {
            super(url);
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
        public void disconnect() {

        }

        @Override
        public boolean usingProxy() {
            return false;
        }
    }
}
