package com.atakmap.android.takconvo.plugin.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.takconvo.plugin.FakePreferences;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class XmppSettingsTest {

    @Test
    public void usernameGetsTheDomain() {
        assertEquals("alice@xmpp.example.org",
                XmppSettings.jid("alice", "xmpp.example.org", true));
        assertEquals("alice@xmpp.example.org",
                XmppSettings.jid("alice", "xmpp.example.org", false));
        assertNull(XmppSettings.jid("alice", null, true));
        assertNull(XmppSettings.jid(null, "xmpp.example.org", true));
    }

    @Test
    public void xmppLoginWithDomainIsAnAddress() {
        assertEquals("alice@other.example",
                XmppSettings.jid("alice@other.example", "xmpp.example.org", false));
    }

    @Test
    public void takUsernameWithDomainTakesTheConfiguredOne() {
        // a Windows login (UPN) as TAK username
        assertEquals("alice@xmpp.example.org",
                XmppSettings.jid("alice@corp.example", "xmpp.example.org", true));
        // its own domain is the configured one: unchanged
        assertEquals("alice@XMPP.example.org",
                XmppSettings.jid("alice@XMPP.example.org", "xmpp.example.org", true));
        // no domain configured: the username is the address
        assertEquals("alice@corp.example", XmppSettings.jid("alice@corp.example", null, true));
    }

    @Test
    public void takServerOnTheXmppDomain() {
        assertTrue(XmppSettings.sameDomain("example.org", "example.org"));
        assertTrue(XmppSettings.sameDomain("tak.example.org", "example.org"));
        assertTrue(XmppSettings.sameDomain("example.org", "xmpp.example.org"));
        assertTrue(XmppSettings.sameDomain("TAK.Example.org", "xmpp.example.org"));
        assertFalse(XmppSettings.sameDomain("tak.partner.net", "xmpp.example.org"));
        // two labels have no shared parent: .org alone doesn't count
        assertFalse(XmppSettings.sameDomain("partner.org", "example.org"));
        // addresses only when equal
        assertTrue(XmppSettings.sameDomain("10.0.0.5", "10.0.0.5"));
        assertFalse(XmppSettings.sameDomain("10.0.0.5", "11.0.0.5"));
        assertFalse(XmppSettings.sameDomain(null, "example.org"));
        assertFalse(XmppSettings.sameDomain("example.org", null));
    }

    @Test
    public void channelDiscoveryParsesLeniently() {
        assertEquals(XmppSettings.ChannelDiscovery.PUBLIC,
                XmppSettings.ChannelDiscovery.parse(" Public "));
        assertEquals(XmppSettings.ChannelDiscovery.SERVER,
                XmppSettings.ChannelDiscovery.parse("server"));
        assertEquals(XmppSettings.ChannelDiscovery.XMPP_SERVER,
                XmppSettings.ChannelDiscovery.parse("nonsense"));
        assertEquals(XmppSettings.ChannelDiscovery.XMPP_SERVER,
                XmppSettings.ChannelDiscovery.parse(null));
    }

    @Test
    public void normalizeStoresPrefStringsTyped() {
        final FakePreferences prefs = new FakePreferences();
        prefs.edit()
                .putString(XmppSettings.KEY_ENABLED, " false ")
                .putString(XmppSettings.KEY_SHOW_QUICK_MESSAGES, "true")
                .putInt(XmppSettings.KEY_PORT, 5223)
                .putBoolean(XmppSettings.KEY_USE_CALLSIGN, true)
                .apply();
        XmppSettings.normalize(prefs);
        assertFalse(prefs.getBoolean(XmppSettings.KEY_ENABLED, true));
        assertTrue(prefs.getBoolean(XmppSettings.KEY_SHOW_QUICK_MESSAGES, false));
        assertEquals("5223", prefs.getString(XmppSettings.KEY_PORT, null));
        assertTrue(prefs.getBoolean(XmppSettings.KEY_USE_CALLSIGN, false));
    }

    @Test
    public void gettersTakePrefStrings() {
        final FakePreferences prefs = new FakePreferences();
        // as a .pref file without class="class java.lang.Boolean" leaves them
        prefs.edit()
                .putString(XmppSettings.KEY_SHOW_QUICK_MESSAGES, "true")
                .putString(XmppSettings.KEY_NOTIFICATION_SOUND, "false")
                .apply();
        assertTrue(XmppSettings.showQuickMessages(prefs));
        assertFalse(XmppSettings.notificationSound(prefs));
        assertTrue(XmppSettings.notificationVibrate(prefs));
        assertEquals("Roger", XmppSettings.quickMessages(prefs, "Roger"));
    }

    @Test
    public void takUsernameWithoutPasswordAsksForIt() {
        assertEquals(XmppSettings.Problem.NO_TAK_PASSWORD,
                XmppSettings.problem(true, true, "alice", null, "xmpp.example.org"));
        assertEquals(XmppSettings.Problem.NO_TAK_CREDENTIALS,
                XmppSettings.problem(true, true, null, null, "xmpp.example.org"));
        // an XMPP login has no such question
        assertEquals(XmppSettings.Problem.NOT_SIGNED_IN,
                XmppSettings.problem(true, false, "alice", "", "xmpp.example.org"));
        assertNull(XmppSettings.problem(true, true, "alice", "secret", "xmpp.example.org"));
    }

    @Test
    public void noDomainComesBeforeThePasswordQuestion() {
        assertEquals(XmppSettings.Problem.NO_DOMAIN,
                XmppSettings.problem(true, true, "alice", null, null));
        // a UPN is an address of its own
        assertEquals(XmppSettings.Problem.NO_TAK_PASSWORD,
                XmppSettings.problem(true, true, "alice@corp.example", null, null));
        assertEquals(XmppSettings.Problem.DISABLED,
                XmppSettings.problem(false, true, "alice", null, "xmpp.example.org"));
    }

    @Test
    public void takCredentialsWithAPasswordComeFirstInTheirGroup() {
        final XmppSettings.TakCredentials usernameOnly = creds("tak.example.org", null);
        final XmppSettings.TakCredentials full = creds("tak2.example.org", "secret");
        assertSame(full, XmppSettings.choose(Arrays.asList(usernameOnly, full),
                Collections.emptyList()));
        // none has one: the first username, whose password the user enters
        final XmppSettings.TakCredentials other = creds("tak3.example.org", null);
        assertSame(usernameOnly, XmppSettings.choose(Arrays.asList(usernameOnly, other),
                Collections.emptyList()));
    }

    @Test
    public void ownTakServerWithoutPasswordComesBeforeAnotherServersPassword() {
        final XmppSettings.TakCredentials own = creds("tak.example.org", null);
        final XmppSettings.TakCredentials partner = creds("tak.partner.net", "secret");
        assertSame(own, XmppSettings.choose(Collections.singletonList(own),
                Collections.singletonList(partner)));
        assertSame(partner, XmppSettings.choose(Collections.emptyList(),
                Collections.singletonList(partner)));
        assertNull(XmppSettings.choose(Collections.emptyList(), Collections.emptyList()));
    }

    private static XmppSettings.TakCredentials creds(final String server, final String password) {
        return new XmppSettings.TakCredentials(server, "alice", password, password == null);
    }
}
