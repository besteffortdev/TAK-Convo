package com.atakmap.android.takconvo.plugin.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.takconvo.plugin.BuildConfig;
import com.atakmap.android.takconvo.plugin.config.XmppSettings;
import com.atakmap.android.takconvo.plugin.xmpp.XmppEngine;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputLayout;

import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.manager.BlockingManager;
import eu.siacs.conversations.xmpp.manager.CarbonsManager;
import eu.siacs.conversations.xmpp.manager.ClientStateIndicationManager;
import eu.siacs.conversations.xmpp.manager.ExternalServiceDiscoveryManager;
import eu.siacs.conversations.xmpp.manager.HttpUploadManager;
import eu.siacs.conversations.xmpp.manager.MessageArchiveManager;
import eu.siacs.conversations.xmpp.manager.PepManager;
import eu.siacs.conversations.xmpp.manager.RosterManager;

/**
 * The XMPP account screen: Conversations' own {@code activity_edit_account} layout, in its
 * Material 3 theme, shown in an ATAK pane and driven by {@link XmppEngine} instead of
 * EditAccountActivity (activities of a plugin can't run inside ATAK).
 *
 * <p>With TAK server credentials the fields only show the account's address, and are hidden
 * while there is none. Otherwise it is the login form; the password it takes goes to ATAK's
 * credential store, see {@link XmppSettings}. While the account connects a spinner shows, then
 * the error it ended with, which stays up while Conversations retries.
 */
public final class AccountView implements XmppEngine.Listener {

    /** Things the pane can't do by itself. */
    public interface Host {
        void openSettings();

        void showTestMessagePane();

        /** The user signed in: once the account is online, the chats are what to show. */
        void onSignInStarted();
    }

    /** Shown, masked, in the disabled password field when a password is stored. */
    private static final String PASSWORD_PLACEHOLDER = "xxxxxxxx";
    /** How long a sign-in may take before its account's state is shown whatever it is. */
    private static final long ATTEMPT_TIMEOUT_MS = 30_000;

    private final Context ui;
    private final Context dialogContext;
    private final Host host;
    private final XmppEngine engine;

    private final View root;
    private final MaterialToolbar toolbar;
    private final View editor;
    private final View progress;
    private final ImageView avatar;
    private final TextInputLayout jidLayout;
    private final EditText jid;
    private final TextInputLayout passwordLayout;
    private final EditText password;
    private final Button save;
    private final Button cancel;
    private final View notice;
    private final TextView noticeText;
    private final Button noticeSecondary;
    private final View stats;

    /** fields hold the user's typing, don't overwrite them from the account */
    private boolean edited;
    /** programmatic text changes, not typing */
    private boolean updating;
    /**
     * The error the account's last connection attempt ended with. It stays up while
     * Conversations retries in the background, until the account connects or the user signs in
     * again, rather than flickering with every retry.
     */
    private Account.State shownError;
    /**
     * The account's state when the user signed in, while that sign-in hasn't led anywhere yet:
     * the reconnection is asynchronous, so the previous attempt's error is still there at first.
     */
    private Account.State attemptFrom;
    private final Runnable attemptTimeout = () -> {
        attemptFrom = null;
        refresh();
    };

