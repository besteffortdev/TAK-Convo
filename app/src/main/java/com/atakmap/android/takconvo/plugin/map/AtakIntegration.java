package com.atakmap.android.takconvo.plugin.map;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;

import com.atakmap.android.importexport.ImportExportMapComponent;
import com.atakmap.android.importexport.ImportReceiver;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapTouchController;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.takconvo.plugin.R;
import com.atakmap.android.takconvo.plugin.config.XmppSettings;
import com.atakmap.android.util.ATAKUtilities;
import com.atakmap.coremap.log.Log;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.utils.TakConvoCompat;
import eu.siacs.conversations.xmpp.Jid;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What Conversations' screens use of ATAK ({@link TakConvoCompat.Atak}): quick messages, TAK
 * users on the map, positions in messages, map files imported. See docs/10.
 */
public final class AtakIntegration implements TakConvoCompat.Atak {

    private static final String TAG = "TakConvo.Map";
    /** What ATAK's importer takes: data packages, KML, GPX, CoT, GeoJSON, imagery. */
    private static final Set<String> MAP_FILES = new HashSet<>(Arrays.asList(
            "zip", "dpk", "kml", "kmz", "gpx", "cot", "geojson", "tif", "tiff", "ntf", "nitf",
            "mbtiles", "sqlite"));
    /** Where ATAK keeps the XMPP address a TAK user advertises. */
    private static final String XMPP_META = "xmppUsername";

    private final MapView mapView;
    private final Context plugin;
    private final SharedPreferences prefs;
    private final Supplier<Account> account;

    public AtakIntegration(final MapView mapView, final Context plugin,
            final SharedPreferences prefs, final Supplier<Account> account) {
        this.mapView = mapView;
        this.plugin = plugin;
        this.prefs = prefs;
        this.account = account;
    }

    @Override
    public List<String> quickMessages() {
        final List<String> messages = new ArrayList<>();
        if (!prefs.getBoolean(XmppSettings.KEY_SHOW_QUICK_MESSAGES, false)) {
            return messages;
        }
        final String value = prefs.getString(XmppSettings.KEY_QUICK_MESSAGES,
                plugin.getString(R.string.takconvo_quick_messages_default));
        for (final String message : value.split("\\|")) {
            if (!message.trim().isEmpty()) {
                messages.add(message.trim());
            }
        }
        return messages;
    }

    @Override
    public boolean isOnMap(final Jid address) {
        return find(address) != null;
    }

    @Override
    public void showOnMap(final Jid address) {
        final MapItem item = find(address);
        if (item == null) {
            Toast.makeText(mapView.getContext(), plugin.getString(R.string.takconvo_not_on_map),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        MapTouchController.goTo(item, false);
    }

    /** The marker of the TAK user advertising this address; ours is the self marker. */
    private MapItem find(final Jid address) {
        final String wanted = address.asBareJid().toString();
        final Account own = account.get();
        if (own != null && own.getJid().asBareJid().toString().equalsIgnoreCase(wanted)) {
            return ATAKUtilities.findSelf(mapView);
        }
        final MapItem[] found = new MapItem[1];
        mapView.getRootGroup().deepForEachItem(new MapGroup.MapItemsCallback() {
            @Override
            public boolean onItemFunction(final MapItem item) {
                if (wanted.equalsIgnoreCase(item.getMetaString(XMPP_META, null))) {
                    found[0] = item;
                    return true;
                }
                return false;
            }
        });
        return found[0];
    }

    @Override
    public List<TakConvoCompat.Coordinates> findCoordinates(final String text) {
        return CoordinateFinder.find(text);
    }

    @Override
    public boolean openFile(final Context context, final File file,
            final Runnable openElsewhere) {
        final String extension = extension(file);
        if (extension == null || !MAP_FILES.contains(extension)) {
            return false;
        }
        final CharSequence[] choices = {
                plugin.getString(R.string.takconvo_import_atak),
                plugin.getString(R.string.takconvo_import_elsewhere)
        };
        // Conversations names received files by message id: show the type instead
        new AlertDialog.Builder(context)
                .setTitle(plugin.getString(R.string.takconvo_import_title,
                        extension.toUpperCase(Locale.ROOT)))
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) {
                        importFile(file);
                    } else {
                        openElsewhere.run();
                    }
                })
                .show();
        return true;
    }

    /** Lower case, or null without one. */
    private static String extension(final File file) {
        final String name = file.getName();
        final int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : null;
    }

    /** ATAK copies the file, imports it with the importer its type needs, and zooms to it. */
    private static void importFile(final File file) {
        Log.d(TAG, "importing a received file into ATAK");
        final Intent intent = new Intent(ImportExportMapComponent.USER_HANDLE_IMPORT_FILE_ACTION);
        intent.putExtra("filepath", file.getAbsolutePath());
        intent.putExtra("showNotificationsDuringImport", true);
        intent.putExtra(ImportReceiver.EXTRA_ZOOM_TO_FILE, true);
        AtakBroadcast.getInstance().sendBroadcast(intent);
    }
}
