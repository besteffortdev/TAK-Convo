package com.atakmap.android.takconvo.plugin.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.theme.MaterialComponentsViewInflater;

/**
 * Inflates Conversations layouts the way its AppCompatActivity does: plain {@code Button},
 * {@code TextView}, {@code ImageView}, ... tags become their Material/AppCompat versions (Material
 * 3 styling, tinting, {@code app:srcCompat}). A plugin view in an ATAK pane has no
 * AppCompatDelegate to do that, so this installs the same view inflater as a factory.
 */
public final class ConversationsInflater {

    private ConversationsInflater() {
    }

    /**
     * @param themed a context with the plugin's resources and a Conversations theme
     */
    public static View inflate(final Context themed, final int layout, final ViewGroup root,
            final boolean attachToRoot) {
        final LayoutInflater inflater = LayoutInflater.from(themed).cloneInContext(themed);
        final MaterialComponentsViewInflater views = new MaterialComponentsViewInflater();
        inflater.setFactory2(new LayoutInflater.Factory2() {
            @Override
            public View onCreateView(final View parent, final String name, final Context context,
                    final AttributeSet attrs) {
                // the arguments AppCompatDelegateImpl passes on API 21+
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
