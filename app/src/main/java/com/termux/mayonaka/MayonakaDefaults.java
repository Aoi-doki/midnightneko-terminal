package com.termux.mayonaka;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Installs the defaults baked into the APK -- the terminal colour scheme, the font, a
 * {@code termux.properties} and the provisioning script -- into {@code $HOME} the first time
 * Mayonaka runs.
 *
 * <p>Two rules govern everything in here:
 *
 * <ul>
 *   <li><b>Never clobber an edit.</b> A config file is written only if it does not exist yet, or
 *       if its bytes still match exactly what Mayonaka last wrote there. The moment you change a
 *       file by hand it becomes yours and app updates stop touching it.
 *   <li><b>$HOME only exists after the bootstrap.</b> So this must run from the {@code whenDone}
 *       callback of
 *       {@link com.termux.app.TermuxInstaller#setupBootstrapIfNeeded(android.app.Activity, Runnable)},
 *       never from {@code onCreate}.
 * </ul>
 */
public final class MayonakaDefaults {

    private static final String LOG_TAG = "MayonakaDefaults";

    /** Directory inside the APK's assets holding everything this class installs. */
    private static final String ASSET_DIR = "mayonaka";

    /** Asset -> destination. Everything here lands in {@code ~/.termux} except the setup script. */
    private static final String ASSET_COLORS = "colors.properties";
    private static final String ASSET_PROPERTIES = "termux.properties";
    private static final String ASSET_FONT = "font.ttf";
    private static final String ASSET_SETUP = "setup.sh";

    /** Where the provisioning script lands. */
    public static final File SETUP_SCRIPT_FILE =
        new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "setup.sh");

    /**
     * Digest of the copy of each file Mayonaka last wrote, keyed by destination path. Used to tell
     * "the user has never touched this" from "the user edited this".
     */
    private static final String PREF_SHIPPED_DIGEST_PREFIX = "mayonaka_shipped_digest_";

    /** Set once the defaults have been installed at least once. */
    private static final String PREF_DEFAULTS_INSTALLED = "mayonaka_defaults_installed";

    /** Set once the user has been offered the provisioning run, whatever they answered. */
    private static final String PREF_PROVISIONING_OFFERED = "mayonaka_provisioning_offered";

    private MayonakaDefaults() {}

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    /**
     * Install every baked-in default that is not already present.
     *
     * <p>Cheap enough to call on every launch: when nothing has changed it is four digests and no
     * writes. Must be called after the bootstrap has been extracted.
     *
     * @return {@code true} if this was the first install, i.e. the run that should offer to
     *         provision.
     */
    public static boolean installIfNeeded(@NonNull Context context) {
        boolean firstInstall = !prefs(context).getBoolean(PREF_DEFAULTS_INSTALLED, false);

        File dataHome = TermuxConstants.TERMUX_DATA_HOME_DIR;
        if (!dataHome.isDirectory() && !dataHome.mkdirs()) {
            Logger.logError(LOG_TAG, "Could not create " + dataHome);
            return false;
        }

        installAsset(context, ASSET_COLORS, TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE, false);
        installAsset(context, ASSET_PROPERTIES, TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE, false);
        installAsset(context, ASSET_FONT, TermuxConstants.TERMUX_FONT_FILE, false);
        installAsset(context, ASSET_SETUP, SETUP_SCRIPT_FILE, true);

        prefs(context).edit().putBoolean(PREF_DEFAULTS_INSTALLED, true).apply();
        return firstInstall;
    }

    /**
     * Force every baked-in default back onto disk, overwriting whatever is there.
     *
     * <p>Only ever called from an explicit user action in Settings, never automatically.
     */
    public static void reinstallAll(@NonNull Context context) {
        File dataHome = TermuxConstants.TERMUX_DATA_HOME_DIR;
        if (!dataHome.isDirectory() && !dataHome.mkdirs()) {
            Logger.logError(LOG_TAG, "Could not create " + dataHome);
            return;
        }

        forceInstallAsset(context, ASSET_COLORS, TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE);
        forceInstallAsset(context, ASSET_PROPERTIES, TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE);
        forceInstallAsset(context, ASSET_FONT, TermuxConstants.TERMUX_FONT_FILE);
        forceInstallAsset(context, ASSET_SETUP, SETUP_SCRIPT_FILE);
    }

    /**
     * Copy one asset to {@code destination}.
     *
     * @param executable whether the destination should be marked executable once written.
     */
    private static void installAsset(@NonNull Context context, @NonNull String assetName,
                                     @NonNull File destination, boolean executable) {
        byte[] shipped = readAsset(context, assetName);
        if (shipped == null) return;

        if (destination.exists()) {
            byte[] onDisk = readFile(destination);
            if (onDisk == null) return;

            String currentDigest = digest(onDisk);
            // Identical to what is baked in: nothing to do.
            if (currentDigest.equals(digest(shipped))) {
                rememberShipped(context, destination, shipped);
                return;
            }

            // Different from what is baked in. Only overwrite if it is untouched since Mayonaka
            // wrote it, which means the APK has shipped a newer version of a file the user has
            // shown no interest in.
            String remembered = prefs(context).getString(shippedDigestKey(destination), null);
            if (remembered == null || !remembered.equals(currentDigest)) {
                Logger.logVerbose(LOG_TAG, "Leaving user-modified " + destination + " alone");
                return;
            }
        }

        writeAsset(context, shipped, destination, executable);
    }

    private static void forceInstallAsset(@NonNull Context context, @NonNull String assetName,
                                          @NonNull File destination) {
        byte[] shipped = readAsset(context, assetName);
        if (shipped == null) return;
        writeAsset(context, shipped, destination, ASSET_SETUP.equals(assetName));
    }

    private static void writeAsset(@NonNull Context context, @NonNull byte[] content,
                                   @NonNull File destination, boolean executable) {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Logger.logError(LOG_TAG, "Could not create " + parent);
            return;
        }

        try (OutputStream out = new FileOutputStream(destination)) {
            out.write(content);
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed writing " + destination, e);
            return;
        }

        if (executable && !destination.setExecutable(true, true)) {
            Logger.logWarn(LOG_TAG, "Could not mark " + destination + " executable");
        }

        rememberShipped(context, destination, content);
        Logger.logInfo(LOG_TAG, "Installed " + destination);
    }

    private static void rememberShipped(@NonNull Context context, @NonNull File destination,
                                        @NonNull byte[] content) {
        prefs(context).edit().putString(shippedDigestKey(destination), digest(content)).apply();
    }

    private static String shippedDigestKey(@NonNull File destination) {
        return PREF_SHIPPED_DIGEST_PREFIX + destination.getAbsolutePath();
    }

    @Nullable
    private static byte[] readAsset(@NonNull Context context, @NonNull String name) {
        try (InputStream in = context.getAssets().open(ASSET_DIR + "/" + name)) {
            return readFully(in);
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed reading asset " + name, e);
            return null;
        }
    }

    @Nullable
    private static byte[] readFile(@NonNull File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            return readFully(in);
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed reading " + file, e);
            return null;
        }
    }

    private static byte[] readFully(@NonNull InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(in.available(), 4096));
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String digest(@NonNull byte[] content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                                 .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform.
            throw new IllegalStateException(e);
        }
    }


    // ---------------------------------------------------------------------------------------
    // Provisioning
    // ---------------------------------------------------------------------------------------

    /** Whether the user still needs to be asked whether to run {@code ~/setup.sh}. */
    public static boolean shouldOfferProvisioning(@NonNull Context context) {
        return !prefs(context).getBoolean(PREF_PROVISIONING_OFFERED, false)
            && SETUP_SCRIPT_FILE.isFile();
    }

    /** Record that the offer has been made, so it is not made again on the next launch. */
    public static void markProvisioningOffered(@NonNull Context context) {
        prefs(context).edit().putBoolean(PREF_PROVISIONING_OFFERED, true).apply();
    }

    /** The command to type into a terminal session to provision the environment. */
    public static String provisioningCommand() {
        return "bash " + SETUP_SCRIPT_FILE.getAbsolutePath() + "\n";
    }

}
