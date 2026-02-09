# Loki

Patch (nearly) any Minecraft version to use custom API servers

![Minecraft Classic 0.0.18a_02 with Loki](img/1.png)
![Minecraft 1.21.10 with Loki](img/2.png)

## Usage

See [doc/usage.md](doc/usage.md) to learn how to use Loki on clients and servers.

## Configuration

See [doc/configuration.md](doc/configuration.md) for documentation of the configuration options.

## Troubleshooting

[doc/troubleshooting.md](doc/troubleshooting.md) has some helpful tips, but it's not complete. Feel free to file an issue if you're having problems.

## Building

Loki needs to be built with Java 8. Gradle should find and build with a Java 8 JDK automatically for you, so there is no need to adjust your `JAVA_HOME`, but you must have a Java 8 JDK installed.

Run `./gradlew build` to build. Your compiled jar will be placed in `build/libs/Loki-X.Y.Z.jar`.

## FAQ
See [doc/faq.md](doc/faq.md)
