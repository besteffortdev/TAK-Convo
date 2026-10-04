package com.atakmap.android.takconvo.plugin.ui.host;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedDispatcher;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.ActionBarOverlayLayout;
import androidx.appcompat.widget.FitWindowsFrameLayout;
import androidx.appcompat.widget.FitWindowsLinearLayout;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.atakmap.android.takconvo.plugin.ui.UiScale;
import com.atakmap.android.takconvo.plugin.xmpp.XmppEngine;
import com.atakmap.coremap.log.Log;

import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.BaseActivity;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.EditAccountActivity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs Conversations' activities inside an ATAK pane: created with
 * {@link Instrumentation#newActivity}, their lifecycle driven here, their views moved into
 * {@link #getView()}. They form a back stack; {@link HostParent} routes what they ask of the
 * system. Main thread only. See docs/04.
 */
public final class EmbeddedActivityHost implements HostParent.Callbacks {

    private static final String TAG = "TakConvo.Host";
    private static final String CONVERSATIONS_PACKAGE = "eu.siacs.conversations.";

    /** Events outside the pane's content. */
    public interface Listener {
        /** The last activity finished. */
        void onHostEmpty();

        /** The top activity finished; the one below shows again. */
        void onTopFinished(Activity finished);

        /** An activity asked for the account screen, which the plugin provides. */
        void onShowAccount();

        /** An activity asked for Conversations' settings, which the plugin's replace. */
        void onShowSettings();
    }

    /** Takes over starts that ATAK does better, e.g. showing a location on its map. */
    public interface Redirect {
        /**
         * Returns true if it handled the start; it answers through {@code result} if the caller
         * wants a result. {@code caller} is null for starts from outside, e.g. a notification.
         */
        boolean start(Activity caller, Intent intent, Result result);
    }

    /** The result for the activity that started another. */
    public interface Result {
        void deliver(int resultCode, Intent data);
    }

    /** Activities that work embedded; others are refused with a toast. */
    private static final Set<String> SUPPORTED = new HashSet<>(Arrays.asList(
            "eu.siacs.conversations.ui.ConversationsActivity",
            "eu.siacs.conversations.ui.StartConversationActivity",
            "eu.siacs.conversations.ui.ContactDetailsActivity",
            "eu.siacs.conversations.ui.ConferenceDetailsActivity",
            "eu.siacs.conversations.ui.MucUsersActivity",
            "eu.siacs.conversations.ui.ChooseContactActivity",
            "eu.siacs.conversations.ui.ChannelDiscoveryActivity",
            "eu.siacs.conversations.ui.EditHistoryActivity",
            "eu.siacs.conversations.ui.AddReactionActivity",
            "eu.siacs.conversations.ui.MediaBrowserActivity",
            "eu.siacs.conversations.ui.BlocklistActivity",
            "eu.siacs.conversations.ui.SearchActivity",
            "eu.siacs.conversations.ui.TrustKeysActivity",
            "eu.siacs.conversations.ui.RecordingActivity",
            // its camera preview is a TextureView, which draws in ATAK's window
            "eu.siacs.conversations.ui.ScanQrCodeActivity",
            // the fork picks the image without the cropper's activity
            "eu.siacs.conversations.ui.PublishProfilePictureActivity"));

    /** Dialog-themed: shown over the activity below, which stays visible and paused. */
    private static final Set<String> FLOATING = new HashSet<>(Arrays.asList(
            "eu.siacs.conversations.ui.RecordingActivity"));

    private static final String SETTINGS_ACTIVITY =
            "eu.siacs.conversations.ui.activity.SettingsActivity";

    /** Brought to the front when started again, not created twice. */
    private static final Set<String> SINGLE_INSTANCE = new HashSet<>(Arrays.asList(
            "eu.siacs.conversations.ui.ConversationsActivity",
            "eu.siacs.conversations.ui.StartConversationActivity"));

    /** Not relaunched when the pane's size changes: upstream declares configChanges for it. */
    private static final Set<String> HANDLES_SIZE_CHANGES = new HashSet<>(Arrays.asList(
            "eu.siacs.conversations.ui.RecordingActivity"));

    /** Waits for the pane to settle, e.g. while its edge is dragged, before relaunching. */
    private static final long RELAUNCH_DELAY_MS = 400;
    /** A first activity starts with the estimated size if the pane isn't laid out by then. */
    private static final long WAIT_FOR_LAYOUT_MS = 500;
    /** Smaller differences between the pane and an activity's configuration are ignored. */
    private static final int RELAUNCH_SLACK_DP = 8;

    private static final class Record {
        /** Replaced when relaunched for another pane size. */
        Activity activity;
        /** Who gets the result, or null. */
        final Record caller;
        final int requestCode;
        /** Shown over the activity below instead of hiding it. */
        final boolean floating;
        /** The pane configuration its resources were chosen for. */
        Configuration configuration;
        View content;
        boolean started;
        boolean resumed;
        boolean stoppedBefore;
        boolean finishing;

        Record(final Record caller, final int requestCode, final boolean floating) {
            this.caller = caller;
            this.requestCode = requestCode;
            this.floating = floating;
        }

        @Override
        public String toString() {
            return activity != null ? activity.getClass().getSimpleName() : "(not created)";
        }
    }

    private final Activity atak;
    private final Context plugin;
    private final XmppEngine engine;
    private final Listener listener;
    private final Instrumentation instrumentation = new Instrumentation();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HostParent parent;
    private final FrameLayout container;
    private final List<Record> stack = new ArrayList<>();
    private final AtomicInteger externalRequests = new AtomicInteger();
    /** Follows ATAK's activity while there are activities here. */
    private LifecycleEventObserver atakObserver;
    private ActivityCompat.PermissionCompatDelegate previousPermissionDelegate;

    /**
     * Asks ATAK's activity for permissions: an embedded one has no ActivityThread to show the
     * system dialog. Conversations requests them through ActivityCompat, which asks this first.
     */
    private final ActivityCompat.PermissionCompatDelegate permissionDelegate =
            new ActivityCompat.PermissionCompatDelegate() {
                @Override
                public boolean requestPermissions(final Activity activity,
                        final String[] permissions, final int requestCode) {
                    final Record record = find(activity);
                    if (record != null) {
                        requestPermissionsFor(record, permissions, requestCode);
                        return true;
                    }
                    final ActivityCompat.PermissionCompatDelegate previous =
                            previousPermissionDelegate;
                    return previous != null
                            && previous.requestPermissions(activity, permissions, requestCode);
                }

                @Override
                public boolean onActivityResult(final Activity activity, final int requestCode,
                        final int resultCode, final Intent data) {
                    final ActivityCompat.PermissionCompatDelegate previous =
                            previousPermissionDelegate;
                    return previous != null
                            && previous.onActivityResult(activity, requestCode, resultCode, data);
                }
            };
    private final Runnable relaunchLater = this::relaunchStale;
    /** Starts of a first activity that wait for the pane's layout, which gives its size. */
    private final List<Runnable> waiting = new ArrayList<>();
    private final Runnable startWaiting = this::startWaiting;
    private boolean visible;
    /** False while the pane's size is estimated: before it is laid out, e.g. after rotating. */
    private boolean laidOut;
    /** Until the next layout: it relaunches at once, as the pane isn't being dragged. */
    private boolean justShown;
    private int paneWidthPx;
    private int paneHeightPx;
    private Redirect redirect;

    public EmbeddedActivityHost(final Activity atak, final Context plugin,
            final XmppEngine engine, final Listener listener) {
        this.atak = atak;
        this.plugin = plugin;
        this.engine = engine;
        this.listener = listener;
        this.parent = new HostParent(atak, plugin.getResources().newTheme(), this);
        this.container = new PaneFrame(atak, () -> {
            final Record top = top();
            return top == null ? null : top.activity;
        }, () -> scheduleRelaunch(RELAUNCH_DELAY_MS));
        // ATAK is always dark, and an embedded activity can't be recreated on a theme change
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    }

    public View getView() {
        return container;
    }

    public void setRedirect(final Redirect redirect) {
        this.redirect = redirect;
    }

    /**
     * Sets the size the pane is about to get, before it is shown: until its layout, a first
     * activity waits and none is relaunched.
     */
    public void setEstimatedPaneSize(final int widthPx, final int heightPx) {
        laidOut = false;
        paneWidthPx = widthPx;
        paneHeightPx = heightPx;
    }

    /**
     * Sets the pane's size after a layout. The shown activities are relaunched for it at once
     * after it was shown, otherwise when it stops changing, e.g. while its edge is dragged.
     */
    public void setPaneSize(final int widthPx, final int heightPx) {
        final boolean justShown = this.justShown || !laidOut;
        this.justShown = false;
        laidOut = true;
        if (!waiting.isEmpty()) {
            // not during the layout
            handler.post(startWaiting);
        }
        if (widthPx != paneWidthPx || heightPx != paneHeightPx) {
            paneWidthPx = widthPx;
            paneHeightPx = heightPx;
            Log.d(TAG, "pane size " + widthPx + "x" + heightPx);
        } else if (!justShown) {
            return;
        }
        scheduleRelaunch(justShown ? 0 : RELAUNCH_DELAY_MS);
    }

    /** Starts the chat list unless something is shown already. */
    public void showMain() {
        if (!isEmpty()) {
            return;
        }
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(atak.getPackageName(),
                ConversationsActivity.class.getName()));
        start(null, intent, -1);
    }

    /** Shows one conversation. */
    public void showConversation(final String conversationUuid) {
        final Intent intent = new Intent(ConversationsActivity.ACTION_VIEW_CONVERSATION);
        intent.setComponent(new ComponentName(atak.getPackageName(),
                ConversationsActivity.class.getName()));
        intent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversationUuid);
        start(null, intent, -1);
    }

    /** Starts an activity as the system would, e.g. a tapped notification's. */
    public void startActivity(final Intent intent) {
        start(null, intent, -1);
    }

    public boolean isEmpty() {
        return stack.isEmpty() && waiting.isEmpty();
    }

    /** Hidden activities are stopped, not destroyed. */
    public void setVisible(final boolean visible) {
        if (this.visible == visible) {
            return;
        }
        this.visible = visible;
        // ATAK lays a pane out after showing it, also when it was hidden by another
        justShown = visible;
        relaunchStale();
        settleShown();
    }

    /** Returns false when there is nothing left to go back to: the pane should close. */
    public boolean onBackPressed() {
        final Record top = top();
        if (top == null) {
            return false;
        }
        if (top.activity instanceof ComponentActivity) {
            final OnBackPressedDispatcher dispatcher =
                    ((ComponentActivity) top.activity).getOnBackPressedDispatcher();
            if (dispatcher.hasEnabledCallbacks()) {
                dispatcher.onBackPressed();
                return true;
            }
        }
        if (stack.size() > 1) {
            finishFromChild(top.activity);
            return true;
        }
        return false;
    }

    /** Destroys every activity; the host can be used again. */
    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        waiting.clear();
        detachFromAtak();
        while (!stack.isEmpty()) {
            final Record record = stack.remove(stack.size() - 1);
            guarded(record, "destroy", () -> destroy(record));
        }
        visible = false;
    }

    // --- what activities ask for, through HostParent ---

    @Override
    public void startFromChild(final Activity child, final Intent intent, final int requestCode,
            final Bundle options) {
        // after the caller's callback, which often finishes it right after
        handler.post(() -> start(find(child), intent, requestCode));
    }

    @Override
    public void finishFromChild(final Activity child) {
        final Record record = find(child);
        if (record == null || record.finishing) {
            return;
        }
        record.finishing = true;
        handler.post(() -> finish(record));
    }

    // --- the back stack ---

    private void start(final Record caller, final Intent intent, final int requestCode) {
        final Redirect r = redirect;
        if (r != null && r.start(caller == null ? null : caller.activity, intent,
                (code, data) -> deliverResult(caller, requestCode, code, data))) {
            return;
        }
        final ComponentName component = intent.getComponent();
        final String name = component == null ? null : component.getClassName();
        if (name == null || !name.startsWith(CONVERSATIONS_PACKAGE)) {
            startExternal(caller, intent, requestCode);
            return;
        }
        if (EditAccountActivity.class.getName().equals(name)
                || name.endsWith(".ManageAccountActivity")) {
            listener.onShowAccount();
            return;
        }
        if (SETTINGS_ACTIVITY.equals(name)) {
            listener.onShowSettings();
            return;
        }
        if (!SUPPORTED.contains(name)) {
            Log.w(TAG, "not available embedded: " + name);
            toastNotAvailable();
            deliverResult(caller, requestCode, Activity.RESULT_CANCELED, null);
            return;
        }
        if (!laidOut && stack.isEmpty()) {
            // its resources are picked for the pane's real size, known one frame later
            if (waiting.isEmpty()) {
                handler.postDelayed(startWaiting, WAIT_FOR_LAYOUT_MS);
            }
            waiting.add(() -> start(caller, intent, requestCode));
            return;
        }

        final Record existing = findByClass(name);
        if (existing != null && (SINGLE_INSTANCE.contains(name)
                || (intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0)) {
            // like singleTask or CLEAR_TOP
            while (top() != existing) {
                final Record above = stack.remove(stack.size() - 1);
                guarded(above, "destroy", () -> destroy(above));
            }
            if (canRelaunch() && isStale(existing, paneConfiguration()) && !relaunch(existing)) {
                return;
            }
            guarded(existing, "new intent", () -> {
                existing.content.setVisibility(View.VISIBLE);
                pause(existing);
                instrumentation.callActivityOnNewIntent(existing.activity, intent);
                settle(existing);
            });
            return;
        }

        final Record previous = top();
        if (previous != null) {
            guarded(previous, "pause", () -> pause(previous));
        }
        final Record record;
        try {
            record = create(name, intent, caller, requestCode);
        } catch (final Exception | LinkageError e) {
            Log.e(TAG, "unable to create " + name, e);
            toastNotAvailable();
            if (previous != null) {
                guarded(previous, "resume", () -> settle(previous));
            }
            deliverResult(caller, requestCode, Activity.RESULT_CANCELED, null);
            return;
        }
        if (!record.finishing) {
            guarded(record, "resume", () -> settle(record));
        }
        if (!record.floating) {
            // hide what was shown below, also through a floating activity
            for (int i = stack.indexOf(record) - 1; i >= 0; i--) {
                final Record below = stack.get(i);
                if (below.content.getVisibility() == View.GONE) {
                    break;
                }
                below.content.setVisibility(View.GONE);
                guarded(below, "stop", () -> stop(below));
            }
        }
    }

    private void finish(final Record record) {
        if (!stack.contains(record)) {
            return;
        }
        final boolean wasTop = top() == record;
        final int resultCode = resultCode(record.activity);
        final Intent resultData = resultData(record.activity);
        stack.remove(record);
        guarded(record, "destroy", () -> destroy(record));
        deliverResult(record.caller, record.requestCode, resultCode, resultData);
        if (!wasTop) {
            return;
        }
        if (stack.isEmpty()) {
            listener.onHostEmpty();
            return;
        }
        relaunchStale();
        settleShown();
        listener.onTopFinished(record.activity);
    }

    private Record create(final String className, final Intent intent, final Record caller,
            final int requestCode) throws Exception {
        // before the record is on the stack: the observer settles the stack at once
        attachToAtak();
        final Record record = new Record(caller, requestCode, FLOATING.contains(className));
        // before onCreate, which may start or finish activities
        stack.add(record);
        boolean created = false;
        try {
            launch(record, className, intent, null);
            created = true;
        } finally {
            if (!created) {
                stack.remove(record);
            }
        }
        container.addView(record.content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return record;
    }

    /** What a relaunched activity gets from the one it replaces. */
    private static final class Saved {
        final Bundle state = new Bundle();
        /** The views' state, which the activity's own window would have kept. */
        final SparseArray<Parcelable> views = new SparseArray<>();
        int focusedId = View.NO_ID;
    }

    /** Creates the record's activity, from {@code saved} when it is relaunched. */
    private void launch(final Record record, final String className, final Intent intent,
            final Saved saved) throws Exception {
        final Class<? extends Activity> cls =
                plugin.getClassLoader().loadClass(className).asSubclass(Activity.class);
        record.configuration = paneConfiguration();
        final Context base = engine.newUiContext(atak.getWindowManager().getDefaultDisplay(),
                record.configuration);
        intent.setComponent(new ComponentName(atak.getPackageName(), className));
        intent.setExtrasClassLoader(plugin.getClassLoader());
        final ActivityInfo info = new ActivityInfo();
        info.applicationInfo = atak.getApplicationInfo();
        info.packageName = atak.getPackageName();
        info.name = className;
        info.theme = record.floating ? R.style.Theme_Conversations3_Dialog
                : R.style.Theme_Conversations3;
        info.flags = ActivityInfo.FLAG_HARDWARE_ACCELERATED;

        Log.d(TAG, (saved == null ? "creating " : "relaunching ") + className + " for "
                + record.configuration.screenWidthDp + "x"
                + record.configuration.screenHeightDp + " dp");
        // nothing retained (ViewModels, retained fragments): handing that over is hidden API
        final Activity activity = instrumentation.newActivity(cls, base, null,
                engine.getApplication(), intent, info, "", parent, className, null);
        activity.setTheme(info.theme);
        record.activity = activity;
        record.content = null;
        record.started = false;
        record.resumed = false;
        record.stoppedBefore = false;
        final Bundle state = saved == null ? null : saved.state;
        instrumentation.callActivityOnCreate(activity, state);
        if (saved != null) {
            // in the system's order: started, then the saved state restored
            start(record);
            instrumentation.callActivityOnRestoreInstanceState(activity, state);
        }
        instrumentation.callActivityOnPostCreate(activity, state);
        final View content = takeContent(activity);
        if (saved != null) {
            content.restoreHierarchyState(saved.views);
            final View focused = content.findViewById(saved.focusedId);
            if (focused != null) {
                focused.requestFocus();
            }
        }
        record.content = record.floating ? floatOver(activity, content) : content;
        // carried over from the activity's own window, which is never shown
        if ((activity.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0) {
            record.content.setKeepScreenOn(true);
        }
    }

    // --- the pane's size ---

    /** Starts what waited for the pane's layout; with the estimated size if it didn't come. */
    private void startWaiting() {
        handler.removeCallbacks(startWaiting);
        if (waiting.isEmpty()) {
            return;
        }
        laidOut = true;
        final List<Runnable> starts = new ArrayList<>(waiting);
        waiting.clear();
        for (final Runnable start : starts) {
            start.run();
        }
        if (stack.isEmpty()) {
            listener.onHostEmpty();
        }
    }

    private void scheduleRelaunch(final long delayMs) {
        handler.removeCallbacks(relaunchLater);
        handler.postDelayed(relaunchLater, delayMs);
    }

    /**
     * Not while the pane's size is estimated, nor while a dialog or menu has ATAK's focus: it
     * would stay open, acting on the activity replaced. The pane tells when the focus is back.
     */
    private boolean canRelaunch() {
        return visible && laidOut && atak.hasWindowFocus();
    }

    /**
     * Relaunches the shown activities whose resources were chosen for another pane size; the
     * hidden ones are relaunched when shown again, as the system does.
     */
    private void relaunchStale() {
        handler.removeCallbacks(relaunchLater);
        if (!canRelaunch()) {
            return;
        }
        final Configuration current = paneConfiguration();
        final List<Record> shown = new ArrayList<>();
        for (int i = stack.size() - 1; i >= 0; i--) {
            shown.add(stack.get(i));
            if (!stack.get(i).floating) {
                break;
            }
        }
        // bottom up, so that a result goes to an activity that is already relaunched
        for (int i = shown.size() - 1; i >= 0; i--) {
            final Record r = shown.get(i);
            if (stack.contains(r) && isStale(r, current)) {
                relaunch(r);
            }
        }
    }

    private static boolean isStale(final Record r, final Configuration current) {
        final Configuration c = r.configuration;
        if (c == null || r.finishing || current.screenWidthDp == 0
                || HANDLES_SIZE_CHANGES.contains(r.activity.getClass().getName())) {
            return false;
        }
        return c.orientation != current.orientation
                || Math.abs(c.screenWidthDp - current.screenWidthDp) > RELAUNCH_SLACK_DP
                || Math.abs(c.screenHeightDp - current.screenHeightDp) > RELAUNCH_SLACK_DP;
    }

    /**
     * Replaces the record's activity with a new one for the pane's configuration, from its
     * saved state, as the system relaunches an activity on a configuration change: the record
     * stays, so results still reach it. Returns false if it failed and the record is gone.
     */
    private boolean relaunch(final Record r) {
        final Activity old = r.activity;
        final int index = container.indexOfChild(r.content);
        Saved saved = new Saved();
        try {
            // the views first: stopping the chat hides the keyboard
            final View focused = r.content.findFocus();
            saved.focusedId = focused != null ? focused.getId() : View.NO_ID;
            stop(r);
            instrumentation.callActivityOnSaveInstanceState(old, saved.state);
            r.content.saveHierarchyState(saved.views);
        } catch (final Exception | LinkageError e) {
            Log.e(TAG, "unable to save " + r + "; relaunching it from its intent", e);
            saved = null;
        }
        guarded(r, "destroy", () -> destroy(r));
        try {
            launch(r, old.getClass().getName(), old.getIntent(), saved);
        } catch (final Exception | LinkageError e) {
            Log.e(TAG, "unable to relaunch " + old.getClass().getName(), e);
            toastNotAvailable();
            stack.remove(r);
            deliverResult(r.caller, r.requestCode, Activity.RESULT_CANCELED, null);
            if (stack.isEmpty()) {
                listener.onHostEmpty();
            } else {
                settleShown();
            }
            return false;
        }
        // where the old one was, below what floats over it (-1: on top)
        container.addView(r.content, index, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (!r.finishing) {
            guarded(r, "resume", () -> settle(r));
        }
        return true;
    }

    /** Wraps the content like a dialog over a dimmed pane; touches outside do nothing. */
    private View floatOver(final Activity activity, final View content) {
        final float density = activity.getResources().getDisplayMetrics().density;
        final TypedArray a = activity.obtainStyledAttributes(new int[] {
                com.google.android.material.R.attr.colorSurfaceContainerHigh});
        final GradientDrawable background = new GradientDrawable();
        background.setColor(a.getColor(0, Color.DKGRAY));
        a.recycle();
        background.setCornerRadius(28 * density);
        content.setBackground(background);

        final FrameLayout scrim = new FrameLayout(atak);
        scrim.setBackgroundColor(Color.argb(153, 0, 0, 0));
        scrim.setClickable(true);
        final int margin = Math.round(24 * density);
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        params.setMargins(margin, margin, margin, margin);
        scrim.addView(content, params);
        return scrim;
    }

    /**
     * Dark, scaled ({@link UiScale}) and sized like the pane, not the screen: the whole pane,
     * like an activity's window, so that the banner above the activities changes nothing.
     */
    private Configuration paneConfiguration() {
        final Configuration override = UiScale.override(atak);
        final float density = UiScale.density(atak);
        final int width = paneWidthPx;
        final int height = paneHeightPx;
        if (width > 0 && height > 0) {
            override.screenWidthDp = Math.round(width / density);
            override.screenHeightDp = Math.round(height / density);
            override.smallestScreenWidthDp =
                    Math.min(override.screenWidthDp, override.screenHeightDp);
            override.orientation = width > height ? Configuration.ORIENTATION_LANDSCAPE
                    : Configuration.ORIENTATION_PORTRAIT;
        }
        return override;
    }

    /**
     * Takes the content frame and the layout holding action mode bars out of the activity's
     * window. The window stays unattached: its decor view would reconfigure ATAK's view root.
     */
    @SuppressLint("RestrictedApi")
    private static View takeContent(final Activity activity) {
        View view = activity.getWindow().getDecorView().findViewById(android.R.id.content);
        if (view == null) {
            throw new IllegalStateException("activity has no content view");
        }
        for (ViewParent p = view.getParent(); p instanceof FitWindowsLinearLayout
                || p instanceof FitWindowsFrameLayout
                || p instanceof ActionBarOverlayLayout; p = view.getParent()) {
            view = (View) p;
        }
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
        if (view.getBackground() == null) {
            // what the window would have drawn
            final TypedArray a = activity.obtainStyledAttributes(
                    new int[] {android.R.attr.windowBackground});
            final Drawable background = a.getDrawable(0);
            a.recycle();
            view.setBackground(background);
        }
        if (activity instanceof BaseActivity) {
            ((BaseActivity) activity).embeddedContent = view;
        }
        return view;
    }

    // --- lifecycle ---

    /**
     * Resumes the top activity while the pane and ATAK are; pauses it while ATAK is only
     * started, or below a floating one; stops it otherwise. A resumed chat marks its messages
     * read and holds back notifications, so it must not stay resumed behind other apps.
     */
    private void settle(final Record r) throws Exception {
        final Lifecycle.State atakState = atak instanceof LifecycleOwner
                ? ((LifecycleOwner) atak).getLifecycle().getCurrentState()
                : Lifecycle.State.RESUMED;
        if (visible && r == top() && atakState.isAtLeast(Lifecycle.State.RESUMED)) {
            resume(r);
        } else if (visible && atakState.isAtLeast(Lifecycle.State.STARTED)) {
            pause(r);
            start(r);
        } else {
            stop(r);
        }
    }

    /** Shows and settles the top activity and what it floats over. */
    private void settleShown() {
        for (int i = stack.size() - 1; i >= 0; i--) {
            final Record r = stack.get(i);
            if (r.content != null) {
                r.content.setVisibility(View.VISIBLE);
            }
            guarded(r, "settle", () -> settle(r));
            if (!r.floating) {
                return;
            }
        }
    }

    /** Follows ATAK's activity and takes over the activities' permission requests. */
    private void attachToAtak() {
        if (atakObserver == null && atak instanceof LifecycleOwner) {
            atakObserver = (source, event) -> settleShown();
            ((LifecycleOwner) atak).getLifecycle().addObserver(atakObserver);
        }
        if (ActivityCompat.getPermissionCompatDelegate() != permissionDelegate) {
            previousPermissionDelegate = ActivityCompat.getPermissionCompatDelegate();
            ActivityCompat.setPermissionCompatDelegate(permissionDelegate);
        }
    }

    private void detachFromAtak() {
        if (atakObserver != null) {
            ((LifecycleOwner) atak).getLifecycle().removeObserver(atakObserver);
            atakObserver = null;
        }
        if (ActivityCompat.getPermissionCompatDelegate() == permissionDelegate) {
            ActivityCompat.setPermissionCompatDelegate(previousPermissionDelegate);
        }
        previousPermissionDelegate = null;
    }

    private void start(final Record r) {
        if (r.started) {
            return;
        }
        if (r.stoppedBefore) {
            instrumentation.callActivityOnRestart(r.activity);
        }
        instrumentation.callActivityOnStart(r.activity);
        lifecycle(r, Lifecycle.Event.ON_START);
        r.started = true;
    }

    private void resume(final Record r) throws Exception {
        if (r.resumed) {
            return;
        }
        start(r);
        Log.d(TAG, "resuming " + r);
        instrumentation.callActivityOnResume(r.activity);
        // fragments are resumed from here, not from onResume
        ON_POST_RESUME.invoke(r.activity);
        lifecycle(r, Lifecycle.Event.ON_RESUME);
        r.resumed = true;
    }

    private void pause(final Record r) {
        if (!r.resumed) {
            return;
        }
        r.resumed = false;
        Log.d(TAG, "pausing " + r);
        lifecycle(r, Lifecycle.Event.ON_PAUSE);
        instrumentation.callActivityOnPause(r.activity);
    }

    private void stop(final Record r) {
        pause(r);
        if (!r.started) {
            return;
        }
        r.started = false;
        r.stoppedBefore = true;
        Log.d(TAG, "stopping " + r);
        lifecycle(r, Lifecycle.Event.ON_STOP);
        instrumentation.callActivityOnStop(r.activity);
    }

    private void destroy(final Record r) {
        try {
            stop(r);
            lifecycle(r, Lifecycle.Event.ON_DESTROY);
            instrumentation.callActivityOnDestroy(r.activity);
        } finally {
            if (r.content != null) {
                container.removeView(r.content);
            }
        }
    }

    /** Dispatches the Lifecycle events Instrumentation doesn't; repeats are ignored. */
    private static void lifecycle(final Record r, final Lifecycle.Event event) {
        if (r.activity instanceof LifecycleOwner) {
            final Lifecycle lifecycle = ((LifecycleOwner) r.activity).getLifecycle();
            if (lifecycle instanceof LifecycleRegistry) {
                ((LifecycleRegistry) lifecycle).handleLifecycleEvent(event);
            }
        }
    }

    /** Runs an activity callback; a failing activity must not take ATAK down. */
    private void guarded(final Record record, final String what, final ThrowingRunnable action) {
        try {
            action.run();
        } catch (final Exception | LinkageError e) {
            Log.e(TAG, "unable to " + what + " " + record, e);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // --- results and activities outside Conversations ---

    private void startExternal(final Record caller, final Intent intent, final int requestCode) {
        try {
            if (caller == null || requestCode < 0
                    || !(atak instanceof ComponentActivity)) {
                atak.startActivity(intent);
                return;
            }
            // the system returns results to ATAK's activity only
            final String key = "takconvo#" + externalRequests.incrementAndGet();
            final ActivityResultLauncher<?>[] launcher = new ActivityResultLauncher<?>[1];
            final ActivityResultLauncher<Intent> l = ((ComponentActivity) atak)
                    .getActivityResultRegistry().register(key,
                            new ActivityResultContracts.StartActivityForResult(), result -> {
                                launcher[0].unregister();
                                deliverResult(caller, requestCode, result.getResultCode(),
                                        result.getData());
                            });
            launcher[0] = l;
            l.launch(intent);
        } catch (final ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "unable to start " + intent, e);
            toastNotAvailable();
            deliverResult(caller, requestCode, Activity.RESULT_CANCELED, null);
        }
    }

    /** The string comes from the plugin's resources; ATAK's would crash. */
    private void toastNotAvailable() {
        Toast.makeText(atak, plugin.getString(com.atakmap.android.takconvo.plugin.R.string
                .takconvo_not_available_in_atak), Toast.LENGTH_SHORT).show();
    }

    private void requestPermissionsFor(final Record record, final String[] permissions,
            final int requestCode) {
        final int[] results = new int[permissions.length];
        Arrays.fill(results, PackageManager.PERMISSION_DENIED);
        if (!(atak instanceof ComponentActivity)) {
            deliverPermissions(record, requestCode, permissions, results);
            return;
        }
        final String key = "takconvo#" + externalRequests.incrementAndGet();
        final ActivityResultLauncher<?>[] launcher = new ActivityResultLauncher<?>[1];
        final ActivityResultLauncher<String[]> l = ((ComponentActivity) atak)
                .getActivityResultRegistry().register(key,
                        new ActivityResultContracts.RequestMultiplePermissions(), granted -> {
                            launcher[0].unregister();
                            for (int i = 0; i < permissions.length; i++) {
                                if (Boolean.TRUE.equals(granted.get(permissions[i]))) {
                                    results[i] = PackageManager.PERMISSION_GRANTED;
                                }
                            }
                            deliverPermissions(record, requestCode, permissions, results);
                        });
        launcher[0] = l;
        Log.d(TAG, "requesting " + Arrays.toString(permissions) + " for " + record);
        l.launch(permissions);
    }

    private void deliverPermissions(final Record to, final int requestCode,
            final String[] permissions, final int[] results) {
        if (to.finishing || !stack.contains(to)) {
            return;
        }
        Log.d(TAG, "permissions " + Arrays.toString(permissions) + " for " + to + ": "
                + Arrays.toString(results));
        guarded(to, "deliver permissions to",
                () -> to.activity.onRequestPermissionsResult(requestCode, permissions, results));
    }

    private void deliverResult(final Record to, final int requestCode, final int resultCode,
            final Intent data) {
        if (to == null || requestCode < 0 || to.finishing || !stack.contains(to)) {
            return;
        }
        if (data != null) {
            data.setExtrasClassLoader(plugin.getClassLoader());
        }
        guarded(to, "deliver a result to",
                () -> ON_ACTIVITY_RESULT.invoke(to.activity, requestCode, resultCode, data));
    }

    // protected SDK methods
    private static final Method ON_POST_RESUME = method("onPostResume");
    private static final Method ON_ACTIVITY_RESULT =
            method("onActivityResult", int.class, int.class, Intent.class);

    private static Method method(final String name, final Class<?>... parameters) {
        try {
            final Method m = Activity.class.getDeclaredMethod(name, parameters);
            m.setAccessible(true);
            return m;
        } catch (final NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What the activity passed to setResult(), which has no public getter. */
    private static int resultCode(final Activity activity) {
        try {
            final Field f = Activity.class.getDeclaredField("mResultCode");
            f.setAccessible(true);
            return f.getInt(activity);
        } catch (final ReflectiveOperationException e) {
            Log.w(TAG, "unable to read the result code of " + activity, e);
            return Activity.RESULT_CANCELED;
        }
    }

    private static Intent resultData(final Activity activity) {
        try {
            final Field f = Activity.class.getDeclaredField("mResultData");
            f.setAccessible(true);
            return (Intent) f.get(activity);
        } catch (final ReflectiveOperationException e) {
            Log.w(TAG, "unable to read the result data of " + activity, e);
            return null;
        }
    }

    // --- helpers ---

    private Record top() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    private Record find(final Activity activity) {
        for (final Record r : stack) {
            if (r.activity == activity) {
                return r;
            }
        }
        return null;
    }

    private Record findByClass(final String className) {
        for (final Record r : stack) {
            if (r.activity.getClass().getName().equals(className) && !r.finishing) {
                return r;
            }
        }
        return null;
    }
}
