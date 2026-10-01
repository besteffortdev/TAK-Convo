package com.atakmap.android.takconvo.plugin.contacts;

import android.content.SharedPreferences;

import com.atakmap.android.contact.Connector;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.XmppConnector;

/**
 * An XMPP group chat in ATAK's contact list, with one XMPP connector that {@link XmppContacts}
 * handles (open, unread count).
 */
final class XmppRoomContact extends IndividualContact {

    private static final String UID_PREFIX = "takconvo.room:";

    private final String address;

    XmppRoomContact(final String name, final String address) {
        super(name, UID_PREFIX + address);
        this.address = address;
        // ATAK's chat room icon
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
