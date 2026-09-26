package me.emirtemur.ringout.util;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logs repeated save failures without flooding the console. Builds retry saves every game, so
 * per key a failure is logged at its level only if nothing as severe was logged yet (a new
 * SEVERE after a WARNING still shows); repeats go to FINE until the key is cleared.
 */
public final class FailureLog {

    private final Logger log;
    private final Map<String, Level> logged = new HashMap<>();

    public FailureLog(Logger log) {
        this.log = log;
    }

    public void fail(String key, Level level, String message, Throwable error) {
        Level previous = logged.get(key);
        boolean escalated = previous == null || level.intValue() > previous.intValue();
        log.log(escalated ? level : Level.FINE, message, error);
        if (escalated) {
            logged.put(key, level);
        }
    }

    /** A save for this key worked; the next failure is loud again. */
    public void clear(String key) {
        logged.remove(key);
    }

    public void clearAll() {
        logged.clear();
    }
}
