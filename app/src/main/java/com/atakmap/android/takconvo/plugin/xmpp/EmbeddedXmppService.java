package com.atakmap.android.takconvo.plugin.xmpp;

import android.content.Context;

import eu.siacs.conversations.services.XmppConnectionService;

/** Conversations' XmppConnectionService, driven by the plugin instead of Android. */
final class EmbeddedXmppService extends XmppConnectionService {

    void attach(final Context base) {
        attachBaseContext(base);
    }
}
