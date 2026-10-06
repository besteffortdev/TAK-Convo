package com.atakmap.android.takconvo.plugin.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.widget.Toast;

import com.atakmap.android.gui.ImportFileBrowserDialog;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.preference.PluginPreferenceFragment;
import com.atakmap.android.takconvo.plugin.R;
import com.atakmap.android.takconvo.plugin.config.TrustedCa;
import com.atakmap.android.takconvo.plugin.config.XmppSettings;
import com.atakmap.android.takconvo.plugin.xmpp.XmppEngine;
import com.atakmap.app.preferences.PreferenceControl;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.xmpp.Jid;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The plugin's page under ATAK's Tool Preferences, over the preferences a {@code .pref} file
 * provisions ({@link XmppSettings}).
 */
public class TakConvoPreferenceFragment extends PluginPreferenceFragment {

    /** The page's key in ToolsPreferenceFragment. */
    public static final String TOOL_KEY = "takconvo_preferences";
    /** AtakBroadcast that shows the account pane. */
    public static final String ACTION_SHOW_ACCOUNT =
            "com.atakmap.android.takconvo.SHOW_ACCOUNT";

    private static final String KEY_ACCOUNT = "takconvo_account";
    private static final String KEY_TRUSTED_CA_PICKER = "takconvo_trusted_ca_picker";
    private static final String KEY_IMPORT = "takconvo_import_pref";
    private static final String KEY_MANAGED = "takconvo_managed";
    /** The settings on this page an MDM can set. */
    private static final String[] MANAGEABLE = {XmppSettings.KEY_ENABLED,
            XmppSettings.KEY_USE_TAK_CREDENTIALS, XmppSettings.KEY_TAK_SERVER,
            XmppSettings.KEY_USE_CALLSIGN,
            XmppSettings.KEY_NOTIFICATION_MESSAGES, XmppSettings.KEY_NOTIFICATION_SOUND,
            XmppSettings.KEY_NOTIFICATION_VIBRATE, XmppSettings.KEY_SHOW_QUICK_MESSAGES,
            XmppSettings.KEY_QUICK_MESSAGES, XmppSettings.KEY_DOMAIN, XmppSettings.KEY_HOST,
            XmppSettings.KEY_PORT, XmppSettings.KEY_CHANNEL_DISCOVERY,
            XmppSettings.KEY_CHANNEL_SERVER, XmppSettings.KEY_USE_TAK_TRUSTSTORE,
            XmppSettings.KEY_USE_ANDROID_CA_STORE};

    // the plugin context, which lives as long as the plugin, for fragment re-creation
    private static Context staticPluginContext;

    /** For fragment re-creation, after the other constructor ran once. */
    public TakConvoPreferenceFragment() {
        super(staticPluginContext, R.xml.takconvo_preferences);
    }

    @SuppressLint("ValidFragment")
    public TakConvoPreferenceFragment(final Context pluginContext) {
        super(pluginContext, R.xml.takconvo_preferences);
        staticPluginContext = pluginContext;
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences",
                pluginContext.getString(R.string.takconvo_prefs_title));
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        XmppSettings.normalize(prefs());
        super.onCreate(savedInstanceState);
        final XmppEngine engine = XmppEngine.get();
        if (engine == null || !engine.isManaged()) {
            getPreferenceScreen().removePreference(findPreference(KEY_MANAGED));
        }

