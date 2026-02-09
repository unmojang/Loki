# Frequently Asked Questions

## What versions are supported?

Loki supports every Minecraft version.

## Wait, what about classic servers?

Minecraft Classic servers use the [Classic Protocol](https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Classic_Protocol), which is entirely incompatible with a Yggdrasil-based API server. Consider playing [ClassiCube](https://www.classicube.net/) or [Classic+](https://legacy-plus.dejvoss.cz/) instead.

## Are total conversion mods or obscure mod loaders supported?

They should be, but if not, please file an issue.

## Does chat reporting/secure-profile work?

It does, as long as you're on the same API server and your API server supports chat reporting. While using Loki, the player signature is not validated, even with `enforce-secure-profile=true` in `server.properties` - it only requires that a signature is provided. In this state, [No Chat Reports](https://modrinth.com/mod/no-chat-reports) will not work, but signatures could potentially be forged unless you additionally set `-DLoki.enforce_secure_profile=true`. Doing this will, however, kick [fallback API server](https://github.com/unmojang/drasl/blob/master/doc/configuration.md) players, and is discouraged (see "Chat validation error" in [troubleshooting.md](troubleshooting.md)). You can even do chat reports across API servers, the API server will of course reject the attempt to make the report though.

![Attempted cross-API server chat report](/img/chatreport.png)

## I'd like to use this on Windows 95, does it support Java 5?

Loki supports Java 5 and above. However, Java 5 lacks some functionality and may have issues in some modded environments. If you are using Java 6 or later, there should be no issues.
