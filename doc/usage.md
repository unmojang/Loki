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
```

If you are using Fjord Launcher, You can use the "Add Agents" feature in the Version tab of your instance. This is equivalent to doing `-javaagent:/path/to/Loki.jar`. Setting Loki.url is not necessary since Fjord Launcher will set `minecraft.api.*.host` parameters for you when authlib-injector is not present.

Although not recommended, Loki can also pick up the API server provided from authlib-injector if you're using both, and kill authlib-injector to prevent breakage.

[^1]: Windows users should prefix the path with `C:`, assuming you are on drive C. You should still use forward slashes in the path. 
