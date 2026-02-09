# Troubleshooting

## Fallback API server players see "Chat validation error"

Whenever anyone from a different API server talks in chat, fallback API server players may see "Chat validation error" and be unable to send messages afterward until they relog. This is due to the vanilla 1.19+ game client performing signature checks on messages prior to accepting them. The solution is to either use Loki or authlib-injector on the client - which will most likely already be in use unless the fallback API server is Mojang - or to disable `enforce-secure-profile` in `server.properties` on the server.


## My game immediately crashed!

If you're getting a crash like this:

```
javax.net.ssl.SSLException: Received fatal alert: protocol_version
```

or this:

```
[LF] ERROR: mouse
        java.lang.ClassCircularityError
        javax/crypto/BadPaddingException
        sun.security.rsa.RSASignature.engineVerify(RSASignature.java:216)
        java.security.Signature$Delegate.engineVerify(Signature.java:1393)
        java.security.Signature.verify(Signature.java:770)
```

then you need to upgrade your Java installation. If the crash is something else, please file an issue.
