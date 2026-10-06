package com.atakmap.android.takconvo.plugin.contacts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.missionpackage.api.MissionPackageApi;
import com.atakmap.android.missionpackage.file.MissionPackageManifest;
import com.atakmap.android.takconvo.plugin.Guard;
import com.atakmap.android.takconvo.plugin.R;
import com.atakmap.android.takconvo.plugin.map.ChatSender;
import com.atakmap.android.takconvo.plugin.xmpp.XmppEngine;
import com.atakmap.coremap.log.Log;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * What the user sends to a group chat from ATAK's send list: a marker's or shape's Send, several
 * map items, a data package sent to contacts. ATAK broadcasts the request as
 * {@link XmppRoomContact#SEND_ACTION}, one per group chat, and {@link ChatSender} posts it there.
 * See docs/10.
 */
public final class GroupChatSends extends BroadcastReceiver {

    private static final String TAG = "TakConvo.Send";

    private final MapView mapView;
    private final Context plugin;
    private final XmppEngine engine;
    private final ChatSender sender;

    public GroupChatSends(final MapView mapView, final Context plugin, final XmppEngine engine,
            final ChatSender sender) {
        this.mapView = mapView;
        this.plugin = plugin;
        this.engine = engine;
        this.sender = sender;
    }

    public void register() {
        final AtakBroadcast.DocumentedIntentFilter filter =
                new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction(XmppRoomContact.SEND_ACTION,
                "Send map items or a data package to a TAK Convo group chat");
        AtakBroadcast.getInstance().registerReceiver(this, filter);
    }

    public void unregister() {
        AtakBroadcast.getInstance().unregisterReceiver(this);
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        Guard.run(TAG, "send to a group chat", () -> send(intent));
    }

    /** The extras are those of ATAK's send list request (ContactPresenceDropdown.SEND_LIST). */
    private void send(final Intent intent) {
        final Conversation chat = groupChat(
                XmppRoomContact.addressOf(intent.getStringExtra("contactUID")));
        if (chat == null) {
            toast(R.string.takconvo_send_no_group_chat);
            return;
        }
        final Object manifest =
                intent.getParcelableExtra(MissionPackageApi.INTENT_EXTRA_MISSIONPACKAGEMANIFEST);
        if (manifest instanceof MissionPackageManifest) {
            sender.sendDataPackage((MissionPackageManifest) manifest, chat);
            return;
        }
        final List<MapItem> items = mapItems(intent);
        if (!items.isEmpty()) {
            sender.sendMapItems(items, chat);
            return;
        }
        final String filename = intent.getStringExtra("filename");
        if (filename != null) {
            sender.sendFile(new File(filename), chat);
            return;
        }
        // e.g. bare CoT events from a plugin, or a GeoChat message
        Log.w(TAG, "nothing a group chat can take in the send request");
        toast(R.string.takconvo_send_unsupported);
    }

    /** The open group chat of the account with this address. */
    private Conversation groupChat(final String address) {
        final Account account = engine.getAccount();
        if (address == null || account == null) {
            return null;
        }
        for (final Conversation conversation : engine.getConversations()) {
            if (conversation.getAccount() == account
                    && conversation.getMode() == Conversation.MODE_MULTI
                    && conversation.getStatus() != Conversation.STATUS_ARCHIVED
                    && conversation.getAddress().asBareJid().toString()
                            .equalsIgnoreCase(address)) {
                return conversation;
            }
        }
        return null;
    }

    /** targetUID (one item) or targetsUID (several), as found on the map. */
    private List<MapItem> mapItems(final Intent intent) {
        final String uid = intent.getStringExtra("targetUID");
        final String[] uids = uid != null ? new String[] {
                uid
        } : intent.getStringArrayExtra("targetsUID");
        final List<MapItem> items = new ArrayList<>();
        if (uids != null) {
            for (final String u : uids) {
                final MapItem item = mapView.getRootGroup().deepFindUID(u);
                if (item != null) {
                    items.add(item);
                }
            }
        }
        return items;
    }

    private void toast(final int message) {
        Toast.makeText(mapView.getContext(), plugin.getString(message), Toast.LENGTH_LONG).show();
    }
}
