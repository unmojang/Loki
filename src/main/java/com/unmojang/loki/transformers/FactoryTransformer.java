package com.unmojang.loki.transformers;

import nilloader.api.lib.mini.MiniTransformer;
import nilloader.api.lib.mini.PatchContext;
import nilloader.api.lib.mini.annotation.Patch;

@Patch.Class("net.minecraft.client.main.Main")
public class FactoryTransformer extends MiniTransformer {
    @Patch.Method("main([Ljava/lang/String;)V")
    public void patchMain(PatchContext ctx) {
        ctx.jumpToStart();
        ctx.add(INVOKESTATIC("com/unmojang/loki/RequestInterceptor",
                "URLFactory", "()V"));
    }
}
