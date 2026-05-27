-dontobfuscate
-dontwarn **
-dontnote **

-keep class org.unmojang.loki.Loki {
    public static void premain(java.lang.String, java.lang.instrument.Instrumentation);
}

# Loaded dynamically
-keep class org.unmojang.loki.hooks.** { *; }
-keep class org.unmojang.loki.util.** { *; }
