package org.unmojang.loki;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.http.*;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.*;
import org.apache.http.entity.*;
import org.apache.http.impl.client.*;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ProxySelector;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class LokiHTTP {
    private static final CloseableHttpClient client = HttpClients.custom()
            .setRoutePlanner(new SystemDefaultRoutePlanner(ProxySelector.getDefault()))
            .build();

    public static HttpResponse request(String method, String url, String body, String contentType) {
        try {
            HttpRequestBase request;
            switch (method.toUpperCase()) {
                case "POST":
                    HttpPost post = new HttpPost(url);
                    if (body != null) {
                        StringEntity entity = new StringEntity(body);
                        entity.setContentType(contentType);
                        post.setEntity(entity);
                    }
                    request = post;
                    break;
                case "PUT":
                    HttpPut put = new HttpPut(url);
                    if (body != null) {
                        StringEntity entity = new StringEntity(body);
                        entity.setContentType(contentType);
                        put.setEntity(entity);
                    }
                    request = put;
                    break;
                case "GET":
                    request = new HttpGet(url);
                    break;
                default:
                    throw new UnsupportedOperationException("Unsupported method: " + method);
            }

            return client.execute(request);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String getAuthlibInjectorApiLocation(String server) {
        try {
            Header AuthlibInjectorApiLocation = request("GET", server, null, null)
                    .getFirstHeader("X-Authlib-Injector-Api-Location");
            return AuthlibInjectorApiLocation != null ? AuthlibInjectorApiLocation.getValue() : null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static Map<String, Object> getAuthlibInjectorConfig(String server) {
        try {
            HttpResponse response = request("GET", server, null, null);
            BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
            String content = reader.lines().collect(Collectors.joining("\n"));
            JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
            JsonObject objMeta = obj.getAsJsonObject("meta");

            if (!objMeta.get("feature.enable_profile_key").getAsBoolean()) {
                return null;
            }

            String implementationName = objMeta.get("implementationName").getAsString();
            String implementationVersion = objMeta.get("implementationVersion").getAsString();
            String serverName = objMeta.get("serverName").getAsString();

            String publicKey = obj.get("signaturePublickey")
                    .getAsString().replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");

            JsonArray skinDomainsJsonArray = obj.get("skinDomains").getAsJsonArray();
            String[] skinDomains = new String[skinDomainsJsonArray.size()];
            for (int i = 0; i < skinDomainsJsonArray.size(); i++) {
                skinDomains[i] = skinDomainsJsonArray.get(i).getAsString();
            }

            Map<String, Object> result = new HashMap<>();
            result.put("implementationName", implementationName);
            result.put("implementationVersion", implementationVersion);
            result.put("serverName", serverName);
            result.put("publicKey", publicKey);
            result.put("skinDomains", skinDomains);
            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
