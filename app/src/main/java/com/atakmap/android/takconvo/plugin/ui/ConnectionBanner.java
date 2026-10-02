package com.atakmap.android.takconvo.plugin.ui;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.atakmap.android.takconvo.plugin.xmpp.XmppEngine;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;

/**
 * The strip above the chat pane's screens while the account isn't online. Conversations shows
 * nothing while it connects, so a first sign-in looked like an empty chat list. A tap shows
 * the account pane, which has the details.
 */
public final class ConnectionBanner implements XmppEngine.Listener {

    private final Context ui;
    private final XmppEngine engine;
    private final View root;
    private final View progress;
    private final View icon;
    private final TextView text;

    public ConnectionBanner(final Context atakActivity, final XmppEngine engine,
            final Runnable showAccount) {
        final Display display = ((WindowManager) atakActivity
                .getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        // scaled like the screens below it
        this.ui = new ContextThemeWrapper(
                engine.newUiContext(display, UiScale.override(atakActivity)),
                R.style.Theme_Conversations3_Dark);
        this.engine = engine;
        root = ConversationsInflater.inflate(ui,
                com.atakmap.android.takconvo.plugin.R.layout.takconvo_connection_banner, null,
                false);
        progress = root.findViewById(
                com.atakmap.android.takconvo.plugin.R.id.takconvo_connection_progress);
        icon = root.findViewById(
                com.atakmap.android.takconvo.plugin.R.id.takconvo_connection_icon);
        text = root.findViewById(
                com.atakmap.android.takconvo.plugin.R.id.takconvo_connection_text);
        root.setOnClickListener(v -> showAccount.run());
        refresh();
    }

    public View getView() {
        return root;
    }

    @Override
    public void onXmppStateChanged() {
        refresh();
    }

    private void refresh() {
        final Account account = engine.getAccount();
        // no account: the account pane shows instead of the chats
        if (account == null || account.isOnlineAndConnected()) {
            root.setVisibility(View.GONE);
            return;
        }
        final Account.State status = account.getStatus();
        final boolean connecting = status == Account.State.CONNECTING
                || status == Account.State.OFFLINE || status == Account.State.ONLINE;
        progress.setVisibility(connecting ? View.VISIBLE : View.GONE);
        icon.setVisibility(connecting ? View.GONE : View.VISIBLE);
        text.setText(connecting
                ? ui.getString(com.atakmap.android.takconvo.plugin.R.string
                        .takconvo_banner_connecting)
                : ui.getString(com.atakmap.android.takconvo.plugin.R.string
                        .takconvo_banner_problem, ui.getString(status.getReadableId())));
        root.setVisibility(View.VISIBLE);
    }
}
