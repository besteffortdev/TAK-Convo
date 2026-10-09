package com.atakmap.android.takconvo.plugin.xmpp;

import android.app.Activity;
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
import com.atakmap.android.takconvo.plugin.Guard;
import com.atakmap.android.takconvo.plugin.SensitiveLog;
import com.atakmap.android.takconvo.plugin.config.AppConfig;
import com.atakmap.android.takconvo.plugin.config.ConversationsSettings;
import com.atakmap.android.takconvo.plugin.config.PrivateFiles;
import com.atakmap.android.takconvo.plugin.config.ServerIdentity;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import javax.net.ssl.X509TrustManager;

/** Runs Conversations' XMPP engine inside the ATAK process. Main thread only. */
public final class XmppEngine {

    private static final String TAG = "TakConvo.XmppEngine";
    /** A .pref import changes many keys at once: act once they're all in. */
    private static final long DEBOUNCE_MS = 750;
    /** {@link PrivateFiles} name of the server the user approved. */
    private static final String APPROVED_SERVER = "approved_server";

    /** ATAK preference that ATAK puts in this device's SA as contact@xmppUsername. */
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
    private final Runnable dispatchTask = this::dispatch;
    private final CallsignNicknames nicknames;
    /**
     * The service's changes. Not a UI listener: Conversations would think it is always on
     * screen, keep the client state active and silence notifications.
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
    private final KeystoreCredentials credentials = new KeystoreCredentials();
    private volatile int unreadCount;
    private final Runnable provisionTask = this::provision;
    private final Runnable conversationsSettingsTask;
    /** Reads ATAK's credential and certificate stores, which are on disk. */
    private final ExecutorService loader =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "TakConvo.Provision"));
    /** The latest provisioning; an older one's result is dropped. */
    private int provisionRun;
    /** The stored accounts may connect: the first provisioning set their trust and approval. */
    private boolean connectionsReleased;
    private boolean stopped;
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener;
    private final CotServiceRemote.OutputsChangedListener takServerListener;
    private final BroadcastReceiver trustStoreReceiver;
    private XmppSettings settings;
    /** See {@link TrustSources#fingerprint}. */
    private String trustFingerprint;
    private XmppSettings.Problem lastProblem;
    private Jid provisionedJid;
    /** Where the user agreed the credentials may go; see {@link #isApproved}. */
    private ServerIdentity approvedServer;
    /** The server the settings name while it waits for the user's approval. */
    private ServerIdentity pendingServer;
    /** The user's sign-in approves the server it connects to. */
    private boolean approveNext;
    /** The server refused the TAK server password the user entered; asked again since. */
    private boolean takPasswordRefused;
    /** What the MDM sets; read on the provisioning thread too. */
    private volatile AppConfig appConfig;
    private boolean appConfigReading;
    /** Read again once the read in progress is done. */
    private boolean appConfigAgain;
    /** The MDM may have changed it while ATAK was in the background. */
    private final Application.ActivityLifecycleCallbacks resumeCallbacks;

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

    /**
     * Deletes what TAK Convo keeps on the device: Conversations' database (messages, contacts,
     * OMEMO keys), files and settings, its notifications, the credentials key, the approved
     * server, the XMPP login and the TAK server passwords the user entered. For ATAK's Clear
     * Content, after {@link #shutdown}; any thread.
     */
    public static void wipe(final Context atakContext) {
        final Context atak = atakContext.getApplicationContext();
        EmbeddedNotifications.cancelAll(atak);
        EmbeddedContext.deleteAll(atak);
        // an earlier copy of the database can't give the password away any more
        KeystoreCredentials.deleteKey();
        EmbeddedContext.deleteRecursively(PrivateFiles.dir(atak));
        XmppSettings.clearLogin();
        XmppSettings.clearTakPasswords();
        Log.i(TAG, "TAK Convo's data deleted");
    }

    private XmppEngine(final Context atakContext, final Context pluginContext) {
        this.atakContext = atakContext.getApplicationContext();
        TakConvoCompat.EMBEDDED = true;

        context = new EmbeddedContext(atakContext, pluginContext);
        final EmbeddedConversations application = new EmbeddedConversations(context);
        context.setApplication(application);
        application.start();
        // decrypted attachments handed to other apps last time
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

        nicknames = new CallsignNicknames(this.atakContext, context, service, mainHandler,
                this::getAccount);
        pendingIntents = new EmbeddedPendingIntents(this.atakContext, context);
        pendingIntents.register();
        TakConvoCompat.PENDING_INTENTS = pendingIntents;
        TakConvoCompat.NOTIFICATIONS = new EmbeddedNotifications(this.atakContext, pluginContext);
        TakConvoCompat.OBSERVER = observer;
        // before the service reads the accounts
        TakConvoCompat.CREDENTIALS = credentials;

        Log.d(TAG, "starting embedded Conversations engine");
        // the MDM's values as last read, put back before anything reads the preferences: a
        // .pref import may have changed them while the plugin wasn't running
        appConfig = AppConfig.restore(this.atakContext);
        appConfig.applyTo(AtakPreferences.getInstance(this.atakContext).getSharedPrefs(),
                AppConfig.NONE);
        ConversationsSettings.apply(AtakPreferences.getInstance(this.atakContext).getSharedPrefs(),
                defaultPreferences());
        // the stored accounts wait for the first provisioning, which installs the trust
        // manager and checks the server's approval; it reads in the background (provision())
        TakConvoCompat.HOLD_CONNECTIONS = true;
        service.onCreate();
        if (credentials.sawPlaintext()) {
            // stored by a version that didn't encrypt them
            Log.i(TAG, "encrypting the stored credentials");
            for (final Account account : service.getAccounts()) {
                service.databaseBackend.updateAccount(account);
            }
        }
        // a few hundred bytes
        approvedServer = ServerIdentity.parse(PrivateFiles.read(this.atakContext,
                APPROVED_SERVER));
        // a new resource per start: Openfire holds the previous session for resumption and
        // doesn't answer a bind of the same resource until it drops it
        for (final Account account : service.getAccounts()) {
            account.setResource(String.format("%s.%s",
                    eu.siacs.conversations.BuildConfig.APP_NAME, CryptoHelper.random(3)));
        }

        // re-provision on our preferences (e.g. a .pref import) and TAK server changes; the
        // TAK credentials are often not there yet when the plugin starts
        final SharedPreferences atakPrefs =
                AtakPreferences.getInstance(this.atakContext).getSharedPrefs();
        conversationsSettingsTask =
                () -> ConversationsSettings.apply(atakPrefs, defaultPreferences());
        prefListener = (prefs, key) -> {
            if (key == null) {
                return;
            }
            if (appConfig.enforce(prefs, key)) {
                // the MDM's value is back, and its change comes next
                return;
            }
            if (key.startsWith(XmppSettings.KEY_PREFIX)) {
                scheduleProvision();
            } else if (key.startsWith(ConversationsSettings.PREFIX)) {
                // once for a .pref import's keys, not once per key
                mainHandler.removeCallbacks(conversationsSettingsTask);
                mainHandler.postDelayed(conversationsSettingsTask, DEBOUNCE_MS);
            } else if (XmppSettings.KEY_ATAK_CALLSIGN.equals(key)) {
                mainHandler.post(Guard.wrap(TAG, "set the callsign as nickname", nicknames::sync));
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

        // a CA added to or removed from the device
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

        // Android tells only the plugin's own process of changes, which isn't running
        resumeCallbacks = new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(final Activity activity) {
                refreshAppConfig();
            }

            // only resumes matter

            @Override
            public void onActivityCreated(final Activity activity, final Bundle state) {
            }

            @Override
            public void onActivityStarted(final Activity activity) {
            }

            @Override
            public void onActivityPaused(final Activity activity) {
            }

            @Override
            public void onActivityStopped(final Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(final Activity activity,
                    final Bundle state) {
            }

            @Override
            public void onActivityDestroyed(final Activity activity) {
            }
        };
        if (this.atakContext instanceof Application) {
            ((Application) this.atakContext).registerActivityLifecycleCallbacks(resumeCallbacks);
        }
        // ATAK's credential store and certificate database, the Android CA store: not on the
        // main thread while ATAK starts
        provision();
        refreshAppConfig();
    }

    /** Debounced: a .pref import changes several keys at once. */
    private void scheduleProvision() {
        mainHandler.removeCallbacks(provisionTask);
        mainHandler.postDelayed(provisionTask, DEBOUNCE_MS);
    }

    private void stop() {
        Log.d(TAG, "stopping embedded Conversations engine");
        stopped = true;
        loader.shutdownNow();
        // provisioning, dispatches, nickname syncs: they would act on a stopped service
        mainHandler.removeCallbacksAndMessages(null);
        nicknames.stop();
        if (atakContext instanceof Application) {
            ((Application) atakContext).unregisterActivityLifecycleCallbacks(resumeCallbacks);
        }
        AtakPreferences.getInstance(atakContext).unregisterListener(prefListener);
        CommsMapComponent.getInstance().removeOutputsChangedListener(takServerListener);
        try {
            atakContext.unregisterReceiver(trustStoreReceiver);
        } catch (final IllegalArgumentException e) {
            Log.w(TAG, "trust store receiver was not registered");
        }
        advertise(null);
        service.onTaskRemoved(null); // logs out and saves, as when the app is swiped away
        service.onDestroy();
        TakConvoCompat.OBSERVER = null;
        // CREDENTIALS stays: the logouts run on and may still save their accounts
        TakConvoCompat.NOTIFICATIONS = null;
        TakConvoCompat.PENDING_INTENTS = null;
        TakConvoCompat.CHANNEL_DISCOVERY_SERVER = null;
        TakConvoCompat.HOLD_CONNECTIONS = false;
        pendingIntents.unregister();
        listeners.clear();
    }

    /**
     * Re-reads {@link XmppSettings} in the background, then updates the account
     * ({@link #apply}). Settings, TAK server and trust store changes come here, often.
     */
    public void provision() {
        mainHandler.removeCallbacks(provisionTask);
        if (stopped) {
            return;
        }
        final int run = ++provisionRun;
        loader.execute(() -> {
            final Loaded loaded;
            try {
                loaded = load();
            } catch (final RuntimeException | LinkageError e) {
                // uncaught on this thread, it would take ATAK down
                Log.e(TAG, "unable to read the XMPP settings", e);
                return;
            }
            mainHandler.post(() -> {
                if (run == provisionRun && !stopped) {
                    Guard.run(TAG, "apply the XMPP settings", () -> apply(loaded));
                }
            });
        });
    }

    /** As {@link #provision}, at once on the main thread: for the user's sign-in and out. */
    private void provisionNow() {
        mainHandler.removeCallbacks(provisionTask);
        // drops the result of one still in the background
        provisionRun++;
        Guard.run(TAG, "apply the XMPP settings", () -> apply(load()));
    }

    /** What provisioning reads: the settings, the CAs they trust, the server they name. */
    private static final class Loaded {
        final XmppSettings settings;
        final X509TrustManager trust;
        final String trustFingerprint;
        /** Null without a domain. */
        final ServerIdentity server;

        Loaded(final XmppSettings settings, final X509TrustManager trust) {
            this.settings = settings;
            this.trust = trust;
            this.trustFingerprint = TrustSources.fingerprint(trust);
            this.server = ServerIdentity.of(settings);
        }
    }

    /** Reads the settings and builds their trust manager; disk work, any thread. */
    private Loaded load() {
        final SharedPreferences prefs = AtakPreferences.getInstance(atakContext).getSharedPrefs();
        XmppSettings.importLogin(prefs, !appConfig.managesLogin());
        XmppSettings.normalize(prefs);
        final XmppSettings loaded = XmppSettings.load(atakContext);
        return new Loaded(loaded, TrustSources.build(loaded));
    }

    /**
     * Applies the settings read ({@link #applySettings}). The first time, the stored accounts
     * may connect, now that their trust and the server's approval are in place.
     */
    private void apply(final Loaded loaded) {
        applySettings(loaded);
        if (!connectionsReleased) {
            connectionsReleased = true;
            TakConvoCompat.HOLD_CONNECTIONS = false;
            Log.d(TAG, "settings applied, connecting the accounts");
            service.onStartCommand(null, 0, 0);
        }
    }

    /**
     * Creates or updates the XMPP account from the settings read; an unchanged configuration
     * leaves the connection alone. Accounts of another address are disabled, never deleted, so
     * their history survives a mistaken configuration.
     */
    private void applySettings(final Loaded loaded) {
        settings = loaded.settings;
        applyChannelDiscovery();
        SensitiveLog.d(TAG, "provisioning " + settings);

        lastProblem = settings.problem();
        final boolean waitsForTak = lastProblem == XmppSettings.Problem.NO_TAK_CREDENTIALS
                || lastProblem == XmppSettings.Problem.NO_TAK_PASSWORD;
        if ((lastProblem == null || waitsForTak) && !isApproved(loaded.server)) {
            // a .pref file can change where the credentials go: the user approves it first,
            // and before being asked for a TAK server password to send there
            Log.w(TAG, "not provisioning: the server settings changed");
            SensitiveLog.d(TAG, "waiting for approval of " + loaded.server);
            lastProblem = XmppSettings.Problem.SERVER_UNCONFIRMED;
            pendingServer = loaded.server;
            applyTrust(null, TrustSources.fingerprint(null));
            disableAccountsExcept(a -> false);
            unprovision();
            return;
        }
        pendingServer = null;
        final boolean trustChanged = applyTrust(loaded.trust, loaded.trustFingerprint);

        if (lastProblem == XmppSettings.Problem.NO_TAK_PASSWORD
                && bareJid(settings.jid()) == null) {
            // no use asking for the password of a username that isn't an address
            lastProblem = XmppSettings.Problem.INVALID_JID;
        }
        if (lastProblem != XmppSettings.Problem.NO_TAK_PASSWORD) {
            takPasswordRefused = false;
        }
        if (lastProblem == XmppSettings.Problem.NO_TAK_CREDENTIALS) {
            // the TAK credentials often come later: keep an account on the configured domain
            Log.w(TAG, "not provisioning: " + lastProblem);
            disableAccountsExcept(a -> settings.domain != null
                    && settings.domain.equalsIgnoreCase(a.getDomain().toString()));
            unprovision();
            return;
        } else if (lastProblem != null) {
            // turned off, signed out, not configured, or the user is asked for the TAK
            // password: no account connects with a password stored before
            Log.w(TAG, "not provisioning: " + lastProblem);
            disableAccountsExcept(a -> false);
            unprovision();
            return;
        }
        final Jid jid;
        try {
            jid = Jid.ofUserInput(settings.jid()).asBareJid();
        } catch (final IllegalArgumentException e) {
            Log.w(TAG, "not provisioning: invalid JID");
            lastProblem = XmppSettings.Problem.INVALID_JID;
            disableAccountsExcept(a -> false);
            unprovision();
            return;
        }

        // upstream uses an account's host and port only with "extended connection settings" on
        defaultPreferences().edit()
                .putBoolean(AppSettings.SHOW_CONNECTION_OPTIONS, settings.host != null)
                .apply();

        disableAccountsExcept(a -> a.asBareJid().equals(jid));

        Account account = service.findAccountByJid(jid);
        if (account == null) {
            account = new Account(jid, settings.password);
            applyConnectionSettings(account);
            SensitiveLog.d(TAG, "creating account " + jid);
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
                SensitiveLog.d(TAG, "updating account " + jid);
                service.updateAccount(account);
            } else if (trustChanged && !account.isOnlineAndConnected()) {
                // e.g. the CA it needs was just added: don't wait out the backoff
                SensitiveLog.d(TAG, "trusted CAs changed, reconnecting " + jid);
                service.reconnectAccountInBackground(account);
            }
        }
        provisionedJid = jid;
        advertise(jid.toString());
        dispatchChanged();
    }

    /** The file PreferenceManager.getDefaultSharedPreferences uses, as EmbeddedContext names it. */
    private SharedPreferences defaultPreferences() {
        return context.getSharedPreferences(context.getPackageName() + "_preferences",
                Context.MODE_PRIVATE);
    }

    /** Makes Conversations trust the selected CAs; returns whether they changed. */
    private boolean applyTrust(final X509TrustManager trust, final String fingerprint) {
        TakConvoCompat.EXTRA_TRUST_MANAGER = trust;
        final boolean changed = trustFingerprint != null && !trustFingerprint.equals(fingerprint);
        trustFingerprint = fingerprint;
        return changed;
    }

    /**
     * Whether the credentials may go to {@code server}: the user approved it, or is signing in
     * to it, or the MDM set it. Approves on its own the one an account already logs in to, the
     * first time: that account was set up before approvals existed.
     */
    private boolean isApproved(final ServerIdentity server) {
        if (server == null || server.equals(approvedServer)) {
            return true;
        }
        if (server.takServer == null && server.sameServer(approvedServer)) {
            // no TAK server credentials yet: nothing new goes to the server
            return true;
        }
        if (approveNext || appConfig.approves(server)
                || (approvedServer == null && loggedInTo(server))
                || approvedWithoutTakServer(server)) {
            approve(server);
            return true;
        }
        return false;
    }

    /**
     * The approval doesn't name the TAK server whose credentials go there: an older version
     * saved it, or it came before any TAK credentials. The first TAK server seen goes with it.
     */
    private boolean approvedWithoutTakServer(final ServerIdentity server) {
        return approvedServer != null && approvedServer.takCredentials
                && approvedServer.takServer == null && server.sameServer(approvedServer);
    }

    /** Whether an enabled account has logged in to that domain, host and port. */
    private boolean loggedInTo(final ServerIdentity server) {
        for (final Account account : service.getAccounts()) {
            if (!account.isOptionSet(Account.OPTION_DISABLED)
                    && account.isOptionSet(Account.OPTION_LOGGED_IN_SUCCESSFULLY)
                    && server.domain.equalsIgnoreCase(account.getDomain().toString())
                    && nullToEmpty(server.host).equalsIgnoreCase(
                            nullToEmpty(account.getHostname()))
                    && server.port == account.getPort()) {
                return true;
            }
        }
        return false;
    }

    private void approve(final ServerIdentity server) {
        approvedServer = server;
        SensitiveLog.d(TAG, "approved " + server);
        // a few hundred bytes, on the user's tap or sign-in
        if (!PrivateFiles.write(atakContext, APPROVED_SERVER, server.serialize())) {
            Log.w(TAG, "the approved server will be asked again after a restart");
        }
    }

    /**
     * Reads the MDM's configuration again, in the background, and applies it if it changed.
     * At start and each time one of ATAK's screens shows.
     */
    public void refreshAppConfig() {
        if (stopped) {
            return;
        }
        if (appConfigReading) {
            // that read may have started before the change
            appConfigAgain = true;
            return;
        }
        appConfigReading = true;
        loader.execute(() -> {
            AppConfig fresh = null;
            try {
                final Bundle managed = AppConfig.read(atakContext);
                if (managed != null) {
                    final AppConfig current = appConfig;
                    fresh = AppConfig.load(atakContext, managed, current);
                    if (!fresh.equals(current)) {
                        fresh.save(atakContext);
                    }
                }
            } catch (final RuntimeException | LinkageError e) {
                // uncaught on this thread, it would take ATAK down
                Log.e(TAG, "unable to read the managed configuration", e);
            }
            final AppConfig result = fresh;
            mainHandler.post(() -> {
                appConfigReading = false;
                if (result != null && !stopped) {
                    applyAppConfig(result);
                }
                if (appConfigAgain) {
                    appConfigAgain = false;
                    refreshAppConfig();
                }
            });
        });
    }

    private void applyAppConfig(final AppConfig fresh) {
        final AppConfig previous = appConfig;
        appConfig = fresh;
        // also when unchanged: puts back what changed while the plugin wasn't listening
        fresh.applyTo(AtakPreferences.getInstance(atakContext).getSharedPrefs(), previous);
        if (!fresh.equals(previous) || fresh.savedLogin()) {
            Log.i(TAG, fresh.isEmpty() ? "no managed configuration"
                    : "managed configuration changed");
            // e.g. a server it approves, a login it stored
            scheduleProvision();
            dispatchChanged();
        }
    }

    /** Debug builds: replaces the values added to the MDM's, then reads them. */
    public void debugSetAppConfig(final Bundle values) {
        if (stopped) {
            return;
        }
        loader.execute(() -> {
            AppConfig.debugSet(atakContext, values);
            mainHandler.post(this::refreshAppConfig);
        });
    }

    /** Whether the MDM sets this preference, so the user can't change it. */
    public boolean isManaged(final String key) {
        return appConfig.isManaged(key);
    }

    /** Whether the MDM sets anything. */
    public boolean isManaged() {
        return !appConfig.isEmpty();
    }

    /** The server waiting for the user's approval, or null. */
    public ServerIdentity getPendingServer() {
        return pendingServer;
    }

    /** The user approves the server shown to them; provisions at once, as a sign-in does. */
    public void approveServer(final ServerIdentity server) {
        approve(server);
        provisionNow();
    }

    /**
     * Sets where "Discover channels" looks: Conversations' own setting, and the fork's hook for
     * a server other than the account's. An invalid server falls back to the account's.
     */
    private void applyChannelDiscovery() {
        Jid server = null;
        if (settings.channelDiscovery == XmppSettings.ChannelDiscovery.SERVER
                && settings.channelServer != null) {
            try {
                server = Jid.ofUserInput(settings.channelServer);
            } catch (final IllegalArgumentException e) {
                Log.w(TAG, "invalid channel discovery server");
            }
        }
        TakConvoCompat.CHANNEL_DISCOVERY_SERVER = server;
        final String method = settings.channelDiscovery == XmppSettings.ChannelDiscovery.PUBLIC
                ? ChannelDiscoveryService.Method.JABBER_NETWORK.name()
                : ChannelDiscoveryService.Method.LOCAL_SERVER.name();
        defaultPreferences().edit()
                .putString(AppSettings.CHANNEL_DISCOVERY_METHOD, method)
                .apply();
    }

    private void unprovision() {
        provisionedJid = null;
        advertise(null);
        dispatchChanged();
    }

    /**
     * Signs in with an XMPP login; a bare username gets the configured domain. Returns false,
     * storing nothing, if that isn't a valid address.
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
        SensitiveLog.d(TAG, "signing in as " + username);
        final Account before = getAccount();
        final String previousPassword = before == null ? null : before.getPassword();
        XmppSettings.saveLogin(username, password);
        // at once: the account pane compares the account before and after; signing in
        // approves the server, which the account pane names
        approveNext = true;
        try {
            provisionNow();
        } finally {
            approveNext = false;
        }
        retryIfUnchanged(before, previousPassword);
        return true;
    }

    /**
     * Signs in with the password the user entered for the TAK server of which ATAK keeps only
     * the username ({@link XmppSettings.Problem#NO_TAK_PASSWORD}). Doesn't approve the server:
     * that came before the question. False if the settings don't take a password from the user.
     */
    public boolean signInTak(final String password) {
        if (settings == null || !settings.takPasswordFromUser || password.isEmpty()) {
            return false;
        }
        SensitiveLog.d(TAG, "TAK server password entered for " + settings.username);
        takPasswordRefused = false;
        final Account before = getAccount();
        final String previousPassword = before == null ? null : before.getPassword();
        XmppSettings.saveTakPassword(settings.credentialOrigin, settings.username, password);
        provisionNow();
        retryIfUnchanged(before, previousPassword);
        return true;
    }

    /** The server refused the TAK server password the user entered last: the pane says so. */
    public boolean isTakPasswordRefused() {
        return takPasswordRefused;
    }

    /**
     * The server refused the TAK server password the user entered: forgets it, so provisioning
     * disables the account and asks again. Conversations would retry it with a backoff, and a
     * directory that locks an account after a few failures would lock the user's.
     */
    private void forgetRefusedTakPassword() {
        final Account account = getAccount();
        if (settings == null || !settings.takPasswordFromUser || account == null
                || account.getStatus() != Account.State.UNAUTHORIZED) {
            return;
        }
        Log.w(TAG, "the XMPP server refused the TAK server password entered");
        XmppSettings.deleteTakPassword(settings.credentialOrigin);
        takPasswordRefused = true;
        // at once: the backoff's next attempt would send it again
        provisionNow();
    }

    /** After a sign-in: an account apply() left alone retries now, not after the backoff. */
    private void retryIfUnchanged(final Account before, final String previousPassword) {
        final Account account = getAccount();
        if (account != null && account == before
                && Objects.equals(previousPassword, account.getPassword())
                && !account.isOptionSet(Account.OPTION_DISABLED)
                && !account.isOnlineAndConnected()) {
            service.reconnectAccountInBackground(account);
        }
    }

    /** Forgets the XMPP login and disables its account; the history is kept. */
    public void signOut() {
        final Account account = getAccount();
        SensitiveLog.d(TAG, "signing out " + (account == null ? "" : account.getJid().asBareJid()));
        XmppSettings.clearLogin();
        if (account != null) {
            account.setPassword("");
        }
        provisionNow();
    }

    public void reconnect() {
        final Account account = getAccount();
        if (account != null) {
            service.reconnectAccountInBackground(account);
        }
    }

    /** Disables, never deletes, every enabled account that {@code keep} rejects. */
    private void disableAccountsExcept(final Predicate<Jid> keep) {
        for (final Account other : new ArrayList<>(service.getAccounts())) {
            if (!keep.test(other.getJid().asBareJid())
                    && !other.isOptionSet(Account.OPTION_DISABLED)) {
                SensitiveLog.d(TAG, "disabling account " + other.getJid());
                other.setOption(Account.OPTION_DISABLED, true);
                service.updateAccount(other);
            }
        }
    }

    private void applyConnectionSettings(final Account account) {
        account.setHostname(nullToEmpty(settings.host));
        account.setPort(settings.port);
    }

    /** Publishes or clears this device's XMPP address in its SA. */
    private void advertise(final String jid) {
        final AtakPreferences prefs = AtakPreferences.getInstance(atakContext);
        if (jid == null) {
            prefs.getSharedPrefs().edit().remove(PREF_SA_XMPP_USERNAME).apply();
        } else {
            prefs.set(PREF_SA_XMPP_USERNAME, jid);
        }
        SensitiveLog.d(TAG, "advertising " + PREF_SA_XMPP_USERNAME + "=" + jid);
    }

    /** The account of the current configuration, or null. */
    public Account getAccount() {
        return provisionedJid == null ? null : service.findAccountByJid(provisionedJid);
    }

    public XmppConnectionService getService() {
        return service;
    }

    /** The Conversations screen a tapped notification opens, or null if the tap isn't ours. */
    public Intent unwrapNotificationTap(final Intent open, final ClassLoader classLoader) {
        return pendingIntents.unwrapActivity(open, classLoader);
    }

    /** Context for a Conversations activity: ATAK's identity, the plugin's resources. */
    public Context newUiContext(final Display display, final Configuration override) {
        return context.forUi(display, override);
    }

    /**
     * The activity of a {@link #newUiContext} is destroyed: its service connections end, as
     * Android ends a destroyed activity's.
     */
    public void releaseUiContext(final Context ui) {
        if (ui instanceof EmbeddedContext) {
            ((EmbeddedContext) ui).release();
        }
    }

    /** Conversations' Application, which its activities expect. */
    public Application getApplication() {
        return (Application) context.getApplicationContext();
    }

    /** Why provisioning failed, or null. */
    public XmppSettings.Problem getProblem() {
        return lastProblem;
    }

    public XmppSettings getSettings() {
        return settings;
    }

    /** Sends a plain-text message; false if there is no account or the address is invalid. */
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
        SensitiveLog.d(TAG, "sent message to " + jid);
        return true;
    }

    /** The 1:1 chat with an address, created if needed; null without account or valid JID. */
    public Conversation openConversation(final String address) {
        final Account account = getAccount();
        final Jid jid = bareJid(address);
        if (account == null || jid == null) {
            return null;
        }
        return service.findOrCreateConversation(account, jid, false, true);
    }

    /** The bare JID of a typed or advertised address, or null if it isn't one. */
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

    /** The unread count Conversations would show on its launcher icon. */
    public int getUnreadCount() {
        return unreadCount;
    }

    // upstream reports changes from worker threads, often many at once
    private void dispatchChanged() {
        if (dispatchPending.compareAndSet(false, true)) {
            mainHandler.post(dispatchTask);
        }
    }

    private void dispatch() {
        dispatchPending.set(false);
        if (stopped) {
            return;
        }
        Guard.run(TAG, "forget a refused TAK server password", this::forgetRefusedTakPassword);
        // e.g. the account came online: the callsign can be published now
        Guard.run(TAG, "set the callsign as nickname", nicknames::sync);
        for (final Listener l : listeners) {
            Guard.run(TAG, "update " + l.getClass().getSimpleName(), l::onXmppStateChanged);
        }
    }

    private static String nullToEmpty(final String s) {
        return s == null ? "" : s;
    }
}
