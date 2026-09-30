
package com.atakmap.android.takconvo.plugin;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.takconvo.plugin.debug.DebugReceiver;
import com.atakmap.android.takconvo.plugin.ui.AccountView;
import com.atakmap.android.takconvo.plugin.ui.TakConvoPreferenceFragment;
import com.atakmap.android.takconvo.plugin.ui.host.ChatDropDown;
import com.atakmap.android.takconvo.plugin.ui.host.EmbeddedActivityHost;
import com.atakmap.android.takconvo.plugin.xmpp.XmppEngine;
import com.atakmap.app.SettingsActivity;
import com.atakmap.app.preferences.ToolsPreferenceFragment;
import com.atakmap.coremap.log.Log;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
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

    /** Sent (AtakBroadcast) to show the chat pane on the map. */
    public static final String ACTION_SHOW_CHAT = "com.atakmap.android.takconvo.SHOW_CHAT";
    /** Sent (AtakBroadcast) as if the back button was pressed on the chat pane. */
    public static final String ACTION_CHAT_BACK = "com.atakmap.android.takconvo.CHAT_BACK";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;

    private XmppEngine engine;
    private DebugReceiver debugReceiver;
    private EmbeddedActivityHost chatHost;
    private ChatDropDown chatDropDown;
    private AccountView accountView;
    private Pane accountPane;
    private Pane testPane;
    private TextView statusView;
    private TextView logView;
    private final Set<String> loggedMessages = new HashSet<>();
    private final StringBuilder log = new StringBuilder();

    private final BroadcastReceiver showReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();
            if (ACTION_SHOW_CHAT.equals(action)) {
                showChat();
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

        // obtain the UI service
        uiService = serviceController.getService(IHostUIService.class);

        // create the button and set the identifier to be well known
        // if you fail to do this, the toolbar configuration will never
        // be able to find it again after the user moves the icon.
        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                        android.graphics.drawable.Drawable.class,
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
        try {
            engine = XmppEngine.start(atakContext, pluginContext);
            engine.addListener(this);
        } catch (final Throwable t) {
            // never take ATAK down with us
            Log.e(TAG, "unable to start the XMPP engine", t);
        }
        if (BuildConfig.DEBUG) {
            debugReceiver = DebugReceiver.register(atakContext);
        }

        ToolsPreferenceFragment.register(new ToolsPreferenceFragment.ToolPreference(
                pluginContext.getString(R.string.takconvo_prefs_title),
                pluginContext.getString(R.string.takconvo_prefs_summary),
                TakConvoPreferenceFragment.TOOL_KEY,
                pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                new TakConvoPreferenceFragment(pluginContext)));
        final AtakBroadcast.DocumentedIntentFilter filter =
                new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction(TakConvoPreferenceFragment.ACTION_SHOW_ACCOUNT,
                "Show the TAK Convo account pane");
        filter.addAction(ACTION_SHOW_CHAT, "Show the TAK Convo chat pane");
        filter.addAction(ACTION_CHAT_BACK, "Go back in the TAK Convo chat pane");        AtakBroadcast.getInstance().registerReceiver(showReceiver, filter);

        // the plugin is starting, add the button to the toolbar
        if (uiService == null)
            return;

        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        AtakBroadcast.getInstance().unregisterReceiver(showReceiver);
        ToolsPreferenceFragment.unregister(TakConvoPreferenceFragment.TOOL_KEY);
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
        } catch (final Throwable t) {
            Log.e(TAG, "unable to stop the XMPP engine", t);
        }

        // the plugin is stopping, remove the button from the toolbar
        if (uiService == null)
            return;

        uiService.removeToolbarItem(toolbarItem);
    }

    /** Conversations' chats. Without an account to chat with, the account screen instead. */
    private void showChat() {
        if (engine == null) {
            return;
        }
        if (engine.getAccount() == null) {
            showAccountPane();
            return;
        }
        final MapView mapView = MapView.getMapView();
        if (chatHost == null) {
            chatHost = new EmbeddedActivityHost((Activity) mapView.getContext(), pluginContext,
                    engine, this);
            chatDropDown = new ChatDropDown(mapView, chatHost);
        }
        chatDropDown.show();
        chatHost.showMain();
    }

    @Override
    public void onHostEmpty() {
        if (chatDropDown != null) {
            chatDropDown.closeDropDown();
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

    /** Conversations' account screen: login, or the status of the provisioned account. */
    private void showAccountPane() {
        if (uiService == null || engine == null) {
            return;
        }
        if (accountPane == null) {
            accountView = new AccountView(pluginContext, MapView.getMapView().getContext(),
                    engine, this);
            engine.addListener(accountView);
            accountPane = new PaneBuilder(accountView.getView())
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.6D)
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

    // --- spike UI: send a plain message and log traffic (debug builds) ---

    @Override
    public void showTestMessagePane() {
        if (uiService == null) {
            return;
        }
        if (testPane == null) {
            final View view = PluginLayoutInflater.inflate(pluginContext,
                    R.layout.main_layout, null);
            bindSpikeView(view);
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

    private void bindSpikeView(final View view) {
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
        final String status = describe(engine);
        Log.d(TAG, "status: " + status.replace('\n', ' '));
        collectMessages(engine);
        if (statusView != null) {
            statusView.setText(status);
        }
        if (logView != null) {
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

    private void collectMessages(final XmppEngine engine) {
        final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.US);
        for (final Conversation conversation : engine.getConversations()) {
            final Message latest = conversation.getLatestMessage();
            if (latest == null || latest.getBody() == null
                    || !loggedMessages.add(latest.getUuid())) {
                continue;
            }
            final boolean incoming = latest.getStatus() == Message.STATUS_RECEIVED;
            final String line = time.format(new Date(latest.getTimeSent()))
                    + (incoming ? "  <- " : "  -> ")
                    + conversation.getAddress().asBareJid() + ": " + latest.getBody();
            Log.d(TAG, "message " + line);
            log.insert(0, line + "\n");
        }
    }
}