    public AccountView(final Context pluginContext, final Context atakActivity,
            final XmppEngine engine, final Host host) {
        // Conversations' resources, scaled down like its screens in the chat pane
        final Display display = ((WindowManager) atakActivity
                .getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        this.ui = new ContextThemeWrapper(
                engine.newUiContext(display, UiScale.override(atakActivity)),
                R.style.Theme_Conversations3_Dark);
        this.dialogContext = atakActivity;
        this.engine = engine;
        this.host = host;

        root = ConversationsInflater.inflate(ui, R.layout.activity_edit_account, null, false);
        root.setFitsSystemWindows(false);
        toolbar = root.findViewById(R.id.toolbar);
        editor = root.findViewById(R.id.editor);
        avatar = root.findViewById(R.id.avater);
        jidLayout = root.findViewById(R.id.account_jid_layout);
        jid = root.findViewById(R.id.account_jid);
        passwordLayout = root.findViewById(R.id.account_password_layout);
        password = root.findViewById(R.id.account_password);
        save = root.findViewById(R.id.save_button);
        cancel = root.findViewById(R.id.cancel_button);
        stats = root.findViewById(R.id.stats);

        // parts of the Conversations screen that don't apply to a provisioned account
        for (final int id : new int[] {R.id.name_port, R.id.account_register_new,
                R.id.service_outage, R.id.os_optimization, R.id.your_name_box,
                R.id.pgp_fingerprint_box, R.id.other_device_keys_card, R.id.show_qr_code_button,
                R.id.action_regenerate_axolotl_key, R.id.push_row}) {
            root.findViewById(id).setVisibility(View.GONE);
        }

        // in landscape the keyboard would otherwise replace the pane with a full-screen field
        for (final EditText field : new EditText[] {jid, password}) {
            field.setImeOptions(field.getImeOptions() | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        }
        // the login button is at the bottom of the pane, under the keyboard
        password.setImeOptions((password.getImeOptions() & ~EditorInfo.IME_MASK_ACTION)
                | EditorInfo.IME_ACTION_DONE);
        password.setOnEditorActionListener((v, actionId, event) -> {
            final boolean enter = actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                            && event.getAction() == KeyEvent.ACTION_DOWN);
            if (enter && save.getVisibility() == View.VISIBLE && save.isEnabled()) {
                signIn();
                return true;
            }
            return false;
        });

        final ViewGroup fields = (ViewGroup) passwordLayout.getParent();
        progress = ConversationsInflater.inflate(ui,
                com.atakmap.android.takconvo.plugin.R.layout.takconvo_account_progress, fields,
                false);
        fields.addView(progress, fields.indexOfChild(passwordLayout) + 1);

        final LinearLayout main = root.findViewById(R.id.account_main_layout);
        notice = ConversationsInflater.inflate(ui,
                com.atakmap.android.takconvo.plugin.R.layout.takconvo_account_notice, main, false);
        main.addView(notice, 1);
        noticeText = notice.findViewById(
                com.atakmap.android.takconvo.plugin.R.id.takconvo_notice_text);
        noticeSecondary = notice.findViewById(
                com.atakmap.android.takconvo.plugin.R.id.takconvo_notice_secondary);
        notice.findViewById(com.atakmap.android.takconvo.plugin.R.id.takconvo_notice_settings)
                .setOnClickListener(v -> host.openSettings());
        noticeSecondary.setOnClickListener(v -> useXmppLogin());

        toolbar.setTitle(com.atakmap.android.takconvo.plugin.R.string.takconvo_account_title);
        toolbar.inflateMenu(com.atakmap.android.takconvo.plugin.R.menu.takconvo_account);
        toolbar.getMenu()
                .findItem(com.atakmap.android.takconvo.plugin.R.id.takconvo_action_test_message)
                .setVisible(BuildConfig.DEBUG);
        toolbar.setOnMenuItemClickListener(this::onMenuItem);

        final TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(final Editable s) {
                if (!updating) {
                    edited = true;
                    jidLayout.setError(null);
                    passwordLayout.setError(null);
                    updateSuffix();
                    updateButtons();
                }
            }
        };
        jid.addTextChangedListener(watcher);
        password.addTextChangedListener(watcher);
        save.setText(R.string.log_in);
        save.setOnClickListener(v -> signIn());
        cancel.setText(R.string.log_out);
        cancel.setOnClickListener(v -> confirmSignOut());

        refresh();
    }

    public View getView() {
        return root;
    }

    @Override
    public void onXmppStateChanged() {
        refresh();
    }

    // --- actions ---

