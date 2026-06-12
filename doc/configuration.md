# Configuration

## JVM Arguments

Loki supports JVM arguments to enable or disable some behaviour.

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

- Re-enable chat restrictions
  ```
  -DLoki.chat_restrictions=true
  ```

- Disable the URL factory
  ```
  -DLoki.disable_factory=true
  ```

- Disable username-based profile lookups [^1]
  ```
  -DLoki.disable_profile_lookup=true
  ```

- Re-enable patchy (server blocking)
  ```
  -DLoki.enable_patchy=true
  ```

- Re-enable snooper
  ```
  -DLoki.enable_snooper=true
  ```

- Require valid chat signatures on 1.19+ servers where `enforce-secure-profile=true` is set in `server.properties` [^2]
  ```
  -DLoki.enforce_secure_profile=true
  ```

- Force the applet launcher to re-download the game, for pre-Beta 1.3 applet launchers that lack a "Force Update" option
  ```
  -DLoki.launcher_trigger_update=true
  ```

- Choose which Minecraft version the applet launcher runs, required since applet launchers have no version picker
  ```
  -DLoki.launcher_version=1.5.2
  ```

- Re-enable modded capes with username-based lookups (OptiFine, Cloaks+, etc.)
  ```
  -DLoki.modded_capes=true
  ```

- Re-enable the username validation added in 1.18.2 that kicks usernames containing invalid characters
  ```
  -DLoki.username_validation=true
  ```

## Changing the Default API Servers

By default, Loki will use Mojang's API servers if none are provided. If you would like, you can change Loki's default API servers by editing `loki.properties` before compiling:

```
authlibInjectorAPIServer=drasl.unmojang.org
authHost=https://authserver.mojang.com
accountHost=https://api.mojang.com
sessionHost=https://sessionserver.mojang.com
servicesHost=https://api.minecraftservices.com
signalingHost=https://signaling-afd.franchise.minecraft-services.net
```

You can also override these properties within the build command:
```
ant -DauthlibInjectorAPIServer=https://drasl.unmojang.org/authlib-injector
```

`authlibInjectorAPIServer` is preferred when it is set, keep it empty if your API server does not support the authlib-injector API.

If you don't want to recompile Loki, you can instead edit the Loki jar's `META-INF/MANIFEST.MF`:

```
AuthlibInjectorAPIServer: 
AuthHost: https://drasl.unmojang.org/auth
AccountHost: https://drasl.unmojang.org/account
SessionHost: https://drasl.unmojang.org/session
ServicesHost: https://drasl.unmojang.org/services
SignalingHost: https://signaling-afd.franchise.minecraft-services.net
```

[^1]: Username-based profile lookups allow for displaying textures on offline mode servers.
[^2]: This option is **NOT** necessary to ensure the integrity of chat reports made to the API server from clients, and will kick [fallback API server](https://github.com/unmojang/drasl/blob/master/doc/configuration.md) players.
