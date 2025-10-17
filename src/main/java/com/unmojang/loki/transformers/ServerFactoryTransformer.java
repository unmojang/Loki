package com.unmojang.loki.transformers;

import nilloader.api.lib.mini.MiniTransformer;
import nilloader.api.lib.mini.PatchContext;
import nilloader.api.lib.mini.annotation.Patch;

@Patch.Class("net.minecraft.server.MinecraftServer")
public class ServerFactoryTransformer extends MiniTransformer {
    @Patch.Method("run()V")
    @Patch.Method.AffectsControlFlow
    @Patch.Method.Optional
    public void patchRun(PatchContext ctx) {
        ctx.jumpToStart();
        ctx.add(INVOKESTATIC("com/unmojang/loki/RequestInterceptor",
                "URLFactory", "()V"));
    }
}
