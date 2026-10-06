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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;

import com.atakmap.android.takconvo.plugin.Guard;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The Context Conversations runs on: ATAK's identity and system services, the plugin APK's
 * code and resources, storage in ATAK's data directory under a {@code takconvo} prefix, and
 * XmppConnectionService routed to the in-process engine. Activities get contexts from
 * {@link #forUi}. See docs/02.
 */
final class EmbeddedContext extends ContextWrapper {

    private static final String TAG = "TakConvo.Context";
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

    /** The UI contexts of one activity ({@link #forUi} and its configuration contexts). */
    private static final class Owner {
        volatile boolean released;
    }

    /** The plugin's own context, never a configuration context. */
    private final Context pluginRoot;
    /** Where resources come from: {@link #pluginRoot} or a configuration context of it. */
    private final Context plugin;
    /** Null on the root instance. */
    private final EmbeddedContext root;
    /** A UI context's configuration override; null on the root instance. */
    private final Configuration uiOverride;
    /** Null for the root and its configuration contexts. */
    private final Owner owner;
    private Resources.Theme uiTheme;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** The connections bound to the in-process service, with the UI contexts that bound them. */
    private final Map<ServiceConnection, Owner> boundConnections = new WeakHashMap<>();
    private volatile Application application;
    private volatile ServiceRouter router;

    EmbeddedContext(final Context atak, final Context plugin) {
        super(atak.getApplicationContext());
        this.pluginRoot = plugin;
        this.plugin = plugin;
        this.root = null;
        this.uiOverride = null;
        this.owner = null;
    }

    private EmbeddedContext(final EmbeddedContext root, final Context base,
            final Configuration override, final Owner owner) {
        super(base);
        this.root = root;
        this.pluginRoot = root.pluginRoot;
        this.uiOverride = new Configuration(override);
        this.plugin = root.pluginRoot.createConfigurationContext(this.uiOverride);
        this.owner = owner;
    }

    /**
     * A context for a Conversations activity, on a base of its own: attaching an activity
     * stores its autofill state in the base, which must not be ATAK's activity.
     */
    Context forUi(final Display display, final Configuration override) {
        final EmbeddedContext r = root();
        return new EmbeddedContext(r, r.getBaseContext().createDisplayContext(display), override,
                new Owner());
    }

    /**
     * The activity of this {@link #forUi} context is destroyed: its connections to the service
     * end, and a connection not yet delivered never is, as Android does for an activity.
     */
    void release() {
        if (owner == null) {
            return;
        }
        owner.released = true;
        final Map<ServiceConnection, Owner> bound = root().boundConnections;
        synchronized (bound) {
            for (final Iterator<Owner> i = bound.values().iterator(); i.hasNext(); ) {
                if (i.next() == owner) {
                    i.remove();
                }
            }
        }
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
                getBaseContext().createConfigurationContext(overrideConfiguration), merged, owner);
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

    /**
     * Deletes everything stored through these contexts: Conversations' databases (messages,
     * OMEMO keys), settings and files, in and outside ATAK's data directory. Once the engine
     * has stopped; any thread.
     */
    static void deleteAll(final Context atak) {
        final Context app = atak.getApplicationContext();
        for (final String name : app.databaseList()) {
            if (name.startsWith(PREFIX)) {
                // with its journal
                app.deleteDatabase(name);
            }
        }
        final File dataDir = new File(app.getApplicationInfo().dataDir);
        final File[] prefs = new File(dataDir, "shared_prefs").listFiles();
        for (final File file : prefs == null ? new File[0] : prefs) {
            final String name = file.getName();
            if (!name.startsWith(PREFIX)) {
                continue;
            }
            if (name.endsWith(".xml") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // also drops the copy in memory, which a later write would save again
                app.deleteSharedPreferences(name.substring(0, name.length() - 4));
            } else {
                deleteRecursively(file);
            }
        }
        final File[] dirs = dataDir.listFiles();
        for (final File dir : dirs == null ? new File[0] : dirs) {
            if (dir.getName().startsWith("app_" + PREFIX)) {
                // getDir's
                deleteRecursively(dir);
            }
        }
        for (final File base : new File[] {app.getFilesDir(), app.getNoBackupFilesDir(),
                app.getCacheDir(), app.getExternalFilesDir(null), app.getExternalCacheDir()}) {
            if (base != null) {
                deleteRecursively(new File(base, DIR));
            }
        }
    }

    static void deleteRecursively(final File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                deleteRecursively(child);
            }
        }
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "unable to delete " + file);
        }
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
            mainHandler.post(startCommand(r, service));
            return service.getComponent();
        }
        return super.startService(service);
    }

    @Override
    public ComponentName startForegroundService(final Intent service) {
        final ServiceRouter r = root().router;
        if (r != null && r.handles(service)) {
            mainHandler.post(startCommand(r, service));
            return service.getComponent();
        }
        return super.startForegroundService(service);
    }

    /** The service's onStartCommand, guarded: it runs in ATAK's main thread. */
    private static Runnable startCommand(final ServiceRouter r, final Intent service) {
        return Guard.wrap(TAG, "run the XMPP service's command " + service.getAction(),
                () -> r.startCommand(service));
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
            final Map<ServiceConnection, Owner> bound = root().boundConnections;
            synchronized (bound) {
                bound.put(conn, owner);
            }
            final IBinder binder = r.bind(service);
            mainHandler.post(Guard.wrap(TAG, "connect to the XMPP service", () -> {
                // not once unbound, or once the activity that bound is destroyed
                if (isBound(conn)) {
                    conn.onServiceConnected(service.getComponent(), binder);
                }
            }));
            return true;
        }
        return super.bindService(service, conn, flags);
    }

    @Override
    public void unbindService(final ServiceConnection conn) {
        final Map<ServiceConnection, Owner> bound = root().boundConnections;
        synchronized (bound) {
            if (bound.containsKey(conn)) {
                bound.remove(conn);
                return;
            }
        }
        if (owner != null && owner.released) {
            // released with its activity already
            return;
        }
        super.unbindService(conn);
    }

    private boolean isBound(final ServiceConnection conn) {
        final Map<ServiceConnection, Owner> bound = root().boundConnections;
        synchronized (bound) {
            return bound.containsKey(conn);
        }
    }
}
