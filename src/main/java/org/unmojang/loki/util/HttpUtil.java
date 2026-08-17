package org.unmojang.loki.util;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HttpUtil {
    private static final int TIMEOUT_MS = 5000;

    private HttpUtil() {}

    public interface ConnectionFactory {
        HttpURLConnection open(String pathSuffix) throws Exception;
    }

    public static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    public static String readStream(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }

    public static Map<String, String> batchLookupUUIDs(ConnectionFactory factory, List<String> usernames) throws Exception {
        HttpURLConnection conn = factory.open("/profiles/minecraft");
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);

        StringBuilder body = new StringBuilder("[");
        for (int i = 0; i < usernames.size(); i++) {
            if (i > 0) body.append(",");
            body.append(Json.JSONObject.quote(usernames.get(i)));
        }
        body.append("]");
        OutputStream os = conn.getOutputStream();
        try {
            os.write(body.toString().getBytes("UTF-8"));
        } finally {
            os.close();
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            if (code == 429) throw UuidBatcher.rateLimited(conn);
            if (code == 404 || code == 405 || code == 501) {
                throw new UuidBatcher.EndpointUnavailableException("Batch UUID endpoint returned " + code);
            }
            throw new IOException("Batch UUID endpoint returned " + code);
        }

        Json.JSONArray arr = new Json.JSONArray(readStream(conn.getInputStream()));
        Map<String, String> result = new HashMap<String, String>();
        for (int i = 0; i < arr.length(); i++) {
            Json.JSONObject obj = arr.getJSONObject(i);
            result.put(obj.getString("name").toLowerCase(Locale.ENGLISH), obj.getString("id"));
        }
        return result;
    }

    public static String singleLookupUUID(ConnectionFactory factory, String username) throws Exception {
        String id = lookupOne(factory, "/users/profiles/minecraft/" + URLEncoder.encode(username, "UTF-8"));
        if (id != null) return id;
        // route not implemented? try the newer bulk-name endpoint's single form
        return lookupOne(factory, "/minecraft/profile/lookup/name/" + URLEncoder.encode(username, "UTF-8"));
    }

    private static String lookupOne(ConnectionFactory factory, String pathSuffix) throws Exception {
        HttpURLConnection conn = factory.open(pathSuffix);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);

        int code = conn.getResponseCode();
        if (code == 200) {
            return new Json.JSONObject(readStream(conn.getInputStream())).getString("id");
        }
        if (code == 429) throw UuidBatcher.rateLimited(conn);
        return null;
    }
}