    private boolean onMenuItem(final MenuItem item) {
        final int id = item.getItemId();
        if (id == com.atakmap.android.takconvo.plugin.R.id.takconvo_action_reconnect) {
            final Account before = engine.getAccount();
            engine.provision();
            engine.reconnect();
            startAttempt(before);
            refresh();
        } else if (id == com.atakmap.android.takconvo.plugin.R.id.takconvo_action_settings) {
            host.openSettings();
        } else if (id == com.atakmap.android.takconvo.plugin.R.id.takconvo_action_test_message) {
            host.showTestMessagePane();
        } else if (id == com.atakmap.android.takconvo.plugin.R.id.takconvo_action_sign_out) {
            confirmSignOut();
        } else {
            return false;
        }
        return true;
    }

    private void signIn() {
        final String user = jid.getText().toString().trim();
        final String pass = password.getText().toString();
        final XmppSettings settings = engine.getSettings();
        if (!user.contains("@") && (settings == null || settings.domain == null)) {
            jidLayout.setError(ui.getString(
                    com.atakmap.android.takconvo.plugin.R.string.takconvo_full_address_required));
            return;
        }
        final Account before = engine.getAccount();
        if (!engine.signIn(user, pass)) {
            jidLayout.setError(ui.getString(R.string.invalid_jid));
            return;
        }
        edited = false;
        startAttempt(before);
        hideKeyboard();
        host.onSignInStarted();
        refresh();
    }

    /**
     * The user started a connection attempt: show a spinner, then how it ended, rather than the
     * previous attempt's error.
     *
     * @param before the account before the attempt was started
     */
    private void startAttempt(final Account before) {
        shownError = null;
        // a new account has no previous attempt; an existing one still shows its last state
        attemptFrom = before != null && before == engine.getAccount() ? before.getStatus()
                : Account.State.OFFLINE;
        root.removeCallbacks(attemptTimeout);
        root.postDelayed(attemptTimeout, ATTEMPT_TIMEOUT_MS);
    }

    private void confirmSignOut() {
        final Account account = engine.getAccount();
        final String who = account != null ? account.getJid().asBareJid().toString()
                : String.valueOf(XmppSettings.getLoginUsername());
        new AlertDialog.Builder(dialogContext)
                .setMessage(ui.getString(
                        com.atakmap.android.takconvo.plugin.R.string.takconvo_sign_out_confirm,
                        who))
                .setPositiveButton(ui.getString(R.string.log_out), (d, w) -> {
                    engine.signOut();
                    edited = false;
                    setText(password, "");
                    refresh();
                })
                .setNegativeButton(ui.getString(R.string.cancel), null)
                .show();
    }

    private void useXmppLogin() {
        AtakPreferences.getInstance(dialogContext)
                .set(XmppSettings.KEY_USE_TAK_CREDENTIALS, false);
        engine.provision();
    }

