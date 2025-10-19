package com.unmojang.loki;

import java.net.spi.URLStreamHandlerProvider;
import java.net.URLStreamHandler;

public final class WrappingProvider extends URLStreamHandlerProvider {
    @Override
    public URLStreamHandler createURLStreamHandler(String protocol) {
        if (!"http".equals(protocol) && !"https".equals(protocol)) return null;
        URLStreamHandler defaultHandler = findPlatformHandler(protocol);
        if (defaultHandler == null) return null;
        return wrapHandler(defaultHandler);
    }

    private URLStreamHandler findPlatformHandler(String protocol) {
        try {
            String cls = "sun.net.www.protocol." + protocol + ".Handler";
            Class<?> c = Class.forName(cls);
            return (URLStreamHandler)c.getDeclaredConstructor().newInstance();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private URLStreamHandler wrapHandler(URLStreamHandler delegate) {
        return RequestInterceptor.wrapHandler(delegate);
    }
}
