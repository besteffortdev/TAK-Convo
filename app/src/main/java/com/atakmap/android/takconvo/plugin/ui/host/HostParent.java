package com.atakmap.android.takconvo.plugin.ui.host;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.Window;

/**
 * The "parent activity" of every embedded Conversations activity.
 *
 * <p>Android still routes what a child activity can't do by itself through its parent, as it did
 * for ActivityGroup: starting an activity, finishing, requesting an orientation. That is where
 * the host takes those over. This object is never attached or shown; it only answers the calls
 * {@link Activity} makes on a parent.
 */
final class HostParent extends Activity {

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

    /** A child's window uses this one as its container, so its dialogs get ATAK's window token. */
    @Override
    public Window getWindow() {
        return atak.getWindow();
    }

    /** A child's theme starts as a copy of its parent's: nothing, so only its own style applies. */
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
        // nothing of ours is identified by a request code alone
    }

    @Override
    public void startIntentSenderFromChild(final Activity child, final IntentSender intent,
            final int requestCode, final Intent fillInIntent, final int flagsMask,
            final int flagsValues, final int extraFlags) {
        // not supported inside ATAK
    }

    @Override
    public void startIntentSenderFromChild(final Activity child, final IntentSender intent,
            final int requestCode, final Intent fillInIntent, final int flagsMask,
            final int flagsValues, final int extraFlags, final Bundle options) {
        // not supported inside ATAK
    }

    /** The orientation is ATAK's to decide. */
    @Override
    public void setRequestedOrientation(final int requestedOrientation) {
    }

    @Override
    public int getRequestedOrientation() {
        return atak.getRequestedOrientation();
    }
}
