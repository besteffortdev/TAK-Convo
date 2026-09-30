package com.atakmap.android.takconvo.plugin.xmpp;

import android.content.Context;

import eu.siacs.conversations.services.XmppConnectionService;

/**
 * Conversations' XmppConnectionService, created and driven by the plugin instead of the Android
 * service manager. Only lifecycle entry points are exposed; the service logic is upstream's.
 */
final class EmbeddedXmppService extends XmppConnectionService {

    void attach(final Context base) {
        attachBaseContext(base);
    }
}
