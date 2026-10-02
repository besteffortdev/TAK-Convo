package com.atakmap.android.takconvo.plugin.ui.host;

import android.content.Context;
import android.content.Intent;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;

/**
 * The ATAK drop-down that shows Conversations; a drop-down rather than a Pane for the back
 * button and visibility changes. See docs/04.
 */
public final class ChatDropDown extends DropDownReceiver implements DropDown.OnStateListener {

    /** The pane's width in landscape, height in portrait. */
    public static final double PANE_FRACTION = 0.4;

    private final EmbeddedActivityHost host;
    private final View pane;

    /** {@code banner}: shown above the activities' screens, e.g. the connection state. */
    public ChatDropDown(final MapView mapView, final EmbeddedActivityHost host,
            final View banner) {
        super(mapView);
        this.host = host;
        final LinearLayout layout = new LinearLayout(mapView.getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(banner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(host.getView(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        this.pane = layout;
    }

    /**
     * Shows the pane on top, also when hidden under another drop-down: showDropDown moves it
     * up (unhideDropDown would unhide the top one instead).
     */
    public void show() {
        if (!isClosed() && isVisible()) {
            return;
        }
        final int[] size = estimatePaneSize(getMapView());
        host.setPaneSize(size[0], size[1]);
        // ignoreBackButton: kept on ATAK's stack, closed by goBack() only
        showDropDown(pane, PANE_FRACTION, FULL_HEIGHT, FULL_WIDTH, PANE_FRACTION, true,
                this);
    }

    /**
     * The pane's size in pixels before layout: a fraction of ATAK's content area. Another open
     * pane narrows the map view, and the display counts the system bars.
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

    /** Goes back in Conversations; closes the pane when nothing is left. */
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
