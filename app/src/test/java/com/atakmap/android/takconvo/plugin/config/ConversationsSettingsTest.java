package com.atakmap.android.takconvo.plugin.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.takconvo.plugin.FakePreferences;

import org.junit.Test;

public class ConversationsSettingsTest {

    private static final String PREFIX = ConversationsSettings.PREFIX;

    @Test
    public void typedValues() {
        assertEquals(Boolean.FALSE, ConversationsSettings.typedValue("confirm_messages", "FALSE"));
        assertEquals("default_off", ConversationsSettings.typedValue("omemo", "default_off"));
        assertEquals("524288",
                ConversationsSettings.typedValue("auto_accept_file_size", "524288"));
        assertNull(ConversationsSettings.typedValue("omemo", "sometimes"));
        assertNull(ConversationsSettings.typedValue("auto_accept_file_size", "-1"));
        assertNull(ConversationsSettings.typedValue("confirm_messages", "yes"));
        // not one the plugin supports
        assertNull(ConversationsSettings.typedValue("theme", "dark"));
    }

    @Test
    public void copiesValidSettingsAndSkipsTheRest() {
        final FakePreferences atak = new FakePreferences();
        final FakePreferences conversations = new FakePreferences();
        atak.edit()
                .putBoolean(PREFIX + "chat_states", false)
                .putString(PREFIX + "confirm_messages", "false")
                .putString(PREFIX + "omemo", "always")
                .putString(PREFIX + "video_compression", "4k")
                .putString(PREFIX + "theme", "light")
                .putString("unrelated", "x")
                .apply();
        ConversationsSettings.apply(atak, conversations);
        assertFalse(conversations.getBoolean("chat_states", true));
        assertFalse(conversations.getBoolean("confirm_messages", true));
        assertEquals("always", conversations.getString("omemo", null));
        assertFalse(conversations.contains("video_compression"));
        assertFalse(conversations.contains("theme"));
        assertFalse(conversations.contains("unrelated"));
    }

    @Test
    public void aRemovedSettingGoesBackToConversationsDefault() {
        final FakePreferences atak = new FakePreferences();
        final FakePreferences conversations = new FakePreferences();
        atak.edit().putString(PREFIX + "omemo", "always").apply();
        ConversationsSettings.apply(atak, conversations);
        assertTrue(conversations.contains("omemo"));
        // one the user set in Conversations itself is left alone
        conversations.edit().putBoolean("large_font", true).apply();

        atak.edit().remove(PREFIX + "omemo").apply();
        ConversationsSettings.apply(atak, conversations);
        assertFalse(conversations.contains("omemo"));
        assertTrue(conversations.getBoolean("large_font", false));
    }
}
