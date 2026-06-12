# Usage

To add Loki to your client/server, add this JVM argument: [^1]

```
-javaagent:/path/to/Loki.jar=drasl.unmojang.org
```

Where `drasl.unmojang.org` is your authlib-injector compatible API server. `https://` is assumed when the scheme is not provided, but you can set the scheme to `http://` if you would like to do so.

You can also avoid adding agent parameters:

```
-javaagent:/path/to/Loki.jar
```

In this case, the default servers will be used, and you can override this with the Loki.url JVM argument:

```
-DLoki.url=drasl.unmojang.org
```

If your API server is not authlib-injector compatible, Loki supports `minecraft.api.*.host` parameters:

```
-Dminecraft.api.env=custom
-Dminecraft.api.auth.host=https://drasl.unmojang.org/auth
-Dminecraft.api.account.host=https://drasl.unmojang.org/account
-Dminecraft.api.session.host=https://drasl.unmojang.org/session
-Dminecraft.api.services.host=https://drasl.unmojang.org/services
-Dminecraft.api.signaling.host=https://drasl.unmojang.org/signaling
```

Although not recommended, Loki can also pick up the API server provided from authlib-injector if you're using both, and kill authlib-injector to prevent breakage.

## Mojang's Java Launchers

Mojang's Java launchers are also supported, provided Loki is injected into the launcher.

Start the launcher with Loki:

```
java -javaagent:/path/to/Loki.jar=https://drasl.unmojang.org -jar launcher.jar
```

Loki is automatically propagated to the game process the launcher spawns.

The applet launcher has no version picker, so you must tell Loki which Minecraft version to run with `-DLoki.launcher_version`:

```
java -javaagent:/path/to/Loki.jar=https://drasl.unmojang.org -DLoki.launcher_version=1.5.2 -jar launcher.jar
```

See [configuration.md](configuration.md) for more information on configuring the applet launcher.

[^1]: Windows users should prefix the path with `C:`, assuming you are on drive C. You should still use forward slashes in the path.
