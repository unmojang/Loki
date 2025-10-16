package com.unmojang.loki.transformers;

import nilloader.api.lib.mini.MiniTransformer;
import nilloader.api.lib.mini.PatchContext;
import nilloader.api.lib.mini.annotation.Patch;

@Patch.Class("net.minecraft.client.MinecraftApplet")
public class ReallyLegacyFactoryTransformer extends MiniTransformer {
    @Patch.Method("init()V")
    public void patchInit(PatchContext ctx) {
        ctx.jumpToStart();
        ctx.add(INVOKESTATIC("com/unmojang/loki/Factories",
                "URLFactory", "()V"));
    }
}
