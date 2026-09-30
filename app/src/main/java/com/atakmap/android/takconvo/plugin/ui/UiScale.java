package com.atakmap.android.takconvo.plugin.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

/**
 * The size of the plugin's Conversations screens relative to ATAK's: Conversations is designed
 * for a whole phone screen, and in an ATAK side pane it looks oversized next to ATAK's own
 * denser UI. Its resources are given a lower density, which scales every dp and sp alike.
 */
public final class UiScale {

    public static final float FACTOR = 0.8f;

    private UiScale() {
    }

    /** The density the plugin's screens use, from ATAK's. */
    public static int densityDpi(final Context atak) {
        return Math.round(atak.getResources().getConfiguration().densityDpi * FACTOR);
    }

    /** Pixels per dp at {@link #densityDpi}. */
    public static float density(final Context atak) {
        return densityDpi(atak) / (float) DisplayMetrics.DENSITY_DEFAULT;
    }

    /** A configuration override with the scaled density, dark like ATAK. */
    public static Configuration override(final Context atak) {
        final Configuration override = new Configuration();
        override.uiMode = Configuration.UI_MODE_NIGHT_YES;
        override.densityDpi = densityDpi(atak);
        return override;
    }
}
