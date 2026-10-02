package com.atakmap.android.takconvo.plugin;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.data.URIContentManager;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.takconvo.plugin.contacts.GroupChatSends;
import com.atakmap.android.takconvo.plugin.contacts.XmppContacts;
import com.atakmap.android.takconvo.plugin.debug.DebugReceiver;
import com.atakmap.android.takconvo.plugin.map.AtakIntegration;
import com.atakmap.android.takconvo.plugin.map.ChatSender;
import com.atakmap.android.takconvo.plugin.map.MapLocations;
import com.atakmap.android.takconvo.plugin.ui.AccountView;
import com.atakmap.android.takconvo.plugin.ui.TakConvoPreferenceFragment;
import com.atakmap.android.takconvo.plugin.ui.host.ChatDropDown;
import com.atakmap.android.takconvo.plugin.ui.host.EmbeddedActivityHost;
import com.atakmap.android.takconvo.plugin.xmpp.EmbeddedPendingIntents;
import com.atakmap.android.takconvo.plugin.xmpp.XmppEngine;
import com.atakmap.app.SettingsActivity;
import com.atakmap.app.preferences.ToolsPreferenceFragment;
import com.atakmap.coremap.log.Log;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.ui.PublishProfilePictureActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.utils.TakConvoCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

public class TakConvoPlugin implements IPlugin, XmppEngine.Listener, AccountView.Host,
        EmbeddedActivityHost.Listener {

    private static final String TAG = "TakConvo.Plugin";

    /** AtakBroadcast that shows the chat pane. */
    public static final String ACTION_SHOW_CHAT = "com.atakmap.android.takconvo.SHOW_CHAT";
    /** AtakBroadcast that acts as back on the chat pane. */
    public static final String ACTION_CHAT_BACK = "com.atakmap.android.takconvo.CHAT_BACK";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;

    private XmppEngine engine;
    private XmppContacts contacts;
    private MapLocations mapLocations;
    private ChatSender chatSender;
    private GroupChatSends groupChatSends;
    private DebugReceiver debugReceiver;
    private EmbeddedActivityHost chatHost;
    private ChatDropDown chatDropDown;
    private AccountView accountView;
    private Pane accountPane;
    private Pane testPane;
    private TextView statusView;
    private TextView logView;
    private final Set<String> loggedMessages = new HashSet<>();
    private String lastStatus;
    /** Uuid of the account the chat pane's screens belong to. */
    private String chatAccount;
    /** The account pane stands in for the chats until the account is online. */
    private boolean showChatWhenOnline;
    /** The profile picture screen returns to the account pane. */
    private boolean profilePictureFromAccount;
    private final StringBuilder log = new StringBuilder();

    private final BroadcastReceiver showReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();
            if (ACTION_SHOW_CHAT.equals(action)) {
                showChat();
            } else if (EmbeddedPendingIntents.ACTION_OPEN.equals(action)) {
                // a notification was tapped
                final Intent activity = EmbeddedPendingIntents.unwrapActivity(intent,
                        pluginContext.getClassLoader());
                if (activity != null) {
                    showChat(activity);
                }
            } else if (ACTION_CHAT_BACK.equals(action)) {
                if (chatDropDown != null) {
                    chatDropDown.goBack();
                }
            } else {
                showAccountPane();
            }
        }
    };

    public TakConvoPlugin(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        uiService = serviceController.getService(IHostUIService.class);

        // a fixed identifier lets ATAK find the button again after the user moves it
        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        toolIcon(pluginContext),
                        Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showChat();
                    }
                })
                .setIdentifier(pluginContext.getPackageName())
                .build();
    }

    @Override
    public void onStart() {
        final Context atakContext = MapView.getMapView().getContext();
        // never take ATAK down with us; LinkageError: a library ATAK provides doesn't match
        try {
            engine = XmppEngine.start(atakContext, pluginContext);
            engine.addListener(this);
        } catch (final RuntimeException | LinkageError e) {
            Log.e(TAG, "unable to start the XMPP engine", e);
        }
        if (engine != null) {
            try {
                contacts = new XmppContacts(pluginContext, atakContext, engine, this::openChat);
                contacts.start();
            } catch (final RuntimeException | LinkageError e) {
                Log.e(TAG, "unable to join ATAK's contacts", e);
                contacts = null;
            }
        }
        if (engine != null) {
            try {
                startMapIntegration(atakContext);
            } catch (final RuntimeException | LinkageError e) {
                Log.e(TAG, "unable to join ATAK's map and Send dialog", e);
            }
        }
        if (BuildConfig.DEBUG) {
            debugReceiver = DebugReceiver.register(atakContext);
            debugReceiver.setChatSender(chatSender);
        }

        ToolsPreferenceFragment.register(new ToolsPreferenceFragment.ToolPreference(
                pluginContext.getString(R.string.takconvo_prefs_title),
                pluginContext.getString(R.string.takconvo_prefs_summary),
                TakConvoPreferenceFragment.TOOL_KEY,
                toolIcon(pluginContext),
                new TakConvoPreferenceFragment(pluginContext)));
        final AtakBroadcast.DocumentedIntentFilter filter =
                new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction(TakConvoPreferenceFragment.ACTION_SHOW_ACCOUNT,
                "Show the TAK Convo account pane");
        filter.addAction(ACTION_SHOW_CHAT, "Show the TAK Convo chat pane");
        filter.addAction(ACTION_CHAT_BACK, "Go back in the TAK Convo chat pane");
        filter.addAction(EmbeddedPendingIntents.ACTION_OPEN,
                "Open a TAK Convo notification's chat (ATAK sends it when one is tapped)");
        AtakBroadcast.getInstance().registerReceiver(showReceiver, filter);

        if (uiService != null) {
            uiService.addToolbarItem(toolbarItem);
        }
    }

    /** Locations on ATAK's map, quick messages, imports, and the Send dialog. See docs/10. */
    private void startMapIntegration(final Context atakContext) {
        final MapView mapView = MapView.getMapView();
        mapLocations = new MapLocations(mapView, pluginContext);
        TakConvoCompat.ATAK = new AtakIntegration(mapView, pluginContext,
                AtakPreferences.getInstance(atakContext).getSharedPrefs(), engine::getAccount);
        chatSender = new ChatSender(mapView, pluginContext, engine, toolIcon(pluginContext),
                this::showConversation);
        URIContentManager.getInstance().registerSender(chatSender);
        groupChatSends = new GroupChatSends(mapView, pluginContext, engine, chatSender);
        groupChatSends.register();
    }

    private void stopMapIntegration() {
        if (groupChatSends != null) {
            groupChatSends.unregister();
            groupChatSends = null;
        }
        if (chatSender != null) {
            URIContentManager.getInstance().unregisterSender(chatSender);
            chatSender = null;
        }
        TakConvoCompat.ATAK = null;
        if (mapLocations != null) {
            mapLocations.dispose();
            mapLocations = null;
        }
    }

    @Override
    public void onStop() {
        AtakBroadcast.getInstance().unregisterReceiver(showReceiver);
        ToolsPreferenceFragment.unregister(TakConvoPreferenceFragment.TOOL_KEY);
        stopMapIntegration();
        if (contacts != null) {
            contacts.stop();
            contacts = null;
        }
        if (chatDropDown != null) {
            chatDropDown.dispose();
            chatDropDown = null;
        }
        if (chatHost != null) {
            // before the engine: the activities unbind from its service as they stop
            chatHost.destroy();
            chatHost = null;
        }
        if (debugReceiver != null) {
            debugReceiver.unregister();
            debugReceiver = null;
        }
        if (engine != null) {
            engine.removeListener(this);
            if (accountView != null) {
                engine.removeListener(accountView);
            }
            engine = null;
        }
        try {
            XmppEngine.shutdown();
        } catch (final RuntimeException | LinkageError e) {
            Log.e(TAG, "unable to stop the XMPP engine", e);
        }

        if (uiService != null) {
            uiService.removeToolbarItem(toolbarItem);
        }
    }

    /**
     * Conversations' speech bubble, drawn to a bitmap. ATAK paints tool icons in one color, so
     * the colored launcher icon would show as a square.
     */
    private static Drawable toolIcon(final Context pluginContext) {
        final Drawable vector = pluginContext.getResources().getDrawable(R.drawable.ic_takconvo,
                pluginContext.getTheme());
        final Bitmap bitmap = Bitmap.createBitmap(vector.getIntrinsicWidth(),
                vector.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        vector.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
        vector.draw(new Canvas(bitmap));
        return new BitmapDrawable(pluginContext.getResources(), bitmap);
    }

    /** Shows the chats, or the account pane if there is no account. */
    private void showChat() {
        final EmbeddedActivityHost host = openChatPane();
        if (host != null) {
            host.showMain();
        }
    }

    /** Shows what a tapped notification points to. */
    private void showChat(final Intent activity) {
        final EmbeddedActivityHost host = openChatPane();
        if (host != null) {
            host.startActivity(activity);
            if (host.isEmpty()) {
                // it went elsewhere, e.g. to the account pane
                chatDropDown.closeDropDown();
            }
        }
    }

    /** Opens the chat with an XMPP address, e.g. from ATAK's contacts. */
    private void openChat(final String address) {
        final EmbeddedActivityHost host = openChatPane();
        if (host == null) {
            return;
        }
        final Conversation conversation = engine.openConversation(address);
        if (conversation != null) {
            host.showConversation(conversation.getUuid());
        } else {
            host.showMain();
        }
    }

    /** Opens a chat, e.g. the one something was just sent to from ATAK. */
    private void showConversation(final Conversation conversation) {
        final EmbeddedActivityHost host = openChatPane();
        if (host != null) {
            host.showConversation(conversation.getUuid());
        }
    }

    /** Shows the chat pane; returns null and shows the account pane if there is no account. */
    private EmbeddedActivityHost openChatPane() {
        if (engine == null) {
            return null;
        }
        if (engine.getAccount() == null) {
            showChatWhenOnline = true;
            showAccountPane();
            return null;
        }
        final MapView mapView = MapView.getMapView();
        if (chatHost == null) {
            chatHost = new EmbeddedActivityHost((Activity) mapView.getContext(), pluginContext,
                    engine, this);
            chatHost.setRedirect(mapLocations);
            chatDropDown = new ChatDropDown(mapView, chatHost);
        }
        chatDropDown.show();
        return chatHost;
    }

    /**
     * Closes the chat pane and destroys its screens once their account is gone; otherwise ATAK
     * shows the old chats again when the account pane closes.
     */
    private void closeChatPane() {
        if (chatDropDown != null && !chatDropDown.isClosed()) {
            // also when hidden on ATAK's drop-down stack
            chatDropDown.closeDropDown();
        }
        if (chatHost != null && !chatHost.isEmpty()) {
            chatHost.destroy();
        }
    }

    @Override
    public void onHostEmpty() {
        profilePictureFromAccount = false;
        if (chatDropDown != null) {
            chatDropDown.closeDropDown();
        }
    }

    @Override
    public void onTopFinished(final Activity finished) {
        if (profilePictureFromAccount && finished instanceof PublishProfilePictureActivity) {
            profilePictureFromAccount = false;
            // back to the account pane; the chats are kept for next time
            if (chatDropDown != null) {
                chatDropDown.closeDropDown();
            }
        }
    }

    @Override
    public void onShowAccount() {
        showAccountPane();
    }

    @Override
    public void onShowSettings() {
        SettingsActivity.start(TakConvoPreferenceFragment.TOOL_KEY, null);
    }

    private void showAccountPane() {
        if (uiService == null || engine == null) {
            return;
        }
        if (accountPane == null) {
            accountView = new AccountView(pluginContext, MapView.getMapView().getContext(),
                    engine, this);
            engine.addListener(accountView);
            // retained: ATAK would close it when the chat pane opens over it
            accountPane = new PaneBuilder(accountView.getView())
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, ChatDropDown.PANE_FRACTION)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, ChatDropDown.PANE_FRACTION)
                    .setMetaValue(Pane.RETAIN, true)
                    .build();
        }
        if (!uiService.isPaneVisible(accountPane)) {
            uiService.showPane(accountPane, null);
        }
        accountView.onXmppStateChanged();
    }

    @Override
    public void openSettings() {
        SettingsActivity.start(TakConvoPreferenceFragment.TOOL_KEY, null);
    }

    @Override
    public void onSignInStarted() {
        showChatWhenOnline = true;
    }

    /** Opens the profile picture screen in the chat pane, over the account pane. */
    @Override
    public void editProfilePicture() {
        final Account account = engine == null ? null : engine.getAccount();
        final EmbeddedActivityHost host = account == null ? null : openChatPane();
        if (host == null) {
            return;
        }
        final Intent intent = new Intent();
        intent.setComponent(new ComponentName(MapView.getMapView().getContext().getPackageName(),
                PublishProfilePictureActivity.class.getName()));
        intent.putExtra(XmppActivity.EXTRA_ACCOUNT, account.getJid().asBareJid().toString());
        host.startActivity(intent);
        profilePictureFromAccount = true;
    }

    /** Replaces the account pane with the chats once the account is online. */
    private void showChatIfSignedIn(final Account account) {
        if (!showChatWhenOnline || account == null || !account.isOnlineAndConnected()) {
            return;
        }
        showChatWhenOnline = false;
        if (accountPane != null && uiService != null && uiService.isPaneVisible(accountPane)) {
            uiService.closePane(accountPane);
            showChat();
        }
    }

    // --- test pane (debug builds): send a plain message, list the traffic ---

    @Override
    public void showTestMessagePane() {
        if (uiService == null) {
            return;
        }
        if (testPane == null) {
            final View view = PluginLayoutInflater.inflate(pluginContext,
                    R.layout.main_layout, null);
            bindTestView(view);
            testPane = new PaneBuilder(view)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }
        if (!uiService.isPaneVisible(testPane)) {
            uiService.showPane(testPane, null);
        }
        onXmppStateChanged();
    }

    private void bindTestView(final View view) {
        statusView = view.findViewById(R.id.takconvo_status);
        logView = view.findViewById(R.id.takconvo_log);
        final EditText to = view.findViewById(R.id.takconvo_to);
        final EditText body = view.findViewById(R.id.takconvo_body);
        final Button send = view.findViewById(R.id.takconvo_send);
        final Button reconnect = view.findViewById(R.id.takconvo_reconnect);

        reconnect.setOnClickListener(v -> {
            if (engine != null) {
                engine.provision();
                engine.reconnect();
            }
        });
        send.setOnClickListener(v -> {
            if (engine != null && engine.sendMessage(to.getText().toString().trim(),
                    body.getText().toString())) {
                body.setText("");
            }
        });
    }

    @Override
    public void onXmppStateChanged() {
        if (engine == null) {
            return;
        }
        // signed out, turned off or another account: the chats shown were the old account's
        final Account account = engine.getAccount();
        final String accountUuid = account == null ? null : account.getUuid();
        if (!Objects.equals(accountUuid, chatAccount)) {
            chatAccount = accountUuid;
            closeChatPane();
        }
        showChatIfSignedIn(account);
        final String status = describe(engine);
        if (!status.equals(lastStatus)) {
            lastStatus = status;
            // tools/deploy.ps1 waits for "status: ONLINE"
            SensitiveLog.d(TAG, "status: " + status.replace('\n', ' '));
        }
        if (testPane != null) {
            collectMessages(engine);
            statusView.setText(status);
            logView.setText(log);
        }
    }

    private static String describe(final XmppEngine engine) {
        final StringBuilder sb = new StringBuilder();
        final Account account = engine.getAccount();
        if (account == null) {
            sb.append("No XMPP account");
        } else {
            sb.append(account.getJid().asBareJid()).append('\n')
                    .append("status: ").append(account.getStatus());
        }
        if (engine.getSettings() != null) {
            sb.append('\n').append("credentials: ")
                    .append(engine.getSettings().credentialSource);
        }
        if (engine.getProblem() != null) {
            sb.append('\n').append("problem: ").append(engine.getProblem());
        }
        return sb.toString();
    }

    /** Adds each chat's latest message to the test pane, not to logcat. */
    private void collectMessages(final XmppEngine engine) {
        final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.US);
        for (final Conversation conversation : engine.getConversations()) {
            final Message latest = conversation.getLatestMessage();
            if (latest == null || latest.getBody() == null
                    || !loggedMessages.add(latest.getUuid())) {
                continue;
            }
            final boolean incoming = latest.getStatus() == Message.STATUS_RECEIVED;
            log.insert(0, time.format(new Date(latest.getTimeSent()))
                    + (incoming ? "  <- " : "  -> ")
                    + conversation.getAddress().asBareJid() + ": " + latest.getBody() + "\n");
        }
    }
}
