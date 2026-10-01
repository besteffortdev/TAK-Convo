package com.atakmap.android.takconvo.plugin.contacts;

import android.content.SharedPreferences;

import com.atakmap.android.contact.Connector;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.XmppConnector;

/**
 * An XMPP group chat at the top of ATAK's contact list, next to ATAK's own "All Chat Rooms".
 * Its only connector is an XMPP connector for the room's address, which {@link XmppContacts}
 * handles: tapping the contact opens the group chat, and the connector carries its unread count.
 */
final class XmppRoomContact extends IndividualContact {

    private static final String UID_PREFIX = "takconvo.room:";

    private final String address;

    XmppRoomContact(final String name, final String address) {
        super(name, UID_PREFIX + address);
        this.address = address;
        // ATAK's chat room icon, as for its own "All Chat Rooms"
        getExtras().putBoolean("fakeGroup", true);
        addConnector(new XmppConnector(address));
    }

    String getAddress() {
        return address;
    }

    @Override
    public Connector getDefaultConnector(final SharedPreferences prefs) {
        return getConnector(XmppConnector.CONNECTOR_TYPE);
    }
}
