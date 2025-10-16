package com.unmojang.loki.transformers;

import nilloader.api.lib.mini.MiniTransformer;
import nilloader.api.lib.mini.PatchContext;
import nilloader.api.lib.mini.annotation.Patch;

@Patch.Class("net.minecraft.client.Minecraft")
public class LegacyFactoryTransformer extends MiniTransformer {
    @Patch.Method("<clinit>()V")
    public void patchClinit(PatchContext ctx) {
        ctx.jumpToStart();
        ctx.add(INVOKESTATIC("com/unmojang/loki/RequestInterceptor",
                "URLFactory", "()V"));
    }
}
