package io.blockcompanion.core.sync;

/** Where the sync code logs; each platform passes its own logger (SLF4J on the mods, the plugin logger on Paper). */
public interface SyncLog {
    void info(String message);

    void warn(String message);

    SyncLog NONE = new SyncLog() {
        public void info(String message) {
        }

        public void warn(String message) {
        }
    };
}
