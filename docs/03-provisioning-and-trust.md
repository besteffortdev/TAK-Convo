# 03 — Provisioning, credentials and trust

The user types nothing XMPP-specific: the XMPP domain arrives in a `.pref` file (on its own or
in a mission package), the login reuses the TAK server's username and password, and the
server's certificate is checked against the CAs ATAK already trusts for its TAK servers.

Classes: `plugin/config/XmppSettings`, `TrustSources`, `TrustedCa`; `plugin/xmpp/XmppEngine`;
`plugin/ui/TakConvoPreferenceFragment`, `AccountView`.

## Settings

All settings are ATAK preferences, so ATAK's own import (`.pref` files, mission packages, the
import manager) provisions them, and they appear under
**Settings › Tool Preferences › TAK Convo**.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `takconvo_xmpp_enabled` | Boolean | `true` | connect at all |
| `takconvo_xmpp_domain` | String | — | XMPP domain; the JID becomes `<username>@<domain>` |
| `takconvo_xmpp_host` | String | — | only if the domain has no reachable SRV/A record |
| `takconvo_xmpp_port` | String | `5222` | port for `takconvo_xmpp_host` |
| `takconvo_xmpp_use_tak_credentials` | Boolean | `true` | reuse the TAK server username/password |
| `takconvo_xmpp_username` | String | — | username suggested on the XMPP login screen |
| `takconvo_xmpp_use_tak_truststore` | Boolean | `true` | trust the CAs of ATAK's TAK server truststores |
| `takconvo_xmpp_use_android_ca_store` | Boolean | `false` | trust the device CA store, including user/MDM CAs |
| `takconvo_xmpp_trusted_ca` | String | — | path of a PEM/DER CA file to trust |
| `takconvo_xmpp_channel_discovery` | String | `xmpp_server` | where "Discover channels" looks: `xmpp_server` (the group chat services of the account's server), `server` (the next key) or `public` (the public directory search.jabber.network) |
| `takconvo_xmpp_channel_server` | String | — | for `server`: a server (`example.org`) or one of its group chat services (`conference.example.org`) |
| `takconvo_xmpp_use_callsign` | Boolean | `true` | the ATAK callsign is the XMPP nickname (see below) |
| `takconvo_notification_sound` | Boolean | `true` | message notifications play the notification sound |
| `takconvo_notification_vibrate` | Boolean | `true` | message notifications vibrate (see [08](08-contacts-and-notifications.md#sound-and-vibration)) |

Upstream Conversations defaults to the public directory, which asks before sending a search to
it. Inside ATAK the default is the account's own server: on an organisation's network the
public directory is out of reach or has nothing of interest. `provision()` hands the choice to
Conversations (its `channel_discovery_method` preference: `LOCAL_SERVER` or `JABBER_NETWORK`)
and sets `TakConvoCompat.CHANNEL_DISCOVERY_SERVER` for another server (see
[05](05-conversations-fork.md#i-channel-discovery-on-another-server)). An address that isn't
valid falls back to the account's server.

Public CAs are always trusted, as in Conversations. `provisioning/takconvo-template.pref`
documents the keys. A `.pref` file may carry Booleans as strings; `XmppSettings.normalize()`
stores them as Booleans, because the preference check boxes fail on strings.

## Credentials

```text
XmppSettings.load():
    read the takconvo_xmpp_* preferences
    if use_tak_credentials:
        for server in (connected TAK servers) + (enabled TAK servers):
            creds = AtakAuthenticationDatabase[TYPE_COT_SERVICE, host of server]
                    ?: server's own username/password
            if creds: return settings(creds, source = TAK_SERVER)
        return settings(no credentials, source = NONE)   # no fallback to the XMPP login
    else:
        login = AtakAuthenticationDatabase["takconvo.xmpp"]
        return settings(login, source = LOGIN) or settings(source = NONE)

problem():                     # why no account can be provisioned, or null
    !enabled                         -> DISABLED
    no username/password             -> NO_TAK_CREDENTIALS | NOT_SIGNED_IN
    no domain, username has no '@'   -> NO_DOMAIN
    otherwise                        -> null        (jid() = username or username@domain)
```

With TAK credentials there is deliberately **no fallback** to a stored XMPP login. At ATAK
start-up the TAK server credentials are often not available yet, and silently switching to
another identity would provision the wrong account.

When TAK credentials are not used, the account pane is a login form. The password goes to
ATAK's encrypted credential store under type `takconvo.xmpp`, never into preferences or `.pref`
files.

## Provisioning the account

The device has one XMPP identity. `provision()` makes Conversations' account list match the
settings. It is idempotent and runs at start and whenever an input changes. Accounts are
**disabled, never deleted**, so history survives a transient or mistaken configuration.

```text
XmppEngine.provision():
    settings = XmppSettings.load(); trustChanged = applyTrust()
    problem  = settings.problem()
    if problem == NO_TAK_CREDENTIALS:
        # TAK credentials often arrive after the plugin starts: keep the account on the
        # configured domain as it is, disable the others
        disable accounts not on settings.domain; unprovision(); return
    if problem != null or settings.jid() is not a valid JID:
        disable all accounts; unprovision(); return

    # upstream only honours an account's host/port with "extended connection settings" on
    Conversations preference SHOW_CONNECTION_OPTIONS = (settings.host != null)

    jid = bare JID of settings
    disable every enabled account other than jid
    account = service.findAccountByJid(jid)
    if account == null:
        service.createAccount(new Account(jid, password, host, port))
    else if password/host/port changed or account disabled:
        update and enable it; service.updateAccount(account)
    else if trustChanged and not connected:
        service.reconnectAccountInBackground(account)    # don't wait out the back-off
    advertise(jid)                                       # saXmppUsername, see 02
    notify listeners

unprovision(): advertise(null); notify listeners
```

Inputs that re-run `provision()` (debounced by 750 ms, because a `.pref` import changes several
keys at once):

- any `takconvo_xmpp_*` preference change: `.pref` import, tool preferences, `adb`;
- TAK server connections added, changed or connected (`CommsMapComponent` outputs listener);
- the device trust store changing (`KeyChain.ACTION_TRUST_STORE_CHANGED`,
  `ACTION_KEYCHAIN_CHANGED`).

`signIn(user, password)` stores the XMPP login and provisions; `signOut()` clears it, empties
the account's password and provisions, which disables the account.

## Trust

Conversations checks server certificates with its `MemorizingTrustManager`: the system CAs,
then certificates the user accepted before. Inside ATAK the server is often signed by an
organisation's CA that isn't in the system store but is in ATAK's TAK server truststore. The
fork adds one hook (see [05](05-conversations-fork.md#b-trust-cas-provisioned-by-the-plugin)):

```text
MemorizingTrustManager.checkCertTrusted(chain):          # fork change
    try defaultTrustManager.check(chain); return
    catch CertificateException:
        if TakConvoCompat.EXTRA_TRUST_MANAGER != null:
            try EXTRA_TRUST_MANAGER.check(chain); return  # "trusted via TAK Convo provisioned CA"
            catch: pass
        ... upstream handling (memorized certificates, user prompt, reject)
```

Hostname verification is not affected: a certificate still has to name the server.

`TrustSources.build(settings)` is a composite that accepts a chain if **any** enabled source
validates it:

| Source | Setting | Read from |
|---|---|---|
| TAK server truststores | `use_tak_truststore` (default on) | `CertificateManager.getInstance().getLocalTrustManager((String) null)`: rebuilt from ATAK's certificate database on each call, so truststores imported since start count |
| Android CA store | `use_android_ca_store` (default off) | `KeyStore "AndroidCAStore"`: system and user CAs, including MDM-installed ones, which apps don't trust by default |
| CA file | `trusted_ca` | `TrustedCa.load(path)`: one or more PEM/DER certificates |

```text
applyTrust():
    trust = TrustSources.build(settings)           # null when no source has a CA
    TakConvoCompat.EXTRA_TRUST_MANAGER = trust
    fingerprint = SHA-256 over the sorted SHA-256s of trust.acceptedIssuers
    return fingerprint changed since last call     # -> provision() reconnects if offline
```

The trust manager is installed **before** `service.onStartCommand()`, because the service
connects stored accounts right away.

## The callsign as nickname

With `takconvo_xmpp_use_callsign` on (the default), the ATAK callsign (ATAK's
`locationCallsign` preference) is the account's XMPP nickname:

```text
XmppEngine.syncCallsign():        # each engine change (e.g. the account came online), and
                                  # each change of locationCallsign; only while online
    if account.displayName != callsign:
        account.displayName = callsign; save
        publishDisplayName(account)   # User Nickname (XEP-0172): what contacts' clients show
        checkMucRequiresRename()      # group chats whose nickname is the display name
    for each group chat of the account:
        if its bookmark has another nickname (once per room and callsign):
            BookmarkManager.create(bookmark with nick = callsign)
            # the server echoes the bookmark; Conversations renames itself in the room then
        if joined under another nickname (once per room and callsign):
            MultiUserChatManager.checkMucRequiresRename(room)
            # a room joined right after connecting, before the display name changed
```

Changing the callsign in ATAK renames the user everywhere within a fraction of a second. "Once
per room and callsign": a nickname the room refuses (taken) isn't retried on every change.

## The account pane

`AccountView` shows Conversations' own `activity_edit_account` layout in an ATAK pane, in
`Theme.Conversations3.Dark`, driven by `XmppEngine` instead of `EditAccountActivity`: status,
the account's address, server features, and login/logout in login mode. Parts that don't apply
to a provisioned account (registration, port fields, push, OMEMO regeneration...) are hidden.
It uses the same scaled-down UI context as the chat pane (`UiScale`, see
[04](04-chat-pane-activity-host.md#configuration-and-theme)), sized for the pane: 40 % of the
map, like the chat pane, which selects Conversations' narrow layouts. Tapping the avatar
(online) opens Conversations' `PublishProfilePictureActivity` in the chat pane over it, to pick
and publish a profile picture. The account pane stays under it (it is retained), and shows
again once the picture is published or the user goes back
([04](04-chat-pane-activity-host.md#showing-the-pane-from-elsewhere)).

```text
refresh():
    TAK credentials:  address and password fields hidden until there is an account; then the
                      address only, read-only. The notice explains a missing account and offers
                      "Use an XMPP account", which switches to the login form.
    login form:       address and password, editable until the account has logged in once;
                      no keyboard extract mode (IME_FLAG_NO_EXTRACT_UI / NO_FULLSCREEN), and
                      "Done" on the password signs in (the button is under the keyboard)
    avatar:           only once the account is online or has logged in before
    spinner:          while the account connects and no error is shown
    error:            the state the last attempt ended with (shownError), under the address or
                      password field; it stays while Conversations retries in the background,
                      until the account connects or the user signs in or reconnects again
    TLS_ERROR_UNTRUSTED: the notice explains the trust sources (Conversations would ask
                      whether to trust the certificate; here the settings decide)

signIn() / Reconnect:
    engine.signIn(...)                 # reconnects even when nothing changed
    shownError = null; attemptFrom = the account's state now
    # the reconnection is asynchronous: while the state is still attemptFrom, it is the
    # previous attempt's and shows as the spinner (for at most 30 s)
```

A plugin view outside an `AppCompatActivity` has no `AppCompatDelegate` to turn `<Button>`,
`<TextView>`... tags into their Material versions, so they lost their styling.
`ConversationsInflater` installs Material's `MaterialComponentsViewInflater` as the
`LayoutInflater` factory, with the arguments `AppCompatDelegateImpl` passes.

The chat pane opens this pane for Conversations' "Manage accounts" and for an account's own
details, and when there is no provisioned account. In that last case, and whenever the user
signs in from it (`Host.onSignInStarted`), the plugin swaps it for the chat list once the
account is online (`TakConvoPlugin.showChatIfSignedIn`): logging in lands on the chats. Only if
the account pane is still showing; opened deliberately with an account online, it stays.

## Tool preferences

`TakConvoPreferenceFragment` (Settings › Tool Preferences › TAK Convo) edits the keys above.
It also has an entry for the account pane, a CA file picker (`.pem`, `.crt`, `.cer`, `.der`,
checked with `TrustedCa.load`), and **Import .pref file**, which uses ATAK's own importer
(`PreferenceControl.loadSettings`), the one mission packages go through.
