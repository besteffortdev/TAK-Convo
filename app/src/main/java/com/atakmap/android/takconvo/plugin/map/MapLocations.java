package com.atakmap.android.takconvo.plugin.map;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapTouchController;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.takconvo.plugin.R;
import com.atakmap.android.takconvo.plugin.ui.host.EmbeddedActivityHost;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;
import com.atakmap.android.user.MapClickTool;
import com.atakmap.android.user.PlacePointTool;
import com.atakmap.android.util.ATAKUtilities;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import de.gultsch.common.MiniUri;

import java.util.Locale;

/**
 * Conversations' location screens, on ATAK's map: a location shown from a chat becomes a marker
 * there, and the one shared is ATAK's own position or a point tapped on the map. See docs/10.
 */
public final class MapLocations implements EmbeddedActivityHost.Redirect {

    private static final String TAG = "TakConvo.Map";
    private static final String SHOW = "eu.siacs.conversations.ui.ShowLocationActivity";
    private static final String SHARE = "eu.siacs.conversations.ui.ShareLocationActivity";
    private static final String ACTION_PICKED = "com.atakmap.android.takconvo.LOCATION_PICKED";
    /** A spot map marker, what ATAK drops for a point of interest. */
    private static final String MARKER_TYPE = "b-m-p-s-m";

    private final MapView mapView;
    private final Context plugin;
    /** The share waiting for a tap on the map. */
    private EmbeddedActivityHost.Result picking;

    private final BroadcastReceiver pickedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final EmbeddedActivityHost.Result result = picking;
            picking = null;
            if (result == null) {
                return;
            }
            final String point = intent.getStringExtra("point");
            final GeoPoint picked = point == null ? null : GeoPoint.parseGeoPoint(point);
            if (picked == null || !picked.isValid()) {
                result.deliver(Activity.RESULT_CANCELED, null);
            } else {
                deliver(result, picked);
            }
        }
    };

    public MapLocations(final MapView mapView, final Context plugin) {
        this.mapView = mapView;
        this.plugin = plugin;
        final AtakBroadcast.DocumentedIntentFilter filter =
                new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction(ACTION_PICKED, "The point tapped for a TAK Convo location share");
        AtakBroadcast.getInstance().registerReceiver(pickedReceiver, filter);
    }

    public void dispose() {
        AtakBroadcast.getInstance().unregisterReceiver(pickedReceiver);
        picking = null;
    }

    @Override
    public boolean start(final Activity caller, final Intent intent,
            final EmbeddedActivityHost.Result result) {
        final ComponentName component = intent.getComponent();
        final String name = component == null ? null : component.getClassName();
        if (SHOW.equals(name)) {
            show(intent);
            return true;
        } else if (SHARE.equals(name)) {
            share(caller, result);
            return true;
        }
        return false;
    }

    /** Puts a marker named {@code label} at the position, or finds it, and centers the map. */
    public void show(final double latitude, final double longitude, final String label) {
        if (!GeoPoint.isValid(latitude, longitude)) {
            toast(R.string.takconvo_location_invalid);
            return;
        }
        // one marker per position, however often it is opened
        final String uid = String.format(Locale.US, "takconvo-location-%.5f,%.5f", latitude,
                longitude);
        MapItem marker = mapView.getRootGroup().deepFindUID(uid);
        if (marker == null) {
            final String name = label == null || label.trim().isEmpty()
                    ? plugin.getString(R.string.takconvo_location_marker)
                    : label.trim();
            marker = new PlacePointTool.MarkerCreator(new GeoPoint(latitude, longitude))
                    .setUid(uid)
                    .setType(MARKER_TYPE)
                    .setCallsign(name)
                    .showCotDetails(false)
                    .placePoint();
        }
        if (marker != null) {
            MapTouchController.goTo(marker, false);
        }
    }

    private void show(final Intent intent) {
        String label = intent.getStringExtra("label");
        if (intent.hasExtra("latitude") && intent.hasExtra("longitude")) {
            show(intent.getDoubleExtra("latitude", 0), intent.getDoubleExtra("longitude", 0),
                    label);
            return;
        }
        final MiniUri uri = intent.getData() == null ? null : MiniUri.getOrNull(intent.getData());
        if (!(uri instanceof MiniUri.Geo)) {
            toast(R.string.takconvo_location_invalid);
            return;
        }
        final MiniUri.Geo geo = (MiniUri.Geo) uri;
        if (label == null && geo.getLabel().isPresent()) {
            label = Uri.decode(geo.getLabel().get());
        }
        show(geo.getLatitude(), geo.getLongitude(), label);
    }

    private void share(final Activity caller, final EmbeddedActivityHost.Result result) {
        final CharSequence[] choices = {
                plugin.getString(R.string.takconvo_location_share_self),
                plugin.getString(R.string.takconvo_location_share_pick)
        };
        new AlertDialog.Builder(caller != null ? caller : mapView.getContext())
                .setTitle(plugin.getString(R.string.takconvo_location_share_title))
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) {
                        shareSelf(result);
                    } else {
                        pickOnMap(result);
                    }
                })
                .setOnCancelListener(dialog -> result.deliver(Activity.RESULT_CANCELED, null))
                .show();
    }

    private void shareSelf(final EmbeddedActivityHost.Result result) {
        final Marker self = ATAKUtilities.findSelf(mapView);
        final GeoPoint point = self == null ? null : self.getPoint();
        if (point == null || !point.isValid()) {
            toast(R.string.takconvo_location_no_self);
            result.deliver(Activity.RESULT_CANCELED, null);
            return;
        }
        deliver(result, point);
    }

    private void pickOnMap(final EmbeddedActivityHost.Result result) {
        if (picking != null) {
            picking.deliver(Activity.RESULT_CANCELED, null);
        }
        picking = result;
        final Bundle extras = new Bundle();
        extras.putString("prompt", plugin.getString(R.string.takconvo_location_pick_prompt));
        extras.putParcelable("callback", new Intent(ACTION_PICKED));
        ToolManagerBroadcastReceiver.getInstance().startTool(MapClickTool.TOOL_NAME, extras);
    }

    /** The result ShareLocationActivity gives; Conversations makes a geo: URI of it. */
    private static void deliver(final EmbeddedActivityHost.Result result, final GeoPoint point) {
        final Intent data = new Intent();
        data.putExtra("latitude", point.getLatitude());
        data.putExtra("longitude", point.getLongitude());
        final double ce = point.getCE();
        if (!Double.isNaN(ce) && ce > 0 && ce < Integer.MAX_VALUE) {
            data.putExtra("accuracy", (int) Math.ceil(ce));
        }
        Log.d(TAG, "sharing a location");
        result.deliver(Activity.RESULT_OK, data);
    }

    private void toast(final int message) {
        Toast.makeText(mapView.getContext(), plugin.getString(message), Toast.LENGTH_SHORT).show();
    }
}
