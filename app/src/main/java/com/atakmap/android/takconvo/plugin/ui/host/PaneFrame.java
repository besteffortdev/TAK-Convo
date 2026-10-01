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
 * The pane's content. What views ask of their window would reach ATAK's, with ATAK's resources
 * and callbacks: context menus go to the top activity's own window instead, and action modes
 * inflate their menus with the starting view's resources. See docs/04.
 */
final class PaneFrame extends FrameLayout {

    interface Top {
        /** The top activity, or null. */
        Activity activity();
    }

    private final Top top;

    PaneFrame(final Context context, final Top top) {
        super(context);
        this.top = top;
        setClickable(true);
    }

    /** Already clear of the system bars: no padding for them again. */
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

    /** The top activity's decor view, never attached. */
    private ViewGroup topDecor() {
        final Activity activity = top.activity();
        if (activity == null || activity.getWindow() == null) {
            return null;
        }
        final View decor = activity.getWindow().getDecorView();
        return decor instanceof ViewGroup ? (ViewGroup) decor : null;
    }

    /** Passes the callback an {@link InflatingActionMode}. */
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

    /** ATAK's action mode with another context's resources. */
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
