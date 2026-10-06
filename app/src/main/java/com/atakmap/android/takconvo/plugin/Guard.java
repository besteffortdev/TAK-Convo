package com.atakmap.android.takconvo.plugin;

import com.atakmap.coremap.log.Log;

/**
 * Runs code that ATAK's main thread enters from a callback, a broadcast or a posted task: an
 * exception there would kill ATAK, as it kills an app. See docs/09.
 */
public final class Guard {

    private Guard() {
    }

    /** Runs {@code action}; what it throws is logged as "unable to {@code what}". */
    public static void run(final String tag, final String what, final Runnable action) {
        try {
            action.run();
        } catch (final RuntimeException | LinkageError e) {
            Log.e(tag, "unable to " + what, e);
        }
    }

    /** {@code action} as a task that runs it guarded, e.g. for a Handler. */
    public static Runnable wrap(final String tag, final String what, final Runnable action) {
        return () -> run(tag, what, action);
    }
}
