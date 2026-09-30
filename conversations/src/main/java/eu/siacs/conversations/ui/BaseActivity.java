package eu.siacs.conversations.ui;

import android.util.Log;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.ui.util.SettingsUtils;
import eu.siacs.conversations.utils.TakConvoCompat;

public abstract class BaseActivity extends AppCompatActivity {
    private Boolean isDynamicColors;

    /**
     * TAKCONVO: embedded in ATAK, the activity's views are shown in an ATAK pane instead of the
     * activity's own window, which is never attached. This is the root of those views.
     */
    public View embeddedContent;

    // TAKCONVO: the views are no longer below the window's decor view
    @Override
    public <T extends View> T findViewById(final int id) {
        final View content = this.embeddedContent;
        if (content != null) {
            final T view = content.findViewById(id);
            if (view != null) {
                return view;
            }
        }
        return super.findViewById(id);
    }

    // TAKCONVO: same, e.g. for hiding the soft keyboard
    @Override
    public View getCurrentFocus() {
        final View content = this.embeddedContent;
        return content != null ? content.findFocus() : super.getCurrentFocus();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (TakConvoCompat.EMBEDDED) {
            return; // TAKCONVO: the plugin picks the theme; an embedded activity can't recreate()
        }
        final var appSettings = new AppSettings(this);
        final int desiredNightMode = appSettings.getDesiredNightMode();
        if (setDesiredNightMode(desiredNightMode)) {
            return;
        }
        final boolean isDynamicColors = appSettings.isDynamicColorsDesired();
        setDynamicColors(isDynamicColors);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SettingsUtils.applyScreenshotSetting(this);
    }

    public void setDynamicColors(final boolean isDynamicColors) {
        if (this.isDynamicColors == null) {
            this.isDynamicColors = isDynamicColors;
        } else {
            if (this.isDynamicColors != isDynamicColors) {
                Log.i(
                        "Recreating {} because dynamic color setting has changed",
                        getClass().getSimpleName());
                recreate();
            }
        }
    }

    public boolean setDesiredNightMode(final int desiredNightMode) {
        if (desiredNightMode == AppCompatDelegate.getDefaultNightMode()) {
            return false;
        }
        AppCompatDelegate.setDefaultNightMode(desiredNightMode);
        Log.i("Recreating {} because desired night mode has changed", getClass().getSimpleName());
        recreate();
        return true;
    }
}
