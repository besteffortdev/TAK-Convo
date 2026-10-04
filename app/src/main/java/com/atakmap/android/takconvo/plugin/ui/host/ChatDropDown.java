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
    /** The pane's size at its last layout, and ATAK's content area then. */
    private int paneWidth;
    private int paneHeight;
    private int areaWidth = -1;
    private int areaHeight;
    /** The pane's size as fractions of ATAK's area. */
    private double widthFraction = PANE_FRACTION;
    private double heightFraction = FULL_HEIGHT;

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
        // resized by its edge, or by a rotation: ATAK pans for the keyboard instead
        layout.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop,
                oldRight, oldBottom) -> {
            if (right > left && bottom > top) {
                final View area = contentArea(mapView);
                areaWidth = area != null ? area.getWidth() : 0;
                areaHeight = area != null ? area.getHeight() : 0;
                paneWidth = right - left;
                paneHeight = bottom - top;
                host.setPaneSize(paneWidth, paneHeight);
            }
        });
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
        // until its layout gives the real size: ATAK may place it elsewhere this time, e.g.
        // at the bottom once in portrait
        final View area = contentArea(getMapView());
        if (area == null || area.getWidth() != areaWidth || area.getHeight() != areaHeight) {
            final int[] size = estimatePaneSize(getMapView());
            host.setEstimatedPaneSize(size[0], size[1]);
        } else {
            host.setEstimatedPaneSize(paneWidth, paneHeight);
        }
        widthFraction = isPortrait() ? FULL_WIDTH : PANE_FRACTION;
        heightFraction = isPortrait() ? PANE_FRACTION : FULL_HEIGHT;
        // ignoreBackButton: kept on ATAK's stack, closed by goBack() only
        showDropDown(pane, PANE_FRACTION, FULL_HEIGHT, FULL_WIDTH, PANE_FRACTION, true,
                this);
    }

    /**
     * The pane's size in pixels before layout: a fraction of ATAK's content area. Another open
     * pane narrows the map view, and the display counts the system bars.
     */
    public static int[] estimatePaneSize(final MapView mapView) {
        final View content = contentArea(mapView);
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

    /** What ATAK divides between the map and its panes, or null. */
    private static View contentArea(final MapView mapView) {
        return mapView.getRootView().findViewById(android.R.id.content);
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
        widthFraction = width;
        heightFraction = height;
    }

    /** The handle asks for full screen, then back, as in ATAK's samples. */
    @Override
    protected void onStateRequested(final int state) {
        if (state == DROPDOWN_STATE_FULLSCREEN) {
            if (!isPortrait() && Double.compare(widthFraction, PANE_FRACTION) == 0) {
                resize(FULL_WIDTH - HANDLE_THICKNESS_LANDSCAPE, FULL_HEIGHT);
            } else if (isPortrait() && Double.compare(heightFraction, PANE_FRACTION) == 0) {
                resize(FULL_WIDTH, FULL_HEIGHT - HANDLE_THICKNESS_PORTRAIT);
            }
        } else if (state == DROPDOWN_STATE_NORMAL) {
            if (isPortrait()) {
                resize(FULL_WIDTH, PANE_FRACTION);
            } else {
                resize(PANE_FRACTION, FULL_HEIGHT);
            }
        }
    }

    @Override
    protected void disposeImpl() {
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
    }
}
