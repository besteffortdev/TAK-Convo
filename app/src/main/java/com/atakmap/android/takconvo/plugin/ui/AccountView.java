package com.atakmap.android.takconvo.plugin.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
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

import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.takconvo.plugin.BuildConfig;
import com.atakmap.android.takconvo.plugin.Guard;
import com.atakmap.android.takconvo.plugin.config.ServerIdentity;
import com.atakmap.android.takconvo.plugin.config.XmppSettings;
import com.atakmap.android.takconvo.plugin.ui.host.ChatDropDown;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The XMPP account pane: Conversations' {@code activity_edit_account} layout driven by
 * {@link XmppEngine}. With TAK server credentials it shows the address; otherwise it is the
 * login form. See docs/03.
 */
public final class AccountView implements XmppEngine.Listener {

    /** What the pane can't do by itself. */
    public interface Host {
        void openSettings();

        void showTestMessagePane();

        /** The chats replace the pane once the account is online. */
        void onSignInStarted();

        void editProfilePicture();
    }

    private static final String TAG = "TakConvo.Account";
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    /** Masked stand-in for a stored password. */
    private static final String PASSWORD_PLACEHOLDER = "xxxxxxxx";
    /** After this, a sign-in shows the account's state whatever it is. */
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
    private final DomainSuffixField jid;
    private final TextInputLayout passwordLayout;
    private final EditText password;
    private final Button save;
    private final Button cancel;
    private final View notice;
    private final TextView noticeText;
    private final Button noticeSecondary;
    /** What {@link #noticeSecondary} does: use an XMPP login, or approve the server. */
    private Runnable secondaryAction;
    private final View stats;

    /** The fields hold the user's typing; don't overwrite them. */
    private boolean edited;
    /**
     * The user signed in from the form, until the account is online or the attempt failed: the
     * form stays as filled in, not the account's view of a login that worked before.
     */
    private boolean signingIn;
    /** The user typed since signing in: the attempt's error isn't shown again. */
    private boolean correcting;
    /** The code is setting the text, not the user. */
    private boolean updating;
    /** The last attempt's error, kept up during background retries instead of flickering. */
    private Account.State shownError;
    /** The account's state when the user signed in, until that attempt starts. */
    private Account.State attemptFrom;
    private final Runnable attemptTimeout = () -> {
        attemptFrom = null;
        // no answer yet: the form can be changed and sent again
        signingIn = false;
        refresh();
    };

