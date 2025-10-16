package com.unmojang.loki;

import com.unmojang.loki.transformers.*;
import nilloader.api.ClassTransformer;
import nilloader.api.NilLogger;

public class Premain implements Runnable {
	public static final NilLogger log = NilLogger.get("Loki");

	@Override
	public void run() {
		log.info("Hello Loki World!");
		// TLS fixes for Mojang's jre-legacy
		NetUtil.loadCacerts();
		// Authentication & skins
		ClassTransformer.register(new ClassicFactoryTransformer());      // c0.0.18a-c0.30
		ClassTransformer.register(new ReallyLegacyFactoryTransformer()); // Indev-a1.0.5_01
		ClassTransformer.register(new LegacyFactoryTransformer());       // a1.0.6-1.6
		ClassTransformer.register(new FactoryTransformer());             // 1.6-1.16
		ClassTransformer.register(new SignatureValidTransformer());      // Texture signatures (possibly unnecessary?)
																	     // 1.7-1.18.2 (deprecated in 1.19)
		ClassTransformer.register(new ServerFactoryTransformer());       // <1.16

		// Allowed texture domains
		ClassTransformer.register(new AllowedDomainTransformer());    // 1.7.6-1.16.5, 1.17-1.19.2
		ClassTransformer.register(new NewAllowedDomainTransformer()); // 1.19.3+

		// Misc fixes
		ClassTransformer.register(new ConcatenateURLTransformer()); // Prevent port number being ignored in old authlib, if you specified it
		if (System.getProperty("minecraft.api.profiles.host") == null) { // 1.21.9+ fixes
			log.info("Applying 1.21.9+ fixes");
			System.setProperty("minecraft.api.profiles.host", RequestInterceptor.YGGDRASIL_MAP.get("api.mojang.com"));
		} else if (System.getProperty("minecraft.api.account.host") == null) {
			log.info("Applying <1.21.9 fixes");
			System.setProperty("minecraft.api.account.host", RequestInterceptor.YGGDRASIL_MAP.get("api.mojang.com"));
		}
	}
}
