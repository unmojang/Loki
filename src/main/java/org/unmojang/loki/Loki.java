package org.unmojang.loki;

import org.unmojang.loki.logger.NilLogger;
import org.unmojang.loki.transformers.*;

import java.lang.instrument.Instrumentation;

public class Loki {
    public static final NilLogger log = NilLogger.get("Loki");
    public static final Boolean debug = System.getProperty("Loki.debug", "false").equalsIgnoreCase("true");
    public static final Boolean enable_realms = !System.getProperty("Loki.enable_realms", "true").equalsIgnoreCase("false");
    public static final Boolean enable_snooper =  System.getProperty("Loki.enable_snooper", "false").equalsIgnoreCase("true");
    public static final Boolean enable_vanilla_env = !System.getProperty("Loki.enable_vanilla_env", "true").equalsIgnoreCase("false");
    public static final Boolean modded_capes = System.getProperty("Loki.modded_capes", "false").equalsIgnoreCase("true");

    public static void premain(String agentArgs, Instrumentation inst) {
        log.info("Hello Loki " + Loki.class.getPackage().getImplementationVersion() + " World!");
        // TLS fixes for Mojang's jre-legacy
        LokiUtil.loadCacerts();
        // Authlib-Injector API
        if (System.getProperty("Loki.url", null) != null) { // Prioritize Loki.url
            LokiUtil.initAuthlibInjectorAPI(System.getProperty("Loki.url"));
        } else if (agentArgs != null) {
            LokiUtil.initAuthlibInjectorAPI(agentArgs);
        }
        // Authentication & skins
        RequestInterceptor.setURLFactory();
        inst.addTransformer(new SignatureValidTransformer());      /* Texture signatures (possibly unnecessary?)
		                                                                    1.7-1.18.2 (deprecated in 1.19) */

        // Allowed texture domains
        inst.addTransformer(new AllowedDomainTransformer());    // 1.7.6-1.16.5, 1.17-1.19.2, 1.19.3+

        // Public keys
        inst.addTransformer(new ServicesKeyInfoTransformer());  // 1.19+

        // Misc fixes
        inst.addTransformer(new ConcatenateURLTransformer()); // Prevent port number being ignored in old authlib, if you specified it
        inst.addTransformer(new MCAuthlibGameProfileTransformer()); // Primarily for MojangFix

        // Intercept OptiFine capes to prevent collisions, for whatever reason it isn't caught by Loki's URL factory
        if (!modded_capes) inst.addTransformer(new OptiFineCapeTransformer());

        // Apply 1.21.9+ fixes if Loki.enable_vanilla_env is true (default), otherwise unset vanilla environment
        if (enable_vanilla_env) {
            LokiUtil.apply1219Fixes();
        } else {
            LokiUtil.unsetVanillaEnv();
        }
    }
}
