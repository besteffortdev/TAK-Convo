package com.atakmap.android.takconvo.plugin.xmpp;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.security.KeyChain;
import android.view.Display;

import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.takconvo.plugin.config.TrustSources;
import com.atakmap.android.takconvo.plugin.config.XmppSettings;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.comms.CotServiceRemote;
import com.atakmap.coremap.log.Log;

import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.ChannelDiscoveryService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.TakConvoCompat;
import eu.siacs.conversations.xmpp.Jid;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import javax.net.ssl.X509TrustManager;

/**
 * Runs Conversations' XMPP engine inside the ATAK process.
 *
 * <p>All methods must be called on the main thread, like the upstream service expects.
 */
public final class XmppEngine {

    private static final String TAG = "TakConvo.XmppEngine";

    /** ATAK preference whose value ATAK puts in this device's SA as contact@xmppUsername. */
    public static final String PREF_SA_XMPP_USERNAME = "saXmppUsername";

    /** Notified on the main thread when accounts or conversations change. */
    public interface Listener {
        void onXmppStateChanged();
    }

    private static XmppEngine instance;

    private final Context atakContext;
    private final EmbeddedContext context;
    private final EmbeddedXmppService service;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean dispatchPending = new AtomicBoolean();
    private final Runnable dispatchTask = () -> {
        dispatchPending.set(false);
        for (final Listener l : listeners) {
            l.onXmppStateChanged();
        }
    };
    /**
     * What the service tells its UI. Not registered as a UI listener: Conversations would then
     * believe it is always on screen, keep the server's client state active and silence its
     * notifications.
     */
    private final TakConvoCompat.Observer observer = new TakConvoCompat.Observer() {
        @Override
        public void onAccountsChanged() {
            dispatchChanged();
        }

        @Override
        public void onConversationsChanged() {
            dispatchChanged();
        }

        @Override
        public void onRosterChanged() {
            dispatchChanged();
        }

        @Override
        public void onUnreadCountChanged(final int count) {
            unreadCount = count;
            dispatchChanged();
        }
    };
    private final EmbeddedPendingIntents pendingIntents;
    private volatile int unreadCount;
    private final Runnable provisionTask = this::provision;
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener;
    private final CotServiceRemote.OutputsChangedListener takServerListener;
    private final BroadcastReceiver trustStoreReceiver;
    private XmppSettings settings;
    /** of the trusted CA set, see {@link TrustSources#fingerprint} */
    private String trustFingerprint;
    private XmppSettings.Problem lastProblem;
    private Jid provisionedJid;

    public static synchronized XmppEngine start(final Context atakContext,
            final Context pluginContext) {
        if (instance == null) {
            instance = new XmppEngine(atakContext, pluginContext);
        }
        return instance;
    }

    public static synchronized XmppEngine get() {
        return instance;
    }

    public static synchronized void shutdown() {
        if (instance != null) {
            instance.stop();
            instance = null;
        }
    }

