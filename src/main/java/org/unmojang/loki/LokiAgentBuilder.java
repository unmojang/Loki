package org.unmojang.loki;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import org.unmojang.loki.LokiInterceptors.*;

import java.net.URL;
import java.lang.instrument.Instrumentation;

public class LokiAgentBuilder {
    public static void buildUnsignedTextureAgents(Instrumentation inst) {
        AgentBuilder agentBuilder = new AgentBuilder.Default();
        // Texture signatures (possibly unnecessary?)
        // 1.7.2-1.18.2 (deprecated in 1.19)
        agentBuilder = installTransform(agentBuilder,
                "com.mojang.authlib.properties.Property",
                "isSignatureValid",
                ReturnTrueInterceptor.class);

        // Textures
        // 1.7.6-1.16.5 (1.7.2-1.7.5 use hardcoded http://skins.minecraft.net in game jar)
        agentBuilder = installTransform(agentBuilder,
                "com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService",
                "isWhitelistedDomain",
                ReturnTrueInterceptor.class);

        // 1.17-1.19.2
        agentBuilder = installTransform(agentBuilder,
                "com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService",
                "isAllowedTextureDomain",
                ReturnTrueInterceptor.class);

        // 1.19.3+
        agentBuilder = installTransform(agentBuilder,
                "com.mojang.authlib.yggdrasil.TextureUrlChecker",
                "isAllowedTextureDomain",
                ReturnTrueInterceptor.class);

        agentBuilder.installOn(inst);
    }

    public static void buildSignedTextureAgents(Instrumentation inst) {
        AgentBuilder agentBuilder = new AgentBuilder.Default();
        // Texture signatures (possibly unnecessary?)
        // 1.7.2-1.18.2 (deprecated in 1.19)
        agentBuilder = agentBuilder.type(ElementMatchers.named("com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService"))
                .transform((builder, typeDescription, classLoader, javaModule, foobar) -> builder
                        .constructor(ElementMatchers.any())
                        .intercept(Advice.to(PublicKeyInterceptor.class)));

        // Textures
        // 1.7.6-1.16.5 (1.7.2-1.7.5 use hardcoded http://skins.minecraft.net in game jar)
        agentBuilder = installTransform(agentBuilder,
                "com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService",
                "isWhitelistedDomain",
                TextureWhitelistInterceptor.class);

        // 1.17-1.19.2
        agentBuilder = installTransform(agentBuilder,
                "com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService",
                "isAllowedTextureDomain",
                TextureWhitelistInterceptor.class);

        // 1.19.3+
        agentBuilder = installTransform(agentBuilder,
                "com.mojang.authlib.yggdrasil.TextureUrlChecker",
                "isAllowedTextureDomain",
                TextureWhitelistInterceptor.class);

        agentBuilder.installOn(inst);
    }

    public static void buildAuthAgents(Instrumentation inst) {
        AgentBuilder agentBuilder = new AgentBuilder.Default();

        // Patch static initializers (auth, session, account servers)
        agentBuilder
                .type(ElementMatchers.namedOneOf(
                        "com.mojang.authlib.yggdrasil.YggdrasilUserAuthentication",
                        "com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService",
                        "com.mojang.authlib.yggdrasil.YggdrasilGameProfileRepository"))
                .transform((builder, typeDescription, classLoader, javaModule, foobar) ->
                        builder.visit(Advice.to(StaticFinalStringInterceptor.class).on(ElementMatchers.isTypeInitializer())))
                .installOn(inst);

        // Patch constantURL method (thanks for this mojang, saves me a lot of work)
        //
        // YggdrasilUserAuthentication, YggdrasilMinecraftSessionService,
        // and YggdrasilGameProfileRepository all use this.
        agentBuilder
                .type(ElementMatchers.named("com.mojang.authlib.HttpAuthenticationService"))
                .transform((builder, typeDescription, classLoader, javaModule, foobar) ->
                        builder.method(ElementMatchers.named("constantURL"))
                                .intercept(Advice.to(ConstantURLInterceptor.class)))
                .installOn(inst);

        // Patch concatenateURL method, 1.7.2 doesn't parse the port which leads to URL being invalid
        // https://github.com/yushijinhun/authlib-injector/issues/126
        agentBuilder
                .type(ElementMatchers.named("com.mojang.authlib.HttpAuthenticationService"))
                .transform((builder, typeDescription, classLoader, javaModule, foobar) ->
                        builder.method(ElementMatchers.named("concatenateURL")
                                        .and(ElementMatchers.takesArguments(URL.class, String.class))
                                        .and(ElementMatchers.returns(URL.class)))
                                .intercept(Advice.to(ConcatenateURLInterceptor.class)))
                .installOn(inst);
    }

    private static net.bytebuddy.agent.builder.AgentBuilder installTransform(AgentBuilder builder, String className,
                                                                             String methodName, Class<?> interceptor) {
        return builder
                .type(ElementMatchers.named(className))
                .transform((builderObj, typeDescription, classLoader, javaModule, foobar) ->
                        builderObj.method(ElementMatchers.named(methodName))
                                .intercept(Advice.to(interceptor))
                );
    }
}
