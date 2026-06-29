package com.p2ppvp.mod;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

public class DebugLogger {
    private static final String LOG_PATH = "mods/p2ppvp_debug.log";

    public static synchronized void resetLog() {
        try {
            File logFile = new File(LOG_PATH);
            if (logFile.exists()) {
                logFile.delete();
            }
            logFile.createNewFile();
        } catch (Exception ignored) {}
    }

    public static synchronized void log(String message) {
        log(message, null);
    }

    public static synchronized void log(String message, Throwable t) {
        try {
            File modsDir = new File("mods");
            if (!modsDir.exists()) {
                modsDir.mkdirs();
            }
            File logFile = new File(LOG_PATH);
            try (PrintWriter out = new PrintWriter(new FileWriter(logFile, true))) {
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
                out.println("[" + timestamp + "] " + message);
                if (t != null) {
                    t.printStackTrace(out);
                }
                out.flush();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
