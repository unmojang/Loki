package com.unmojang.loki;

import com.unmojang.loki.transformers.*;
import nilloader.api.ClassTransformer;
import nilloader.api.NilLogger;

public class Premain implements Runnable {
	public static final NilLogger log = NilLogger.get("Loki");

	@Override
	public void run() {
		log.info("Hello Loki World!");
		ClassTransformer.register(new FactoryTransformer());
		ClassTransformer.register(new LegacyFactoryTransformer());
		ClassTransformer.register(new SignatureValidTransformer());   // Texture signatures (possibly unnecessary?)
																	  // 1.7.2-1.18.2 (deprecated in 1.19)

		// Allowed domains
		ClassTransformer.register(new AllowedDomainTransformer());    // 1.7.6-1.16.5, 1.17-1.19.2
		ClassTransformer.register(new NewAllowedDomainTransformer()); // 1.19.3+
	}
}
