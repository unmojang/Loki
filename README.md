# Loki

Patch (nearly) any Minecraft version to use custom API servers

![Minecraft Alpha 1.0.16 with Loki](img/a1.0.16.png)
![Minecraft 1.21.10 with Loki](img/1.21.10.png)

## Supported versions

Skins/capes: c0.0.18a and above

Authentication: a1.0.16 and above

Skin support was added to the game in c0.0.18a. Although multiplayer was reintroduced in a1.0.15, a1.0.16 implemented
online mode functionality. This means that Loki effectively supports every Minecraft version.

## Wait, what about classic servers?

Classic servers use the [Classic Protocol](https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Classic_Protocol), and while this may be supported in the future, as of now there is
little incentive to do so due to the availability of significantly better clones like [ClassiCube](https://www.classicube.net/).

## Are total conversion mods or obscure mod loaders supported?

They should be, but if not, please file an issue.

## Java arguments

Loki supports Java arguments to enable or disable some behaviour.

- Re-enable snooper
  ```
  -DLoki.enable_snooper=true
  ```

## Building

Loki should be built with Java 17, though it supports Java 8 at runtime.

## Troubleshooting

### I can't join the server/send chat messages due to profile public key-related errors

If you get "Chat disabled due to missing profile public key" when you try to send chat messages, you'll need to set
`enforce-secure-profile=false` in `server.properties`. This problem affects 1.19.3-1.21.8.

If you are kicked for "Invalid signature for profile public key", follow the above step. Then, in addition to that,
install the [No Chat Reports](https://modrinth.com/mod/no-chat-reports) mod on either the server or the client. This problem affects 1.19+, though it
typically only occurs when joining from a fallback API server account (e.g. Mojang account fallback).

### It doesn't seem to work at all on Babric/BTW/something else using Java 16+

First, try using Java 8 or 11 instead. If that doesn't work, unfortunately you will need to set these JVM arguments on
both the client and the server:

```
--add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/sun.net.www.protocol.http=ALL-UNNAMED --add-opens java.base/sun.net.www.protocol.https=ALL-UNNAMED
```

See [#3](https://github.com/unmojang/loki/issues/3) for more details.
