package org.unmojang.loki.transformers;

import nilloader.api.lib.mini.MiniTransformer;
import nilloader.api.lib.mini.PatchContext;
import nilloader.api.lib.mini.annotation.Patch;
import org.unmojang.loki.Premain;

@Patch.Class("com.github.steveice10.mc.auth.data.GameProfile")
public class MCAuthlibAllowedDomainTransformer extends MiniTransformer {
    @Patch.Method("isWhitelistedDomain(Ljava/lang/String;)Z")
    @Patch.Method.AffectsControlFlow
    @Patch.Method.Optional
    public void patchWhitelistedDomain(PatchContext ctx) {
        Premain.log.info("Patching isWhitelistedDomain in com.github.steveice10.mc.auth.data.GameProfile");
        ctx.jumpToStart();   // HEAD
        ctx.add(ICONST_1()); // push 1 (true)
        ctx.add(IRETURN());  // return it
    }
}