        findPreference(KEY_ACCOUNT).setOnPreferenceClickListener(p -> {
            AtakBroadcast.getInstance().sendBroadcast(new Intent(ACTION_SHOW_ACCOUNT));
            getActivity().finish();
            return true;
        });
        findPreference(KEY_TRUSTED_CA_PICKER).setOnPreferenceClickListener(p -> {
            chooseTrustedCa();
            return true;
        });
        findPreference(KEY_IMPORT).setOnPreferenceClickListener(p -> {
            importPrefFile();
            return true;
        });
        for (final String key : new String[] {XmppSettings.KEY_DOMAIN, XmppSettings.KEY_HOST,
                XmppSettings.KEY_PORT}) {
            findPreference(key).setOnPreferenceChangeListener((p, value) -> {
                if (XmppSettings.KEY_PORT.equals(key) && !validPort(String.valueOf(value))) {
                    return false;
                }
                showSummary(key, String.valueOf(value));
                return true;
            });
        }
        findPreference(XmppSettings.KEY_TAK_SERVER).setOnPreferenceChangeListener((p, value) -> {
            showTakServerSummary(String.valueOf(value));
            return true;
        });
        findPreference(XmppSettings.KEY_CHANNEL_DISCOVERY).setOnPreferenceChangeListener(
                (p, value) -> {
                    showChannelDiscovery(XmppSettings.ChannelDiscovery.parse(
                            String.valueOf(value)), channelServer());
                    return true;
                });
        findPreference(XmppSettings.KEY_CHANNEL_SERVER).setOnPreferenceChangeListener(
                (p, value) -> {
                    final String server = String.valueOf(value).trim();
                    if (!server.isEmpty() && !validServer(server)) {
                        Toast.makeText(getActivity(), pluginContext.getString(
                                R.string.takconvo_pref_channel_server_invalid, server),
                                Toast.LENGTH_LONG).show();
                        return false;
                    }
                    showChannelDiscovery(XmppSettings.ChannelDiscovery.parse(prefs().getString(
                            XmppSettings.KEY_CHANNEL_DISCOVERY, null)), server);
                    return true;
                });
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    /** Shows the stored values, e.g. after a .pref import. */
    private void reload() {
        final SharedPreferences prefs = prefs();
        for (final String key : new String[] {XmppSettings.KEY_ENABLED,
                XmppSettings.KEY_USE_TAK_CREDENTIALS, XmppSettings.KEY_USE_TAK_TRUSTSTORE,
                XmppSettings.KEY_USE_CALLSIGN, XmppSettings.KEY_NOTIFICATION_SOUND,
                XmppSettings.KEY_NOTIFICATION_VIBRATE}) {
            ((CheckBoxPreference) findPreference(key)).setChecked(prefs.getBoolean(key, true));
        }
        for (final String key : new String[] {XmppSettings.KEY_USE_ANDROID_CA_STORE,
                XmppSettings.KEY_NOTIFICATION_MESSAGES}) {
            ((CheckBoxPreference) findPreference(key)).setChecked(prefs.getBoolean(key, false));
        }
        for (final String key : new String[] {XmppSettings.KEY_DOMAIN, XmppSettings.KEY_HOST,
                XmppSettings.KEY_PORT}) {
            final String value = prefs.getString(key, XmppSettings.KEY_PORT.equals(key)
                    ? String.valueOf(XmppSettings.DEFAULT_PORT) : "");
            ((EditTextPreference) findPreference(key)).setText(value);
            showSummary(key, value);
        }
        final String ca = prefs.getString(XmppSettings.KEY_TRUSTED_CA, null);
        final boolean noCa = ca == null || ca.trim().isEmpty();
        final Preference caPicker = findPreference(KEY_TRUSTED_CA_PICKER);
        if (managed(XmppSettings.KEY_TRUSTED_CA)) {
            // the managed file is in ATAK's private storage: its path means nothing to users
            caPicker.setSummary(pluginContext.getString(noCa
                    ? R.string.takconvo_pref_trusted_ca_managed_none
                    : R.string.takconvo_pref_trusted_ca_managed));
            caPicker.setEnabled(false);
        } else {
            caPicker.setSummary(noCa
                    ? pluginContext.getString(R.string.takconvo_pref_trusted_ca_none) : ca);
            caPicker.setEnabled(true);
        }
        findPreference(KEY_ACCOUNT).setSummary(accountSummary());
        showTakServers(prefs.getString(XmppSettings.KEY_TAK_SERVER, ""));

        final XmppSettings.ChannelDiscovery discovery = XmppSettings.ChannelDiscovery.parse(
                prefs.getString(XmppSettings.KEY_CHANNEL_DISCOVERY, null));
        ((ListPreference) findPreference(XmppSettings.KEY_CHANNEL_DISCOVERY))
                .setValue(discovery.value);
        ((EditTextPreference) findPreference(XmppSettings.KEY_CHANNEL_SERVER))
                .setText(channelServer());
        showChannelDiscovery(discovery, channelServer());

        // shown, not editable: the device management's values
        for (final String key : MANAGEABLE) {
            if (managed(key)) {
                findPreference(key).setEnabled(false);
            }
        }
        keepDependents(XmppSettings.KEY_USE_TAK_CREDENTIALS, XmppSettings.KEY_TAK_SERVER);
        keepDependents(XmppSettings.KEY_NOTIFICATION_MESSAGES,
                XmppSettings.KEY_NOTIFICATION_SOUND, XmppSettings.KEY_NOTIFICATION_VIBRATE);
        keepDependents(XmppSettings.KEY_SHOW_QUICK_MESSAGES, XmppSettings.KEY_QUICK_MESSAGES);
    }

