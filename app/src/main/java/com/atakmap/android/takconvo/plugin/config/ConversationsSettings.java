package com.atakmap.android.takconvo.plugin.config;

import android.content.SharedPreferences;

import com.atakmap.coremap.log.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conversations' own settings from ATAK's preferences, {@code takconvo_conversations_<key>}, so
 * a .pref file can set them. Only the settings that work inside ATAK and that the plugin
 * doesn't manage itself; a removed key goes back to Conversations' default. See docs/03.
 */
public final class ConversationsSettings {

    private static final String TAG = "TakConvo.Settings";

    public static final String PREFIX = "takconvo_conversations_";
    /** In Conversations' preferences: the keys set from ATAK's. */
    private static final String APPLIED = "takconvo_applied_settings";

    private enum Type {
        BOOLEAN,
        NUMBER,
        CHOICE
    }

    private static final class Spec {
        final Type type;
        final List<String> choices;

        Spec(final Type type, final String... choices) {
            this.type = type;
            this.choices = Arrays.asList(choices);
        }
    }

    private static final Map<String, Spec> SETTINGS = new HashMap<>();

    static {
        for (final String key : new String[] {
                // privacy
                "confirm_messages", "chat_states", "last_activity", "entity_time",
                "allow_message_correction", "accept_invites_from_strangers", "use_relays",
                // security and connection
                "btbv", "trust_system_ca_store", "require_tls_v1_3", "channel_binding_required",
                "use_tor",
                // availability
                "manually_change_presence", "away_when_screen_off", "dnd_on_silent_mode",
                "treat_vibrate_as_silent",
                // interface
                "show_dynamic_tags", "scroll_to_bottom", "start_searching",
                "display_enter_key", "enter_is_send", "use_green_background", "large_font",
                "align_start", "show_avatars", "show_avatars_accounts",
                // attachments and notifications
                "auto_send_recording", "notifications_from_strangers"}) {
            SETTINGS.put(key, new Spec(Type.BOOLEAN));
        }
        SETTINGS.put("omemo", new Spec(Type.CHOICE, "always", "default_on", "default_off"));
        SETTINGS.put("picture_compression", new Spec(Type.CHOICE, "never", "auto", "always"));
        SETTINGS.put("video_compression",
                new Spec(Type.CHOICE, "360", "480", "720", "1080", "uncompressed"));
        // bytes, seconds, seconds
        SETTINGS.put("auto_accept_file_size", new Spec(Type.NUMBER));
        SETTINGS.put("grace_period_length", new Spec(Type.NUMBER));
        SETTINGS.put("automatic_message_deletion", new Spec(Type.NUMBER));
    }

    private ConversationsSettings() {
    }

    /** Copies the settings from ATAK's preferences into Conversations'. */
    public static void apply(final SharedPreferences atak, final SharedPreferences conversations) {
        final SharedPreferences.Editor editor = conversations.edit();
        final Set<String> applied = new HashSet<>();
        for (final Map.Entry<String, ?> entry : atak.getAll().entrySet()) {
            if (!entry.getKey().startsWith(PREFIX) || entry.getValue() == null) {
                continue;
            }
            final String key = entry.getKey().substring(PREFIX.length());
            final String value = String.valueOf(entry.getValue()).trim();
            final Spec spec = SETTINGS.get(key);
            if (spec == null) {
                Log.w(TAG, "not a Conversations setting the plugin supports: " + key);
            } else if (!valid(spec, value)) {
                Log.w(TAG, "invalid value for " + entry.getKey() + ": " + value);
            } else {
                if (spec.type == Type.BOOLEAN) {
                    editor.putBoolean(key, Boolean.parseBoolean(value));
                } else {
                    editor.putString(key, value);
                }
                applied.add(key);
            }
        }
        // removed from ATAK's preferences: back to Conversations' default
        for (final String key : conversations.getStringSet(APPLIED, Collections.emptySet())) {
            if (!applied.contains(key)) {
                editor.remove(key);
            }
        }
        editor.putStringSet(APPLIED, applied);
        editor.apply();
    }

    /**
     * The value to store for setting {@code key} (without the prefix): a Boolean or a String.
     * Null if the setting isn't supported or the value isn't valid.
     */
    public static Object typedValue(final String key, final String value) {
        final Spec spec = SETTINGS.get(key);
        if (spec == null || !valid(spec, value)) {
            return null;
        }
        return spec.type == Type.BOOLEAN ? (Object) Boolean.valueOf(value) : value;
    }

    private static boolean valid(final Spec spec, final String value) {
        switch (spec.type) {
            case BOOLEAN:
                return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
            case CHOICE:
                return spec.choices.contains(value);
            case NUMBER:
            default:
                try {
                    return Long.parseLong(value) >= 0;
                } catch (final NumberFormatException e) {
                    return false;
                }
        }
    }
}
