package com.atakmap.android.takconvo.plugin.debug;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import androidx.core.content.ContextCompat;

import com.atakmap.android.contact.Connector;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.ContactConnectorManager;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.IpConnector;
import com.atakmap.android.contact.XmppConnector;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.data.ClearContentRegistry;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.takconvo.plugin.Guard;
import com.atakmap.android.takconvo.plugin.TakConvoPlugin;
import com.atakmap.android.takconvo.plugin.map.ChatSender;
import com.atakmap.android.takconvo.plugin.ui.TakConvoPreferenceFragment;
import com.atakmap.android.takconvo.plugin.xmpp.XmppEngine;
import com.atakmap.app.SettingsActivity;
import com.atakmap.app.preferences.PreferenceControl;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.comms.CotService;
import com.atakmap.comms.NetConnectString;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.time.CoordinatedTime;
import com.atakmap.net.AtakAuthenticationCredentials;
import com.atakmap.net.AtakAuthenticationDatabase;
import com.google.common.base.MoreObjects;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.TakConvoCompat;
import eu.siacs.conversations.xmpp.Jid;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Debug builds only: drive the engine from adb.
 *
 * <pre>
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_SET_PREF --es key K --es value V
 *     (without value: removes K)
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_IMPORT_PREF --es path P
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_DUMP_SELF_SA
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_PROVISION
 * # as if the MDM set these (the takconvo_* extras; --ez for Booleans); without any: none
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_APP_CONFIG \
 *     [--es takconvo_xmpp_domain D] [--ez takconvo_xmpp_use_tak_credentials B ...]
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_SEND --es to J --es body B
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_ADD_TAK_SERVER \
 *     --es connect host:8089:ssl --es user U --es pass P
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_REMOVE_TAK_SERVER \
 *     --es connect host:8089:ssl
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_SHOW_ACCOUNT
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_OPEN_SETTINGS
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_SHOW_CHAT
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_CHAT_BACK
 *
 * # contacts; the JID defaults to this device's own (the self chat)
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_FAKE_CONTACT \
 *     [--es jid J] [--es callsign C]
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_REMOVE_FAKE_CONTACT
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_OPEN_CONTACT
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_DUMP_CONTACT
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_FAKE_INCOMING \
 *     [--es from J] --es body B
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_ATAK_BROADCAST --es action A \
 *     [--es extra.K V ...]
 *
 * # what a group chat gets from ATAK's send list (a line and a data package), to the self chat
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_SEND_MAP_ITEM --es uid U
 * # ATAK's send list to a contact with a send broadcast, e.g. uid takconvo.room:ROOM
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_SEND_TO_CONTACT --es uid U \
 *     [--es extra.K V ...]
 * # a file to the self chat, as ATAK's Send dialog would send it
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_SEND_FILE --es path P
 * # what ATAK's Clear Content does to TAK Convo, without clearing ATAK: stops the plugin and
 * # deletes its chats, keys and XMPP login
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_CLEAR_CONTENT
 * </pre>
 *
 * <p>Only adb's shell can send these: the receiver requires android.permission.DUMP. The fake
 * contact and fake incoming messages stay on this device; nothing is sent. DEBUG_SEND,
 * DEBUG_SEND_MAP_ITEM and DEBUG_SEND_FILE send, to the self chat by default; so does
 * DEBUG_SEND_TO_CONTACT when given something to send.
 */
public final class DebugReceiver extends BroadcastReceiver {

