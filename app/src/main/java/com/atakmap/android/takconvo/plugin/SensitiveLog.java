package com.atakmap.android.takconvo.plugin;

import com.atakmap.coremap.log.Log;

/** Debug logs that name users (addresses, callsigns, rooms): debug builds only. */
public final class SensitiveLog {

    private SensitiveLog() {
    }

    public static void d(final String tag, final String message) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message);
        }
    }
}
