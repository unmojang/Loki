package org.unmojang.loki;

import java.lang.instrument.Instrumentation;

import static org.unmojang.loki.LokiHTTP.getAuthlibInjectorApiLocation;

public class Loki {
	public static String accountHost  = System.getProperty("minecraft.api.account.host",  "https://api.mojang.com");
	public static String authHost     = System.getProperty("minecraft.api.auth.host",     "https://authserver.mojang.com");
	public static String sessionHost  = System.getProperty("minecraft.api.session.host",  "https://sessionserver.mojang.com");
	public static String servicesHost = System.getProperty("minecraft.api.services.host", "https://api.minecraftservices.com");
	public static boolean usingAuthlibInjectorAPI = false;

	public static void premain(String agentArgs, Instrumentation inst) {
		if(agentArgs != null && (agentArgs.startsWith("http://") || agentArgs.startsWith("https://"))) {
			String authlibInjectorApiLocation = getAuthlibInjectorApiLocation(agentArgs);
			if(authlibInjectorApiLocation != null) {
				System.out.println("[Loki] Using authlib-injector API, secure-profile and domain whitelisting will be available");

				// 1.16+, have authlib handle it for us
				System.setProperty("minecraft.api.env", "custom");
				System.setProperty("minecraft.api.account.host", authlibInjectorApiLocation + "/api");
				System.setProperty("minecraft.api.auth.host", authlibInjectorApiLocation + "/authserver");
				System.setProperty("minecraft.api.session.host", authlibInjectorApiLocation + "/sessionserver");
				System.setProperty("minecraft.api.services.host", authlibInjectorApiLocation + "/minecraftservices");

				accountHost = authlibInjectorApiLocation + "/api";
				authHost = authlibInjectorApiLocation + "/authserver";
				sessionHost = authlibInjectorApiLocation + "/sessionserver";
				servicesHost = authlibInjectorApiLocation + "/minecraftservices";

				usingAuthlibInjectorAPI = true;
			}
		}

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
