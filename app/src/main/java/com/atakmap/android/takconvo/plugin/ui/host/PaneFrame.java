package com.atakmap.android.takconvo.plugin.ui.host;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;

/**
 * The pane's content: the views of the embedded activities.
 *
 * <p>It sits inside ATAK's window, and what views ask of their window arrives here on its way
 * to ATAK's, which would handle it with ATAK's resources and callbacks. A context menu is
 * handed to the embedded activity's own window instead, whose callback is that activity. An
 * action mode stays with ATAK's window, which is the one on screen, but its menu is inflated
 * with the resources of the view that started it.
 */
final class PaneFrame extends FrameLayout {

    interface Top {
        /** the activity on top of the pane, or null */
        Activity activity();
    }

    private final Top top;

    PaneFrame(final Context context, final Top top) {
        super(context);
        this.top = top;
        setClickable(true);
    }

    /** Clear of the system bars already: insets must not pad Conversations' layouts again. */
    @Override
    public WindowInsets dispatchApplyWindowInsets(final WindowInsets insets) {
        return insets;
    }

    @Override
    public boolean showContextMenuForChild(final View originalView) {
        final ViewGroup decor = topDecor();
        return decor != null ? decor.showContextMenuForChild(originalView)
                : super.showContextMenuForChild(originalView);
    }

    @Override
    public boolean showContextMenuForChild(final View originalView, final float x,
            final float y) {
        final ViewGroup decor = topDecor();
        return decor != null ? decor.showContextMenuForChild(originalView, x, y)
                : super.showContextMenuForChild(originalView, x, y);
    }

    @Override
    public ActionMode startActionModeForChild(final View originalView,
            final ActionMode.Callback callback) {
        return super.startActionModeForChild(originalView,
                new InflatingCallback(originalView.getContext(), callback));
    }

    @Override
    public ActionMode startActionModeForChild(final View originalView,
            final ActionMode.Callback callback, final int type) {
        return super.startActionModeForChild(originalView,
                new InflatingCallback(originalView.getContext(), callback), type);
    }

    /** The decor view of the top activity's window. It is never attached to a screen. */
    private ViewGroup topDecor() {
        final Activity activity = top.activity();
        if (activity == null || activity.getWindow() == null) {
            return null;
        }
        final View decor = activity.getWindow().getDecorView();
        return decor instanceof ViewGroup ? (ViewGroup) decor : null;
    }

    /** Hands the callback an action mode whose menu inflater uses the given resources. */
    private static final class InflatingCallback extends ActionMode.Callback2 {

        private final Context context;
        private final ActionMode.Callback callback;
        private InflatingActionMode wrapped;

        InflatingCallback(final Context context, final ActionMode.Callback callback) {
            this.context = context;
            this.callback = callback;
        }

        private ActionMode wrap(final ActionMode mode) {
            if (wrapped == null || wrapped.mode != mode) {
                wrapped = new InflatingActionMode(mode, context);
            }
            return wrapped;
        }

        @Override
        public boolean onCreateActionMode(final ActionMode mode, final Menu menu) {
            return callback.onCreateActionMode(wrap(mode), menu);
        }

        @Override
        public boolean onPrepareActionMode(final ActionMode mode, final Menu menu) {
            return callback.onPrepareActionMode(wrap(mode), menu);
        }

        @Override
        public boolean onActionItemClicked(final ActionMode mode, final MenuItem item) {
            return callback.onActionItemClicked(wrap(mode), item);
        }

        @Override
        public void onDestroyActionMode(final ActionMode mode) {
            callback.onDestroyActionMode(wrap(mode));
        }

        @Override
        public void onGetContentRect(final ActionMode mode, final View view,
                final Rect outRect) {
            if (callback instanceof ActionMode.Callback2) {
                ((ActionMode.Callback2) callback).onGetContentRect(wrap(mode), view, outRect);
            } else {
                super.onGetContentRect(mode, view, outRect);
            }
        }
    }

    /** The action mode ATAK's window made, with the resources of another context. */
    private static final class InflatingActionMode extends ActionMode {

        final ActionMode mode;
        private final Context context;
        private final MenuInflater inflater;

        InflatingActionMode(final ActionMode mode, final Context context) {
            this.mode = mode;
            this.context = context;
            this.inflater = new MenuInflater(context);
        }

        @Override
        public MenuInflater getMenuInflater() {
            return inflater;
        }

        @Override
        public void setTitle(final CharSequence title) {
            mode.setTitle(title);
        }

        @Override
        public void setTitle(final int resId) {
            mode.setTitle(context.getText(resId));
        }

        @Override
        public void setSubtitle(final CharSequence subtitle) {
            mode.setSubtitle(subtitle);
        }

        @Override
        public void setSubtitle(final int resId) {
            mode.setSubtitle(context.getText(resId));
        }

        @Override
        public void setTitleOptionalHint(final boolean titleOptional) {
            mode.setTitleOptionalHint(titleOptional);
        }

        @Override
        public boolean isTitleOptional() {
            return mode.isTitleOptional();
        }

        @Override
        public void setCustomView(final View view) {
            mode.setCustomView(view);
        }

        @Override
        public void setTag(final Object tag) {
            mode.setTag(tag);
        }

        @Override
        public Object getTag() {
            return mode.getTag();
        }

        @Override
        public void setType(final int type) {
            mode.setType(type);
        }

        @Override
        public int getType() {
            return mode.getType();
        }

        @Override
        public void invalidate() {
            mode.invalidate();
        }

        @Override
        public void invalidateContentRect() {
            mode.invalidateContentRect();
        }

        @Override
        public void hide(final long duration) {
            mode.hide(duration);
        }

        @Override
        public void onWindowFocusChanged(final boolean hasWindowFocus) {
            mode.onWindowFocusChanged(hasWindowFocus);
        }

        @Override
        public void finish() {
            mode.finish();
        }

        @Override
        public Menu getMenu() {
            return mode.getMenu();
        }

        @Override
        public CharSequence getTitle() {
            return mode.getTitle();
        }

        @Override
        public CharSequence getSubtitle() {
            return mode.getSubtitle();
        }

        @Override
        public View getCustomView() {
            return mode.getCustomView();
        }
    }
}
