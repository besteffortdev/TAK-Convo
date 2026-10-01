package com.atakmap.android.takconvo.plugin.ui.host;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.Window;

import com.atakmap.coremap.log.Log;

/**
 * The parent of every embedded activity. Android routes what a child activity can't do itself
 * through its parent (starting, finishing, orientation), so the host takes those over here.
 * Never attached or shown.
 */
final class HostParent extends Activity {

    private static final String TAG = "TakConvo.Host";

    interface Callbacks {
        void startFromChild(Activity child, Intent intent, int requestCode, Bundle options);

        void finishFromChild(Activity child);
    }

    private final Activity atak;
    private final Resources.Theme emptyTheme;
    private final Callbacks callbacks;

    HostParent(final Activity atak, final Resources.Theme emptyTheme, final Callbacks callbacks) {
        this.atak = atak;
        this.emptyTheme = emptyTheme;
        this.callbacks = callbacks;
    }

    /** Gives a child's dialogs ATAK's window token. */
    @Override
    public Window getWindow() {
        return atak.getWindow();
    }

    /** Empty, so a child's theme is only its own style. */
    @Override
    public Resources.Theme getTheme() {
        return emptyTheme;
    }

    @Override
    public void startActivityFromChild(final Activity child, final Intent intent,
            final int requestCode) {
        callbacks.startFromChild(child, intent, requestCode, null);
    }

    @Override
    public void startActivityFromChild(final Activity child, final Intent intent,
            final int requestCode, final Bundle options) {
        callbacks.startFromChild(child, intent, requestCode, options);
    }

    @Override
    public void finishFromChild(final Activity child) {
        callbacks.finishFromChild(child);
    }

    @Override
    public void finishActivityFromChild(final Activity child, final int requestCode) {
        // nothing here is identified by a request code alone
    }

    @Override
    public void startIntentSenderFromChild(final Activity child, final IntentSender intent,
            final int requestCode, final Intent fillInIntent, final int flagsMask,
            final int flagsValues, final int extraFlags) {
        Log.w(TAG, "intent senders are not supported embedded: " + child);
    }

    @Override
    public void startIntentSenderFromChild(final Activity child, final IntentSender intent,
            final int requestCode, final Intent fillInIntent, final int flagsMask,
            final int flagsValues, final int extraFlags, final Bundle options) {
        Log.w(TAG, "intent senders are not supported embedded: " + child);
    }

    /** ATAK decides the orientation. */
    @Override
    public void setRequestedOrientation(final int requestedOrientation) {
    }

    @Override
    public int getRequestedOrientation() {
        return atak.getRequestedOrientation();
    }
}
