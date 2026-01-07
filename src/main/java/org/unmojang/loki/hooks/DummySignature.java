package org.unmojang.loki.hooks;

import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

public class DummySignature {
    // thanks yushijinhun!
    // https://github.com/yushijinhun/authlib-injector/blob/6425a2745264593da7e35896d12c6ea23638d679/src/main/java/moe/yushi/authlibinjector/transform/support/YggdrasilKeyTransformUnit.java#L116-L166
    @SuppressWarnings("unused")
    public static Signature createDummySignature() {
        Signature sig = new Signature("dummy") {
            @Override
            protected boolean engineVerify(byte[] sigBytes) { return true; }
            @Override
            protected void engineUpdate(byte[] b, int off, int len) {}
            @Override
            protected void engineUpdate(byte b) {}
            @Override
            protected byte[] engineSign() { throw new UnsupportedOperationException(); }
            @Override @Deprecated
            protected void engineSetParameter(String param, Object value) {}
            @Override
            protected void engineInitVerify(PublicKey publicKey) {}
            @Override
            protected void engineInitSign(PrivateKey privateKey) { throw new UnsupportedOperationException(); }
            @Override @Deprecated
            protected Object engineGetParameter(String param) { return null; }
        };
        try { sig.initVerify((PublicKey)null); } catch (InvalidKeyException e) { throw new RuntimeException(e); }
        return sig;
    }
}
