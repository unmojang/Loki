package org.unmojang.loki.transformers;

import nilloader.api.lib.mini.MiniTransformer;
import nilloader.api.lib.mini.PatchContext;
import nilloader.api.lib.mini.annotation.Patch;
import org.unmojang.loki.Premain;

@Patch.Class("com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService")
public class AllowedDomainTransformer extends MiniTransformer {
    @Patch.Method("isWhitelistedDomain(Ljava/lang/String;)Z")
    @Patch.Method.AffectsControlFlow
    @Patch.Method.Optional
    public void patchWhitelistedDomain(PatchContext ctx) {
        Premain.log.info("Patching isWhitelistedDomain in com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService");
        ctx.jumpToStart();   // HEAD
        ctx.add(ICONST_1()); // push 1 (true)
        ctx.add(IRETURN());  // return it
    }

    @Patch.Method("isAllowedTextureDomain(Ljava/lang/String;)Z")
    @Patch.Method.AffectsControlFlow
    @Patch.Method.Optional
    public void patchIsAllowedTextureDomain(PatchContext ctx) {
        Premain.log.info("Patching isAllowedTextureDomain in com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService");
        ctx.jumpToStart();   // HEAD
        ctx.add(ICONST_1()); // push 1 (true)
        ctx.add(IRETURN());  // return it
    }
}