package com.atakmap.android.takconvo.plugin.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class ServerIdentityTest {

    private static ServerIdentity server(final String takServer, final String host,
            final int port) {
        return ServerIdentity.of(true, takServer, "XMPP.Example.org", host, port, true, false,
                null, null);
    }

    @Test
    public void namesAreLowerCase() {
        final ServerIdentity s = server("TAK.Example.org", "Chat.Example.org", 5222);
        assertEquals("xmpp.example.org", s.domain);
        assertEquals("chat.example.org", s.host);
        assertEquals("tak.example.org", s.takServer);
    }

    @Test
    public void serializedAndParsedBack() {
        final ServerIdentity s = ServerIdentity.of(false, null, "example.org", "h.example.org",
                5223, false, true, "/sdcard/ca.pem", "ab12");
        assertEquals(s, ServerIdentity.parse(s.serialize()));
        final ServerIdentity t = server("tak.example.org", null, 5222);
        assertEquals(t, ServerIdentity.parse(t.serialize()));
    }

    @Test
    public void approvalOfAnOlderVersionHasNoTakServer() {
        final ServerIdentity old = ServerIdentity.parse("{\"takCredentials\":true,"
                + "\"domain\":\"xmpp.example.org\",\"port\":5222,\"takTrustStore\":true,"
                + "\"androidCaStore\":false}");
        assertNull(old.takServer);
        assertNull(old.host);
        assertNull(old.caPath);
        assertTrue(old.sameServer(server("tak.example.org", null, 5222)));
    }

    @Test
    public void unreadableApprovalIsNone() {
        assertNull(ServerIdentity.parse(null));
        assertNull(ServerIdentity.parse("not json"));
        assertNull(ServerIdentity.parse("{\"domain\":\"example.org\"}"));
    }

    @Test
    public void anotherTakServersCredentialsIsAnotherIdentity() {
        final ServerIdentity ours = server("tak.example.org", null, 5222);
        final ServerIdentity partner = server("tak.partner.net", null, 5222);
        assertTrue(ours.sameServer(partner));
        assertNotEquals(ours, partner);
        assertEquals(ours, server("TAK.example.org", null, 5222));
    }

    @Test
    public void everyPartOfWhereTheCredentialsGoCounts() {
        final ServerIdentity base = server("tak.example.org", null, 5222);
        assertFalse(base.sameServer(server("tak.example.org", "other.example.org", 5222)));
        assertFalse(base.sameServer(server("tak.example.org", null, 5223)));
        assertFalse(base.sameServer(ServerIdentity.of(true, "tak.example.org",
                "xmpp.example.org", null, 5222, true, false, "/ca.pem", "ab")));
        assertFalse(base.sameServer(ServerIdentity.of(false, null, "xmpp.example.org", null,
                5222, true, false, null, null)));
        assertFalse(base.sameServer(null));
    }

    @Test
    public void connectionNamesOnlyWhatIsntTheDefault() {
        assertEquals("", server(null, null, 5222).connection());
        assertEquals("5223", server(null, null, 5223).connection());
        assertEquals("chat.example.org:5222", server(null, "chat.example.org", 5222)
                .connection());
    }

    @Test
    public void sha256OfBytes() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ServerIdentity.sha256("abc".getBytes(StandardCharsets.US_ASCII)));
    }
}
