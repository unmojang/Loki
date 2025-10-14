package com.unmojang.loki.transformers;

import nilloader.api.lib.mini.MiniTransformer;
import nilloader.api.lib.mini.PatchContext;
import nilloader.api.lib.mini.annotation.Patch;

@Patch.Class("com.mojang.authlib.yggdrasil.TextureUrlChecker")
public class NewAllowedDomainTransformer extends MiniTransformer {
    @Patch.Method("isAllowedTextureDomain(Ljava/lang/String;)Z")
    public void patchIsAllowedTextureDomain(PatchContext ctx) {
        ctx.jumpToStart();   // HEAD
        ctx.add(ICONST_1()); // push 1 (true)
        ctx.add(IRETURN());  // return it
    }
}
