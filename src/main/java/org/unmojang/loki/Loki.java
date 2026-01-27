package org.unmojang.loki;

import org.unmojang.loki.util.logger.NilLogger;
import org.unmojang.loki.transformers.*;

import java.lang.instrument.Instrumentation;

public class Loki {
    public static final NilLogger log = NilLogger.get("Loki");

    public static final Boolean chat_restrictions =  Boolean.getBoolean("Loki.chat_restrictions");
    public static Boolean disable_factory = Boolean.getBoolean("Loki.disable_factory");
    public static final Boolean disable_realms = Boolean.getBoolean("Loki.disable_realms");
    public static final Boolean enable_patchy =  Boolean.getBoolean("Loki.enable_patchy");
    public static final Boolean enable_snooper =  Boolean.getBoolean("Loki.enable_snooper");
    public static final Boolean enforce_secure_profile = Boolean.getBoolean("Loki.enforce_secure_profile");
    public static final Boolean modded_capes = Boolean.getBoolean("Loki.modded_capes");
    public static final Boolean username_validation = Boolean.getBoolean("Loki.username_validation");

    public static void premain(String agentArgs, Instrumentation inst) {
        log.info("Hello Loki " + Loki.class.getPackage().getImplementationVersion() + " World!");
        LokiUtil.earlyInit(agentArgs, inst);

        // Kill Loki alternatives so that Loki won't break
        LokiUtil.addRetransformTransformer(new LokiAlternativesTransformer(), inst);
        LokiUtil.retransformClass("moe.yushi.authlibinjector.Premain", inst);
        LokiUtil.retransformClass("moe.yushi.authlibinjector.javaagent.AuthlibInjectorPremain", inst);
        LokiUtil.retransformClass("org.to2mbn.authlibinjector.javaagent.AuthlibInjectorPremain", inst);
        LokiUtil.retransformClass("moe.yushi.authlibinjector.transform.ClassTransformer", inst);
        LokiUtil.retransformClass("org.to2mbn.authlibinjector.transform.ClassTransformer", inst);

        // Authentication & skins/capes
        RequestInterceptor.setURLFactory();
        inst.addTransformer(new YggdrasilURLTransformer());
        inst.addTransformer(new SignatureValidTransformer());    /* Texture signatures (possibly unnecessary?)
		                                                                    1.7-1.18.2 (deprecated in 1.19) */

        // Allowed texture domains
        inst.addTransformer(new AllowedDomainTransformer());    // 1.7.6-1.16.5, 1.17-1.19.2, 1.19.3+

        // Public keys
        inst.addTransformer(new MainArgsTransformer()); // secure-profile breaks if userType is "mojang" on 1.19.3-1.21.8
        inst.addTransformer(new ServicesKeyInfoTransformer());  // 1.19+

        // Patchy
        inst.addTransformer(new PatchyTransformer());

        // Usernames
        inst.addTransformer(new UsernameCharacterCheckTransformer()); // Support cursed usernames on 1.18.2+
        inst.addTransformer(new UsernameConstantTransformer());

        // Misc fixes
        inst.addTransformer(new ConcatenateURLTransformer()); // Prevent port number being ignored in old authlib, if you specified it
        inst.addTransformer(new MCAuthlibGameProfileTransformer()); // Primarily for MojangFix
        LokiUtil.addRetransformTransformer(new SetURLFactoryTransformer(), inst); // Fix 1.13-1.16 Forge, LegacyFix agent
        LokiUtil.retransformClass("uk.betacraft.legacyfix.LegacyFixLauncher", inst);

        // Intercept OptiFine capes to prevent collisions, for whatever reason it isn't caught by Loki's URL factory
        inst.addTransformer(new OptiFineCapeTransformer());

        // Block some DNS lookups
        LokiUtil.addRetransformTransformer(new InetAddressTransformer(), inst);
        LokiUtil.retransformClass("java.net.InetAddress", inst);

        // Apply 1.21.9+ fixes
        LokiUtil.apply1219Fixes();
    }
}
