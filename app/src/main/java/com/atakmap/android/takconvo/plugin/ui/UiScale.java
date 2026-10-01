package com.atakmap.android.takconvo.plugin.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

/**
 * Scales Conversations' screens to ATAK's denser UI, through a lower density that scales every
 * dp and sp alike.
 */
public final class UiScale {

    public static final float FACTOR = 0.9f;

    private UiScale() {
    }

    public static int densityDpi(final Context atak) {
        return Math.round(atak.getResources().getConfiguration().densityDpi * FACTOR);
    }

    /** Pixels per dp at {@link #densityDpi}. */
    public static float density(final Context atak) {
        return densityDpi(atak) / (float) DisplayMetrics.DENSITY_DEFAULT;
    }

    /** The scaled density, dark like ATAK. */
    public static Configuration override(final Context atak) {
        final Configuration override = new Configuration();
        override.uiMode = Configuration.UI_MODE_NIGHT_YES;
        override.densityDpi = densityDpi(atak);
        return override;
    }
}
