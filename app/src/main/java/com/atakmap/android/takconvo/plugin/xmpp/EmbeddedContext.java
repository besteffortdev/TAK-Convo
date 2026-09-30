
package com.atakmap.android.takconvo.plugin.xmpp;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The Context Conversations runs on inside the ATAK process.
 *
 * <ul>
 *   <li>Process identity, system services, permissions: ATAK's (the base context). Anything else
 *       fails, because the plugin package does not own this process.</li>
 *   <li>Resources, assets, class loader, theme: the plugin APK's, where Conversations' code and
 *       resources live.</li>
 *   <li>Storage (preferences, databases, files, dirs): ATAK's data directory, but under a
 *       {@code takconvo} prefix so nothing collides with ATAK's own.</li>
 *   <li>{@code startService}/{@code bindService} aimed at XmppConnectionService are routed to
 *       the in-process engine instead of the Android service manager.</li>
 * </ul>
 */
final class EmbeddedContext extends ContextWrapper {

    private static final String PREFIX = "takconvo_";
    private static final String DIR = "takconvo";

    /** Routes service intents to the in-process XmppConnectionService. */
    interface ServiceRouter {
        boolean handles(Intent intent);

        void startCommand(Intent intent);

        IBinder bind(Intent intent);
    }

    private final Context plugin;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<ServiceConnection> boundConnections =
            Collections.newSetFromMap(new WeakHashMap<>());
    private volatile Application application;
    private volatile ServiceRouter router;

    EmbeddedContext(final Context atak, final Context plugin) {
        super(atak.getApplicationContext());
        this.plugin = plugin;
    }

    void setApplication(final Application application) {
        this.application = application;
    }

    void setServiceRouter(final ServiceRouter router) {
        this.router = router;
    }

    @Override
    public Context getApplicationContext() {
        final Application app = application;
        return app != null ? app : this;
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
        return plugin.getClassLoader();
    }

    @Override
    public Resources.Theme getTheme() {
        return plugin.getTheme();
    }

    @Override
    public void setTheme(final int resid) {
        plugin.setTheme(resid);
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

    // --- the XmppConnectionService is an object in this process, not an Android service ---

    @Override
    public ComponentName startService(final Intent service) {
        final ServiceRouter r = router;
        if (r != null && r.handles(service)) {
            mainHandler.post(() -> r.startCommand(service));
            return service.getComponent();
        }
        return super.startService(service);
    }

    @Override
    public ComponentName startForegroundService(final Intent service) {
        final ServiceRouter r = router;
        if (r != null && r.handles(service)) {
            mainHandler.post(() -> r.startCommand(service));
            return service.getComponent();
        }
        return super.startForegroundService(service);
    }

    @Override
    public boolean stopService(final Intent name) {
        final ServiceRouter r = router;
        if (r != null && r.handles(name)) {
            return true;
        }
        return super.stopService(name);
    }

    @Override
    public boolean bindService(final Intent service, final ServiceConnection conn, final int flags) {
        final ServiceRouter r = router;
        if (r != null && r.handles(service)) {
            synchronized (boundConnections) {
                boundConnections.add(conn);
            }
            final IBinder binder = r.bind(service);
            mainHandler.post(() -> conn.onServiceConnected(service.getComponent(), binder));
            return true;
        }
        return super.bindService(service, conn, flags);
    }

    @Override
    public void unbindService(final ServiceConnection conn) {
        synchronized (boundConnections) {
            if (boundConnections.remove(conn)) {
                return;
            }
        }
        super.unbindService(conn);
    }
}
