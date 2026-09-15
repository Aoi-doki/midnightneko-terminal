package com.termux.styling;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * One colour scheme or font that can be installed into {@code ~/.termux}.
 *
 * <p>Merged from termux-styling, whose single Kotlin activity is rewritten in Java here so the
 * build does not need the Kotlin plugin for 192 lines of code. The asset layout is unchanged --
 * {@code assets/colors/*.properties} and {@code assets/fonts/*.ttf}, each optionally accompanied
 * by a {@code .txt} licence of the same basename.
 */
public final class StyleAsset {

    private static final String LOG_TAG = "StyleAsset";

    /** The pseudo-entry that restores Mayonaka's own baked-in scheme or font. */
    public static final String DEFAULT_FILE_NAME = "Default";

    /** What is being styled. */
    public enum Kind {
        COLORS("colors", ".properties", "colors.properties", "colors"),
        FONT("fonts", ".ttf", "font.ttf", "font");

        /** Assets subdirectory holding this kind. */
        public final String assetDir;
        /** Extension that marks a real entry (as opposed to a licence file). */
        public final String extension;
        /** Basename written into {@code ~/.termux}. */
        public final String destinationName;
        /** Value passed in the reload broadcast so the app knows what changed. */
        public final String reloadExtra;

        Kind(String assetDir, String extension, String destinationName, String reloadExtra) {
            this.assetDir = assetDir;
            this.extension = extension;
            this.destinationName = destinationName;
            this.reloadExtra = reloadExtra;
        }

        File destinationFile() {
            return new File(TermuxConstants.TERMUX_DATA_HOME_DIR, destinationName);
        }

        String preferenceKey() {
            return "mayonaka_style_" + name().toLowerCase();
        }
    }

    public final String fileName;
    public final String displayName;

    StyleAsset(@NonNull String fileName) {
        this.fileName = fileName;

        String name = fileName.replace('-', ' ');
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex != -1) name = name.substring(0, dotIndex);
        this.displayName = capitalize(name);
    }

    public boolean isDefault() {
        return DEFAULT_FILE_NAME.equals(fileName);
    }

    @NonNull
    @Override
    public String toString() {
        return displayName;
    }

    /** Upper-case the first letter of every word, leaving the rest alone. */
    static String capitalize(@NonNull String str) {
        char[] chars = str.toCharArray();
        boolean lastWhitespace = true;
        for (int i = 0; i < chars.length; i++) {
            if (Character.isLetter(chars[i])) {
                if (lastWhitespace) chars[i] = Character.toUpperCase(chars[i]);
                lastWhitespace = false;
            } else {
                lastWhitespace = Character.isWhitespace(chars[i]);
            }
        }
        return new String(chars);
    }


    // ---------------------------------------------------------------------------------------
    // Listing
    // ---------------------------------------------------------------------------------------

    /**
     * Every entry of {@code kind}, alphabetically, with the "Default" pseudo-entry first.
     */
    @NonNull
    public static List<StyleAsset> list(@NonNull Context context, @NonNull Kind kind) {
        List<StyleAsset> entries = new ArrayList<>();

        String[] names;
        try {
            names = context.getAssets().list(kind.assetDir);
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed listing assets/" + kind.assetDir, e);
            names = null;
        }

        if (names != null) {
            for (String name : names) {
                if (name.endsWith(kind.extension)) entries.add(new StyleAsset(name));
            }
        }

        // Collections.sort with an explicit Comparator rather than List.sort/Comparator.comparing,
        // which are API 24 and this app still supports API 21.
        Collections.sort(entries, new Comparator<StyleAsset>() {
            @Override
            public int compare(StyleAsset a, StyleAsset b) {
                return a.displayName.compareToIgnoreCase(b.displayName);
            }
        });
        entries.add(0, new StyleAsset(DEFAULT_FILE_NAME));
        return entries;
    }

    /**
     * The licence text shipped alongside this entry, or {@code null} if it has none.
     */
    @Nullable
    public String readLicense(@NonNull Context context, @NonNull Kind kind) {
        int dotIndex = fileName.lastIndexOf('.');
        String base = dotIndex == -1 ? fileName : fileName.substring(0, dotIndex);

        try (InputStream in = context.getAssets().open(kind.assetDir + "/" + base + ".txt")) {
            byte[] buffer = new byte[in.available()];
            int read = 0;
            while (read < buffer.length) {
                int n = in.read(buffer, read, buffer.length - read);
                if (n < 0) break;
                read += n;
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }


    // ---------------------------------------------------------------------------------------
    // Installing
    // ---------------------------------------------------------------------------------------

    /**
     * Write this entry into {@code ~/.termux} and tell the terminal to reload.
     *
     * <p>Picking "Default" restores Mayonaka's own baked-in scheme or font rather than leaving an
     * empty marker file the way upstream did -- the point of this fork is that the midnight
     * defaults are always one tap away.
     *
     * @return {@code null} on success, otherwise a message describing what went wrong.
     */
    @Nullable
    public String install(@NonNull Context context, @NonNull Kind kind) {
        try {
            File dataHome = TermuxConstants.TERMUX_DATA_HOME_DIR;
            if (!dataHome.isDirectory() && !dataHome.mkdirs())
                return "Cannot create " + dataHome.getAbsolutePath();

            // canonicalFile follows a symlink the user may have put here on purpose.
            File destination = kind.destinationFile().getCanonicalFile();

            // Undo a chmod the user may have applied to the directory or the file.
            destination.setWritable(true);
            File parent = destination.getParentFile();
            if (parent != null) {
                parent.setWritable(true);
                parent.setExecutable(true);
            }

            AtomicFile atomicFile = new AtomicFile(destination);
            FileOutputStream out = atomicFile.startWrite();
            try {
                String assetPath = isDefault()
                    ? "mayonaka/" + kind.destinationName
                    : kind.assetDir + "/" + fileName;
                try (InputStream in = context.getAssets().open(assetPath)) {
                    copy(in, out);
                }
                atomicFile.finishWrite(out);
                out = null;
            } finally {
                if (out != null) atomicFile.failWrite(out);
            }

            remember(context, kind, this);
            sendReloadBroadcast(context, kind);
            return null;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write " + kind.destinationName, e);
            return e.getMessage();
        }
    }

    private static void copy(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static void sendReloadBroadcast(@NonNull Context context, @NonNull Kind kind) {
        Intent intent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intent.putExtra(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE, kind.reloadExtra);
        // Colours and fonts are picked up without rebuilding the activity.
        intent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, false);
        intent.setPackage(TermuxConstants.TERMUX_PACKAGE_NAME);
        context.sendBroadcast(intent);
    }


    // ---------------------------------------------------------------------------------------
    // Remembering what is currently applied
    // ---------------------------------------------------------------------------------------
    //
    // ~/.termux/colors.properties and font.ttf are plain files with no record of which entry
    // produced them, so the chosen name is kept in preferences purely so the pickers can show
    // what is currently applied.

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    private static void remember(@NonNull Context context, @NonNull Kind kind, @NonNull StyleAsset asset) {
        prefs(context).edit().putString(kind.preferenceKey(), asset.fileName).apply();
    }

    /** File name of the entry last applied for {@code kind}, defaulting to "Default". */
    @NonNull
    public static String currentFileName(@NonNull Context context, @NonNull Kind kind) {
        return prefs(context).getString(kind.preferenceKey(), DEFAULT_FILE_NAME);
    }

    /** Display name of the entry last applied for {@code kind}. */
    @NonNull
    public static String currentDisplayName(@NonNull Context context, @NonNull Kind kind) {
        return new StyleAsset(currentFileName(context, kind)).displayName;
    }

    /** Index of the currently applied entry in {@code entries}, or 0 if it is not there. */
    public static int indexOfCurrent(@NonNull Context context, @NonNull Kind kind,
                                     @NonNull List<StyleAsset> entries) {
        String current = currentFileName(context, kind);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).fileName.equals(current)) return i;
        }
        return 0;
    }

    /** Every kind, for callers that want to iterate. */
    public static List<Kind> kinds() {
        return Arrays.asList(Kind.values());
    }
}
