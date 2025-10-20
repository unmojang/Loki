package org.unmojang.loki;

import org.unmojang.loki.transformers.*;
import nilloader.api.ClassTransformer;
import nilloader.api.NilLogger;

import java.util.Objects;

public class Premain implements Runnable {
    public static final NilLogger log = NilLogger.get("Loki");

    @Override
    public void run() {
        log.info("Hello Loki World!");
        // TLS fixes for Mojang's jre-legacy
        LokiUtil.loadCacerts();
        // Authlib-Injector API
        if (System.getProperty("Loki.url", null) != null) {
            LokiUtil.initAuthlibInjectorAPI(System.getProperty("Loki.url"));
        }
        // Authentication & skins
        RequestInterceptor.setURLFactory();
        ClassTransformer.register(new SignatureValidTransformer());      /* Texture signatures (possibly unnecessary?)
		                                                                    1.7-1.18.2 (deprecated in 1.19) */

        // Allowed texture domains
        ClassTransformer.register(new AllowedDomainTransformer());    // 1.7.6-1.16.5, 1.17-1.19.2
        ClassTransformer.register(new NewAllowedDomainTransformer()); // 1.19.3+

        // Public keys
        ClassTransformer.register(new ServicesKeyInfoTransformer());  // 1.19+

        // Misc fixes
        ClassTransformer.register(new ConcatenateURLTransformer()); // Prevent port number being ignored in old authlib, if you specified it
        if (Objects.equals(System.getProperty("Loki.enable_vanilla_env", "true"), "true")) {
            LokiUtil.apply1219Fixes();
        } else {
            LokiUtil.unsetApiEnv();
        }
    }
}