    public AccountView(final Context pluginContext, final Context atakActivity,
            final XmppEngine engine, final Host host) {
        // scaled and sized like the chat pane, or Conversations picks its wide layouts
        final Display display = ((WindowManager) atakActivity
                .getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        final Configuration override = UiScale.override(atakActivity);
        final MapView mapView = MapView.getMapView();
        if (mapView != null) {
            final int[] pane = ChatDropDown.estimatePaneSize(mapView);
            final float density = UiScale.density(atakActivity);
            override.screenWidthDp = Math.round(pane[0] / density);
            override.screenHeightDp = Math.round(pane[1] / density);
            override.smallestScreenWidthDp =
                    Math.min(override.screenWidthDp, override.screenHeightDp);
        }
        this.ui = new ContextThemeWrapper(engine.newUiContext(display, override),
                R.style.Theme_Conversations3_Dark);
        this.dialogContext = atakActivity;
        this.engine = engine;
        this.host = host;

        // the address field shows the domain after the username without crowding it out
        root = ConversationsInflater.inflate(ui, R.layout.activity_edit_account, null, false,
                (context, attrs) -> attrs.getAttributeResourceValue(ANDROID_NS, "id", 0)
                        == R.id.account_jid ? new DomainSuffixField(context, attrs) : null);
        root.setFitsSystemWindows(false);
        toolbar = root.findViewById(R.id.toolbar);
        editor = root.findViewById(R.id.editor);
        avatar = root.findViewById(R.id.avater);
        // publishing needs the account online
        avatar.setContentDescription(ui.getString(
                com.atakmap.android.takconvo.plugin.R.string.takconvo_change_profile_picture));
        avatar.setOnClickListener(v -> {
            final Account account = engine.getAccount();
            if (account != null && account.isOnlineAndConnected()) {
                host.editProfilePicture();
            }
        });
        jidLayout = root.findViewById(R.id.account_jid_layout);
        jid = root.findViewById(R.id.account_jid);
        passwordLayout = root.findViewById(R.id.account_password_layout);
        password = root.findViewById(R.id.account_password);
        save = root.findViewById(R.id.save_button);
        cancel = root.findViewById(R.id.cancel_button);
        stats = root.findViewById(R.id.stats);

        // not for a provisioned account
        for (final int id : new int[] {R.id.name_port, R.id.account_register_new,
                R.id.service_outage, R.id.os_optimization, R.id.your_name_box,
                R.id.pgp_fingerprint_box, R.id.other_device_keys_card, R.id.show_qr_code_button,
                R.id.action_regenerate_axolotl_key, R.id.push_row}) {
            root.findViewById(id).setVisibility(View.GONE);
        }

        // no full-screen keyboard field in landscape
        for (final EditText field : new EditText[] {jid, password}) {
            field.setImeOptions(field.getImeOptions() | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        }
        // Done signs in: the button is under the keyboard
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
        noticeSecondary.setOnClickListener(v -> {
            if (secondaryAction != null) {
                secondaryAction.run();
            }
        });

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
                    correcting = true;
                    jidLayout.setError(null);
                    passwordLayout.setError(null);
                    updateSuffix();
                    updateButtons();
                }
            }
        };
        jid.addTextChangedListener(watcher);
        password.addTextChangedListener(watcher);
        // refresh() fills them: ATAK's restored state would count as an edit, and a password
        // has no place in saved state
        jid.setSaveEnabled(false);
        password.setSaveEnabled(false);
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
        final String beforePassword = before == null ? null : before.getPassword();
        if (!engine.signIn(user, pass)) {
            jidLayout.setError(ui.getString(R.string.invalid_jid));
            return;
        }
        final Account after = engine.getAccount();
        // the form holds what was sent until the server answers
        edited = true;
        correcting = false;
        signingIn = after != null && !(after == before && after.isOnlineAndConnected()
                && Objects.equals(beforePassword, after.getPassword()));
        if (signingIn) {
            startAttempt(before);
        } else {
            // the login in use already, or nothing to sign in to: no attempt to wait for
            edited = after == null;
        }
        hideKeyboard();
        host.onSignInStarted();
        refresh();
    }

    /** Shows a spinner, then how the attempt ended, not the previous attempt's error. */
    private void startAttempt(final Account before) {
        shownError = null;
        // an existing account still shows its last state at first
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
                    signingIn = false;
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

    /** Shows the account's state; from clicks, timeouts and engine changes. */
    private void refresh() {
        // it reads the connection's managers, which change on Conversations' threads
        Guard.run(TAG, "show the account", this::showState);
    }

    private void showState() {
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
        // the account's details only for a login that worked, not over the form
        final boolean shown = showsAccount(tak, account);
        showAvatar(shown ? account : null);
        showStats(shown ? account : null);

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
     * The account's own view, not the login form: with TAK credentials, or a login that worked
     * while the user isn't changing it or signing in again.
     */
    private boolean showsAccount(final boolean tak, final Account account) {
        return account != null && (tak || (!edited && !signingIn
                && account.isOptionSet(Account.OPTION_LOGGED_IN_SUCCESSFULLY)
                && !account.unauthorized()));
    }

    /** TAK server credentials: the address only, once there is an account. */
    private void showTakAccount(final XmppSettings settings, final Account account) {
        edited = false;
        signingIn = false;
        editor.setVisibility(account != null ? View.VISIBLE : View.GONE);
        jidLayout.setHint(ui.getString(R.string.account_settings_jabber_id));
        jid.setSuffix(null);
        setText(jid, account != null ? account.getJid().asBareJid().toString() : "");
        setEditable(jid, false);
        passwordLayout.setVisibility(View.GONE);
        save.setVisibility(View.GONE);
        cancel.setVisibility(View.GONE);
    }

    private void showLoginForm(final XmppSettings settings, final Account account) {
        editor.setVisibility(View.VISIBLE);
        passwordLayout.setVisibility(View.VISIBLE);
        final boolean loggedIn = showsAccount(false, account);
        if (!edited) {
            final String stored = XmppSettings.getLoginUsername();
            setText(jid, stored != null ? stored
                    : settings != null && settings.suggestedUsername != null
                            ? settings.suggestedUsername : "");
            setText(password, loggedIn ? PASSWORD_PLACEHOLDER : "");
        }
        jidLayout.setHint(ui.getString(settings != null && settings.domain != null
                ? R.string.username_hint : R.string.account_settings_jabber_id));
        // like Conversations: fixed once logged in; and while the server checks the login
        setEditable(jid, !loggedIn && !signingIn);
        setEditable(password, !loggedIn && !signingIn);
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
        jid.setSuffix(suffix ? "@" + settings.domain : null);
    }

    private void updateButtons() {
        final XmppSettings settings = engine.getSettings();
        if (settings == null || settings.usesTakCredentials) {
            return;
        }
        final Account account = engine.getAccount();
        final boolean filled = jid.getText().toString().trim().length() > 0
                && password.getText().length() > 0;
        if (signingIn) {
            save.setVisibility(View.VISIBLE);
            save.setText(R.string.account_status_connecting);
            save.setEnabled(false);
        } else if (edited || account == null || shownError != null) {
            save.setVisibility(View.VISIBLE);
            save.setText(R.string.log_in);
            // a new password has to be typed
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
            signingIn = false;
            return;
        }
        final Account.State status = account.getStatus();
        if (attemptFrom != null && status != attemptFrom) {
            attemptFrom = null; // the attempt started or ended
        }
        if (attemptFrom != null) {
            return;
        }
        root.removeCallbacks(attemptTimeout);
        if (account.isOnlineAndConnected()) {
            shownError = null;
            if (signingIn) {
                // the login is confirmed: the account's view from now on
                signingIn = false;
                edited = false;
            }
        } else if (isError(status)) {
            shownError = status;
            // refused, or unreachable: the form again, as filled in, with the error
            signingIn = false;
        }
    }

    private boolean isConnecting(final Account account) {
        if (account == null || account.isOnlineAndConnected()) {
            return false;
        }
        if (signingIn || attemptFrom != null) {
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
            // no password field with TAK server credentials
            errorLayout = !tak && (shownError == Account.State.UNAUTHORIZED
                    || shownError == Account.State.DOWNGRADE_ATTACK)
                    ? passwordLayout : jidLayout;
            error = ui.getString(shownError.getReadableId());
        } else if (problem == XmppSettings.Problem.INVALID_JID) {
            errorLayout = jidLayout;
            error = ui.getString(R.string.invalid_jid);
        }
        if (correcting && errorLayout != null) {
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
        final ServerIdentity pending = engine.getPendingServer();
        String text = null;
        boolean offerLogin = false;
        Runnable secondary = this::useXmppLogin;
        int secondaryText = com.atakmap.android.takconvo.plugin.R.string.takconvo_use_xmpp_login;
        // the device management (MDM) decides between TAK credentials and an XMPP login
        final boolean loginOffered = !engine.isManaged(XmppSettings.KEY_USE_TAK_CREDENTIALS);
        if (problem == XmppSettings.Problem.DISABLED) {
            text = ui.getString(
                    com.atakmap.android.takconvo.plugin.R.string.takconvo_notice_disabled);
        } else if (problem == XmppSettings.Problem.SERVER_UNCONFIRMED && pending != null) {
            text = describe(pending);
            offerLogin = true;
            secondary = () -> approveServer(pending);
            secondaryText = com.atakmap.android.takconvo.plugin.R.string.takconvo_connect;
        } else if (problem == XmppSettings.Problem.NO_TAK_CREDENTIALS) {
            text = ui.getString(com.atakmap.android.takconvo.plugin.R.string
                    .takconvo_notice_no_tak_credentials);
            offerLogin = loginOffered;
        } else if (problem == XmppSettings.Problem.NO_DOMAIN) {
            text = ui.getString(
                    com.atakmap.android.takconvo.plugin.R.string.takconvo_notice_no_domain);
            offerLogin = tak && loginOffered;
        } else if (problem == XmppSettings.Problem.INVALID_JID && tak) {
            text = ui.getString(
                    com.atakmap.android.takconvo.plugin.R.string.takconvo_notice_invalid_jid);
            offerLogin = loginOffered;
        } else if (shownError == Account.State.TLS_ERROR_UNTRUSTED && attemptFrom == null) {
            // the trust settings decide, not a prompt
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
        noticeSecondary.setText(secondaryText);
        secondaryAction = secondary;
    }

    /** Where the credentials would go, for the user to approve. */
    private String describe(final ServerIdentity server) {
        final String connection = server.connection();
        final String where = connection.isEmpty() ? server.domain
                : ui.getString(com.atakmap.android.takconvo.plugin.R.string.takconvo_server_via,
                        server.domain, connection);
        final List<String> trust = new ArrayList<>();
        if (server.takTrustStore) {
            trust.add(ui.getString(
                    com.atakmap.android.takconvo.plugin.R.string.takconvo_trust_tak));
        }
        if (server.androidCaStore) {
            trust.add(ui.getString(
                    com.atakmap.android.takconvo.plugin.R.string.takconvo_trust_android));
        }
        if (server.caPath != null) {
            trust.add(server.caPath);
        }
        final String credentials = !server.takCredentials
                ? ui.getString(
                        com.atakmap.android.takconvo.plugin.R.string.takconvo_credentials_login)
                : server.takServer == null
                        ? ui.getString(com.atakmap.android.takconvo.plugin.R.string
                                .takconvo_credentials_tak)
                        : ui.getString(com.atakmap.android.takconvo.plugin.R.string
                                .takconvo_credentials_tak_server, server.takServer);
        return ui.getString(
                com.atakmap.android.takconvo.plugin.R.string.takconvo_notice_server_unconfirmed,
                where,
                credentials,
                trust.isEmpty()
                        ? ui.getString(
                                com.atakmap.android.takconvo.plugin.R.string.takconvo_trust_none)
                        : TextUtils.join(", ", trust));
    }

    private void approveServer(final ServerIdentity server) {
        final Account before = engine.getAccount();
        engine.approveServer(server);
        startAttempt(before);
        host.onSignInStarted();
        refresh();
    }

    /** Shown once the account has worked. */
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

    /** Fills the server info card. */
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
            case SERVER_UNCONFIRMED:
                return com.atakmap.android.takconvo.plugin.R.string
                        .takconvo_status_server_unconfirmed;
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
