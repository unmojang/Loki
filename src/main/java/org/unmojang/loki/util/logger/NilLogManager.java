package org.unmojang.loki.util.logger;

public class NilLogManager {
    private static final NilLogImpl IMPL;

    static {
        /*
         * Attempting to load the application logger config can (through very long dependency chain
         * accidents involving custom appenders and formatters) load application classes
         * prematurely, preventing them from being correctly patched.
         *
         * So, give up. Always use the "adhoc" logger impl.
         */
        IMPL = new AdHocLogImpl("NilLoader");
    }

    public static NilLogger getLogger(String name) {
        return new NilLogger(IMPL.fork(name));
    }
}
