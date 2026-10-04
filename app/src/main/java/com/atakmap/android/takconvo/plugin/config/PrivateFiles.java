package com.atakmap.android.takconvo.plugin.config;

import android.content.Context;
import android.util.AtomicFile;

import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Small values a {@code .pref} file must not be able to set. ATAK's {@code .pref} import writes
 * any preferences file a {@code <preference name>} names, the plugin's own included; a file in
 * ATAK's no-backup directory is out of its reach, and out of backups. See docs/03.
 */
public final class PrivateFiles {

    private static final String TAG = "TakConvo.PrivateFiles";
    private static final String DIR = "takconvo_plugin";

    private PrivateFiles() {
    }

    /** The directory, in ATAK's no-backup storage; the Clear Content wipe deletes it. */
    public static File dir(final Context atakContext) {
        return new File(atakContext.getNoBackupFilesDir(), DIR);
    }

    /** The value stored under {@code name}, or null. Disk work. */
    public static String read(final Context atakContext, final String name) {
        final AtomicFile file = new AtomicFile(new File(dir(atakContext), name));
        try {
            return new String(file.readFully(), StandardCharsets.UTF_8);
        } catch (final FileNotFoundException e) {
            return null;
        } catch (final IOException e) {
            Log.w(TAG, "unable to read " + name, e);
            return null;
        }
    }

    /** Stores a value; false if it couldn't be written. Disk work. */
    public static boolean write(final Context atakContext, final String name, final String value) {
        final File dir = dir(atakContext);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "unable to create " + dir);
            return false;
        }
        final AtomicFile file = new AtomicFile(new File(dir, name));
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(value.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
            return true;
        } catch (final IOException e) {
            if (out != null) {
                file.failWrite(out);
            }
            Log.w(TAG, "unable to write " + name, e);
            return false;
        }
    }
}
