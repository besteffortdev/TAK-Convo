package com.atakmap.android.takconvo.plugin.contacts;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.atakmap.android.contact.Connector;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.ContactConnectorManager;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.XmppConnector;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.navigation.NavButtonManager;
import com.atakmap.android.navigation.models.NavButtonModel;
import com.atakmap.android.takconvo.plugin.R;
import com.atakmap.android.takconvo.plugin.xmpp.XmppEngine;
import com.atakmap.coremap.log.Log;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.xmpp.Jid;

import im.conversations.android.xmpp.model.stanza.Presence;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * TAK Convo in ATAK's contacts.
 *
 * <p>A TAK user whose SA advertises an XMPP address ({@code <contact xmppUsername>}) gets an XMPP
 * connector in ATAK's contact list. ATAK's own handler for it starts an external XMPP app; this
 * one, registered ahead of it, opens the chat in TAK Convo instead, and gives the connector the
 * chat's unread count and the address's XMPP presence. ATAK adds those unread counts to the
 * contact's row and to its Contacts button, like GeoChat's.
 *
 * <p>The open XMPP group chats are contacts too, at the top of the list ({@link
 * XmppRoomContact}), so that ATAK's contact list has both the users and the group chats.
 *
 * <p>It also shows Conversations' total unread count on the plugin's toolbar button.
 *
 * <p>ATAK asks for connector features on its UI thread and on its unread count thread, so they
 * are answered from a snapshot, made on the main thread when Conversations reports a change.
 */
