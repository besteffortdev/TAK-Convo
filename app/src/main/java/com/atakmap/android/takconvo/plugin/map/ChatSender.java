package com.atakmap.android.takconvo.plugin.map;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.atakmap.android.data.URIContentSender;
import com.atakmap.android.data.URIHelper;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.missionpackage.MissionPackageMapComponent;
import com.atakmap.android.missionpackage.api.MissionPackageApi;
import com.atakmap.android.missionpackage.file.MissionPackageManifest;
import com.atakmap.android.missionpackage.file.task.MissionPackageBaseTask;
import com.atakmap.android.takconvo.plugin.R;
import com.atakmap.android.takconvo.plugin.xmpp.XmppEngine;
import com.atakmap.android.util.ATAKUtilities;
import com.atakmap.coremap.conversions.CoordinateFormat;
import com.atakmap.coremap.conversions.CoordinateFormatUtilities;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Sends ATAK's data to chats, as file uploads: "TAK Convo" in ATAK's Send dialog (a file, a data
 * package), then a chat the user picks; and map items sent to a group chat from ATAK's contact
 * list (contacts.GroupChatSends). See docs/10.
 */
public final class ChatSender implements URIContentSender {

    private static final String TAG = "TakConvo.Send";
    private static final String FILE = "file://";
    private static final String DATA_PACKAGE = "mpm://";

    private final MapView mapView;
    private final Context plugin;
    private final XmppEngine engine;
    private final Drawable icon;
    private final Consumer<Conversation> showChat;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ChatSender(final MapView mapView, final Context plugin, final XmppEngine engine,
            final Drawable icon, final Consumer<Conversation> showChat) {
        this.mapView = mapView;
        this.plugin = plugin;
        this.engine = engine;
        this.icon = icon;
        this.showChat = showChat;
    }

    @Override
    public String getName() {
        return plugin.getString(R.string.app_name);
    }

    @Override
    public Drawable getIcon() {
        return icon;
    }

    @Override
    public boolean isSupported(final String contentUri) {
        return contentUri != null && engine.getAccount() != null
                && (contentUri.startsWith(FILE) || contentUri.startsWith(DATA_PACKAGE));
    }

    @Override
    public boolean sendContent(final String contentUri, final Callback callback) {
        final List<Conversation> chats = chats();
        if (chats.isEmpty()) {
            toast(plugin.getString(R.string.takconvo_send_no_chats));
            return false;
        }
        final CharSequence[] names = names(chats);
        new AlertDialog.Builder(mapView.getContext())
                .setTitle(plugin.getString(R.string.takconvo_send_to))
                .setItems(names, (dialog, which) -> send(contentUri, chats.get(which), callback))
                .show();
        return true;
    }

    /** The account's open chats, most recent first, as in the chat list. */
    private List<Conversation> chats() {
        final Account account = engine.getAccount();
        final List<Conversation> chats = new ArrayList<>();
        if (account == null) {
            return chats;
        }
        for (final Conversation conversation : engine.getConversations()) {
            if (conversation.getAccount() == account
                    && conversation.getStatus() != Conversation.STATUS_ARCHIVED) {
                chats.add(conversation);
            }
        }
        Collections.sort(chats, (a, b) -> Long.compare(lastActivity(b), lastActivity(a)));
        return chats;
    }

    /** Each chat's name; with its address when another chat has the same name. */
    private CharSequence[] names(final List<Conversation> chats) {
        final Map<String, Integer> counts = new HashMap<>();
        for (final Conversation chat : chats) {
            final String name = chat.getName().toString();
            final Integer count = counts.get(name);
            counts.put(name, count == null ? 1 : count + 1);
        }
        final CharSequence[] names = new CharSequence[chats.size()];
        for (int i = 0; i < names.length; i++) {
            final Conversation chat = chats.get(i);
            String name = chat.getName().toString();
            if (counts.get(name) > 1) {
                name = plugin.getString(R.string.takconvo_send_chat_address, name,
                        chat.getAddress().asBareJid().toString());
            }
            names[i] = chat.getMode() == Conversation.MODE_MULTI
                    ? plugin.getString(R.string.takconvo_send_group_chat, name)
                    : name;
        }
        return names;
    }

    private static long lastActivity(final Conversation conversation) {
        final Message latest = conversation.getLatestMessage();
        return latest == null ? 0 : latest.getTimeSent();
    }

    private void send(final String contentUri, final Conversation chat, final Callback callback) {
        if (contentUri.startsWith(FILE)) {
            attach(URIHelper.getFile(contentUri), chat, contentUri, callback);
        } else {
            sendDataPackage(URIHelper.getManifest(contentUri), chat, contentUri, callback);
        }
    }

