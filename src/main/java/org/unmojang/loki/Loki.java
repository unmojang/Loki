package org.unmojang.loki;

import org.unmojang.loki.logger.NilLogger;
import org.unmojang.loki.transformers.*;

import java.lang.instrument.Instrumentation;

public class Loki {
    public static final NilLogger log = NilLogger.get("Loki");
    public static final Boolean disable_realms = Boolean.getBoolean("Loki.disable_realms");
    public static final Boolean enable_snooper =  Boolean.getBoolean("Loki.enable_snooper");
    public static final Boolean modded_capes = Boolean.getBoolean("Loki.modded_capes");
    public static Boolean disable_factory = Boolean.getBoolean("Loki.disable_factory");

    public static void premain(String agentArgs, Instrumentation inst) {
        log.info("Hello Loki " + Loki.class.getPackage().getImplementationVersion() + " World!");
        LokiUtil.earlyInit(agentArgs, inst);
        // Authentication & skins/capes
        RequestInterceptor.setURLFactory();
        inst.addTransformer(new YggdrasilURLTransformer());
        inst.addTransformer(new SignatureValidTransformer());    /* Texture signatures (possibly unnecessary?)
		                                                                    1.7-1.18.2 (deprecated in 1.19) */

        // Allowed texture domains
        inst.addTransformer(new AllowedDomainTransformer());    // 1.7.6-1.16.5, 1.17-1.19.2, 1.19.3+

        // Public keys
        inst.addTransformer(new ServicesKeyInfoTransformer());  // 1.19+

        // Patchy
        inst.addTransformer(new PatchyTransformer());

        // Misc fixes
        inst.addTransformer(new ConcatenateURLTransformer()); // Prevent port number being ignored in old authlib, if you specified it
        inst.addTransformer(new MCAuthlibGameProfileTransformer()); // Primarily for MojangFix
        inst.addTransformer(new ForgeSetURLFactoryTransformer(), true); // Fix 1.13-1.16 Forge

        // Intercept OptiFine capes to prevent collisions, for whatever reason it isn't caught by Loki's URL factory
        if (!modded_capes) {
            inst.addTransformer(new OptiFineCapeTransformer());
            inst.addTransformer(new InetAddressTransformer(), true);

            try {
                Class<?> inetClass = Class.forName("java.net.InetAddress");
                inst.retransformClasses(inetClass);
            } catch (Throwable t) {
                Loki.log.error("InetAddress is not modifiable!");
            }
        }

        // Apply 1.21.9+ fixes
        LokiUtil.apply1219Fixes();
    }
}
