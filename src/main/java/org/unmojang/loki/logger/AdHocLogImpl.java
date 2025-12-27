package org.unmojang.loki.logger;

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

    @Override
    public NilLogImpl fork(String name) {
        return new AdHocLogImpl(name);
    }

    private void log(String tag, String message, Throwable t) {
        if (t != null) {
            t.printStackTrace(out);
        }
        out.printf("[%s] [%s/%s]: %s%n", fmt.format(new Date()), name, tag, message);
    }

    @Override
    public boolean isTraceEnabled() {
        return TRACE;
    }

    @Override
    public boolean isDebugEnabled() {
        return DEBUG || TRACE;
    }

    @Override
    public boolean isInfoEnabled() {
        return true;
    }

    @Override
    public boolean isWarnEnabled() {
        return true;
    }

    @Override
    public boolean isErrorEnabled() {
        return true;
    }

    @Override
    public void trace(String message, Throwable t) {
        log("TRACE", message, t);
    }

    @Override
    public void debug(String message, Throwable t) {
        log("DEBUG", message, t);
    }

    @Override
    public void info(String message, Throwable t) {
        log("INFO", message, t);
    }

    @Override
    public void warn(String message, Throwable t) {
        log("WARN", message, t);
    }

    @Override
    public void error(String message, Throwable t) {
        log("ERROR", message, t);
    }
}
