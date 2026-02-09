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

- Re-enable patchy (server blocking)
  ```
  -DLoki.enable_patchy=true
  ```

- Re-enable snooper
  ```
  -DLoki.enable_snooper=true
  ```

- Require valid chat signatures on 1.19+ servers where `enforce-secure-profile=true` is set in `server.properties` [^1]
  ```
  -DLoki.enforce_secure_profile=true
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

By default, Loki will use Mojang's API servers if none are provided. If you would like, you can change Loki's default API servers by editing `gradle.properties` before compiling:

```
authlibInjectorAPIServer=drasl.unmojang.org
authHost=https://authserver.mojang.com
accountHost=https://api.mojang.com
sessionHost=https://sessionserver.mojang.com
servicesHost=https://api.minecraftservices.com
```

`authlibInjectorAPIServer` is preferred when it is set, keep it empty if your API server does not support the authlib-injector API.

If you don't want to recompile Loki, you can instead edit the Loki jar's `META-INF/MANIFEST.MF`:

```
AuthlibInjectorAPIServer: 
AuthHost: https://drasl.unmojang.org/auth
AccountHost: https://drasl.unmojang.org/account
SessionHost: https://drasl.unmojang.org/session
ServicesHost: https://drasl.unmojang.org/services
```

[^1]: This option is **NOT** necessary to ensure the integrity of chat reports made to the API server from clients, and will kick [fallback API server](https://github.com/unmojang/drasl/blob/master/doc/configuration.md) players.
