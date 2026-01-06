# Loki

Patch (nearly) any Minecraft version to use custom API servers

![Minecraft Classic 0.0.18a_02 with Loki](img/1.png)
![Minecraft 1.21.10 with Loki](img/2.png)

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

- Use Authlib-Injector URL instead of `minecraft.api.*.host` parameters
  ```
  -DLoki.url=https://drasl.unmojang.org
  ```

- Enable debug mode (increased verbosity)
  ```
  -DLoki.debug=true
  ```

- Enable trace mode (maximum verbosity)
  ```
  -DLoki.trace=true
  ```

- Disable the URL factory
  ```
  -DLoki.disable_factory=true
  ```

- Re-enable snooper
  ```
  -DLoki.enable_snooper=true
  ```

- Disable realms APIs
  ```
  -DLoki.disable_realms=true
  ```

- Re-enable modded capes with username-based lookups (OptiFine, Cloaks+, etc.)
  ```
  -DLoki.modded_capes=true
  ```

- Re-enable the username validation added in 1.18.2 that kicks usernames containing invalid characters
  ```
  -DLoki.username_validation=true
  ```
