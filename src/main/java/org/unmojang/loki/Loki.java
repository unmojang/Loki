package org.unmojang.loki;

import java.lang.instrument.Instrumentation;
import java.util.Map;

public class Loki {
	public static String accountHost  = System.getProperty("minecraft.api.account.host",  "https://api.mojang.com");
	public static String authHost     = System.getProperty("minecraft.api.auth.host",     "https://authserver.mojang.com");
	public static String sessionHost  = System.getProperty("minecraft.api.session.host",  "https://sessionserver.mojang.com");
	public static String servicesHost = System.getProperty("minecraft.api.services.host", "https://api.minecraftservices.com");
	public static boolean usingAuthlibInjectorAPI = false;
	public static Map<String, Object> authlibInjectorConfig;

	public static void premain(String agentArgs, Instrumentation inst) {
		LokiUtil.InitAuthlibInjectorAPI(agentArgs);
		System.out.printf(
				"[Loki]  accountHost: %s\n" +
				"[Loki]     authHost: %s\n" +
				"[Loki]  sessionHost: %s\n" +
				"[Loki] servicesHost: %s\n",
				accountHost, authHost, sessionHost, servicesHost
		);

		//if(!usingAuthlibInjectorAPI) {
			LokiAgentBuilder.buildUnsignedTextureAgents(inst); // only used when running without authlib-injector API
		/*} else {
			LokiAgentBuilder.buildSignedTextureAgents(inst);
		}*/
		LokiAgentBuilder.buildAuthAgents(inst); // for <1.16, no harm in applying it to newer versions though
	}
}
