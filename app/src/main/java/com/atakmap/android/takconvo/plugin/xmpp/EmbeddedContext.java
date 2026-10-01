package com.atakmap.android.takconvo.plugin.xmpp;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The Context Conversations runs on: ATAK's identity and system services, the plugin APK's
 * code and resources, storage in ATAK's data directory under a {@code takconvo} prefix, and
 * XmppConnectionService routed to the in-process engine. Activities get contexts from
 * {@link #forUi}. See docs/02.
 */
final class EmbeddedContext extends ContextWrapper {

    private static final String PREFIX = "takconvo_";
    private static final String DIR = "takconvo";
    /** Context.AUTOFILL_MANAGER_SERVICE, hidden in the SDK. */
    private static final String AUTOFILL_SERVICE = "autofill";
    /** Context.CONTENT_CAPTURE_MANAGER_SERVICE, hidden in the SDK. */
    private static final String CONTENT_CAPTURE_SERVICE = "content_capture";

    /** Routes service intents to the in-process XmppConnectionService. */
    interface ServiceRouter {
        boolean handles(Intent intent);

        void startCommand(Intent intent);

        IBinder bind(Intent intent);
    }

    /** The plugin's own context, never a configuration context. */
    private final Context pluginRoot;
    /** Where resources come from: {@link #pluginRoot} or a configuration context of it. */
    private final Context plugin;
    /** Null on the root instance. */
    private final EmbeddedContext root;
    /** A UI context's configuration override; null on the root instance. */
    private final Configuration uiOverride;
    private Resources.Theme uiTheme;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<ServiceConnection> boundConnections =
            Collections.newSetFromMap(new WeakHashMap<>());
    private volatile Application application;
    private volatile ServiceRouter router;

    EmbeddedContext(final Context atak, final Context plugin) {
        super(atak.getApplicationContext());
        this.pluginRoot = plugin;
        this.plugin = plugin;
        this.root = null;
        this.uiOverride = null;
    }

    private EmbeddedContext(final EmbeddedContext root, final Context base,
            final Configuration override) {
        super(base);
        this.root = root;
        this.pluginRoot = root.pluginRoot;
        this.uiOverride = new Configuration(override);
        this.plugin = root.pluginRoot.createConfigurationContext(this.uiOverride);
    }

    /**
     * A context for a Conversations activity, on a base of its own: attaching an activity
     * stores its autofill state in the base, which must not be ATAK's activity.
     */
    Context forUi(final Display display, final Configuration override) {
        final EmbeddedContext r = root();
        return new EmbeddedContext(r, r.getBaseContext().createDisplayContext(display), override);
    }

    private EmbeddedContext root() {
        return root == null ? this : root;
    }

    void setApplication(final Application application) {
        this.application = application;
    }

    void setServiceRouter(final ServiceRouter router) {
        this.router = router;
    }

    @Override
    public Context getApplicationContext() {
        final Application app = root().application;
        return app != null ? app : root();
    }

    // --- code and resources come from the plugin APK ---

    @Override
    public Resources getResources() {
        return plugin.getResources();
    }

    @Override
    public AssetManager getAssets() {
        return plugin.getAssets();
    }

    @Override
    public ClassLoader getClassLoader() {
        return pluginRoot.getClassLoader();
    }

    @Override
    public Resources.Theme getTheme() {
        if (root == null) {
            return plugin.getTheme();
        }
        // an activity starts from an empty theme, not the plugin's
        if (uiTheme == null) {
            uiTheme = getResources().newTheme();
        }
        return uiTheme;
    }

    @Override
    public void setTheme(final int resid) {
        if (root == null) {
            plugin.setTheme(resid);
        } else {
            getTheme().applyStyle(resid, true);
        }
    }

    /** AppCompat applies night mode and locales through this. */
    @Override
    public Context createConfigurationContext(final Configuration overrideConfiguration) {
        final Configuration merged = new Configuration();
        if (uiOverride != null) {
            merged.setTo(uiOverride);
        }
        merged.updateFrom(overrideConfiguration);
        return new EmbeddedContext(root(),
                getBaseContext().createConfigurationContext(overrideConfiguration), merged);
    }

    @Override
    public Object getSystemService(final String name) {
        // the system rejects these sessions: an embedded activity has no token it knows
        if (root != null
                && (AUTOFILL_SERVICE.equals(name) || CONTENT_CAPTURE_SERVICE.equals(name))) {
            return null;
        }
        return super.getSystemService(name);
    }

    // --- storage lives in ATAK's data dir, namespaced ---

    @Override
    public SharedPreferences getSharedPreferences(final String name, final int mode) {
        return super.getSharedPreferences(PREFIX + name, mode);
    }

    @Override
    public boolean deleteSharedPreferences(final String name) {
        return super.deleteSharedPreferences(PREFIX + name);
    }

    @Override
    public File getDatabasePath(final String name) {
        return super.getDatabasePath(PREFIX + name);
    }

    @Override
    public boolean deleteDatabase(final String name) {
        return super.deleteDatabase(PREFIX + name);
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(
            final String name, final int mode, final SQLiteDatabase.CursorFactory factory) {
        return super.openOrCreateDatabase(PREFIX + name, mode, factory);
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(
            final String name,
            final int mode,
            final SQLiteDatabase.CursorFactory factory,
            final DatabaseErrorHandler errorHandler) {
        return super.openOrCreateDatabase(PREFIX + name, mode, factory, errorHandler);
    }

    @Override
    public File getDir(final String name, final int mode) {
        return super.getDir(PREFIX + name, mode);
    }

    @Override
    public File getFilesDir() {
        return sub(super.getFilesDir());
    }

    @Override
    public File getNoBackupFilesDir() {
        return sub(super.getNoBackupFilesDir());
    }

    @Override
    public File getCacheDir() {
        return sub(super.getCacheDir());
    }

    @Override
    public File getExternalCacheDir() {
        return sub(super.getExternalCacheDir());
    }

    @Override
    public File getExternalFilesDir(final String type) {
        final File base = sub(super.getExternalFilesDir(null));
        if (base == null || type == null) {
            return base;
        }
        final File dir = new File(base, type);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    @Override
    public File[] getExternalFilesDirs(final String type) {
        return new File[] {getExternalFilesDir(type)};
    }

    @Override
    public File getFileStreamPath(final String name) {
        return new File(getFilesDir(), name);
    }

    @Override
    public FileInputStream openFileInput(final String name) throws FileNotFoundException {
        return new FileInputStream(getFileStreamPath(name));
    }

    @Override
    public FileOutputStream openFileOutput(final String name, final int mode)
            throws FileNotFoundException {
        return new FileOutputStream(getFileStreamPath(name), (mode & MODE_APPEND) != 0);
    }

    @Override
    public boolean deleteFile(final String name) {
        return getFileStreamPath(name).delete();
    }

    private static File sub(final File base) {
        if (base == null) {
            return null;
        }
        final File dir = new File(base, DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    // --- XmppConnectionService is an object here, not an Android service ---

    @Override
    public ComponentName startService(final Intent service) {
        final ServiceRouter r = root().router;
        if (r != null && r.handles(service)) {
            mainHandler.post(() -> r.startCommand(service));
            return service.getComponent();
        }
        return super.startService(service);
    }

    @Override
    public ComponentName startForegroundService(final Intent service) {
        final ServiceRouter r = root().router;
        if (r != null && r.handles(service)) {
            mainHandler.post(() -> r.startCommand(service));
            return service.getComponent();
        }
        return super.startForegroundService(service);
    }

    @Override
    public boolean stopService(final Intent name) {
        final ServiceRouter r = root().router;
        if (r != null && r.handles(name)) {
            return true;
        }
        return super.stopService(name);
    }

    @Override
    public boolean bindService(final Intent service, final ServiceConnection conn,
            final int flags) {
        final ServiceRouter r = root().router;
        if (r != null && r.handles(service)) {
            final Set<ServiceConnection> bound = root().boundConnections;
            synchronized (bound) {
                bound.add(conn);
            }
            final IBinder binder = r.bind(service);
            mainHandler.post(() -> conn.onServiceConnected(service.getComponent(), binder));
            return true;
        }
        return super.bindService(service, conn, flags);
    }

    @Override
    public void unbindService(final ServiceConnection conn) {
        final Set<ServiceConnection> bound = root().boundConnections;
        synchronized (bound) {
            if (bound.remove(conn)) {
                return;
            }
        }
        super.unbindService(conn);
    }
}