    private static final String TAG = "TakConvo.Debug";
    private static final String PREFIX = "com.atakmap.android.takconvo.";
    public static final String ACTION_SET_PREF = PREFIX + "DEBUG_SET_PREF";
    public static final String ACTION_PROVISION = PREFIX + "DEBUG_PROVISION";
    public static final String ACTION_APP_CONFIG = PREFIX + "DEBUG_APP_CONFIG";
    public static final String ACTION_SEND = PREFIX + "DEBUG_SEND";
    public static final String ACTION_ADD_TAK_SERVER = PREFIX + "DEBUG_ADD_TAK_SERVER";
    public static final String ACTION_REMOVE_TAK_SERVER = PREFIX + "DEBUG_REMOVE_TAK_SERVER";
    public static final String ACTION_DUMP_SELF_SA = PREFIX + "DEBUG_DUMP_SELF_SA";
    public static final String ACTION_IMPORT_PREF = PREFIX + "DEBUG_IMPORT_PREF";
    public static final String ACTION_SHOW_ACCOUNT = PREFIX + "DEBUG_SHOW_ACCOUNT";
    public static final String ACTION_OPEN_SETTINGS = PREFIX + "DEBUG_OPEN_SETTINGS";
    public static final String ACTION_SHOW_CHAT = PREFIX + "DEBUG_SHOW_CHAT";
    public static final String ACTION_CHAT_BACK = PREFIX + "DEBUG_CHAT_BACK";
    public static final String ACTION_FAKE_CONTACT = PREFIX + "DEBUG_FAKE_CONTACT";
    public static final String ACTION_REMOVE_FAKE_CONTACT = PREFIX + "DEBUG_REMOVE_FAKE_CONTACT";
    public static final String ACTION_OPEN_CONTACT = PREFIX + "DEBUG_OPEN_CONTACT";
    public static final String ACTION_DUMP_CONTACT = PREFIX + "DEBUG_DUMP_CONTACT";
    public static final String ACTION_FAKE_INCOMING = PREFIX + "DEBUG_FAKE_INCOMING";
    public static final String ACTION_ATAK_BROADCAST = PREFIX + "DEBUG_ATAK_BROADCAST";
    public static final String ACTION_SEND_MAP_ITEM = PREFIX + "DEBUG_SEND_MAP_ITEM";
    public static final String ACTION_SEND_TO_CONTACT = PREFIX + "DEBUG_SEND_TO_CONTACT";
    public static final String ACTION_SEND_FILE = PREFIX + "DEBUG_SEND_FILE";
    public static final String ACTION_CLEAR_CONTENT = PREFIX + "DEBUG_CLEAR_CONTENT";

    private static final String FAKE_UID = "TAKCONVO-DEBUG-CONTACT";
    /** Extras named extra.K become extra K of the broadcast sent. */
    private static final String EXTRA_PREFIX = "extra.";

    private final Context context;
    /** The fake TAK user's XMPP address. */
    private String fakeJid;
    private ChatSender chatSender;
    private ClearContentRegistry.ClearContentListener clearContent;

    private DebugReceiver(final Context context) {
        this.context = context;
    }

