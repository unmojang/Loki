package com.unmojang.loki.transformers;

import nilloader.api.lib.mini.MiniTransformer;
import nilloader.api.lib.mini.PatchContext;
import nilloader.api.lib.mini.annotation.Patch;

@Patch.Class("com.mojang.authlib.properties.Property")
public class SignatureValidTransformer extends MiniTransformer {
    @Patch.Method("isSignatureValid(Ljava/security/PublicKey;)Z")
    public void patchSignatureValid(PatchContext ctx) {
        ctx.jumpToStart();   // HEAD
        ctx.add(ICONST_1()); // push 1 (true)
        ctx.add(IRETURN());  // return it
    }
}