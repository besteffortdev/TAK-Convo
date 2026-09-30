package com.atakmap.android.takconvo.plugin.ui.host;

import android.content.Context;
import android.content.Intent;
import android.util.DisplayMetrics;

import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;

/**
 * The ATAK side pane that shows Conversations. A drop-down of our own rather than a
 * {@code Pane}, because it needs the back button and the visibility changes.
 */
public final class ChatDropDown extends DropDownReceiver implements DropDown.OnStateListener {

    private final EmbeddedActivityHost host;

    public ChatDropDown(final MapView mapView, final EmbeddedActivityHost host) {
        super(mapView);
        this.host = host;
    }

    /** Opens the pane on whatever the host currently shows. */
    public void show() {
        if (!isClosed()) {
            if (!isVisible()) {
                unhideDropDown();
            }
            return;
        }
        final DisplayMetrics metrics = getMapView().getContext().getResources()
                .getDisplayMetrics();
        if (isPortrait()) {
            host.setPaneSize((int) (metrics.widthPixels * FULL_WIDTH),
                    (int) (metrics.heightPixels * HALF_HEIGHT));
        } else {
            host.setPaneSize((int) (metrics.widthPixels * HALF_WIDTH),
                    (int) (metrics.heightPixels * FULL_HEIGHT));
        }
        // ignoreBackButton: ATAK keeps the pane on its stack under other drop-downs, and back
        // never closes it by itself, only goBack() does
        showDropDown(host.getView(), HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, true,
                this);
    }

    /** Back in Conversations; closes the pane once there is nothing left to go back to. */
    public void goBack() {
        if (!host.onBackPressed()) {
            closeDropDown();
        }
    }

    @Override
    protected boolean onBackButtonPressed() {
        goBack();
        return true;
    }

    @Override
    public void onDropDownVisible(final boolean visible) {
        host.setVisible(visible);
    }

    @Override
    public void onDropDownClose() {
        host.setVisible(false);
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownSizeChanged(final double width, final double height) {
    }

    @Override
    protected void disposeImpl() {
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
    }
}
