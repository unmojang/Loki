package com.unmojang.loki.transformers;

import nilloader.api.lib.mini.MiniTransformer;
import nilloader.api.lib.mini.PatchContext;
import nilloader.api.lib.mini.annotation.Patch;

import java.net.MalformedURLException;
import java.net.URL;

@Patch.Class("com.mojang.authlib.HttpAuthenticationService")
public class ConcatenateURLTransformer extends MiniTransformer {
    @Patch.Method("concatenateURL(Ljava/net/URL;Ljava/lang/String;)Ljava/net/URL;")
    @Patch.Method.AffectsControlFlow
    @Patch.Method.Optional
    public void patchConcatenateURL(PatchContext ctx) {
        ctx.jumpToStart();
        ctx.add(ALOAD( 0)); // Load first argument (URL)
        ctx.add(ALOAD(1)); // Load second argument (query)
        ctx.add(INVOKESTATIC(
                "com/unmojang/loki/transformers/ConcatenateURLTransformer$Hooks",
                "concatenateURL",
                "(Ljava/net/URL;Ljava/lang/String;)Ljava/net/URL;"
        ));

        ctx.add(ARETURN());
    }

    public static class Hooks {
        // thanks yushijinhun!
        // https://github.com/yushijinhun/authlib-injector/blob/develop/src/main/java/moe/yushi/authlibinjector/transform/support/ConcatenateURLTransformUnit.java
        public static URL concatenateURL(URL url, String query) {
            try {
                if (url.getQuery() != null && !url.getQuery().isEmpty()) {
                    return new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile() + "&" + query);
                } else {
                    return new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile() + "?" + query);
                }
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("Could not concatenate given URL with GET arguments!", e);
            }
        }
    }
}
