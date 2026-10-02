package com.atakmap.android.takconvo.plugin.contacts;

import android.content.SharedPreferences;

import com.atakmap.android.contact.Connector;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.IpConnector;
import com.atakmap.android.contact.XmppConnector;
import com.atakmap.android.hierarchy.filters.FOVFilter;

/**
 * An XMPP group chat in ATAK's contact list, with one XMPP connector that {@link XmppContacts}
 * handles (open, unread count). Its IP connector makes it a recipient in ATAK's send list:
 * ATAK broadcasts {@link #SEND_ACTION} for it instead of sending CoT ({@link GroupChatSends}).
 */
final class XmppRoomContact extends IndividualContact implements FOVFilter.Filterable {

    private static final String UID_PREFIX = "takconvo.room:";
    /** What ATAK broadcasts when the user sends something to this contact. */
    static final String SEND_ACTION = "com.atakmap.android.takconvo.SEND_TO_GROUP_CHAT";

    private final String address;

    XmppRoomContact(final String name, final String address) {
        super(name, UID_PREFIX + address);
        this.address = address;
        // ATAK's chat room icon
        getExtras().putBoolean("fakeGroup", true);
        addConnector(new XmppConnector(address));
        addConnector(new IpConnector(SEND_ACTION));
    }

    String getAddress() {
        return address;
    }

    /** The group chat's address, or null if the contact isn't one of ours. */
    static String addressOf(final String uid) {
        return uid != null && uid.startsWith(UID_PREFIX) ? uid.substring(UID_PREFIX.length())
                : null;
    }

    @Override
    public Connector getDefaultConnector(final SharedPreferences prefs) {
        return getConnector(XmppConnector.CONNECTOR_TYPE);
    }

    /** Not on the map, so never hidden by the contact list's map-view filter. */
    @Override
    public boolean accept(final FOVFilter.MapState fov) {
        return true;
    }
}