    private void hideKeyboard() {
        final InputMethodManager imm =
                (InputMethodManager) ui.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        }
    }

    // --- state ---

    private void refresh() {
        final XmppSettings settings = engine.getSettings();
        final XmppSettings.Problem problem = engine.getProblem();
        final Account account = engine.getAccount();
        final boolean tak = settings == null || settings.usesTakCredentials;
        followAttempt(account);

        toolbar.setSubtitle(account != null
                ? ui.getString(account.getStatus().getReadableId())
                : ui.getString(problemStatus(problem)));

        if (tak) {
            showTakAccount(settings, account);
        } else {
            showLoginForm(settings, account);
        }
        progress.setVisibility(isConnecting(account) ? View.VISIBLE : View.GONE);
        showErrors(problem, tak);
        showNotice(settings, problem, account);
        showAvatar(account);
        showStats(account);

        final boolean login = settings != null
                && settings.credentialSource == XmppSettings.CredentialSource.LOGIN;
        toolbar.getMenu()
                .findItem(com.atakmap.android.takconvo.plugin.R.id.takconvo_action_sign_out)
                .setVisible(login);
        toolbar.getMenu()
                .findItem(com.atakmap.android.takconvo.plugin.R.id.takconvo_action_reconnect)
                .setVisible(account != null);
    }

    /**
     * TAK server credentials: nothing to edit here, the settings decide. The address shows once
     * there is an account; until then there is nothing to show, and the notice explains why and
     * offers the login form.
     */
    private void showTakAccount(final XmppSettings settings, final Account account) {
        edited = false;
        editor.setVisibility(account != null ? View.VISIBLE : View.GONE);
        jidLayout.setHint(ui.getString(R.string.account_settings_jabber_id));
        jidLayout.setSuffixText(null);
        setText(jid, account != null ? account.getJid().asBareJid().toString() : "");
        setEditable(jid, false);
        // the password is ATAK's: nothing to show
        passwordLayout.setVisibility(View.GONE);
        save.setVisibility(View.GONE);
        cancel.setVisibility(View.GONE);
    }

    /** XMPP login: Conversations' login form. */
    private void showLoginForm(final XmppSettings settings, final Account account) {
        editor.setVisibility(View.VISIBLE);
        passwordLayout.setVisibility(View.VISIBLE);
        final boolean loggedIn = account != null
                && account.isOptionSet(Account.OPTION_LOGGED_IN_SUCCESSFULLY)
                && !account.unauthorized();
        if (!edited) {
            final String stored = XmppSettings.getLoginUsername();
            setText(jid, stored != null ? stored
                    : settings != null && settings.suggestedUsername != null
                            ? settings.suggestedUsername : "");
            setText(password, loggedIn ? PASSWORD_PLACEHOLDER : "");
        }
        jidLayout.setHint(ui.getString(settings != null && settings.domain != null
                ? R.string.username_hint : R.string.account_settings_jabber_id));
        // like Conversations: the address is fixed once it logged in, the password until it fails
        setEditable(jid, !loggedIn);
        setEditable(password, !loggedIn);
        passwordLayout.setEndIconMode(loggedIn ? TextInputLayout.END_ICON_NONE
                : TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        cancel.setVisibility(settings != null
                && settings.credentialSource == XmppSettings.CredentialSource.LOGIN
                ? View.VISIBLE : View.GONE);
        updateSuffix();
        updateButtons();
    }

    private void updateSuffix() {
        final XmppSettings settings = engine.getSettings();
        final boolean suffix = settings != null && !settings.usesTakCredentials
                && settings.domain != null && !jid.getText().toString().contains("@");
        jidLayout.setSuffixText(suffix ? "@" + settings.domain : null);
    }

    private void updateButtons() {
        final XmppSettings settings = engine.getSettings();
        if (settings == null || settings.usesTakCredentials) {
            return;
        }
        final Account account = engine.getAccount();
        final boolean filled = jid.getText().toString().trim().length() > 0
                && password.getText().length() > 0;
        if (edited || account == null || shownError != null) {
            save.setVisibility(View.VISIBLE);
            save.setText(R.string.log_in);
            // the placeholder stands for a stored password: a new one has to be typed
            save.setEnabled(filled
                    && !password.getText().toString().equals(PASSWORD_PLACEHOLDER));
        } else if (!account.isOnlineAndConnected()) {
            save.setVisibility(View.VISIBLE);
            save.setText(R.string.account_status_connecting);
            save.setEnabled(false);
        } else {
            save.setVisibility(View.GONE);
        }
    }

    /** Keeps {@link #shownError} and {@link #attemptFrom} up to date with the account. */
    private void followAttempt(final Account account) {
        if (account == null) {
            shownError = null;
            attemptFrom = null;
            return;
        }
        final Account.State status = account.getStatus();
        if (attemptFrom != null && status != attemptFrom) {
            attemptFrom = null; // the sign-in's attempt started, or already ended
        }
        if (attemptFrom != null) {
            return;
        }
        root.removeCallbacks(attemptTimeout);
        if (account.isOnlineAndConnected()) {
            shownError = null;
        } else if (isError(status)) {
            shownError = status;
        }
    }

    private boolean isConnecting(final Account account) {
        if (account == null || account.isOnlineAndConnected()) {
            return false;
        }
        if (attemptFrom != null) {
            return true;
        }
        final Account.State status = account.getStatus();
        return shownError == null
                && (status == Account.State.CONNECTING || status == Account.State.OFFLINE);
    }

    private static boolean isError(final Account.State status) {
        return status.isError() || status == Account.State.NO_INTERNET
                || status == Account.State.AIRPLANE_MODE;
    }

    private void showErrors(final XmppSettings.Problem problem, final boolean tak) {
        TextInputLayout errorLayout = null;
        String error = null;
        if (shownError != null && attemptFrom == null) {
            // with TAK server credentials the password field isn't shown
            errorLayout = !tak && (shownError == Account.State.UNAUTHORIZED
                    || shownError == Account.State.DOWNGRADE_ATTACK)
                    ? passwordLayout : jidLayout;
            error = ui.getString(shownError.getReadableId());
        } else if (problem == XmppSettings.Problem.INVALID_JID) {
            errorLayout = jidLayout;
            error = ui.getString(R.string.invalid_jid);
        }
        if (edited && errorLayout != null) {
            // the user is already correcting it
            return;
        }
        for (final TextInputLayout layout : new TextInputLayout[] {jidLayout, passwordLayout}) {
            if (layout == errorLayout) {
                layout.setError(error);
            } else {
                layout.setError(null);
                layout.setErrorEnabled(false);
            }
        }
    }

    private void showNotice(final XmppSettings settings, final XmppSettings.Problem problem,
            final Account account) {
        final boolean tak = settings == null || settings.usesTakCredentials;
        String text = null;
        boolean offerLogin = false;
        if (problem == XmppSettings.Problem.DISABLED) {
            text = ui.getString(com.atakmap.android.takconvo.plugin.R.string.takconvo_notice_disabled);
        } else if (problem == XmppSettings.Problem.NO_TAK_CREDENTIALS) {
            text = ui.getString(
                    com.atakmap.android.takconvo.plugin.R.string.takconvo_notice_no_tak_credentials);
            offerLogin = true;
        } else if (problem == XmppSettings.Problem.NO_DOMAIN) {
            text = ui.getString(
                    com.atakmap.android.takconvo.plugin.R.string.takconvo_notice_no_domain);
            offerLogin = tak;
        } else if (problem == XmppSettings.Problem.INVALID_JID && tak) {
            text = ui.getString(
                    com.atakmap.android.takconvo.plugin.R.string.takconvo_notice_invalid_jid);
            offerLogin = true;
        } else if (shownError == Account.State.TLS_ERROR_UNTRUSTED && attemptFrom == null) {
            // Conversations would ask whether to trust the certificate; here the settings decide
            text = ui.getString(
                    com.atakmap.android.takconvo.plugin.R.string.takconvo_notice_untrusted);
        } else if (tak && account != null) {
            text = ui.getString(
                    com.atakmap.android.takconvo.plugin.R.string.takconvo_notice_tak_credentials,
                    settings.credentialOrigin);
        }
        notice.setVisibility(text == null ? View.GONE : View.VISIBLE);
        noticeText.setText(text);
        noticeSecondary.setVisibility(offerLogin ? View.VISIBLE : View.GONE);
    }

    /** Once the account has worked: a login that is still being tried keeps the plain form. */
    private void showAvatar(final Account account) {
        if (account == null || !(account.isOnlineAndConnected()
                || account.isOptionSet(Account.OPTION_LOGGED_IN_SUCCESSFULLY))) {
            avatar.setVisibility(View.GONE);
            return;
        }
        avatar.setVisibility(View.VISIBLE);
        final int size = ui.getResources()
                .getDimensionPixelSize(R.dimen.avatar_on_details_screen_size);
        avatar.setImageBitmap(engine.getService().getAvatarService().get(account, size));
    }

    /** The server info card of Conversations' account screen. */
    private void showStats(final Account account) {
        if (account == null || !account.isOnlineAndConnected()) {
            stats.setVisibility(View.GONE);
            return;
        }
        final XmppConnection connection = account.getXmppConnection();
        final XmppConnection.Features features = connection.getFeatures();
        stats.setVisibility(View.VISIBLE);
        text(R.id.session_est, UIHelper.readableTimeDifferenceFull(ui,
                connection.getLastSessionEstablished()));
        available(R.id.server_info_roster_version,
                connection.getManager(RosterManager.class).versioning());
        available(R.id.server_info_carbons,
                connection.getManager(CarbonsManager.class).isEnabled());
        available(R.id.server_info_mam,
                connection.getManager(MessageArchiveManager.class).hasFeature());
        available(R.id.server_info_csi,
                connection.getManager(ClientStateIndicationManager.class).hasFeature());
        available(R.id.server_info_blocking,
                connection.getManager(BlockingManager.class).hasFeature());
        available(R.id.server_info_sm, features.sm());
        available(R.id.server_info_external_service,
                connection.getManager(ExternalServiceDiscoveryManager.class).hasFeature());
        available(R.id.server_info_bind2, features.bind2());
        available(R.id.server_info_sasl2, features.sasl2());
        available(R.id.server_info_http_upload,
                connection.getManager(HttpUploadManager.class).getService() != null);
        final PepManager pep = connection.getManager(PepManager.class);
        final AxolotlService axolotl = account.getAxolotlService();
        if (!pep.isAvailable()) {
            text(R.id.server_info_pep, ui.getString(R.string.server_info_unavailable));
        } else if (axolotl != null && axolotl.isPepBroken()) {
            text(R.id.server_info_pep, ui.getString(R.string.server_info_broken));
        } else {
            text(R.id.server_info_pep, ui.getString(pep.hasPublishOptions()
                    ? R.string.server_info_available : R.string.server_info_partial));
        }
        root.findViewById(R.id.server_info_more).setVisibility(View.VISIBLE);
        root.findViewById(R.id.server_info_login_mechanism).setVisibility(View.VISIBLE);
        text(R.id.login_mechanism, TextUtils.isEmpty(features.loginMechanism())
                ? "" : features.loginMechanism());
        text(R.id.stanza_rx_tx, connection.getStanzaRxTx().rx() + "/"
                + connection.getStanzaRxTx().tx());

        final String fingerprint = axolotl == null ? null : axolotl.getOwnFingerprint();
        final View box = root.findViewById(R.id.axolotl_fingerprint_box);
        if (fingerprint == null) {
            box.setVisibility(View.GONE);
        } else {
            box.setVisibility(View.VISIBLE);
            text(R.id.own_fingerprint_desc, ui.getString(R.string.omemo_fingerprint));
            text(R.id.axolotl_fingerprint,
                    CryptoHelper.prettifyFingerprint(fingerprint.substring(2)));
        }
    }

    // --- helpers ---

    private static int problemStatus(final XmppSettings.Problem problem) {
        if (problem == null) {
            return com.atakmap.android.takconvo.plugin.R.string.takconvo_status_not_signed_in;
        }
        switch (problem) {
            case DISABLED:
                return com.atakmap.android.takconvo.plugin.R.string.takconvo_status_disabled;
            case NO_TAK_CREDENTIALS:
                return com.atakmap.android.takconvo.plugin.R.string
                        .takconvo_status_no_tak_credentials;
            case NO_DOMAIN:
                return com.atakmap.android.takconvo.plugin.R.string.takconvo_status_no_domain;
            case INVALID_JID:
                return com.atakmap.android.takconvo.plugin.R.string.takconvo_status_invalid_jid;
            case NOT_SIGNED_IN:
            default:
                return com.atakmap.android.takconvo.plugin.R.string.takconvo_status_not_signed_in;
        }
    }

    private void setText(final EditText field, final String value) {
        if (value.equals(field.getText().toString())) {
            return;
        }
        updating = true;
        try {
            field.setText(value);
        } finally {
            updating = false;
        }
    }

    private static void setEditable(final EditText field, final boolean editable) {
        field.setEnabled(editable);
        field.setFocusable(editable);
        field.setFocusableInTouchMode(editable);
        field.setCursorVisible(editable);
    }

    private void text(final int id, final CharSequence value) {
        ((TextView) root.findViewById(id)).setText(value);
    }

    private void available(final int id, final boolean available) {
        text(id, ui.getString(available ? R.string.server_info_available
                : R.string.server_info_unavailable));
    }
}
