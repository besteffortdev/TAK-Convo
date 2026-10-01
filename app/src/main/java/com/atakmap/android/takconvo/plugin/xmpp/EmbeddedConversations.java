package com.atakmap.android.takconvo.plugin.xmpp;

import eu.siacs.conversations.Conversations;

/** Conversations' Application, returned by {@link EmbeddedContext#getApplicationContext()}. */
final class EmbeddedConversations extends Conversations {

    EmbeddedConversations(final EmbeddedContext base) {
        attachBaseContext(base);
    }

    void start() {
        onCreateEmbedded();
    }
}
