package com.atakmap.android.takconvo.plugin.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.takconvo.plugin.FakePreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class AppConfigTest {

    private static AppConfig config(final Object... keysAndValues) {
        final Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return new AppConfig(values, false, null, false);
    }

    /** What managing the domain gives: the server, approved without asking. */
    private static AppConfig managing(final ServerIdentity server) {
        return new AppConfig(new HashMap<>(), false, server, false);
    }

    private static ServerIdentity server(final String takServer) {
        return ServerIdentity.of(true, takServer, "xmpp.example.org", null, 5222, true, false,
                null, null);
    }

    @Test
    public void managedValuesBecomePreferences() {
        final FakePreferences prefs = new FakePreferences();
        prefs.edit().putString(XmppSettings.KEY_HOST, "chosen.by.user").apply();
        config(XmppSettings.KEY_DOMAIN, "xmpp.example.org",
                XmppSettings.KEY_USE_CALLSIGN, Boolean.FALSE,
                XmppSettings.KEY_HOST, AppConfig.DEFAULT).applyTo(prefs, AppConfig.NONE);
        assertEquals("xmpp.example.org", prefs.getString(XmppSettings.KEY_DOMAIN, null));
        assertFalse(prefs.getBoolean(XmppSettings.KEY_USE_CALLSIGN, true));
        // managed at its default: not there
        assertFalse(prefs.contains(XmppSettings.KEY_HOST));
    }

    @Test
    public void keysNoLongerManagedGoBackToTheirDefault() {
        final FakePreferences prefs = new FakePreferences();
        final AppConfig before = config(XmppSettings.KEY_DOMAIN, "xmpp.example.org",
                XmppSettings.KEY_USE_CALLSIGN, Boolean.FALSE);
        before.applyTo(prefs, AppConfig.NONE);
        prefs.edit().putString(XmppSettings.KEY_CHANNEL_SERVER, "user.example.org").apply();

        config(XmppSettings.KEY_USE_CALLSIGN, Boolean.FALSE).applyTo(prefs, before);
        assertFalse(prefs.contains(XmppSettings.KEY_DOMAIN));
        assertFalse(prefs.getBoolean(XmppSettings.KEY_USE_CALLSIGN, true));
        // never managed: the user's
        assertEquals("user.example.org", prefs.getString(XmppSettings.KEY_CHANNEL_SERVER, null));
    }

    @Test
    public void aChangeToAManagedKeyIsUndone() {
        final FakePreferences prefs = new FakePreferences();
        final AppConfig config = config(XmppSettings.KEY_DOMAIN, "xmpp.example.org");
        config.applyTo(prefs, AppConfig.NONE);
        assertFalse(config.enforce(prefs, XmppSettings.KEY_DOMAIN));

        // e.g. a .pref import
        prefs.edit().putString(XmppSettings.KEY_DOMAIN, "evil.example").apply();
        assertTrue(config.enforce(prefs, XmppSettings.KEY_DOMAIN));
        assertEquals("xmpp.example.org", prefs.getString(XmppSettings.KEY_DOMAIN, null));
        assertFalse(config.enforce(prefs, XmppSettings.KEY_DOMAIN));
        // not managed: left alone
        prefs.edit().putString(XmppSettings.KEY_CHANNEL_SERVER, "x.example").apply();
        assertFalse(config.enforce(prefs, XmppSettings.KEY_CHANNEL_SERVER));
    }

    @Test
    public void aManagedBooleanStoredAsStringIsRewritten() {
        final FakePreferences prefs = new FakePreferences();
        prefs.edit().putString(XmppSettings.KEY_ENABLED, "true").apply();
        final AppConfig config = config(XmppSettings.KEY_ENABLED, Boolean.TRUE);
        assertTrue(config.enforce(prefs, XmppSettings.KEY_ENABLED));
        assertTrue(prefs.getBoolean(XmppSettings.KEY_ENABLED, false));
    }

    @Test
    public void aDefaultKeyWrittenIsRemovedAgain() {
        final FakePreferences prefs = new FakePreferences();
        final AppConfig config = config(XmppSettings.KEY_TRUSTED_CA, AppConfig.DEFAULT);
        prefs.edit().putString(XmppSettings.KEY_TRUSTED_CA, "/sdcard/evil.pem").apply();
        assertTrue(config.enforce(prefs, XmppSettings.KEY_TRUSTED_CA));
        assertFalse(prefs.contains(XmppSettings.KEY_TRUSTED_CA));
    }

    @Test
    public void approvesTheServerItSets() {
        final AppConfig anyTakServer = managing(server(null));
        assertTrue(anyTakServer.approves(server("tak.example.org")));
        assertTrue(anyTakServer.approves(server("tak.partner.net")));
        assertFalse(anyTakServer.approves(ServerIdentity.of(true, null, "evil.example", null,
                5222, true, false, null, null)));

        final AppConfig named = managing(server("tak.example.org"));
        assertTrue(named.approves(server("tak.example.org")));
        assertFalse(named.approves(server("tak.partner.net")));

        assertFalse(AppConfig.NONE.approves(server("tak.example.org")));
    }

    @Test
    public void state() {
        assertTrue(AppConfig.NONE.isEmpty());
        final AppConfig config = config(XmppSettings.KEY_DOMAIN, "xmpp.example.org");
        assertFalse(config.isEmpty());
        assertTrue(config.isManaged(XmppSettings.KEY_DOMAIN));
        assertFalse(config.isManaged(XmppSettings.KEY_HOST));
        assertEquals(config, config(XmppSettings.KEY_DOMAIN, "xmpp.example.org"));
    }
}
