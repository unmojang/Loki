package org.unmojang.loki.util.logger;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

public class AdHocLogImpl implements NilLogImpl {
    private static final boolean DEBUG = Boolean.getBoolean("Loki.debug");
    private static final boolean TRACE = Boolean.getBoolean("Loki.trace");
    // SimpleDateFormat isn't thread-safe and Loki logs from many threads
    private static final ThreadLocal<DateFormat> fmt = new ThreadLocal<DateFormat>() {
        protected DateFormat initialValue() {
            return new SimpleDateFormat("HH:mm:ss");
        }
    };

    private final PrintStream out = System.out;
    private final String name;

    public AdHocLogImpl(String name) {
        this.name = name;
    }

    public NilLogImpl fork(String name) {
        return new AdHocLogImpl(name);
    }

    private void log(String tag, String message, Throwable t) {
        // write to output stream once to prevent interleaving
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%s] [%s/%s]: %s%n", fmt.get().format(new Date()), name, tag, message));
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append(sw);
        }
        out.print(sb);
        out.flush();
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