    /**
     * A disabled check box disables the settings that depend on it: when the device management
     * sets it, they follow its value instead, which can't change.
     */
    private void keepDependents(final String parent, final String... dependents) {
        if (!managed(parent)) {
            return;
        }
        final Preference parentPref = findPreference(parent);
        final boolean on = ((CheckBoxPreference) parentPref).isChecked();
        for (final String key : dependents) {
            final Preference p = findPreference(key);
            p.setDependency(null);
            p.onDependencyChanged(parentPref, false);
            p.setEnabled(on && !managed(key));
        }
    }

    /** Whether the device management (MDM) sets {@code key}. */
    private static boolean managed(final String key) {
        final XmppEngine engine = XmppEngine.get();
        return engine != null && engine.isManaged(key);
    }

    /**
     * Offers Automatic and ATAK's TAK servers; the one set stays listed, also when ATAK
     * doesn't have it (yet).
     */
    private void showTakServers(final String value) {
        final String current = value == null ? "" : value.trim();
        final List<CharSequence> entries = new ArrayList<>();
        final List<CharSequence> values = new ArrayList<>();
        entries.add(pluginContext.getString(R.string.takconvo_pref_tak_server_auto));
        values.add("");
        String selected = current.isEmpty() ? "" : null;
        for (final String host : XmppSettings.takServerHosts()) {
            entries.add(host);
            values.add(host);
            if (host.equalsIgnoreCase(current)) {
                selected = host;
            }
        }
        if (selected == null) {
            entries.add(pluginContext.getString(R.string.takconvo_pref_tak_server_missing,
                    current));
            values.add(current);
            selected = current;
        }
        final ListPreference p = (ListPreference) findPreference(XmppSettings.KEY_TAK_SERVER);
        p.setEntries(entries.toArray(new CharSequence[0]));
        p.setEntryValues(values.toArray(new CharSequence[0]));
        if (!selected.equals(p.getValue())) {
            // setValue stores it: not when unset, a managed default is the key's absence
            p.setValue(selected);
        }
        showTakServerSummary(selected);
    }

    private void showTakServerSummary(final String host) {
        findPreference(XmppSettings.KEY_TAK_SERVER).setSummary(host == null || host.isEmpty()
                ? pluginContext.getString(R.string.takconvo_pref_tak_server_auto_summary)
                : host);
    }

    /** Shows the choice; the server field is enabled for "another server" only. */
    private void showChannelDiscovery(final XmppSettings.ChannelDiscovery discovery,
            final String server) {
        final boolean empty = server == null || server.trim().isEmpty();
        final String summary;
        switch (discovery) {
            case SERVER:
                summary = pluginContext.getString(
                        R.string.takconvo_pref_channel_discovery_server) + ": "
                        + (empty ? pluginContext.getString(R.string.takconvo_pref_not_set)
                                : server.trim());
                break;
            case PUBLIC:
                summary = pluginContext.getString(
                        R.string.takconvo_pref_channel_discovery_public_summary);
                break;
            case XMPP_SERVER:
            default:
                summary = pluginContext.getString(
                        R.string.takconvo_pref_channel_discovery_xmpp_server_summary);
        }
        findPreference(XmppSettings.KEY_CHANNEL_DISCOVERY).setSummary(summary);
        final Preference serverPref = findPreference(XmppSettings.KEY_CHANNEL_SERVER);
        serverPref.setEnabled(discovery == XmppSettings.ChannelDiscovery.SERVER
                && !managed(XmppSettings.KEY_CHANNEL_SERVER));
        serverPref.setSummary(empty
                ? pluginContext.getString(R.string.takconvo_pref_channel_server_summary)
                : server.trim());
    }

    private String channelServer() {
        final String value = prefs().getString(XmppSettings.KEY_CHANNEL_SERVER, "");
        return value == null ? "" : value;
    }

