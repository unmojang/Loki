package org.unmojang.loki;

import org.apache.http.*;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.*;
import org.apache.http.entity.*;
import org.apache.http.impl.client.*;

public class LokiHTTP {
    public static HttpResponse request(String method, String url, String body, String contentType) throws Exception {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
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
}
