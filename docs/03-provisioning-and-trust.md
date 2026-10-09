# 03 — Provisioning, credentials and trust

The user types nothing XMPP-specific: the XMPP domain arrives in a `.pref` file (on its own or
in a mission package), the login reuses the TAK server's username and password, and the
server's certificate is checked against the CAs ATAK already trusts for its TAK servers.

Classes: `plugin/config/XmppSettings`, `ConversationsSettings`, `TrustSources`, `TrustedCa`,
`ServerIdentity`, `PrivateFiles`, `AppConfig`, `AppConfigProvider`; `plugin/xmpp/XmppEngine`,
`CallsignNicknames`;
`plugin/ui/TakConvoPreferenceFragment`, `AccountView`.

## Settings

All settings are ATAK preferences, so ATAK's own import (`.pref` files, mission packages, the
import manager) provisions every one of them. An MDM can set them too, and then they're locked
([below](#managed-configuration-mdm)). Those in the table also appear under
**Settings › Tool Preferences › TAK Convo**; Conversations' own settings and the login are
.pref-only (below).

| Key | Type | Default | Meaning |
|---|---|---|---|
| `takconvo_xmpp_enabled` | Boolean | `true` | connect at all |
| `takconvo_xmpp_domain` | String | — | XMPP domain; the JID becomes `<username>@<domain>`, also for a TAK username with a domain of its own (see Credentials) |
| `takconvo_xmpp_host` | String | — | only if the domain has no reachable SRV/A record |
| `takconvo_xmpp_port` | String | `5222` | port for `takconvo_xmpp_host` |
| `takconvo_xmpp_use_tak_credentials` | Boolean | `true` | reuse the TAK server username/password |
| `takconvo_xmpp_tak_server` | String | — | the host of the TAK server whose credentials to use, and no other's; empty: chosen (see Credentials) |
| `takconvo_xmpp_username` | String | — | the XMPP login's username; without a password, only suggested on the login screen |
| `takconvo_xmpp_password` | String | — | the XMPP login's password, moved to ATAK's credential store at once (see Credentials) |
| `takconvo_xmpp_use_tak_truststore` | Boolean | `true` | trust the CAs of ATAK's TAK server truststores |
| `takconvo_xmpp_use_android_ca_store` | Boolean | `false` | trust the device CA store, including user/MDM CAs |
| `takconvo_xmpp_trusted_ca` | String | — | path of a PEM/DER CA file to trust |
| `takconvo_xmpp_channel_discovery` | String | `xmpp_server` | where "Discover channels" looks: `xmpp_server` (the group chat services of the account's server), `server` (the next key) or `public` (the public directory search.jabber.network) |
| `takconvo_xmpp_channel_server` | String | — | for `server`: a server (`example.org`) or one of its group chat services (`conference.example.org`) |
| `takconvo_xmpp_use_callsign` | Boolean | `true` | the ATAK callsign is the XMPP nickname (see below) |
| `takconvo_notification_messages` | Boolean | `false` | new messages notify while the chats are closed; off because the Conversations app on the device, same account, notifies (see [08](08-contacts-and-notifications.md#off-by-default)) |
| `takconvo_notification_sound` | Boolean | `true` | message notifications play the notification sound |
| `takconvo_notification_vibrate` | Boolean | `true` | message notifications vibrate (see [08](08-contacts-and-notifications.md#sound-and-vibration)) |
| `takconvo_show_quick_messages` | Boolean | `false` | show the quick message buttons above the message field (see [10](10-atak-map-integration.md#quick-messages)) |
| `takconvo_quick_messages` | String | `Roger\|Wilco\|Say again\|In position\|Moving\|All secure` | their texts, separated by `\|`; empty for none |

Upstream Conversations defaults to the public directory, which asks before sending a search to
it. Inside ATAK the default is the account's own server: on an organisation's network the
public directory is out of reach or has nothing of interest. `provision()` hands the choice to
Conversations (its `channel_discovery_method` preference: `LOCAL_SERVER` or `JABBER_NETWORK`)
and sets `TakConvoCompat.CHANNEL_DISCOVERY_SERVER` for another server (see
[05](05-conversations-fork.md#i-channel-discovery-on-another-server)). An address that isn't
valid falls back to the account's server.

Public CAs are always trusted, as in Conversations. `provisioning/takconvo-template.pref`
documents the keys. A `.pref` file may carry Booleans as strings; `XmppSettings.normalize()`
stores them as Booleans, because the preference check boxes fail on strings. Code that reads
one of these Booleans before `normalize()` ran goes through `XmppSettings`' getters, which take
either type (the quick messages' switch, read by every chat as it opens, for example).

### Conversations' own settings

The plugin's settings page replaces Conversations' settings screen. Conversations' settings
are set with ATAK preferences named `takconvo_conversations_<key>` (the keys and values are
listed in the template), which only a `.pref` file sets:

```text
ConversationsSettings.apply(atak, conversations):   # engine start, and each change of one
    for each takconvo_conversations_<key> in ATAK's preferences:
        unknown key or invalid value: log a warning, skip
        switch: putBoolean(key, value)               # "true" / "false", as String or Boolean
        list or number: putString(key, value)        # Conversations parses numbers itself
    keys applied last time but gone now: remove      # back to Conversations' default
    remember the applied keys (takconvo_applied_settings)
```

Supported are the settings that work inside ATAK and that the plugin doesn't manage:
privacy, security, availability, attachments, chat appearance, and two notification
settings. Left out:
- theme and dynamic colours: ATAK is dark;
- connection options and channel discovery: the plugin's own settings;
- notification sound, vibration, LED and heads-up: the plugin's notification channels;
- calls, the foreground service, crash reports, shared storage, backups and UnifiedPush: not
  available embedded;
- screenshot blocking: it applies to the activity's own window, which is never shown.

Most settings are read when used. The connection ones (TLS 1.3, channel binding, Tor, system
CAs) apply at the next connection.

## Credentials

```text
XmppSettings.load():
    read the takconvo_xmpp_* preferences
    if use_tak_credentials:
        servers = (connected TAK servers) + (enabled TAK servers)
        if tak_server is set: one group, the servers with that host
        else: two groups, those on the XMPP domain or host,  # tak.example.org for
              then the others                                #   example.org or
                                                             #   xmpp.example.org
        for each server: creds =
            AtakAuthenticationDatabase[TYPE_COT_SERVICE, host of server]
                ?: server's own username/password       # ATAK's credentials dialog
                ?: only a username from either: username +
                   AtakAuthenticationDatabase["takconvo.tak", host] if saved for that
                   username, else no password           # the user enters it, below
        in the first group, else the second:
            the first creds with a password, else the first with only a username
        if creds: return settings(creds, source = TAK_SERVER, origin = host of server)
        return settings(no credentials, source = NONE)   # no fallback to the XMPP login
    else:
        login = AtakAuthenticationDatabase["takconvo.xmpp"]
        return settings(login, source = LOGIN) or settings(source = NONE)

problem():                     # why no account can be provisioned, or null
    !enabled                         -> DISABLED
    no username                      -> NO_TAK_CREDENTIALS | NOT_SIGNED_IN
    no domain, username has no '@'   -> NO_DOMAIN
    no password                      -> NO_TAK_PASSWORD | NOT_SIGNED_IN
    otherwise                        -> null        (jid(), below)

jid():
    username without '@'             -> username@domain
    TAK username user@other, a domain set and not "other"
                                     -> user@domain     # a TAK login isn't an XMPP address
    otherwise                        -> username        # an XMPP login's full address
```

A TAK server whose users log in with their Windows login (`alice@corp.example`, the UPN) gives
`alice@<XMPP domain>`, as a TAK login without `@` does: the XMPP server's own user is usually
the account name (Openfire's LDAP: `sAMAccountName`). The configured domain also keeps the
server identity the MDM sets. An XMPP server whose usernames are the whole UPN needs them
escaped (`alice\40corp.example@domain`, XEP-0106) instead, which TAK Convo doesn't do; with no
domain set, the UPN itself is the address.

With TAK credentials there is deliberately **no fallback** to a stored XMPP login. At ATAK
start-up the TAK server credentials are often not available yet, and silently switching to
another identity would provision the wrong account.

A device often has several TAK server connections, for example its own and a partner's from a
data package. Which one connects first changes from one start to the next, and each has its own
password. The servers on the XMPP domain come first, so the XMPP identity stays the same. The
TAK server whose credentials go to the XMPP server is part of its identity (next section), so
another one's password goes there only once the user approves it. `takconvo_xmpp_tak_server`
names the one to use (in the tool preferences, a list of ATAK's TAK servers): then only its
credentials are used, and none while it has none.

### A TAK server without its password

ATAK doesn't always keep the TAK server's password. It keeps only the username:

- when the connection's credentials option is "Cache username" (or "Do not cache", which
  keeps neither);
- typically on networks that **enroll for a client certificate**: once enrolled, ATAK connects
  with the certificate and never needs the password again. Enrollment itself saves both
  (`CertificateEnrollmentClient.onEnrollmentOk`, the quick-connect and QR code path), but a
  server added by hand follows its cache option;
- with "Use Authentication" off and "Enroll for client certificate" on, ATAK asks for the
  username and password in a dialog without a cache option, and saves neither. They live in the
  connection's in-memory settings until ATAK restarts, then they are gone. TAK Convo uses them
  until then, and asks for the password after a restart only if the username is still there.

The XMPP server needs the password at each login. When ATAK has only the username,
`problem()` is `NO_TAK_PASSWORD`:

```text
XmppEngine.apply(loaded), NO_TAK_PASSWORD:
    the server must be approved first, as for TAK credentials (Approving the server): a
    .pref file can't get the user asked for a TAK password to send to a server of its own
    the username isn't a valid address  -> INVALID_JID, no question
    otherwise: disable all accounts, unprovision      # none connects with a password
                                                      #   stored before

account pane: the address the TAK username signs in as (read-only), a password field,
              Log in; the notice names the TAK server
signInTak(password):              # XmppEngine
    AtakAuthenticationDatabase["takconvo.tak", host] = (TAK username, password)
    provisionNow()                # the TAK credentials now have a password
forgetRefusedTakPassword():       # each dispatch of the engine's changes
    the account of an entered password is UNAUTHORIZED:
        delete it from "takconvo.tak"; takPasswordRefused = true; provisionNow()
        # NO_TAK_PASSWORD again: the account is disabled, the pane asks again and says the
        # password was refused
```

Conversations retries a refused login with a backoff (32 s, 42 s, ... in the test). With a
password typed by hand, and usually the organisation's directory password, a directory that
locks an account after a few failures would lock the user's after a typo; so a refused entered
password is tried once. Disabling sets the account's state to `OFFLINE` at once
(`Account.setOption`), so a later sign-in doesn't see the old `UNAUTHORIZED`. A password from
ATAK's own store, or an XMPP login, still gets Conversations' retries.

The entered password is used only while ATAK keeps the same username for that host. A password
in ATAK's own store, or in the connection's settings, still comes first. A server on the XMPP
domain without its password comes before another domain's server that has one: the user's own
password, not a partner server's. Clear Content deletes the entered passwords with the rest
([02](02-embedded-engine.md#ataks-clear-content)).

When TAK credentials are not used, the account pane is a login form. The password goes to
ATAK's encrypted credential store under type `takconvo.xmpp`. With a domain set, the username
field shows `@<domain>` in grey after what is typed (`ui/DomainSuffixField`, in place of the
layout's field); a long username keeps the field and the domain runs out of it on the right.
Material's suffix text kept its whole width instead, and pushed the username out to the left.

A `.pref` file can also provision the login, with `takconvo_xmpp_username` and
`takconvo_xmpp_password`. The password doesn't stay in the preferences:

```text
XmppSettings.importLogin(prefs):          # at the start of each load(), below
    if takconvo_xmpp_password is set:
        if takconvo_xmpp_username is set: saveLogin(username, password)   # credential store
        else: log a warning
        remove takconvo_xmpp_password from the preferences
```

ATAK's preference exports therefore never carry it. The `.pref` file itself holds the password
in clear text, as ATAK's own `.pref` files hold certificate passwords: distribute it the same
way.

## Provisioning the account

The device has one XMPP identity. `provision()` makes Conversations' account list match the
settings. It is idempotent and runs at start and whenever an input changes. Accounts are
**disabled, never deleted**, so history survives a transient or mistaken configuration.

Reading the settings means reading ATAK's encrypted credential store and its certificate
database, the Android CA store when enabled, and hashing every trusted CA. That is disk and
crypto work, and the inputs below change often (each TAK server status change), so it runs
on a thread of its own, and only the account changes run on the main thread:

```text
XmppEngine.provision():                        # main thread
    run = ++provisionRun
    on the TakConvo.Provision thread:
        loaded = load()                        # importLogin, normalize, XmppSettings.load,
                                               #   TrustSources.build and its fingerprint
        then on the main thread: if run is still the latest and not stopped: apply(loaded)

provisionNow():                                # sign-in and sign-out: the account pane
    ++provisionRun; apply(load())              #   compares the account before and after

XmppEngine.apply(loaded):                      # main thread; the first one then lets the
                                               #   stored accounts connect (see Trust)
    settings = loaded.settings
    problem  = settings.problem()
    if problem is null, NO_TAK_CREDENTIALS or NO_TAK_PASSWORD, and loaded.server isn't
            approved (next section):
        problem = SERVER_UNCONFIRMED; trust nothing extra; disable all accounts
        unprovision(); return
    trustChanged = applyTrust(loaded)
    if problem == NO_TAK_PASSWORD and settings.jid() is not a valid JID:
        problem = INVALID_JID                          # no question it couldn't use
    if problem == NO_TAK_CREDENTIALS:
        # TAK credentials often arrive after the plugin starts: keep the account on the
        # configured domain as it is, disable the others
        disable accounts not on settings.domain; unprovision(); return
    if problem != null or settings.jid() is not a valid JID:   # NO_TAK_PASSWORD among them
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
the account's password and provisions, which disables the account. `signInTak(password)`
stores the password of a TAK server ATAK keeps only the username of, and provisions
([A TAK server without its password](#a-tak-server-without-its-password)).

## Approving the server

ATAK applies a `.pref` file found in an imported data package without asking (its
`pref_import_pref_action` defaults to allow), and anyone who can send the device a data
package can make it import one. Without a check, a `.pref` setting `takconvo_xmpp_domain` (and
a CA to trust) would make TAK Convo send the TAK server password, in SASL PLAIN, to a server of
the sender's choosing. So the credentials go only to a server the user approved:

```text
ServerIdentity.of(settings):     # read with the settings, on the provisioning thread
    credentials: TAK or XMPP login; for TAK, the TAK server they belong to (none yet: null)
    domain (the JID's), host, port
    trust: TAK truststores on/off, Android CA store on/off, CA file path and its SHA-256

isApproved(server):
    no domain, or equal to the approved one        -> yes
    no TAK credentials yet, otherwise the approved one
                                                   -> yes        # nothing new goes out
    the user is signing in (login form)            -> approve it, yes
    the MDM's settings give it (below)             -> approve it, yes
    nothing approved yet, and an enabled account has logged in to that domain, host and
    port                                           -> approve it, yes    # set up before this check
    the approved one without a TAK server, otherwise the same
                                                   -> approve it, yes    # saved by an older
                                                                         #   version, or before
                                                                         #   any TAK credentials
    otherwise                                      -> no: SERVER_UNCONFIRMED
```

While a server waits:

- every account is disabled and no extra CA is trusted, so nothing connects;
- an ATAK notification ("approve the new server") opens the account pane;
- the account pane names the server, how it signs in (with TAK credentials, which TAK server's)
  and the extra CAs it trusts, with **Connect** (`XmppEngine.approveServer`, then provisions at
  once, as a sign-in does).

Setting the values back to the approved ones reconnects without a question: approval is a
comparison, not a state to clear. A first setup from a `.pref` file therefore asks once too.
Changes made in the tool preferences ask as well: the check can't tell who changed a value.

The approved server is stored in a file, `no_backup/takconvo_plugin/approved_server` in ATAK's
data directory (`PrivateFiles`), not in a preferences file: ATAK's `.pref` import writes any
preferences file a `<preference name="...">` names (`PreferenceControl.loadSettings` calls
`getSharedPreferences(name)`), the plugin's own and Conversations' included.

What a `.pref` file can still do:

- **Conversations' own settings**: the supported ones through `takconvo_conversations_*`, and
  any of them by naming Conversations' preferences file (`takconvo_<package>_preferences`). The
  ones that matter for security (OMEMO, blind trust, system CAs, TLS 1.3) are supported
  settings anyway.
- **Add a TAK server with its CA**: with "Use TAK server truststore" on, that CA is trusted for
  XMPP as well. Using it takes an attacker on the network path too, and the server list shows
  the new server.
- **Turn TAK Convo off** (`takconvo_xmpp_enabled`), as it can turn off any ATAK feature.

## Managed configuration (MDM)

An MDM (SOTI MobiControl, Intune, Workspace ONE...) that installs the plugin APK can also set
its settings through Android's managed configurations ("app config").
`app/src/main/res/xml/app_restrictions.xml` declares them, and the manifest points to it
(`android.content.APP_RESTRICTIONS`), so the MDM console lists them for TAK Convo's package,
with titles and descriptions. Each key is the ATAK preference it sets: the table above and
`takconvo_conversations_<key>`. Two more aren't preferences:

| Key | Meaning |
|---|---|
| `takconvo_xmpp_password` | with `takconvo_xmpp_username`: the XMPP login, stored in ATAK's credential store |
| `takconvo_xmpp_trusted_ca_certificate` | a CA certificate as text: PEM (several may follow each other, whatever the console does to the line breaks) or Base64 DER |

Booleans and lists are choices that start at **Not managed** (`unset`). A console that sends
every key with its default value therefore manages nothing until the admin picks a value.
SOTI MobiControl adds a **Do nothing** of its own to each choice, which sends nothing: the same.
The choices' labels are plain text in `res/values/app_config.xml`, not `@string` references:
MobiControl, which reads the schema from the APK itself, showed other strings of the APK for
references inside an array (their values were right). An
empty text is not managed either. A console that sends its own key/value pairs may give
Booleans as `true`/`false` strings or as Booleans, and the port as a string or a number.

### Reading it

Android gives a package's managed configuration only to that package
(`RestrictionsManager.getApplicationRestrictions` checks the caller's uid), and the plugin's
code runs as ATAK. A provider in the plugin's own process (`:appconfig`) reads it for ATAK:

```text
AppConfigProvider.call("get"):          # plugin package, process :appconfig, exported
    caller isn't ATAK (getCallingPackage)  -> SecurityException
    nor signed as ATAK (below)             -> SecurityException
    return RestrictionsManager.getApplicationRestrictions()   # + DEBUG_APP_CONFIG's, debug builds

XmppEngine.refreshAppConfig():          # at start, and when an ATAK activity resumes
    on the TakConvo.Provision thread:
        bundle = unstable ContentProviderClient(AppConfigProvider).call("get")
        bundle null (provider unreachable): change nothing
        config = AppConfig.load(bundle)     # validates, stores the CA file and the login
        changed: save it to no_backup/takconvo_plugin/managed_config
    then on the main thread: applyAppConfig(config)
```

ATAK can see the plugin's package (its `<queries>` names the plugin discovery intent), so it
can reach the provider. The client is "unstable": if the plugin's process dies during a call,
ATAK gets an error instead of being killed with it. That process runs only this provider: the
other providers in the merged manifest (androidx startup) belong to the default process. ATAK's
classes don't exist there, so the provider logs with `android.util.Log`.

The package name alone isn't enough: while ATAK isn't installed (before the MDM installs it, or
after it's removed), any app can be installed under `com.atakmap.app.civ` and would read the
managed XMPP password. So the caller's signing certificate must also be:

1. the plugin's own (`PackageManager.checkSignatures`): developer ATAK and debug plugins are both
   signed with the SDK's development key. That key ships with every SDK, so it protects nothing
   on a test device; a release plugin is signed by TAK.gov;
2. one listed at build time: `-PatakSigners=<sha256>,...` (`BuildConfig.ATAK_SIGNERS`, empty by
   default), the SHA-256 of `apksigner verify --print-certs atak.apk`;
3. otherwise that of the first ATAK that called, remembered in the plugin's own storage
   (`takconvo_app_config_caller`, which neither ATAK nor a `.pref` file reaches). A key ATAK
   rotates keeps the earlier ones in its signing history, which counts.

Release ATAK's key isn't known here, hence 3: an app installed under ATAK's name after the first
call is refused, one installed before ATAK ever ran isn't. Setting `atakSigners` closes that
too. The plugin's `<queries>` names ATAK's package, so it can read the caller's certificates.

Android announces a change (`ACTION_APPLICATION_RESTRICTIONS_CHANGED`) only to a running process
of the package, and the plugin's process doesn't run. TAK Convo reads the configuration again
each time one of ATAK's activities resumes. A change pushed while ATAK is on screen applies the
next time the user comes back to ATAK.

### Applying it

```text
AppConfig.load(bundle):
    each key: a plugin setting or takconvo_conversations_<key>, typed and validated;
              empty or "unset": left out; unknown or invalid: logged, left out
    trusted_ca_certificate: rewritten as PEM to no_backup/takconvo_plugin/managed_ca_<sha>.pem,
              and takconvo_xmpp_trusted_ca = its path
    username and password set, use_tak_credentials not: use_tak_credentials = false
    domain set: host, port, use_tak_credentials, tak_server, use_tak_truststore,
              use_android_ca_store and trusted_ca are managed too, at their defaults unless set
              server = the ServerIdentity these values give (no tak_server: any TAK server)
    username and password set: stored in the credential store, unless it holds them already

applyAppConfig(config):                 # main thread
    write each managed value into ATAK's preferences where it differs
    keys the previous configuration managed and this one doesn't: removed (their defaults)
    configuration changed or login stored: provision
```

**Locked.** Nothing else can change a managed key. When anything writes one (a `.pref` import,
`adb`), the engine's preference listener puts the managed value back before provisioning reads
it. The tool preferences show managed settings greyed out, under "Managed by your
organization". The account pane doesn't offer "Use an XMPP account" when the MDM decides which
credentials to use. At start, the configuration read last time is applied before anything
reads the preferences, so a `.pref` imported while the plugin wasn't running doesn't stick
either.

**Approved.** The server the MDM's values give is approved without asking: `isApproved` also
accepts what `appConfig.approves()`. That is safe because managing the domain manages every other
part of the server identity. The settings give the MDM's identity only if nothing else changed
them. Unless the MDM names the TAK server (`takconvo_xmpp_tak_server`), the credentials of any
TAK server are approved with it, those of one on the XMPP domain first.
The CA file is named after its contents, so a new certificate is a new identity, and the file
the current settings name stays until the new configuration is applied.

**Removed.** A key the MDM no longer sets goes back to its default, as Conversations' settings
do. Removing the domain therefore disables the account (its history stays). A managed login
stays in the credential store.

**Login.** A username and password from the MDM are used instead of the TAK credentials: they
turn "Use TAK server credentials" off unless the MDM sets it (set to on, the login is stored,
unused, and a warning logged). While the MDM sets the login, a `.pref` file's
`takconvo_xmpp_password` is discarded. A user who signs out is signed in again at the next read.

ATAK reads an app config of its own too, for ATAK's package:
`enterpriseConfigurationPreferences` there carries a whole `.pref` file, and TAK Convo's keys
work in it. That file counts as any other `.pref` file, though: a new server waits for the
user's approval, and nothing is locked.

Clear Content deletes `no_backup/takconvo_plugin/`, the managed configuration and the CA file
included. The next start reads them from the MDM again.

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

The trust manager is installed **before** the stored accounts connect. Reading the trust
sources and the TAK credentials takes ATAK's databases and the Android CA store, so at start it
runs on the provisioning thread, not while ATAK starts. Until the first `apply()`, the service
holds its connections (`TakConvoCompat.HOLD_CONNECTIONS`, see
[05](05-conversations-fork.md#b-trust-cas-provisioned-by-the-plugin)): the system's connectivity
broadcast, an alarm or a screen opening would otherwise connect them without the trust manager,
and before the server's approval is checked. That first `apply()` then calls
`service.onStartCommand()`.

## The callsign as nickname

With `takconvo_xmpp_use_callsign` on (the default), the ATAK callsign (ATAK's
`locationCallsign` preference) is the account's XMPP nickname:

```text
CallsignNicknames.sync():         # each engine change (e.g. the account came online), and
                                  # each change of locationCallsign; only while online
    if account.displayName != callsign:
        account.displayName = callsign; save
        publishDisplayName(account)   # User Nickname (XEP-0172): what contacts' clients show
        checkMucRequiresRename()      # group chats whose nickname is the display name
    for each open group chat of the account:
        if the callsign is marked taken there: skip      # keeps its nickname
        if not joined:
            if the join failed with "nickname in use" and proposed the callsign:
                mark taken; changeUsername(room, its last other nickname, else the username)
            continue                  # a pending join: re-check every 3 s, up to 30 s
        remember its nickname if it isn't the callsign
        if another account is in the room under the callsign:
            mark taken; bookmark nick = current nickname; continue
        if its bookmark has another nickname (once per room and callsign):
            BookmarkManager.create(bookmark with nick = callsign)
            # the server echoes the bookmark; Conversations renames itself in the room then
        if joined under another nickname (once per room and callsign):
            MultiUserChatManager.checkMucRequiresRename(room)
            # a room joined right after connecting, before the display name changed
```

Changing the callsign in ATAK renames the user everywhere within a fraction of a second.

**A taken callsign.** Two people can't hold the same nickname in a room. Where another account
has the callsign, the room keeps the nickname it had, and the display name and the other rooms
keep the callsign. Conversations alone would leave that room unjoined after the next reconnect,
with a "nickname in use" error. The plugin catches the conflict two ways:

- while joined: it sees the other occupant, in a room that shows accounts;
- when joining: the join fails, and it joins again with the room's last nickname.

The "taken" mark is stored per room, in the engine's `takconvo_room_nicknames` preferences, so
a restart doesn't try again. A new callsign gets a new try. The account's own other sessions,
such as the Conversations app on the same phone, may share a nickname, so they don't count as
taking it. A failed join doesn't notify the engine, which is why pending joins are re-checked.

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
    TAK password:     NO_TAK_PASSWORD: the address read-only, the password field and Log in
                      (signInTak); the notice names the TAK server, or says the password
                      entered was refused (then the password field shows Unauthorized too)
    login form:       address and password, editable until the account has logged in once;
                      no keyboard extract mode (IME_FLAG_NO_EXTRACT_UI / NO_FULLSCREEN), and
                      "Done" on the password signs in (the button is under the keyboard)
    signing in:       the form stays as filled in, read-only, with the spinner and
                      "Connecting", until the account is online (then the account's view, and
                      the chats replace the pane) or the attempt fails (the form again,
                      editable, with the error); not the view of an account that logged in
                      before, e.g. the same address with TAK credentials (signingIn)
    avatar:           only in the account's view: online, or logged in before and not being
                      signed in again
    spinner:          while the account connects and no error is shown
    error:            the state the last attempt ended with (shownError), under the address or
                      password field; it stays while Conversations retries in the background,
                      until the account connects or the user signs in or reconnects again
    TLS_ERROR_UNTRUSTED: the notice explains the trust sources (Conversations would ask
                      whether to trust the certificate; here the settings decide)
    SERVER_UNCONFIRMED: the notice names the server waiting for approval, with Connect
                      ("Approving the server" above)

signIn() / Reconnect:
    engine.signIn(...)                 # reconnects even when nothing changed
    shownError = null; attemptFrom = the account's state now
    # the reconnection is asynchronous: while the state is still attemptFrom, it is the
    # previous attempt's and shows as the spinner (for at most 30 s)
    from the form: signingIn, unless the account is online with that login already; it ends
    when the account is online, its state is an error, or after those 30 s without a change
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