    /** A server or group chat service address: no user, no resource. */
    private static boolean validServer(final String value) {
        try {
            final Jid jid = Jid.ofUserInput(value);
            return jid.getLocal() == null && jid.isBareJid();
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    private void showSummary(final String key, final String value) {
        final Preference p = findPreference(key);
        final boolean empty = value == null || value.trim().isEmpty();
        if (XmppSettings.KEY_DOMAIN.equals(key)) {
            p.setSummary(empty ? pluginContext.getString(R.string.takconvo_pref_domain_summary)
                    : value.trim());
        } else if (XmppSettings.KEY_HOST.equals(key)) {
            p.setSummary(empty ? pluginContext.getString(R.string.takconvo_pref_host_summary)
                    : value.trim());
        } else {
            p.setSummary(empty ? String.valueOf(XmppSettings.DEFAULT_PORT) : value.trim());
        }
    }

    private String accountSummary() {
        final XmppEngine engine = XmppEngine.get();
        final Account account = engine == null ? null : engine.getAccount();
        if (account == null) {
            return pluginContext.getString(R.string.takconvo_status_not_signed_in);
        }
        return account.getJid().asBareJid() + " – "
                + pluginContext.getString(account.getStatus().getReadableId());
    }

    private void chooseTrustedCa() {
        final String[] items = {
                pluginContext.getString(R.string.takconvo_pref_trusted_ca_choose),
                pluginContext.getString(R.string.takconvo_pref_trusted_ca_clear)
        };
        new AlertDialog.Builder(getActivity())
                .setTitle(pluginContext.getString(R.string.takconvo_pref_trusted_ca))
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        ImportFileBrowserDialog.show(
                                pluginContext.getString(R.string.takconvo_pref_trusted_ca),
                                new String[] {"pem", "crt", "cer", "der"},
                                new ImportFileBrowserDialog.DialogDismissed() {
                                    @Override
                                    public void onFileSelected(final File file) {
                                        setTrustedCa(file);
                                    }

                                    @Override
                                    public void onDialogClosed() {
                                    }
                                }, getActivity());
                    } else {
                        prefs().edit().remove(XmppSettings.KEY_TRUSTED_CA).apply();
                        reload();
                    }
                })
                .show();
    }

    private void setTrustedCa(final File file) {
        if (file == null) {
            return;
        }
        if (TrustedCa.load(file.getAbsolutePath()) == null) {
            Toast.makeText(getActivity(), pluginContext.getString(
                    R.string.takconvo_pref_import_failed, file.getName()), Toast.LENGTH_LONG)
                    .show();
            return;
        }
        AtakPreferences.getInstance(getActivity())
                .set(XmppSettings.KEY_TRUSTED_CA, file.getAbsolutePath());
        reload();
    }

    private void importPrefFile() {
        ImportFileBrowserDialog.show(pluginContext.getString(R.string.takconvo_pref_import),
                new String[] {"pref"},
                new ImportFileBrowserDialog.DialogDismissed() {
                    @Override
                    public void onFileSelected(final File file) {
                        if (file == null) {
                            return;
                        }
                        // ATAK's importer, as mission packages use it
                        final List<String> keys = PreferenceControl.getInstance(getActivity())
                                .loadSettings(file);
                        final int count = keys == null ? 0 : keys.size();
                        Toast.makeText(getActivity(), count == 0
                                ? pluginContext.getString(R.string.takconvo_pref_import_failed,
                                        file.getName())
                                : pluginContext.getString(R.string.takconvo_pref_imported,
                                        count, file.getName()),
                                Toast.LENGTH_LONG).show();
                        XmppSettings.normalize(prefs());
                        reload();
                    }

                    @Override
                    public void onDialogClosed() {
                    }
                }, getActivity());
    }

    private static boolean validPort(final String value) {
        if (value == null || value.trim().isEmpty()) {
            return true;
        }
        try {
            final int port = Integer.parseInt(value.trim());
            return port > 0 && port < 65536;
        } catch (final NumberFormatException e) {
            return false;
        }
    }

    private SharedPreferences prefs() {
        return AtakPreferences.getInstance(getActivity() != null ? getActivity()
                : pluginContext).getSharedPrefs();
    }
}