    private XmppEngine(final Context atakContext, final Context pluginContext) {
        this.atakContext = atakContext.getApplicationContext();
        TakConvoCompat.EMBEDDED = true;

        context = new EmbeddedContext(atakContext, pluginContext);
        final EmbeddedConversations application = new EmbeddedConversations(context);
        context.setApplication(application);
        application.start();
        // decrypted attachments that were handed to other apps last time
        FileBackend.deleteShareableCopies(context);

        service = new EmbeddedXmppService();
        service.attach(context);
        context.setServiceRouter(new EmbeddedContext.ServiceRouter() {
            @Override
            public boolean handles(final Intent intent) {
                final ComponentName c = intent == null ? null : intent.getComponent();
                return c != null && (XmppConnectionService.class.getName()
                        .equals(c.getClassName())
                        || EmbeddedXmppService.class.getName().equals(c.getClassName()));
            }

            @Override
            public void startCommand(final Intent intent) {
                service.onStartCommand(intent, 0, 0);
            }

            @Override
            public IBinder bind(final Intent intent) {
                return service.onBind(intent);
            }
        });

        // notification taps and actions, alarms, notification icons, and what changed
        pendingIntents = new EmbeddedPendingIntents(this.atakContext, context);
        pendingIntents.register();
        TakConvoCompat.PENDING_INTENTS = pendingIntents;
        TakConvoCompat.NOTIFICATIONS = new EmbeddedNotifications(this.atakContext, pluginContext);
        TakConvoCompat.OBSERVER = observer;

        Log.d(TAG, "starting embedded Conversations engine");
        service.onCreate();
        // the service connects the stored accounts right away: trust has to be in place first
        XmppSettings.normalize(AtakPreferences.getInstance(this.atakContext).getSharedPrefs());
        settings = XmppSettings.load(this.atakContext);
        applyTrust();
        // When ATAK is killed, the server keeps its XMPP session detached for stream management
        // resumption, which we can't do after a restart. Openfire then doesn't answer a bind for
        // that same resource until it drops the old session (it closes the new stream as idle
        // first). A new resource per ATAK start avoids that; the old session expires by itself.
        for (final Account account : service.getAccounts()) {
            account.setResource(String.format("%s.%s",
                    eu.siacs.conversations.BuildConfig.APP_NAME, CryptoHelper.random(3)));
        }
        service.onStartCommand(null, 0, 0);
        provision();

        // Re-provision when the inputs change: a .pref import (or any edit) of our keys, and
        // TAK server connections being added, changed or connected. At ATAK startup the TAK
        // server credentials are usually not available yet when the plugin starts.
        prefListener = (prefs, key) -> {
            if (key != null && key.startsWith(XmppSettings.KEY_PREFIX)) {
                scheduleProvision();
            }
        };
        AtakPreferences.getInstance(this.atakContext).registerListener(prefListener);
        takServerListener = new CotServiceRemote.OutputsChangedListener() {
            @Override
            public void onCotOutputUpdated(final Bundle descBundle) {
                scheduleProvision();
            }

            @Override
            public void onCotOutputRemoved(final Bundle descBundle) {
                scheduleProvision();
            }
        };
        CommsMapComponent.getInstance().addOutputsChangedListener(takServerListener);

        // a CA added to (or removed from) the device, by the user or an MDM
        trustStoreReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context c, final Intent intent) {
                Log.d(TAG, "device trust store changed");
                scheduleProvision();
            }
        };
        final IntentFilter trustFilter = new IntentFilter(KeyChain.ACTION_TRUST_STORE_CHANGED);
        trustFilter.addAction(KeyChain.ACTION_KEYCHAIN_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.atakContext.registerReceiver(trustStoreReceiver, trustFilter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            this.atakContext.registerReceiver(trustStoreReceiver, trustFilter);
        }
    }

    /** Debounced: a .pref import changes several keys at once. */
    private void scheduleProvision() {
        mainHandler.removeCallbacks(provisionTask);
        mainHandler.postDelayed(provisionTask, 750);
    }

    private void stop() {
        Log.d(TAG, "stopping embedded Conversations engine");
        mainHandler.removeCallbacks(provisionTask);
        AtakPreferences.getInstance(atakContext).unregisterListener(prefListener);
        CommsMapComponent.getInstance().removeOutputsChangedListener(takServerListener);
        try {
            atakContext.unregisterReceiver(trustStoreReceiver);
        } catch (final IllegalArgumentException ignored) {
            // not registered
        }
        advertise(null);
        service.onTaskRemoved(null); // logs out and saves, as when the app is swiped away
        service.onDestroy();
        TakConvoCompat.OBSERVER = null;
        TakConvoCompat.NOTIFICATIONS = null;
        TakConvoCompat.PENDING_INTENTS = null;
        TakConvoCompat.CHANNEL_DISCOVERY_SERVER = null;
        pendingIntents.unregister();
        listeners.clear();
    }

    /**
     * Creates or updates the XMPP account from {@link XmppSettings}. Idempotent: called on
     * start, on changes to takconvo_xmpp_* preferences and TAK server connections. An
     * unchanged configuration leaves the connection alone.
     *
     * <p>The device has one active XMPP identity. When the configured JID changes, accounts
     * for other JIDs are disabled, never deleted, so their history survives a transient or
     * mistaken configuration.
     */
    public void provision() {
        mainHandler.removeCallbacks(provisionTask);
        XmppSettings.normalize(AtakPreferences.getInstance(atakContext).getSharedPrefs());
        settings = XmppSettings.load(atakContext);
        final boolean trustChanged = applyTrust();
        applyChannelDiscovery();
        Log.d(TAG, "provisioning " + settings);

        lastProblem = settings.problem();
        if (lastProblem == XmppSettings.Problem.NO_TAK_CREDENTIALS) {
            // TAK server credentials often arrive after the plugin starts, so an account on the
            // configured domain is kept as it is; accounts of another configuration are not
            Log.w(TAG, "not provisioning: " + lastProblem);
            disableAccountsExcept(a -> settings.domain != null
                    && settings.domain.equalsIgnoreCase(a.getDomain().toString()));
            unprovision();
            return;
        } else if (lastProblem != null) {
            // turned off, signed out or not configured: no account stays online
            Log.w(TAG, "not provisioning: " + lastProblem);
            disableAccountsExcept(a -> false);
            unprovision();
            return;
        }
        final Jid jid;
        try {
            jid = Jid.ofUserInput(settings.jid()).asBareJid();
        } catch (final IllegalArgumentException e) {
            Log.w(TAG, "not provisioning: invalid JID " + settings.jid());
            lastProblem = XmppSettings.Problem.INVALID_JID;
            disableAccountsExcept(a -> false);
            unprovision();
            return;
        }

        // upstream only honours an account's hostname/port when "extended connection settings"
        // is on; enable it exactly when a host override is provisioned
        // (the file PreferenceManager.getDefaultSharedPreferences uses; EmbeddedContext prefixes it)
        context.getSharedPreferences(context.getPackageName() + "_preferences",
                        Context.MODE_PRIVATE).edit()
                .putBoolean(AppSettings.SHOW_CONNECTION_OPTIONS, settings.host != null)
                .commit();

        disableAccountsExcept(a -> a.asBareJid().equals(jid));

        Account account = service.findAccountByJid(jid);
        if (account == null) {
            account = new Account(jid, settings.password);
            applyConnectionSettings(account);
            Log.d(TAG, "creating account " + jid);
            service.createAccount(account);
        } else {
            final boolean changed = !Objects.equals(account.getPassword(), settings.password)
                    || !Objects.equals(account.getHostname(), nullToEmpty(settings.host))
                    || account.getPort() != settings.port
                    || account.isOptionSet(Account.OPTION_DISABLED);
            if (changed) {
                account.setPassword(settings.password);
                applyConnectionSettings(account);
                account.setOption(Account.OPTION_DISABLED, false);
                Log.d(TAG, "updating account " + jid);
                service.updateAccount(account);
            } else if (trustChanged && !account.isOnlineAndConnected()) {
                // e.g. the CA its certificate needs was just provided: don't wait out the backoff
                Log.d(TAG, "trusted CAs changed, reconnecting " + jid);
                service.reconnectAccountInBackground(account);
            }
        }
        provisionedJid = jid;
        advertise(jid.toString());
        dispatchChanged();
    }

    /**
     * Makes Conversations trust the CAs {@link #settings} select.
     *
     * @return whether the set of trusted CAs changed since the last call
     */
    private boolean applyTrust() {
        final X509TrustManager trust = TrustSources.build(settings);
        TakConvoCompat.EXTRA_TRUST_MANAGER = trust;
        final String fingerprint = TrustSources.fingerprint(trust);
        final boolean changed = trustFingerprint != null && !trustFingerprint.equals(fingerprint);
        trustFingerprint = fingerprint;
        return changed;
    }

    /**
     * Where "Discover channels" looks: Conversations' own setting (the public directory or XMPP
     * servers), and the fork's hook for a server other than the account's. A server that isn't
     * a valid address falls back to the account's.
     */
    private void applyChannelDiscovery() {
        Jid server = null;
        if (settings.channelDiscovery == XmppSettings.ChannelDiscovery.SERVER
                && settings.channelServer != null) {
            try {
                server = Jid.ofUserInput(settings.channelServer);
            } catch (final IllegalArgumentException e) {
                Log.w(TAG, "invalid channel discovery server " + settings.channelServer);
            }
        }
        TakConvoCompat.CHANNEL_DISCOVERY_SERVER = server;
        final String method = settings.channelDiscovery == XmppSettings.ChannelDiscovery.PUBLIC
                ? ChannelDiscoveryService.Method.JABBER_NETWORK.name()
                : ChannelDiscoveryService.Method.LOCAL_SERVER.name();
        // the file PreferenceManager.getDefaultSharedPreferences uses; EmbeddedContext prefixes it
        context.getSharedPreferences(context.getPackageName() + "_preferences",
                        Context.MODE_PRIVATE).edit()
                .putString(AppSettings.CHANNEL_DISCOVERY_METHOD, method)
                .apply();
    }

    private void unprovision() {
        provisionedJid = null;
        advertise(null);
        dispatchChanged();
    }

    /**
     * Signs in with an XMPP login entered by the user (used when TAK server credentials are not
     * reused). A bare username gets the configured domain appended.
     *
     * @return false if that does not make a valid XMPP address; nothing is stored then
     */
    public boolean signIn(final String user, final String password) {
        final String username = user.trim();
        final String domain = settings == null ? null : settings.domain;
        if (username.isEmpty() || (!username.contains("@") && domain == null)) {
            return false;
        }
        try {
            Jid.ofUserInput(username.contains("@") ? username : username + "@" + domain);
        } catch (final IllegalArgumentException e) {
            return false;
        }
        Log.d(TAG, "signing in as " + username);
        final Account before = getAccount();
        final String previousPassword = before == null ? null : before.getPassword();
        XmppSettings.saveLogin(username, password);
        provision();
        final Account account = getAccount();
        if (account != null && account == before
                && Objects.equals(previousPassword, account.getPassword())
                && !account.isOptionSet(Account.OPTION_DISABLED)
                && !account.isOnlineAndConnected()) {
            // nothing changed, so provision() left it alone: try now rather than after the
            // backoff, e.g. the server is back or its CA was just imported
            service.reconnectAccountInBackground(account);
        }
        return true;
    }

    /** Forgets the XMPP login and disables its account (history is kept). */
    public void signOut() {
        final Account account = getAccount();
        Log.d(TAG, "signing out " + (account == null ? "" : account.getJid().asBareJid()));
        XmppSettings.clearLogin();
        if (account != null) {
            account.setPassword("");
        }
        provision();
    }

    /** Forces a reconnect of the provisioned account (e.g. from a "retry" button). */
    public void reconnect() {
        final Account account = getAccount();
        if (account != null) {
            service.reconnectAccountInBackground(account);
        }
    }

    /** Disables (never deletes) every enabled account whose bare JID {@code keep} rejects. */
    private void disableAccountsExcept(final Predicate<Jid> keep) {
        for (final Account other : new ArrayList<>(service.getAccounts())) {
            if (!keep.test(other.getJid().asBareJid())
                    && !other.isOptionSet(Account.OPTION_DISABLED)) {
                Log.d(TAG, "disabling account of a previous configuration " + other.getJid());
                other.setOption(Account.OPTION_DISABLED, true);
                service.updateAccount(other);
            }
        }
    }

    private void applyConnectionSettings(final Account account) {
        account.setHostname(nullToEmpty(settings.host));
        account.setPort(settings.port);
    }

    /** Publishes (or clears) this device's XMPP address in its SA, via ATAK's own preference. */
    private void advertise(final String jid) {
        final AtakPreferences prefs = AtakPreferences.getInstance(atakContext);
        if (jid == null) {
            prefs.getSharedPrefs().edit().remove(PREF_SA_XMPP_USERNAME).apply();
        } else {
            prefs.set(PREF_SA_XMPP_USERNAME, jid);
        }
        Log.d(TAG, "advertising " + PREF_SA_XMPP_USERNAME + "=" + jid);
    }

    /** @return the account of the current configuration, or null if none is provisioned */
    public Account getAccount() {
        return provisionedJid == null ? null : service.findAccountByJid(provisionedJid);
    }

    public XmppConnectionService getService() {
        return service;
    }

    /**
     * A context for one of Conversations' activities: ATAK's identity, the plugin's resources
     * for {@code override}, and the engine's storage and service routing.
     */
    public Context newUiContext(final Display display, final Configuration override) {
        return context.forUi(display, override);
    }

    /** Conversations' Application object, which its activities expect to be attached to. */
    public Application getApplication() {
        return (Application) context.getApplicationContext();
    }

    /** @return null if provisioning succeeded, otherwise why it did not */
    public XmppSettings.Problem getProblem() {
        return lastProblem;
    }

    public XmppSettings getSettings() {
        return settings;
    }

    /** Sends a plain-text 1:1 message. @return false if there is no account or the JID is bad */
    public boolean sendMessage(final String to, final String body) {
        final Account account = getAccount();
        if (account == null || to == null || body == null || body.isEmpty()) {
            return false;
        }
        final Jid jid;
        try {
            jid = Jid.ofUserInput(to).asBareJid();
        } catch (final IllegalArgumentException e) {
            return false;
        }
        final Conversation conversation =
                service.findOrCreateConversation(account, jid, false, false);
        final Message message = new Message(conversation, body, Message.ENCRYPTION_NONE);
        service.sendMessage(message);
        Log.d(TAG, "sent message to " + jid);
        return true;
    }

    /**
     * The 1:1 chat of the provisioned account with an XMPP address, created if there is none.
     *
     * @return null if no account is provisioned or the address is not a valid JID
     */
    public Conversation openConversation(final String address) {
        final Account account = getAccount();
        final Jid jid = bareJid(address);
        if (account == null || jid == null) {
            return null;
        }
        return service.findOrCreateConversation(account, jid, false, true);
    }

    /** @return the bare JID of an address as typed or advertised, or null if it isn't one */
    public static Jid bareJid(final String address) {
        if (address == null || address.trim().isEmpty()) {
            return null;
        }
        try {
            return Jid.ofUserInput(address.trim()).asBareJid();
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    public List<Conversation> getConversations() {
        return service.getConversations();
    }

    public void addListener(final Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(final Listener listener) {
        listeners.remove(listener);
    }

    /**
     * The count of unread messages Conversations would show on its launcher icon.
     */
    public int getUnreadCount() {
        return unreadCount;
    }

    // upstream reports changes from worker threads, often many at once (e.g. catching up)
    private void dispatchChanged() {
        if (dispatchPending.compareAndSet(false, true)) {
            mainHandler.post(dispatchTask);
        }
    }

    private static String nullToEmpty(final String s) {
        return s == null ? "" : s;
    }
}