public final class XmppContacts extends ContactConnectorManager.ContactConnectorHandler
        implements XmppEngine.Listener {

    private static final String TAG = "TakConvo.Contacts";
    /** Conversations reports changes in bursts */
    private static final long REFRESH_DELAY_MS = 300;

    /** Opens the chat with an XMPP address. Called on the main thread. */
    public interface ChatOpener {
        void openChat(String address);
    }

    private final Context plugin;
    private final Context atak;
    private final XmppEngine engine;
    private final ChatOpener opener;
    /** of the plugin's toolbar button: its identifier */
    private final String toolbarReference;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = this::refresh;
    private final NavButtonManager.OnModelListChangedListener toolbarListener =
            new NavButtonManager.OnModelListChangedListener() {
                @Override
                public void onModelListChanged() {
                    // e.g. the toolbar button was just added, or the user moved it
                    handler.post(() -> updateToolbar(engine.getUnreadCount()));
                }
            };
    /** unread messages of the chats with each bare JID (lower case), 1:1 and group chats */
    private volatile Map<String, Integer> unread = Collections.emptyMap();
    /**
     * presence of each bare JID whose presence we are subscribed to, and of each group chat
     * (joined or not)
     */
    private volatile Map<String, Contact.UpdateStatus> presence = Collections.emptyMap();
    /** the group chats' contacts, by bare JID (lower case); main thread */
    private final Map<String, XmppRoomContact> rooms = new HashMap<>();
    private boolean started;

    public XmppContacts(final Context plugin, final Context atak, final XmppEngine engine,
            final ChatOpener opener) {
        this.plugin = plugin;
        this.atak = atak;
        this.engine = engine;
        this.opener = opener;
        this.toolbarReference = plugin.getPackageName();
    }

    /** Registers with ATAK's contact connectors and starts following Conversations. */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        CotMapComponent.getInstance().getContactConnectorMgr().addContactHandler(this);
        final NavButtonManager buttons = NavButtonManager.getInstance();
        if (buttons != null) {
            buttons.addModelListChangedListener(toolbarListener);
        }
        engine.addListener(this);
        refresh();
    }

    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        handler.removeCallbacksAndMessages(null);
        engine.removeListener(this);
        final CotMapComponent cot = CotMapComponent.getInstance();
        if (cot != null) {
            cot.getContactConnectorMgr().removeContactHandler(this);
        }
        final NavButtonManager buttons = NavButtonManager.getInstance();
        if (buttons != null) {
            buttons.removeModelListChangedListener(toolbarListener);
        }
        unread = Collections.emptyMap();
        presence = Collections.emptyMap();
        syncRooms(Collections.emptyMap());
        updateToolbar(0);
        updateContacts();
    }

    // --- XmppEngine.Listener ---

    @Override
    public void onXmppStateChanged() {
        handler.removeCallbacks(refreshTask);
        handler.postDelayed(refreshTask, REFRESH_DELAY_MS);
    }

    private void refresh() {
        if (!started) {
            return;
        }
        final Map<String, Integer> newUnread = new HashMap<>();
        final Map<String, Contact.UpdateStatus> newPresence = new HashMap<>();
        /* the open group chats: address to name */
        final Map<String, String> openRooms = new HashMap<>();
        final Account account = engine.getAccount();
        if (account != null) {
            try {
                for (final Conversation conversation : engine.getConversations()) {
                    if (conversation.getAccount() != account) {
                        continue;
                    }
                    final String key = key(conversation.getAddress());
                    if (conversation.getMode() == Conversation.MODE_MULTI) {
                        openRooms.put(conversation.getAddress().asBareJid().toString(),
                                String.valueOf(conversation.getName()));
                        newPresence.put(key, conversation.getMucOptions().online()
                                ? Contact.UpdateStatus.CURRENT : Contact.UpdateStatus.DEAD);
                    } else if (conversation.getMode() != Conversation.MODE_SINGLE) {
                        continue;
                    }
                    final int count = conversation.unreadCount();
                    if (count > 0) {
                        newUnread.put(key, count);
                    }
                }
                for (final eu.siacs.conversations.entities.Contact contact :
                        account.getRoster().getContacts()) {
                    if (contact.getOption(eu.siacs.conversations.entities.Contact.Options.TO)) {
                        newPresence.put(key(contact.getAddress()),
                                status(contact.getShownStatus()));
                    }
                }
            } catch (final RuntimeException e) {
                // e.g. the account's connection being replaced; the next change retries
                Log.w(TAG, "unable to read unread counts and presence", e);
                return;
            }
        }
        final boolean changed = !newUnread.equals(unread) || !newPresence.equals(presence);
        unread = newUnread;
        presence = newPresence;
        final boolean roomsChanged = syncRooms(openRooms);
        if (changed || roomsChanged) {
            Log.d(TAG, "unread " + newUnread + ", presence of " + newPresence.size()
                    + ", group chats " + rooms.size());
            updateContacts();
        }
        updateToolbar(engine.getUnreadCount());
    }

    /**
     * Makes the group chat contacts, at the top of ATAK's contact list, those of the open group
     * chats.
     *
     * @param open the open group chats: address to name
     * @return whether any contact was added, removed or renamed
     */
    private boolean syncRooms(final Map<String, String> open) {
        final Contacts contacts = Contacts.getInstance();
        if (contacts == null) {
            return false;
        }
        boolean changed = false;
        final Map<String, String> byKey = new HashMap<>();
        for (final Map.Entry<String, String> room : open.entrySet()) {
            byKey.put(room.getKey().toLowerCase(Locale.ROOT), room.getKey());
        }
        for (final Iterator<Map.Entry<String, XmppRoomContact>> i = rooms.entrySet().iterator();
                i.hasNext(); ) {
            final Map.Entry<String, XmppRoomContact> room = i.next();
            if (!byKey.containsKey(room.getKey())) {
                contacts.removeContact(room.getValue());
                i.remove();
                changed = true;
            }
        }
        for (final Map.Entry<String, String> room : byKey.entrySet()) {
            final String address = room.getValue();
            final String name = open.get(address);
            final XmppRoomContact existing = rooms.get(room.getKey());
            if (existing == null) {
                final XmppRoomContact contact = new XmppRoomContact(name, address);
                rooms.put(room.getKey(), contact);
                contacts.addContact(contacts.getRootGroup(), contact);
                changed = true;
            } else if (!name.equals(existing.getName())) {
                existing.setName(name);
                changed = true;
            }
        }
        return changed;
    }

    /** ATAK recounts its contacts' unread messages and redraws their rows. */
    private static void updateContacts() {
        final Contacts contacts = Contacts.getInstance();
        if (contacts != null) {
            contacts.updateTotalUnreadCount();
        }
    }

    private void updateToolbar(final int count) {
        final NavButtonManager buttons = NavButtonManager.getInstance();
        final NavButtonModel model = buttons == null ? null
                : buttons.getModelByReference(toolbarReference);
        if (model == null || model.getBadgeCount() == count) {
            return;
        }
        model.setBadgeCount(count);
        buttons.notifyModelChanged(model);
    }

    // --- ContactConnectorHandler ---

    @Override
    public boolean isSupported(final String connectorType) {
        return XmppConnector.CONNECTOR_TYPE.equals(connectorType);
    }

    @Override
    public boolean isSupported(final Connector connector) {
        return connector != null && isSupported(connector.getConnectionType());
    }

    @Override
    public boolean hasFeature(final ContactConnectorManager.ConnectorFeature feature) {
        return feature == ContactConnectorManager.ConnectorFeature.NotificationCount
                || feature == ContactConnectorManager.ConnectorFeature.Presence;
    }

    @Override
    public String getName() {
        return plugin.getString(R.string.app_name);
    }

    @Override
    public String getDescription() {
        return plugin.getString(R.string.takconvo_connector_description);
    }

    /** The XMPP connector was tapped: on the UI thread. */
    @Override
    public boolean handleContact(final String connectorType, final String contactUid,
            final String address) {
        Log.d(TAG, "chat with " + address + " (contact " + contactUid + ")");
        if (XmppEngine.bareJid(address) == null) {
            Toast.makeText(atak, plugin.getString(R.string.takconvo_invalid_xmpp_address,
                    address), Toast.LENGTH_SHORT).show();
        } else {
            opener.openChat(address);
        }
        // handled either way: ATAK's fallback would look for an external XMPP app
        return true;
    }

    @Override
    public Object getFeature(final String connectorType,
            final ContactConnectorManager.ConnectorFeature feature, final String contactUid,
            final String address) {
        final String key = key(XmppEngine.bareJid(address));
        if (key == null) {
            return null;
        }
        if (feature == ContactConnectorManager.ConnectorFeature.NotificationCount) {
            final Integer count = unread.get(key);
            return count == null ? 0 : count;
        } else if (feature == ContactConnectorManager.ConnectorFeature.Presence) {
            // null when we don't see its presence: no dot rather than "offline"
            return presence.get(key);
        }
        return null;
    }

    private static String key(final Jid jid) {
        return jid == null ? null : jid.asBareJid().toString().toLowerCase(Locale.ROOT);
    }

    /** ATAK colors a connector green, yellow or red by these */
    private static Contact.UpdateStatus status(final Presence.Availability availability) {
        switch (availability) {
            case CHAT:
            case ONLINE:
                return Contact.UpdateStatus.CURRENT;
            case AWAY:
            case XA:
            case DND:
                return Contact.UpdateStatus.STALE;
            default:
                return Contact.UpdateStatus.DEAD;
        }
    }
}
