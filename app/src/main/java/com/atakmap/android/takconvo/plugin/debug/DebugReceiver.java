package com.atakmap.android.takconvo.plugin.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;

import com.atakmap.android.contact.Connector;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.ContactConnectorManager;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.XmppConnector;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.takconvo.plugin.TakConvoPlugin;
import com.atakmap.android.takconvo.plugin.ui.TakConvoPreferenceFragment;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.comms.CotService;
import com.atakmap.android.takconvo.plugin.xmpp.XmppEngine;
import com.atakmap.app.SettingsActivity;
import com.atakmap.app.preferences.PreferenceControl;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.time.CoordinatedTime;

import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Debug builds only: drive the engine from adb.
 *
 * <pre>
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_SET_PREF --es key K --es value V
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_PROVISION
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_SEND --es to J --es body B
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_ADD_TAK_SERVER \
 *     --es connect host:8089:ssl --es user U --es pass P
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_SHOW_ACCOUNT
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_OPEN_SETTINGS
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_SHOW_CHAT
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_CHAT_BACK
 *
 * # contacts integration; the JID defaults to this device's own (the self chat)
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_FAKE_CONTACT [--es jid J] [--es callsign C]
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_REMOVE_FAKE_CONTACT
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_OPEN_CONTACT
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_DUMP_CONTACT
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_FAKE_INCOMING [--es from J] --es body B
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_ATAK_BROADCAST --es action A
 * </pre>
 *
 * <p>The fake contact is a TAK user SA injected into this ATAK only (ATAK's internal dispatcher:
 * nothing is sent). A fake incoming message is stored locally as if received; nothing is sent
 * either.
 */
public final class DebugReceiver extends BroadcastReceiver {

    private static final String TAG = "TakConvo.Debug";
    private static final String PREFIX = "com.atakmap.android.takconvo.";
    public static final String ACTION_SET_PREF = PREFIX + "DEBUG_SET_PREF";
    public static final String ACTION_PROVISION = PREFIX + "DEBUG_PROVISION";
    public static final String ACTION_SEND = PREFIX + "DEBUG_SEND";
    public static final String ACTION_ADD_TAK_SERVER = PREFIX + "DEBUG_ADD_TAK_SERVER";
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

    /** the fake TAK user's UID */
    private static final String FAKE_UID = "TAKCONVO-DEBUG-CONTACT";

    private final Context context;
    /** the fake TAK user's XMPP address */
    private String fakeJid;

    private DebugReceiver(final Context context) {
        this.context = context;
    }

    public static DebugReceiver register(final Context context) {
        final DebugReceiver receiver = new DebugReceiver(context);
        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SET_PREF);
        filter.addAction(ACTION_PROVISION);
        filter.addAction(ACTION_SEND);
        filter.addAction(ACTION_ADD_TAK_SERVER);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        Log.d(TAG, "debug receiver registered");
        return receiver;
    }

    public void unregister() {
        context.unregisterReceiver(this);
    }

    @Override
    public void onReceive(final Context c, final Intent intent) {
        final String action = intent.getAction();
        final XmppEngine engine = XmppEngine.get();
        Log.d(TAG, "received " + action);
        if (ACTION_SET_PREF.equals(action)) {
            final String key = intent.getStringExtra("key");
            final String value = intent.getStringExtra("value");
            if (key != null) {
                AtakPreferences.getInstance(context).set(key, value);
                Log.d(TAG, "set " + key);
            }
        } else if (ACTION_PROVISION.equals(action) && engine != null) {
            engine.provision();
        } else if (ACTION_ADD_TAK_SERVER.equals(action)) {
            // same calls as ATAK's "add TAK server" dialog; credentials go to ATAK's auth DB
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
        } else if (ACTION_IMPORT_PREF.equals(action)) {
            // ATAK's own .pref import (what the import manager / mission packages use)
            final String path = intent.getStringExtra("path");
            final List<String> loaded = PreferenceControl.getInstance(context)
                    .loadSettings(new File(path));
            Log.d(TAG, "imported " + path + ": " + loaded);
        } else if (ACTION_DUMP_SELF_SA.equals(action)) {
            // the SA this device sends, built by ATAK's private CotMapComponent.getSelfEvent(int)
            try {
                final Method m = CotMapComponent.class.getDeclaredMethod("getSelfEvent",
                        int.class);
                m.setAccessible(true);
                Log.d(TAG, "self SA: " + m.invoke(CotMapComponent.getInstance(), 0));
            } catch (final Exception e) {
                Log.e(TAG, "unable to build self SA", e);
            }
        } else if (ACTION_SHOW_ACCOUNT.equals(action)) {
            AtakBroadcast.getInstance().sendBroadcast(
                    new Intent(TakConvoPreferenceFragment.ACTION_SHOW_ACCOUNT));
        } else if (ACTION_OPEN_SETTINGS.equals(action)) {
            SettingsActivity.start(TakConvoPreferenceFragment.TOOL_KEY, null);
        } else if (ACTION_SHOW_CHAT.equals(action)) {
            AtakBroadcast.getInstance().sendBroadcast(
                    new Intent(TakConvoPlugin.ACTION_SHOW_CHAT));
        } else if (ACTION_CHAT_BACK.equals(action)) {
            AtakBroadcast.getInstance().sendBroadcast(
                    new Intent(TakConvoPlugin.ACTION_CHAT_BACK));
        } else if (ACTION_SEND.equals(action) && engine != null) {
            final boolean ok = engine.sendMessage(intent.getStringExtra("to"),
                    intent.getStringExtra("body"));
            Log.d(TAG, "send " + (ok ? "queued" : "rejected"));
        } else if (ACTION_FAKE_CONTACT.equals(action)) {
            fakeJid = orSelf(intent.getStringExtra("jid"), engine);
            final String callsign = intent.getStringExtra("callsign");
            injectFakeContact(fakeJid, callsign == null ? "XMPP Test" : callsign);
        } else if (ACTION_REMOVE_FAKE_CONTACT.equals(action)) {
            // from its team group too: removeContactByUuid alone leaves it there, still counted
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
        } else if (ACTION_OPEN_CONTACT.equals(action)) {
            // what tapping the contact's XMPP connector does
            final boolean handled = CotMapComponent.getInstance().getContactConnectorMgr()
                    .initiateContact(XmppConnector.CONNECTOR_TYPE, FAKE_UID,
                            orSelf(fakeJid, engine));
            Log.d(TAG, "initiated XMPP contact: " + handled);
        } else if (ACTION_DUMP_CONTACT.equals(action)) {
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
        } else if (ACTION_ATAK_BROADCAST.equals(action)) {
            // e.g. com.atakmap.android.contact.CONTACT_LIST opens ATAK's contacts
            AtakBroadcast.getInstance().sendBroadcast(
                    new Intent(intent.getStringExtra("action")));
        } else if (ACTION_FAKE_INCOMING.equals(action) && engine != null) {
            fakeIncoming(engine, orSelf(intent.getStringExtra("from"), engine),
                    intent.getStringExtra("body"));
        }
    }

    private static String orSelf(final String jid, final XmppEngine engine) {
        if (jid != null || engine == null || engine.getAccount() == null) {
            return jid;
        }
        return engine.getAccount().getJid().asBareJid().toString();
    }

    /** SA of a TAK user advertising an XMPP address, handled as if it came from the network. */
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

    /** A message stored and notified as if it had been received from {@code from}. */
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
