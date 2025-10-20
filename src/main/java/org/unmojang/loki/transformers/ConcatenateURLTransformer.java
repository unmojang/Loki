package org.unmojang.loki.transformers;

import nilloader.api.lib.asm.tree.LabelNode;
import nilloader.api.lib.mini.MiniTransformer;
import nilloader.api.lib.mini.PatchContext;
import nilloader.api.lib.mini.annotation.Patch;
import org.unmojang.loki.Premain;

@Patch.Class("com.mojang.authlib.HttpAuthenticationService")
public class ConcatenateURLTransformer extends MiniTransformer {
    // thanks yushijinhun!
    // https://github.com/yushijinhun/authlib-injector/blob/develop/src/main/java/moe/yushi/authlibinjector/transform/support/ConcatenateURLTransformUnit.java
    // https://github.com/yushijinhun/authlib-injector/issues/126
    /*  try {
            if (url.getQuery() != null && !url.getQuery().isEmpty()) {
                return new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile() + "&" + query);
            } else {
                return new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile() + "?" + query);
            }
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Could not concatenate given URL with GET arguments!", e);
        }
    */
    @Patch.Method("concatenateURL(Ljava/net/URL;Ljava/lang/String;)Ljava/net/URL;")
    @Patch.Method.AffectsControlFlow
    @Patch.Method.Optional
    public void patchConcatenateURL(PatchContext ctx) {
        Premain.log.info("Applying ConcatenateURL fixes");
        ctx.jumpToStart();

        LabelNode EX_START_1 = new LabelNode();
        LabelNode EX_END_1 = new LabelNode();
        LabelNode EX_START_2 = new LabelNode();
        LabelNode EX_END_2 = new LabelNode();
        LabelNode EX_HANDLER_2 = new LabelNode();

        // start TRY region 1
        ctx.add(EX_START_1);

        // AL0: if (url.getQuery() == null) goto EX_START_2
        ctx.add(ALOAD(0));
        ctx.add(INVOKEVIRTUAL("java/net/URL", "getQuery", "()Ljava/lang/String;"));
        ctx.add(IFNULL(EX_START_2));

        // if (url.getQuery().isEmpty()) goto EX_START_2
        ctx.add(ALOAD(0));
        ctx.add(INVOKEVIRTUAL("java/net/URL", "getQuery", "()Ljava/lang/String;"));
        ctx.add(INVOKEVIRTUAL("java/lang/String", "isEmpty", "()Z"));
        ctx.add(IFNE(EX_START_2));

        // B: construct new URL(..., file + "&" + query)
        ctx.add(NEW("java/net/URL"));
        ctx.add(DUP());

        ctx.add(ALOAD(0));
        ctx.add(INVOKEVIRTUAL("java/net/URL", "getProtocol", "()Ljava/lang/String;"));

        ctx.add(ALOAD(0));
        ctx.add(INVOKEVIRTUAL("java/net/URL", "getHost", "()Ljava/lang/String;"));

        ctx.add(ALOAD(0));
        ctx.add(INVOKEVIRTUAL("java/net/URL", "getPort", "()I"));

        // build file + "&" + query
        ctx.add(NEW("java/lang/StringBuilder"));
        ctx.add(DUP());
        ctx.add(INVOKESPECIAL("java/lang/StringBuilder", "<init>", "()V"));

        ctx.add(ALOAD(0));
        ctx.add(INVOKEVIRTUAL("java/net/URL", "getFile", "()Ljava/lang/String;"));
        ctx.add(INVOKEVIRTUAL("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;"));

        ctx.add(LDC("&"));
        ctx.add(INVOKEVIRTUAL("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;"));

        ctx.add(ALOAD(1)); // query param
        ctx.add(INVOKEVIRTUAL("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;"));

        ctx.add(INVOKEVIRTUAL("java/lang/StringBuilder", "toString", "()Ljava/lang/String;"));

        ctx.add(INVOKESPECIAL("java/net/URL", "<init>", "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V"));

        // end TRY region 1
        ctx.add(EX_END_1);
        ctx.add(ARETURN());

        // start TRY region 2
        ctx.add(EX_START_2);

        // construct new URL(..., file + "?" + query)
        ctx.add(NEW("java/net/URL"));
        ctx.add(DUP());

        ctx.add(ALOAD(0));
        ctx.add(INVOKEVIRTUAL("java/net/URL", "getProtocol", "()Ljava/lang/String;"));

        ctx.add(ALOAD(0));
        ctx.add(INVOKEVIRTUAL("java/net/URL", "getHost", "()Ljava/lang/String;"));

        ctx.add(ALOAD(0));
        ctx.add(INVOKEVIRTUAL("java/net/URL", "getPort", "()I"));

        // build file + "?" + query
        ctx.add(NEW("java/lang/StringBuilder"));
        ctx.add(DUP());
        ctx.add(INVOKESPECIAL("java/lang/StringBuilder", "<init>", "()V"));

        ctx.add(ALOAD(0));
        ctx.add(INVOKEVIRTUAL("java/net/URL", "getFile", "()Ljava/lang/String;"));
        ctx.add(INVOKEVIRTUAL("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;"));

        ctx.add(LDC("?"));
        ctx.add(INVOKEVIRTUAL("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;"));

        ctx.add(ALOAD(1)); // query param
        ctx.add(INVOKEVIRTUAL("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;"));

        ctx.add(INVOKEVIRTUAL("java/lang/StringBuilder", "toString", "()Ljava/lang/String;"));

        ctx.add(INVOKESPECIAL("java/net/URL", "<init>", "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V"));

        // end TRY region 2
        ctx.add(EX_END_2);
        ctx.add(ARETURN());

        // exception handler: store in local e and throw new IllegalArgumentException(msg, e)
        ctx.add(EX_HANDLER_2);
        ctx.add(ASTORE(2)); // store MalformedURLException in local 2 (e)
        ctx.add(NEW("java/lang/IllegalArgumentException"));
        ctx.add(DUP());
        ctx.add(LDC("Could not concatenate given URL with GET arguments!"));
        ctx.add(ALOAD(2));
        ctx.add(INVOKESPECIAL("java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;Ljava/lang/Throwable;)V"));
        ctx.add(ATHROW());

        // register both try blocks to the same handler for MalformedURLException
        ctx.addTryBlock(EX_START_1, EX_END_1, EX_HANDLER_2, "java/net/MalformedURLException");
        ctx.addTryBlock(EX_START_2, EX_END_2, EX_HANDLER_2, "java/net/MalformedURLException");
    }
}
