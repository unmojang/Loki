package org.unmojang.loki;

import java.io.File;

/**
 * Standalone helper spawned on JVM exit on Windows, where the JVM's lock on the agent
 * jar prevents replacing it in place.
 */
public class UpdateApplier {
    public static void main(String[] args) {
        if (args.length != 2) return;
        File src = new File(args[0]);
        File dst = new File(args[1]);
        File backup = new File(args[1] + ".old");
        for (int i = 0; i < 30; i++) { // the last Loki-carrying process to exit wins
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            if (!src.isFile()) return; // another applier finished the job
            if (backup.exists() && !backup.delete()) continue;
            if (!dst.exists()) { // finish a crashed applier's half-done swap
                if (src.renameTo(dst)) return;
            } else if (dst.renameTo(backup)) { // fails while a process still holds the jar
                if (src.renameTo(dst)) {
                    deleteQuietly(backup);
                    return;
                }
                // roll back; a failure here is healed by the missing-jar branch above
                //noinspection ResultOfMethodCallIgnored
                backup.renameTo(dst);
            }
        }
    }

    private static void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }
}
