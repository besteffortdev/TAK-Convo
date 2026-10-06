package com.atakmap.android.takconvo.plugin.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.theme.MaterialComponentsViewInflater;

/**
 * Inflates Conversations layouts as its AppCompatActivity would, with Material and AppCompat
 * views for plain tags; a view in an ATAK pane has no AppCompatDelegate to do it.
 */
public final class ConversationsInflater {

    /** Creates a view of the layout itself, in place of the one the XML names. */
    public interface Replacement {
        /** The view for {@code attrs} (the XML's), or null for the layout's own. */
        View create(Context context, AttributeSet attrs);
    }

    private ConversationsInflater() {
    }

    /** {@code themed}: the plugin's resources with a Conversations theme. */
    public static View inflate(final Context themed, final int layout, final ViewGroup root,
            final boolean attachToRoot) {
        return inflate(themed, layout, root, attachToRoot, null);
    }

    /** As {@link #inflate(Context, int, ViewGroup, boolean)}, with views of the plugin's. */
    public static View inflate(final Context themed, final int layout, final ViewGroup root,
            final boolean attachToRoot, final Replacement replacement) {
        final LayoutInflater inflater = LayoutInflater.from(themed).cloneInContext(themed);
        final MaterialComponentsViewInflater views = new MaterialComponentsViewInflater();
        inflater.setFactory2(new LayoutInflater.Factory2() {
            @Override
            public View onCreateView(final View parent, final String name, final Context context,
                    final AttributeSet attrs) {
                final View replaced = replacement == null ? null
                        : replacement.create(context, attrs);
                if (replaced != null) {
                    return replaced;
                }
                // as AppCompatDelegateImpl passes them on API 21+
                return views.createView(parent, name, context, attrs, false, false, true, false);
            }

            @Override
            public View onCreateView(final String name, final Context context,
                    final AttributeSet attrs) {
                return onCreateView(null, name, context, attrs);
            }
        });
        return inflater.inflate(layout, root, attachToRoot);
    }
}
