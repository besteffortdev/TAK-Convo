package com.atakmap.android.takconvo.plugin.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;

import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.takconvo.plugin.Guard;
import com.atakmap.android.takconvo.plugin.SensitiveLog;
import com.atakmap.android.takconvo.plugin.config.XmppSettings;
import com.atakmap.coremap.log.Log;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.JidHelper;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.manager.BookmarkManager;
import eu.siacs.conversations.xmpp.manager.MultiUserChatManager;

import im.conversations.android.model.Bookmark;
import im.conversations.android.model.ImmutableBookmark;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Makes the ATAK callsign the XMPP nickname: the display name (XEP-0172 User Nickname) and the
 * nickname in group chats. A room where another occupant has the callsign keeps its nickname
 * until the callsign changes. Main thread only. See docs/03.
 */
final class CallsignNicknames {

    private static final String TAG = "TakConvo.Nicknames";
    /** Re-checks while a join is pending: a failed join isn't reported as a change. */
    private static final long RECHECK_MS = 3000;
    private static final int MAX_RECHECKS = 10;
    /** Per-room keys: the callsign taken there, and its last other nickname. */
    private static final String TAKEN = "|taken";
    private static final String NICK = "|nick";

    private final Context atakContext;
    private final XmppConnectionService service;
    private final Handler handler;
    private final Supplier<Account> account;
    private final SharedPreferences rooms;
    /** Room address to the callsign its bookmark nickname was set to. */
    private final Map<String, String> renamedBookmarks = new HashMap<>();
    /** Room address to the callsign it was asked to rename to. */
    private final Map<String, String> renamedRooms = new HashMap<>();
    private final Runnable recheck = Guard.wrap(TAG, "set the callsign as nickname", this::sync);
    private int rechecks;

    CallsignNicknames(final Context atakContext, final Context engineContext,
            final XmppConnectionService service, final Handler handler,
            final Supplier<Account> account) {
        this.atakContext = atakContext;
        this.service = service;
        this.handler = handler;
        this.account = account;
        this.rooms = engineContext.getSharedPreferences("room_nicknames", Context.MODE_PRIVATE);
    }

    void stop() {
        handler.removeCallbacks(recheck);
    }

    /** Applies the callsign while the account is online; called on every change. */
    void sync() {
        handler.removeCallbacks(recheck);
        final Account account = this.account.get();
        final SharedPreferences prefs = AtakPreferences.getInstance(atakContext).getSharedPrefs();
        if (account == null || !account.isOnlineAndConnected()
                || !XmppSettings.usesCallsign(prefs)) {
            return;
        }
        final String callsign = XmppSettings.atakCallsign(prefs);
        if (callsign == null) {
            return;
        }
        if (!callsign.equals(account.getDisplayName())) {
            SensitiveLog.d(TAG, "nickname " + account.getDisplayName() + " -> callsign "
                    + callsign);
            account.setDisplayName(callsign);
            service.databaseBackend.updateAccount(account);
            service.publishDisplayName(account);
            // the group chats that use the display name
            service.checkMucRequiresRename();
        }
        boolean joining = false;
        try {
            for (final Conversation conversation : service.getConversations()) {
                if (conversation.getAccount() == account
                        && conversation.getMode() == Conversation.MODE_MULTI
                        && conversation.getStatus() != Conversation.STATUS_ARCHIVED) {
                    joining |= syncRoom(account, conversation, callsign);
                }
            }
        } catch (final RuntimeException e) {
            // e.g. the conversations changing on another thread; the next change retries
            Log.w(TAG, "unable to check the group chats' nicknames", e);
        }
        if (!joining) {
            rechecks = 0;
        } else if (rechecks++ < MAX_RECHECKS) {
            handler.postDelayed(recheck, RECHECK_MS);
        }
    }

    /** Gives one room the callsign as nickname, or keeps its own; returns whether it's joining. */
    private boolean syncRoom(final Account account, final Conversation conversation,
            final String callsign) {
        final String room = conversation.getAddress().asBareJid().toString();
        if (callsign.equals(rooms.getString(room + TAKEN, null))) {
            return false;
        }
        final MucOptions options = conversation.getMucOptions();
        final MultiUserChatManager muc =
                account.getXmppConnection().getManager(MultiUserChatManager.class);
        if (!options.online()) {
            if (options.getError() == MucOptions.Error.NICK_IN_USE
                    && callsign.equals(options.getProposedNickPure())) {
                // joining with the callsign failed: join again with the room's last nickname
                final String previous = rooms.getString(room + NICK,
                        JidHelper.localPartOrFallback(account.getJid()));
                SensitiveLog.d(TAG, callsign + " taken in " + room + ", joining as " + previous);
                markTaken(room, callsign);
                muc.changeUsername(conversation, previous);
                return true;
            }
            return options.getError() == MucOptions.Error.NONE;
        }
        final String current = options.getActualNick();
        if (!callsign.equals(current)) {
            remember(room, current);
            if (heldByOther(options, callsign, account.getJid().asBareJid())) {
                SensitiveLog.d(TAG, callsign + " taken in " + room + ", keeping " + current);
                markTaken(room, callsign);
                setBookmarkNick(account, conversation, current);
                return false;
            }
        }
        final Bookmark bookmark = conversation.getBookmark();
        if (bookmark != null && bookmark.getNick() != null
                && !callsign.equals(bookmark.getNick())
                && !callsign.equals(renamedBookmarks.put(room, callsign))) {
            SensitiveLog.d(TAG, "bookmarked nickname in " + room + ": " + bookmark.getNick()
                    + " -> " + callsign);
            setBookmarkNick(account, conversation, callsign);
        }
        // joined before the display name changed: Conversations only renames on change
        if (!callsign.equals(current) && !callsign.equals(renamedRooms.put(room, callsign))) {
            muc.checkMucRequiresRename(conversation);
        }
        return false;
    }

    /**
     * Whether another account has the nickname in the room. The account's other sessions may
     * share it; an occupant whose account the room hides is left to the join to tell.
     */
    private static boolean heldByOther(final MucOptions options, final String nick,
            final Jid self) {
        for (final MucOptions.User user : options.getUsers()) {
            final Jid jid = user.getFullJid();
            final Jid real = user.getRealJid();
            if (jid != null && nick.equals(jid.getResource()) && real != null
                    && !self.equals(real.asBareJid())) {
                return true;
            }
        }
        return false;
    }

    /** The bookmark's nickname overrides the display name in that room. */
    private static void setBookmarkNick(final Account account, final Conversation conversation,
            final String nick) {
        final Bookmark bookmark = conversation.getBookmark();
        if (bookmark != null && !nick.equals(bookmark.getNick())) {
            account.getXmppConnection().getManager(BookmarkManager.class)
                    .create(ImmutableBookmark.builder().from(bookmark).nick(nick).build());
        }
    }

    private void remember(final String room, final String nick) {
        if (nick != null && !nick.equals(rooms.getString(room + NICK, null))) {
            rooms.edit().putString(room + NICK, nick).apply();
        }
    }

    private void markTaken(final String room, final String callsign) {
        rooms.edit().putString(room + TAKEN, callsign).apply();
    }
}
