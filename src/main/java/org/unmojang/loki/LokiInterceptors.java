package org.unmojang.loki;

import net.bytebuddy.asm.Advice;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.unmojang.loki.Loki.*;

public class LokiInterceptors {
    // generic false -> true interceptor, for lots of things
    public static class ReturnTrueInterceptor {
        @Advice.OnMethodEnter
        static void onEnter(@Advice.Origin("#m") String method) {
            System.out.println("[Loki] Intercepted " + method + " to return true");
        }

        @Advice.OnMethodExit
        static void onExit(@Advice.Return(readOnly = false) boolean returnValue) {
            returnValue = true;
        }
    }

    public static class TextureWhitelistInterceptor {
        @Advice.OnMethodEnter
        static void onEnter(@Advice.Origin("#m") String method) {
            System.out.println("[Loki] Intercepted " + method + " to use correct texture whitelist");
        }

        @Advice.OnMethodExit
        static void onExit(@Advice.Argument(0) String url, @Advice.Return(readOnly = false) boolean returnValue) {
            String[] skinDomains = System.getProperty("loki.internal.skinDomains").split(",");
            for (String domain : skinDomains) {
                if (url.startsWith("http://" + domain) || url.startsWith("https://" + domain)) {
                    returnValue = true;
                }
            }
        }
    }

    public static class PublicKeyInterceptor {
        @Advice.OnMethodExit
        static void onExit(@Advice.This Object instance) {
            try {
                String pem = System.getProperty("loki.internal.publicKey");
                byte[] encoded = Base64.getDecoder().decode(pem);
                X509EncodedKeySpec spec = new X509EncodedKeySpec(encoded);
                PublicKey customKey = KeyFactory.getInstance("RSA").generatePublic(spec);

                Field pubKeyField = instance.getClass().getDeclaredField("publicKey");
                pubKeyField.setAccessible(true);
                pubKeyField.set(instance, customKey);
                System.out.println("[Loki] Replaced yggdrasil public key.");
            } catch (Exception e) {
                // probably just due to newer version, can be ignored
            }
        }
    }

    public static class StaticFinalStringInterceptor {
        @Advice.OnMethodExit
        public static void onExit() {
            try {
                Class<?> clazz = Class.forName("com.mojang.authlib.yggdrasil.YggdrasilUserAuthentication");
                overwriteStringConstant(clazz, "BASE_URL", authHost + "/");

                Class<?> clazb = Class.forName("com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService");
                overwriteStringConstant(clazb, "BASE_URL", sessionHost + "/session/minecraft/");

                Class<?> clazc = Class.forName("com.mojang.authlib.yggdrasil.YggdrasilGameProfileRepository");
                overwriteStringConstant(clazc, "BASE_URL", accountHost + "/");
                overwriteStringConstant(clazc, "SEARCH_PAGE_URL", accountHost + "/profiles/");

                System.out.println("[Loki] Intercepted URL constants");
            } catch (ClassNotFoundException e) {
                // this happens on early 1.7 because there's no YggdrasilGameProfileRepository.
            } catch (NoSuchFieldException e) {
                // newer versions will probably trigger this
            } catch (Exception e) {
                e.printStackTrace();
            }
        }


        public static void overwriteStringConstant(Class<?> clazz, String fieldName, String newValue) throws Exception {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
            field.set(null, newValue);
        }
    }

    public static class ConstantURLInterceptor {
        @Advice.OnMethodExit
        public static void onExit(@Advice.Argument(0) String urlString,
                                  @Advice.Return(readOnly = false) URL returned) {
            try {
                String replaced = urlString
                        .replace("https://api.mojang.com", System.getProperty("minecraft.api.account.host"))
                        .replace("https://authserver.mojang.com", System.getProperty("minecraft.api.auth.host"))
                        .replace("https://sessionserver.mojang.com", System.getProperty("minecraft.api.session.host"))
                        .replace("https://api.minecraftservices.com", System.getProperty("minecraft.api.services.host"));

                if (!replaced.equals(urlString)) {
                    System.out.println("[Loki] URL intercepted: " + urlString);
                }

                returned = new URL(replaced);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // thanks yushijinhun!
    // https://github.com/yushijinhun/authlib-injector/blob/develop/src/main/java/moe/yushi/authlibinjector/transform/support/ConcatenateURLTransformUnit.java
    public static class ConcatenateURLInterceptor {
        @Advice.OnMethodExit
        public static void onExit(@Advice.Argument(0) URL url, @Advice.Argument(1) String query, @Advice.Return(readOnly = false) URL returnedURL) {
            try {
                if (url.getQuery() != null && !url.getQuery().isEmpty()) {
                    returnedURL = new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile() + "&" + query);
                } else {
                    returnedURL = new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile() + "?" + query);
                }
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("Could not concatenate given URL with GET arguments!", e);
            }
        }
    }
}
