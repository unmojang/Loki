package org.unmojang.loki;

import static org.unmojang.loki.Loki.*;
import static org.unmojang.loki.LokiHTTP.getAuthlibInjectorApiLocation;
import static org.unmojang.loki.LokiHTTP.getAuthlibInjectorConfig;

public class LokiUtil {
    public static void InitAuthlibInjectorAPI(String agentArgs) {
        if (agentArgs != null && (agentArgs.startsWith("http://") || agentArgs.startsWith("https://"))) {
            String authlibInjectorApiLocation = getAuthlibInjectorApiLocation(agentArgs);
            if (authlibInjectorApiLocation == null) authlibInjectorApiLocation = agentArgs;
            authlibInjectorConfig = getAuthlibInjectorConfig(authlibInjectorApiLocation);
            if (authlibInjectorConfig == null) return;
            System.out.println("[Loki] Using authlib-injector API");

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

            // System property smuggling
            System.setProperty("loki.internal.skinDomains", String.join(",",
                    (String[]) authlibInjectorConfig.get("skinDomains")));

            usingAuthlibInjectorAPI = true;
        }
    }
}