    /** Sends a file to a chat. */
    public void sendFile(final File file, final Conversation chat) {
        attach(file, chat, null, null);
    }

    /** Sends a data package to a chat, saved first if it is only a manifest. */
    public void sendDataPackage(final MissionPackageManifest manifest, final Conversation chat) {
        sendDataPackage(manifest, chat, null, null);
    }

    /**
     * Sends map items to a chat, as ATAK sends a data package in GeoChat: a line naming them,
     * with the position when there is one, then a data package of them, which a TAK Convo user
     * imports into ATAK with a tap.
     */
    public void sendMapItems(final List<MapItem> items, final Conversation chat) {
        if (items.isEmpty()) {
            failed(null, null, null);
            return;
        }
        final MapItem first = items.get(0);
        final String name = items.size() == 1 ? ATAKUtilities.getDisplayName(first)
                : plugin.getString(R.string.takconvo_shared_items_name, items.size());
        final MissionPackageManifest manifest =
                MissionPackageApi.CreateTempManifest(name, true, false, null);
        for (final MapItem item : items) {
            manifest.addMapItem(item.getUID());
        }
        sendText(chat, describe(items));
        sendDataPackage(manifest, chat);
    }

    /** "Rally point · 18T VR 30500 17000", or "3 map items: a, b, c". */
    private String describe(final List<MapItem> items) {
        if (items.size() > 1) {
            final List<String> names = new ArrayList<>();
            for (final MapItem item : items) {
                names.add(ATAKUtilities.getDisplayName(item));
            }
            return plugin.getString(R.string.takconvo_shared_items, items.size(),
                    TextUtils.join(", ", names));
        }
        final MapItem item = items.get(0);
        final GeoPoint point = item instanceof PointMapItem ? ((PointMapItem) item).getPoint()
                : item instanceof Shape ? ((Shape) item).getCenter().get() : null;
        final String name = ATAKUtilities.getDisplayName(item);
        if (point == null || !point.isValid()) {
            return name;
        }
        // ATAK puts a left-to-right mark before each space: plain spaces for other clients
        final String mgrs = CoordinateFinder.plainSpaces(
                CoordinateFormatUtilities.formatToString(point, CoordinateFormat.MGRS));
        return plugin.getString(R.string.takconvo_shared_item, name, mgrs);
    }

    /** A text message, encrypted as the chat's next message is. */
    private void sendText(final Conversation chat, final String body) {
        final Message message = new Message(chat, body, chat.getNextEncryption());
        engine.getService().encryptIfNeededAndSend(message);
    }

    private void sendDataPackage(final MissionPackageManifest manifest, final Conversation chat,
            final String contentUri, final Callback callback) {
        if (manifest == null || !manifest.isValid()) {
            failed(contentUri, callback, null);
            return;
        }
        if (manifest.pathExists()) {
            attach(new File(manifest.getPath()), chat, contentUri, callback);
            return;
        }
        // a data package made from a selection exists only as a manifest until it's saved
        MissionPackageMapComponent.getInstance().getFileIO().save(manifest,
                new MissionPackageBaseTask.Callback() {
                    @Override
                    public void onMissionPackageTaskComplete(final MissionPackageBaseTask task,
                            final boolean success) {
                        mainHandler.post(() -> {
                            if (success && manifest.pathExists()) {
                                attach(new File(manifest.getPath()), chat, contentUri, callback);
                            } else {
                                failed(contentUri, callback, null);
                            }
                        });
                    }
                });
    }

    /** Conversations copies the file and uploads it, encrypted when the chat is. */
    private void attach(final File file, final Conversation chat, final String contentUri,
            final Callback callback) {
        if (file == null || !file.isFile()) {
            failed(contentUri, callback, null);
            return;
        }
        Log.d(TAG, "sending a file from ATAK to a chat");
        Futures.addCallback(
                engine.getService().attachFileToConversation(chat, Uri.fromFile(file), null),
                new FutureCallback<Void>() {
                    @Override
                    public void onSuccess(final Void result) {
                        if (callback != null) {
                            callback.onSentContent(ChatSender.this, contentUri, true);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable t) {
                        failed(contentUri, callback, t);
                    }
                },
                mainHandler::post);
        showChat.accept(chat);
    }

    private void failed(final String contentUri, final Callback callback, final Throwable t) {
        // the exception's message can hold the file's path
        Log.w(TAG, "unable to send to a chat: "
                + (t == null ? "no file" : t.getClass().getName()));
        toast(plugin.getString(R.string.takconvo_send_failed));
        if (callback != null) {
            callback.onSentContent(this, contentUri, false);
        }
    }

    private void toast(final String message) {
        Toast.makeText(mapView.getContext(), message, Toast.LENGTH_LONG).show();
    }
}
