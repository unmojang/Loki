package org.unmojang.loki.util.logger;

import java.io.PrintStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

public class AdHocLogImpl implements NilLogImpl {
    private static final boolean DEBUG = Boolean.getBoolean("Loki.debug");
    private static final boolean TRACE = Boolean.getBoolean("Loki.trace");
    private static final DateFormat fmt = new SimpleDateFormat("HH:mm:ss");

    private final PrintStream out = System.out;
    private final String name;

    public AdHocLogImpl(String name) {
        this.name = name;
    }

    public NilLogImpl fork(String name) {
        return new AdHocLogImpl(name);
    }

    private void log(String tag, String message, Throwable t) {
        if (t != null) {
            t.printStackTrace(out);
        }
        out.printf("[%s] [%s/%s]: %s%n", fmt.format(new Date()), name, tag, message);
    }

    public boolean isTraceEnabled() {
        return TRACE;
    }

    public boolean isDebugEnabled() {
        return DEBUG || TRACE;
    }

    public boolean isInfoEnabled() {
        return true;
    }

    public boolean isWarnEnabled() {
        return true;
    }

    public boolean isErrorEnabled() {
        return true;
    }

    public void trace(String message, Throwable t) {
        log("TRACE", message, t);
    }

    public void debug(String message, Throwable t) {
        log("DEBUG", message, t);
    }

    public void info(String message, Throwable t) {
        log("INFO", message, t);
    }

    public void warn(String message, Throwable t) {
        log("WARN", message, t);
    }

    public void error(String message, Throwable t) {
        log("ERROR", message, t);
    }
}
