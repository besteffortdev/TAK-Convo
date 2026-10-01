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
 * Runs Conversations' own activities inside an ATAK pane.
 *
 * <p>An activity of a plugin can't be started in ATAK's process, so each one is created here the
 * way {@code ActivityUnitTestCase} did: {@link Instrumentation#newActivity} attaches it with a
 * window of its own, and this class calls its lifecycle. That window is never shown. The
 * activity's views are moved out of it into {@link #getView()}, which ATAK shows in a pane.
 *
 * <p>The activities form a back stack like a task's. What an activity asks of the system goes
 * through {@link HostParent}: starting another Conversations activity pushes it here, anything
 * else is started by ATAK's real activity, and finishing pops the stack.
 *
 * <p>All methods must be called on the main thread.
 */
public final class EmbeddedActivityHost implements HostParent.Callbacks {

    private static final String TAG = "TakConvo.Host";
    private static final String CONVERSATIONS_PACKAGE = "eu.siacs.conversations.";

    /** Things that happen outside the pane's content. */
    public interface Listener {
        /** The last activity finished: there is nothing left to show. */
        void onHostEmpty();

        /** The activity on top finished; the one below it shows again. */
        void onTopFinished(Activity finished);

        /** An activity asked for the XMPP account screen, which the plugin provides itself. */
        void onShowAccount();

        /** An activity asked for Conversations' settings; the plugin's own replace them. */
        void onShowSettings();
    }

    /** Conversations activities that work embedded. Others are refused with a toast. */
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
            // camera preview on a TextureView, which draws in ATAK's window like any view
            "eu.siacs.conversations.ui.ScanQrCodeActivity",
            // the fork picks the image without the cropper's activity
            "eu.siacs.conversations.ui.PublishProfilePictureActivity"));

    /**
     * Dialog-themed activities: shown over the activity below, which stays visible and paused,
     * as a floating window would leave it.
     */
    private static final Set<String> FLOATING = new HashSet<>(Arrays.asList(
            "eu.siacs.conversations.ui.RecordingActivity"));

    private static final String SETTINGS_ACTIVITY =
            "eu.siacs.conversations.ui.activity.SettingsActivity";

    /** Started again, these come back to the front instead of being created twice. */
    private static final Set<String> SINGLE_INSTANCE = new HashSet<>(Arrays.asList(
            "eu.siacs.conversations.ui.ConversationsActivity",
            "eu.siacs.conversations.ui.StartConversationActivity"));

    private static final class Record {
        final Activity activity;
        /** who gets the result, or null */
        final Record caller;
        final int requestCode;
        /** shown over the activity below instead of hiding it */
        final boolean floating;
        View content;
        boolean started;
        boolean resumed;
        boolean stoppedBefore;
        boolean finishing;

        Record(final Activity activity, final Record caller, final int requestCode,
                final boolean floating) {
            this.activity = activity;
            this.caller = caller;
            this.requestCode = requestCode;
            this.floating = floating;
        }

        @Override
        public String toString() {
            return activity.getClass().getSimpleName();
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
    /** follows ATAK's activity once there is an activity here */
    private LifecycleEventObserver atakObserver;
    private ActivityCompat.PermissionCompatDelegate previousPermissionDelegate;

    /**
     * An embedded activity can't request permissions itself: Activity.requestPermissions starts
     * the system's dialog through the ActivityThread it doesn't have. ATAK's activity asks
     * instead. Conversations requests them through ActivityCompat, which asks this first.
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
    private boolean visible;
    private int paneWidthPx;
    private int paneHeightPx;

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
        });
        // ATAK is dark whatever the device setting is. AppCompat would otherwise follow the
        // device, and an embedded activity can't be recreated when that changes.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    }

    /** The view to show in the pane. */
    public View getView() {
        return container;
    }

    /** The size the pane is about to get, which selects the activities' resources. */
    public void setPaneSize(final int widthPx, final int heightPx) {
        this.paneWidthPx = widthPx;
        this.paneHeightPx = heightPx;
    }

    /** Starts Conversations' main screen, the list of chats, unless something is shown already. */
    public void showMain() {
        if (!stack.isEmpty()) {
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

    /**
     * Starts an activity as if the system had, e.g. what a tapped notification aims at. Routed
     * like one an activity starts: the account screen and settings go to the {@link Listener}.
     */
    public void startActivity(final Intent intent) {
        start(null, intent, -1);
    }

    /** @return true if no activity is shown */
    public boolean isEmpty() {
        return stack.isEmpty();
    }

    /** The pane became visible or hidden; hidden activities are stopped, not destroyed. */
    public void setVisible(final boolean visible) {
        if (this.visible == visible) {
            return;
        }
        this.visible = visible;
        settleShown();
    }

    /**
     * @return true if the activity on top used the back press, false if there is nothing left
     *     to go back to and the pane should close
     */
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

    /** Destroys every activity. The host can be used again afterwards. */
    public void destroy() {
        handler.removeCallbacksAndMessages(null);
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
        // after the caller's current callback: it often finishes itself right after starting
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

        final Record existing = findByClass(name);
        if (existing != null && (SINGLE_INSTANCE.contains(name)
                || (intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0)) {
            // like singleTask / CLEAR_TOP: drop what is above it and hand it the intent
            while (top() != existing) {
                final Record above = stack.remove(stack.size() - 1);
                guarded(above, "destroy", () -> destroy(above));
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
        } catch (final Throwable t) {
            Log.e(TAG, "unable to create " + name, t);
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
            // what was shown below, including what showed through a floating activity
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
        settleShown();
        listener.onTopFinished(record.activity);
    }

    private Record create(final String className, final Intent intent, final Record caller,
            final int requestCode) throws Exception {
        attachToAtak();
        final Class<? extends Activity> cls =
                plugin.getClassLoader().loadClass(className).asSubclass(Activity.class);
        final Context base = engine.newUiContext(atak.getWindowManager().getDefaultDisplay(),
                paneConfiguration());
        intent.setComponent(new ComponentName(atak.getPackageName(), className));
        intent.setExtrasClassLoader(plugin.getClassLoader());
        final boolean floating = FLOATING.contains(className);
        final ActivityInfo info = new ActivityInfo();
        info.applicationInfo = atak.getApplicationInfo();
        info.packageName = atak.getPackageName();
        info.name = className;
        info.theme = floating ? R.style.Theme_Conversations3_Dialog
                : R.style.Theme_Conversations3;
        info.flags = ActivityInfo.FLAG_HARDWARE_ACCELERATED;

        Log.d(TAG, "creating " + className);
        final Activity activity = instrumentation.newActivity(cls, base, null,
                engine.getApplication(), intent, info, "", parent, className, null);
        activity.setTheme(info.theme);
        final Record record = new Record(activity, caller, requestCode, floating);
        // on the stack before onCreate, which may already start or finish activities
        stack.add(record);
        try {
            instrumentation.callActivityOnCreate(activity, null);
            instrumentation.callActivityOnPostCreate(activity, null);
            final View content = takeContent(activity);
            record.content = floating ? floatOver(activity, content) : content;
        } catch (final Throwable t) {
            stack.remove(record);
            throw t;
        }
        // set on the activity's own window, which is never shown; the pane is in ATAK's
        if ((activity.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0) {
            record.content.setKeepScreenOn(true);
        }
        container.addView(record.content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return record;
    }

    /**
     * What a floating window would look like: the content in a dialog's shape over a dimmed
     * pane. Touches outside it do nothing, as with {@code setFinishOnTouchOutside(false)}.
     */
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
     * The resources an activity gets: dark, scaled down ({@link UiScale}), and sized like the
     * pane rather than the screen, so that Conversations picks its phone layouts in a side pane
     * of a tablet.
     */
    private Configuration paneConfiguration() {
        final Configuration override = UiScale.override(atak);
        final float density = UiScale.density(atak);
        final int width = container.getWidth() > 0 ? container.getWidth() : paneWidthPx;
        final int height = container.getHeight() > 0 ? container.getHeight() : paneHeightPx;
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
     * Takes the activity's views out of its window: AppCompat's content frame together with the
     * layout around it that holds action mode bars. The window itself stays unattached, because
     * a window's decor view reconfigures the view root it is attached to, and that is ATAK's.
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
            // what the window would have drawn behind them
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
     * Brings a shown activity to the state it would have in a task: the top one resumed while
     * the pane is visible and ATAK is resumed, paused while ATAK is only started (or, below a
     * floating one, always), stopped otherwise. Conversations marks what a resumed chat shows
     * as read and holds back its notifications, so it must not stay resumed behind other apps.
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

    /** Shows and settles the top activity and, below a floating one, what it floats over. */
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

    /** Follows ATAK's activity and takes over the permission requests of the activities here. */
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

    /**
     * An activity's own Lifecycle follows callbacks the system makes around onStart, onResume
     * and onStop. Instrumentation doesn't make those, so the events are dispatched here.
     * Dispatching one that already happened does nothing.
     */
    private static void lifecycle(final Record r, final Lifecycle.Event event) {
        if (r.activity instanceof LifecycleOwner) {
            final Lifecycle lifecycle = ((LifecycleOwner) r.activity).getLifecycle();
            if (lifecycle instanceof LifecycleRegistry) {
                ((LifecycleRegistry) lifecycle).handleLifecycleEvent(event);
            }
        }
    }

    /** A failing activity is dropped; it must not take ATAK down. */
    private void guarded(final Record record, final String what, final ThrowingRunnable action) {
        try {
            action.run();
        } catch (final Throwable t) {
            Log.e(TAG, "unable to " + what + " " + record, t);
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
            // ATAK's activity is the only one the system returns results to
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

    /** From the plugin's resources: ATAK's context would look the string up in ATAK's. */
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

    // onPostResume and onActivityResult are protected, but part of the SDK
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

    /** What the activity passed to setResult(). There is no public getter. */
    private static int resultCode(final Activity activity) {
        try {
            final Field f = Activity.class.getDeclaredField("mResultCode");
            f.setAccessible(true);
            return f.getInt(activity);
        } catch (final Exception e) {
            return Activity.RESULT_CANCELED;
        }
    }

    private static Intent resultData(final Activity activity) {
        try {
            final Field f = Activity.class.getDeclaredField("mResultData");
            f.setAccessible(true);
            return (Intent) f.get(activity);
        } catch (final Exception e) {
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