    public static DebugReceiver register(final Context context) {
        final DebugReceiver receiver = new DebugReceiver(context);
        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SET_PREF);
        filter.addAction(ACTION_PROVISION);
        filter.addAction(ACTION_APP_CONFIG);
        filter.addAction(ACTION_SEND);
        filter.addAction(ACTION_ADD_TAK_SERVER);
        filter.addAction(ACTION_REMOVE_TAK_SERVER);
        filter.addAction(ACTION_DUMP_SELF_SA);
        filter.addAction(ACTION_IMPORT_PREF);
        filter.addAction(ACTION_SHOW_ACCOUNT);
        filter.addAction(ACTION_OPEN_SETTINGS);
        filter.addAction(ACTION_SHOW_CHAT);
        filter.addAction(ACTION_CHAT_BACK);
        filter.addAction(ACTION_FAKE_CONTACT);
        filter.addAction(ACTION_REMOVE_FAKE_CONTACT);
        filter.addAction(ACTION_OPEN_CONTACT);
        filter.addAction(ACTION_DUMP_CONTACT);
        filter.addAction(ACTION_FAKE_INCOMING);
        filter.addAction(ACTION_ATAK_BROADCAST);
        filter.addAction(ACTION_SEND_MAP_ITEM);
        filter.addAction(ACTION_SEND_TO_CONTACT);
        filter.addAction(ACTION_SEND_FILE);
        filter.addAction(ACTION_CLEAR_CONTENT);
        // exported for adb's shell, which holds DUMP; apps can't get it
        ContextCompat.registerReceiver(context, receiver, filter, Manifest.permission.DUMP, null,
                ContextCompat.RECEIVER_EXPORTED);
        Log.d(TAG, "debug receiver registered");
        return receiver;
    }

    public void unregister() {
        context.unregisterReceiver(this);
    }

    @Override
    public void onReceive(final Context c, final Intent intent) {
        final String action = intent.getAction();
        Log.d(TAG, "received " + action);
        if (action != null) {
            Guard.run(TAG, "run " + action, () -> handle(action, intent));
        }
    }

    /** The commands that work without the engine, then the engine's. */
    private void handle(final String action, final Intent intent) {
        switch (action) {
            case ACTION_SET_PREF:
                setPref(intent.getStringExtra("key"), intent.getStringExtra("value"));
                return;
            case ACTION_ADD_TAK_SERVER:
                addTakServer(intent);
                return;
            case ACTION_REMOVE_TAK_SERVER:
                removeTakServer(intent.getStringExtra("connect"));
                return;
            case ACTION_IMPORT_PREF:
                importPref(intent.getStringExtra("path"));
                return;
            case ACTION_DUMP_SELF_SA:
                dumpSelfSa();
                return;
            case ACTION_SHOW_ACCOUNT:
                AtakBroadcast.getInstance().sendBroadcast(
                        new Intent(TakConvoPreferenceFragment.ACTION_SHOW_ACCOUNT));
                return;
            case ACTION_OPEN_SETTINGS:
                SettingsActivity.start(TakConvoPreferenceFragment.TOOL_KEY, null);
                return;
            case ACTION_SHOW_CHAT:
                AtakBroadcast.getInstance().sendBroadcast(
                        new Intent(TakConvoPlugin.ACTION_SHOW_CHAT));
                return;
            case ACTION_CHAT_BACK:
                AtakBroadcast.getInstance().sendBroadcast(
                        new Intent(TakConvoPlugin.ACTION_CHAT_BACK));
                return;
            case ACTION_FAKE_CONTACT:
                fakeJid = orSelf(intent.getStringExtra("jid"));
                injectFakeContact(fakeJid, MoreObjects.firstNonNull(
                        intent.getStringExtra("callsign"), "XMPP Test"));
                return;
            case ACTION_REMOVE_FAKE_CONTACT:
                removeFakeContact();
                return;
            case ACTION_OPEN_CONTACT:
                openFakeContact();
                return;
            case ACTION_DUMP_CONTACT:
                dumpFakeContact();
                return;
            case ACTION_ATAK_BROADCAST:
                sendAtakBroadcast(intent);
                return;
            case ACTION_SEND_TO_CONTACT:
                sendToContact(intent);
                return;
            case ACTION_CLEAR_CONTENT:
                runClearContent();
                return;
            default:
                break;
        }
        final XmppEngine engine = XmppEngine.get();
        if (engine == null) {
            Log.w(TAG, "no engine for " + action);
            return;
        }
        switch (action) {
            case ACTION_PROVISION:
                engine.provision();
                break;
            case ACTION_APP_CONFIG:
                setAppConfig(engine, intent);
                break;
            case ACTION_SEND:
                Log.d(TAG, "send " + (engine.sendMessage(intent.getStringExtra("to"),
                        intent.getStringExtra("body")) ? "queued" : "rejected"));
                break;
            case ACTION_SEND_FILE:
                sendFile(engine, intent.getStringExtra("path"));
                break;
            case ACTION_SEND_MAP_ITEM:
                sendMapItem(engine, intent.getStringExtra("uid"));
                break;
            case ACTION_FAKE_INCOMING:
                fakeIncoming(engine, orSelf(intent.getStringExtra("from")),
                        intent.getStringExtra("body"));
                break;
            default:
                Log.w(TAG, "unknown command " + action);
        }
    }

    /** Without a value: removes the key. */
    private void setPref(final String key, final String value) {
        if (key == null) {
            return;
        }
        if (value == null) {
            AtakPreferences.getInstance(context).remove(key);
            Log.d(TAG, "removed " + key);
        } else {
            AtakPreferences.getInstance(context).set(key, value);
            Log.d(TAG, "set " + key);
        }
    }

    /** As ATAK's "add TAK server" dialog does. */
    private static void addTakServer(final Intent intent) {
        final String connect = intent.getStringExtra("connect");
        final Bundle data = new Bundle();
        data.putString("description", "TAK Convo test server");
        data.putBoolean("enabled", true);
        data.putBoolean("useAuth", true);
        final CotService cot = CommsMapComponent.getInstance().getCotService();
        cot.addStreaming(connect, data);
        cot.setCredentialsForStream(connect, intent.getStringExtra("user"),
                intent.getStringExtra("pass"));
        Log.d(TAG, "added TAK server " + connect);
    }

    /** Also deletes the password {@link #ACTION_ADD_TAK_SERVER} stored for it. */
    private static void removeTakServer(final String connect) {
        // not soft: that only disconnects, and keeps it in files/cotservice/cot_streams
        CommsMapComponent.getInstance().getCotService().removeStreaming(connect, false);
        final NetConnectString ncs = NetConnectString.fromString(connect);
        if (ncs != null) {
            AtakAuthenticationDatabase.delete(AtakAuthenticationCredentials.TYPE_COT_SERVICE,
                    ncs.getHost());
        }
        Log.d(TAG, "removed TAK server " + connect);
    }

    /** ATAK's .pref import, as mission packages use it. */
    private void importPref(final String path) {
        final List<String> loaded = PreferenceControl.getInstance(context)
                .loadSettings(new File(path));
        Log.d(TAG, "imported " + path + ": " + loaded);
    }

    /** The SA this device sends, from ATAK's private getSelfEvent(int). */
    private static void dumpSelfSa() {
        try {
            final Method m = CotMapComponent.class.getDeclaredMethod("getSelfEvent", int.class);
            m.setAccessible(true);
            Log.d(TAG, "self SA: " + m.invoke(CotMapComponent.getInstance(), 0));
        } catch (final ReflectiveOperationException e) {
            Log.e(TAG, "unable to build self SA", e);
        }
    }

    /** Also from its team group, where removeContactByUuid leaves it. */
    private static void removeFakeContact() {
        final Contact contact = Contacts.getInstance().getContactByUuid(FAKE_UID);
        if (contact != null) {
            Contacts.getInstance().removeContact(contact);
        }
        final MapItem item = MapView.getMapView().getRootGroup().deepFindUID(FAKE_UID);
        if (item != null) {
            item.removeFromGroup();
        }
        Contacts.getInstance().updateTotalUnreadCount();
        Log.d(TAG, "removed the fake contact");
    }

    /** As tapping its XMPP connector. */
    private void openFakeContact() {
        final boolean handled = CotMapComponent.getInstance().getContactConnectorMgr()
                .initiateContact(XmppConnector.CONNECTOR_TYPE, FAKE_UID, orSelf(fakeJid));
        Log.d(TAG, "initiated XMPP contact: " + handled);
    }

    /** Its unread counts and presence, and how long "Show on map" takes to find it. */
    private void dumpFakeContact() {
        final Contact contact = Contacts.getInstance().getContactByUuid(FAKE_UID);
        if (contact instanceof IndividualContact) {
            final IndividualContact individual = (IndividualContact) contact;
            final Connector xmpp = individual.getConnector(XmppConnector.CONNECTOR_TYPE);
            final Object presence = xmpp == null ? null : CotMapComponent.getInstance()
                    .getContactConnectorMgr().getFeature(individual, xmpp,
                            ContactConnectorManager.ConnectorFeature.Presence);
            Log.d(TAG, "contact " + individual.getName() + ": unread="
                    + individual.getUnreadCount() + ", xmpp unread="
                    + (xmpp == null ? "no connector" : individual.getUnreadCount(xmpp))
                    + ", xmpp presence=" + presence + ", default connector="
                    + individual.getDefaultConnector(
                            AtakPreferences.getInstance(context).getSharedPrefs()));
        } else {
            Log.d(TAG, "no fake contact");
        }
        // what a chat's "Show on map" menu item asks
        final TakConvoCompat.Atak atak = TakConvoCompat.atak();
        final Jid jid = XmppEngine.bareJid(orSelf(fakeJid));
        if (atak != null && jid != null) {
            final long start = System.nanoTime();
            final boolean onMap = atak.isOnMap(jid);
            Log.d(TAG, jid + " on the map: " + onMap + ", found in "
                    + (System.nanoTime() - start) / 1000 + " µs");
        }
    }

    /** E.g. com.atakmap.android.contact.CONTACT_LIST opens ATAK's contacts. */
    private static void sendAtakBroadcast(final Intent intent) {
        final Intent broadcast = new Intent(intent.getStringExtra("action"));
        copyExtras(intent, broadcast);
        AtakBroadcast.getInstance().sendBroadcast(broadcast);
    }

    /** As ATAK calls it: from its clear content task, off the main thread. */
    private void runClearContent() {
        final ClearContentRegistry.ClearContentListener listener = clearContent;
        if (listener == null) {
            Log.w(TAG, "no Clear Content listener");
            return;
        }
        new Thread(() -> listener.onClearContent(false), "TakConvo.DebugClear").start();
    }

    /** The takconvo_* extras, as if the MDM had set them; none without any. */
    private static void setAppConfig(final XmppEngine engine, final Intent intent) {
        final Bundle values = intent.getExtras() == null ? new Bundle()
                : new Bundle(intent.getExtras());
        for (final String key : new ArrayList<>(values.keySet())) {
            if (!key.startsWith("takconvo_")) {
                values.remove(key);
            }
        }
        Log.d(TAG, "managed configuration for testing: " + values.keySet());
        engine.debugSetAppConfig(values);
    }

    /** A file to the self chat, as ATAK's Send dialog would send it. */
    private void sendFile(final XmppEngine engine, final String path) {
        if (chatSender == null) {
            Log.w(TAG, "no map integration");
            return;
        }
        final Conversation self = engine.openConversation(orSelf(null));
        if (self == null || path == null) {
            Log.w(TAG, "no account, or no path");
            return;
        }
        chatSender.sendFile(new File(path), self);
    }

    /** What a group chat gets from ATAK's send list, sent to the self chat instead. */
    private void sendMapItem(final XmppEngine engine, final String uid) {
        if (chatSender == null) {
            Log.w(TAG, "no map integration");
            return;
        }
        final MapItem item = MapView.getMapView().getRootGroup().deepFindUID(uid);
        final Conversation self = engine.openConversation(orSelf(null));
        if (item == null || self == null) {
            Log.w(TAG, "no such map item, or no account");
            return;
        }
        chatSender.sendMapItems(Collections.singletonList(item), self);
    }

    /** The plugin's sender, for {@link #ACTION_SEND_MAP_ITEM}. */
    public void setChatSender(final ChatSender chatSender) {
        this.chatSender = chatSender;
    }

    /** What ATAK's Clear Content calls in the plugin. */
    public void setClearContent(final ClearContentRegistry.ClearContentListener clearContent) {
        this.clearContent = clearContent;
    }

    /**
     * What ATAK's send list does for a contact whose IP connector names a broadcast (a group
     * chat's): that broadcast, with contactUID and the given extras.
     */
    private static void sendToContact(final Intent intent) {
        final Contact contact = Contacts.getInstance().getContactByUuid(
                intent.getStringExtra("uid"));
        final Connector connector = contact instanceof IndividualContact
                ? ((IndividualContact) contact).getConnector(IpConnector.CONNECTOR_TYPE)
                : null;
        final String sendIntent = connector instanceof IpConnector
                ? ((IpConnector) connector).getSendIntent() : null;
        if (sendIntent == null || sendIntent.isEmpty()) {
            Log.w(TAG, "no contact with that uid whose IP connector names a broadcast");
            return;
        }
        final Intent send = new Intent(sendIntent);
        send.putExtra("contactUID", contact.getUid());
        copyExtras(intent, send);
        AtakBroadcast.getInstance().sendBroadcast(send);
        Log.d(TAG, "sent the contact's send broadcast");
    }

    private static void copyExtras(final Intent from, final Intent to) {
        final Bundle extras = from.getExtras();
        if (extras == null) {
            return;
        }
        for (final String key : extras.keySet()) {
            if (key.startsWith(EXTRA_PREFIX)) {
                to.putExtra(key.substring(EXTRA_PREFIX.length()), extras.getString(key));
            }
        }
    }

    /** {@code jid}, or else this device's own address: the self chat. */
    private static String orSelf(final String jid) {
        final XmppEngine engine = XmppEngine.get();
        final Account account = engine == null ? null : engine.getAccount();
        if (jid != null || account == null) {
            return jid;
        }
        return account.getJid().asBareJid().toString();
    }

    /** Dispatches locally the SA of a TAK user advertising an XMPP address. */
    private static void injectFakeContact(final String jid, final String callsign) {
        if (jid == null) {
            Log.w(TAG, "no JID for the fake contact");
            return;
        }
        final CoordinatedTime now = new CoordinatedTime();
        final GeoPoint center = MapView.getMapView().getCenterPoint().get();
        final CotEvent event = new CotEvent();
        event.setUID(FAKE_UID);
        event.setType("a-f-G-U-C");
        event.setHow("m-g");
        event.setTime(now);
        event.setStart(now);
        event.setStale(now.addMinutes(30));
        event.setPoint(new CotPoint(center.getLatitude(), center.getLongitude(),
                CotPoint.UNKNOWN, CotPoint.UNKNOWN, CotPoint.UNKNOWN));
        final CotDetail detail = new CotDetail("detail");
        final CotDetail contact = new CotDetail("contact");
        contact.setAttribute("callsign", callsign);
        contact.setAttribute("endpoint", "*:-1:stcp");
        contact.setAttribute("xmppUsername", jid);
        detail.addChild(contact);
        final CotDetail group = new CotDetail("__group");
        group.setAttribute("name", "Cyan");
        group.setAttribute("role", "Team Member");
        detail.addChild(group);
        event.setDetail(detail);
        CotMapComponent.getInternalDispatcher().dispatch(event);
        Log.d(TAG, "injected fake contact " + callsign + " with XMPP address " + jid);
    }

    /** Stores and notifies a message as if received from {@code from}. */
    private static void fakeIncoming(final XmppEngine engine, final String from,
            final String body) {
        final Conversation conversation = engine.openConversation(from);
        if (conversation == null || body == null) {
            Log.w(TAG, "no conversation with " + from + " or no body");
            return;
        }
        final Message message = new Message(conversation, body, Message.ENCRYPTION_NONE,
                Message.STATUS_RECEIVED);
        message.markUnread();
        conversation.add(message);
        final XmppConnectionService service = engine.getService();
        service.createMessageAsync(message);
        service.getNotificationService().push(message);
        service.updateConversationUi();
        Log.d(TAG, "fake message from " + from + ", unread now "
                + conversation.unreadCount());
    }
}
