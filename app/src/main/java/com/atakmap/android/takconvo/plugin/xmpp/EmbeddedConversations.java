package com.atakmap.android.takconvo.plugin.xmpp;

import eu.siacs.conversations.Conversations;

/**
 * Stand-in for Conversations' Application object. Upstream code reaches it through
 * {@code context.getApplicationContext()}; inside ATAK that returns this instance (see
 * {@link EmbeddedContext#getApplicationContext()}).
 */
final class EmbeddedConversations extends Conversations {

    EmbeddedConversations(final EmbeddedContext base) {
        attachBaseContext(base);
    }

    void start() {
        onCreateEmbedded();
    }
}
