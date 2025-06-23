package org.unmojang.loki;

import java.lang.instrument.Instrumentation;

public class Loki {
	public static final String accountHost  = System.getProperty("minecraft.api.account.host",  "https://api.mojang.com");
	public static final String authHost     = System.getProperty("minecraft.api.auth.host",     "https://authserver.mojang.com");
	public static final String sessionHost  = System.getProperty("minecraft.api.session.host",  "https://sessionserver.mojang.com");
	public static final String servicesHost = System.getProperty("minecraft.api.services.host", "https://api.minecraftservices.com");

	public static void premain(String agentArgs, Instrumentation inst) {
		System.out.printf(
				"[Loki]   accountHost: %s\n" +
				"[Loki]      authHost: %s\n" +
				"[Loki]   sessionHost: %s\n" +
				"[Loki]  servicesHost: %s\n",
				accountHost, authHost, sessionHost, servicesHost
		);

		LokiAgentBuilder.buildTextureAgents(inst); // for all versions
		LokiAgentBuilder.buildHostAgents(inst); // for <1.16, no harm in applying it to newer versions though
	}
}
