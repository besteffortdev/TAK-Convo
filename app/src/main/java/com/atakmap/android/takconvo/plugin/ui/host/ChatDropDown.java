package com.atakmap.android.takconvo.plugin.ui.host;

import android.content.Context;
import android.content.Intent;
import android.util.DisplayMetrics;
import android.view.View;

import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;

/**
 * The ATAK side pane that shows Conversations. A drop-down of our own rather than a
 * {@code Pane}, because it needs the back button and the visibility changes.
 */
public final class ChatDropDown extends DropDownReceiver implements DropDown.OnStateListener {

    /** The pane's share of the map: its width in landscape, its height in portrait. */
    public static final double PANE_FRACTION = 0.4;

    private final EmbeddedActivityHost host;

    public ChatDropDown(final MapView mapView, final EmbeddedActivityHost host) {
        super(mapView);
        this.host = host;
    }

    /**
     * Opens the pane on whatever the host currently shows, on top of ATAK's other drop-downs.
     * Open but hidden under another drop-down (e.g. the account pane opened from a chat), it
     * is brought to the front: showDropDown closes it first, which only stops the host's
     * activities. Unhiding instead would unhide the drop-down on top, not this one.
     */
    public void show() {
        if (!isClosed() && isVisible()) {
            return;
        }
        final int[] size = estimatePaneSize(getMapView());
        host.setPaneSize(size[0], size[1]);
        // ignoreBackButton: ATAK keeps the pane on its stack under other drop-downs, and back
        // never closes it by itself, only goBack() does
        showDropDown(host.getView(), PANE_FRACTION, FULL_HEIGHT, FULL_WIDTH, PANE_FRACTION, true,
                this);
    }

    /**
     * The size in pixels a pane of {@link #PANE_FRACTION} gets, before it is laid out. ATAK
     * sizes it as a fraction of its content area, which the map fills while no pane is open
     * (another open pane narrows the map, not that area); the display also counts the
     * system bars.
     */
    public static int[] estimatePaneSize(final MapView mapView) {
        final View content = mapView.getRootView().findViewById(android.R.id.content);
        final DisplayMetrics metrics = mapView.getContext().getResources().getDisplayMetrics();
        int width = content != null ? content.getWidth() : 0;
        int height = content != null ? content.getHeight() : 0;
        if (width <= 0 || height <= 0) {
            width = mapView.getWidth() > 0 ? mapView.getWidth() : metrics.widthPixels;
            height = mapView.getHeight() > 0 ? mapView.getHeight() : metrics.heightPixels;
        }
        final boolean portrait = height > width;
        return new int[] {
                (int) (width * (portrait ? FULL_WIDTH : PANE_FRACTION)),
                (int) (height * (portrait ? PANE_FRACTION : FULL_HEIGHT))};
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
