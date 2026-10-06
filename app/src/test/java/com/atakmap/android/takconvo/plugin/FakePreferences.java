package com.atakmap.android.takconvo.plugin;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SharedPreferences in memory, for the JVM tests. As Android's, a getter of the wrong type
 * throws ClassCastException, and listeners hear the keys whose value changed.
 */
public final class FakePreferences implements SharedPreferences {

    private final Map<String, Object> values = new HashMap<>();
    private final List<OnSharedPreferenceChangeListener> listeners = new ArrayList<>();

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<>(values);
    }

    @Override
    public String getString(final String key, final String defValue) {
        return values.containsKey(key) ? (String) values.get(key) : defValue;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(final String key, final Set<String> defValues) {
        return values.containsKey(key) ? (Set<String>) values.get(key) : defValues;
    }

    @Override
    public int getInt(final String key, final int defValue) {
        return values.containsKey(key) ? (Integer) values.get(key) : defValue;
    }

    @Override
    public long getLong(final String key, final long defValue) {
        return values.containsKey(key) ? (Long) values.get(key) : defValue;
    }

    @Override
    public float getFloat(final String key, final float defValue) {
        return values.containsKey(key) ? (Float) values.get(key) : defValue;
    }

    @Override
    public boolean getBoolean(final String key, final boolean defValue) {
        return values.containsKey(key) ? (Boolean) values.get(key) : defValue;
    }

    @Override
    public boolean contains(final String key) {
        return values.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new FakeEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(
            final OnSharedPreferenceChangeListener listener) {
        listeners.add(listener);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(
            final OnSharedPreferenceChangeListener listener) {
        listeners.remove(listener);
    }

    private final class FakeEditor implements Editor {

        /** Key to its new value; null removes it. */
        private final Map<String, Object> changes = new HashMap<>();
        private boolean clear;

        @Override
        public Editor putString(final String key, final String value) {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor putStringSet(final String key, final Set<String> value) {
            changes.put(key, value == null ? null : new HashSet<>(value));
            return this;
        }

        @Override
        public Editor putInt(final String key, final int value) {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor putLong(final String key, final long value) {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor putFloat(final String key, final float value) {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor putBoolean(final String key, final boolean value) {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor remove(final String key) {
            changes.put(key, null);
            return this;
        }

        @Override
        public Editor clear() {
            clear = true;
            return this;
        }

        @Override
        public boolean commit() {
            final Set<String> changed = new HashSet<>();
            if (clear) {
                changed.addAll(values.keySet());
                values.clear();
            }
            for (final Map.Entry<String, Object> change : changes.entrySet()) {
                final Object before = values.get(change.getKey());
                if (change.getValue() == null) {
                    values.remove(change.getKey());
                } else {
                    values.put(change.getKey(), change.getValue());
                }
                if (before == null ? change.getValue() != null
                        : !before.equals(change.getValue())) {
                    changed.add(change.getKey());
                }
            }
            for (final String key : changed) {
                for (final OnSharedPreferenceChangeListener l : new ArrayList<>(listeners)) {
                    l.onSharedPreferenceChanged(FakePreferences.this, key);
                }
            }
            return true;
        }

        @Override
        public void apply() {
            commit();
        }
    }
}
