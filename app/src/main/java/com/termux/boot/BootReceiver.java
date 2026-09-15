package com.termux.boot;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.Arrays;

/**
 * Schedules every script in {@code ~/.termux/boot/} to run once the device has finished booting.
 *
 * <p>Merged from termux-boot. The only change from upstream is that the boot script directory
 * comes from {@link TermuxConstants} instead of a hardcoded path, and logging goes through
 * {@link Logger} like the rest of the app.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "BootReceiver";

    public static final int TERMUX_BOOT_JOB_ID_BASE = 1000;

    static int jobId = TERMUX_BOOT_JOB_ID_BASE;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        File[] files = TermuxConstants.TERMUX_BOOT_SCRIPTS_DIR.listFiles();
        if (files == null) files = new File[0];

        // Sort so that scripts run in a repeatable and predictable order -- this is why naming
        // them 10-foo, 20-bar works.
        Arrays.sort(files, (f1, f2) -> f1.getName().compareTo(f2.getName()));

        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobScheduler == null) {
            Logger.logError(LOG_TAG, "No JobScheduler available, boot scripts will not run");
            return;
        }

        StringBuilder logMessage = new StringBuilder();
        for (File file : files) {
            if (!file.isFile()) continue;

            if (logMessage.length() > 0) logMessage.append(", ");
            logMessage.append(file.getName());

            ensureFileReadableAndExecutable(file);

            PersistableBundle extras = new PersistableBundle();
            extras.putString(BootJobService.SCRIPT_FILE_PATH, file.getAbsolutePath());

            ComponentName serviceComponent = new ComponentName(context, BootJobService.class);
            JobInfo job = new JobInfo.Builder(jobId++, serviceComponent)
                .setExtras(extras)
                .setOverrideDeadline(3 * 1000)
                .build();
            jobScheduler.schedule(job);
        }

        if (logMessage.length() > 0) {
            Logger.logInfo(LOG_TAG, "Scheduled boot scripts: " + logMessage);
        } else {
            Logger.logInfo(LOG_TAG, "No boot scripts in " + TermuxConstants.TERMUX_BOOT_SCRIPTS_DIR_PATH);
        }
    }

    /** Make the script readable and executable, in case the user forgot to chmod it. */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void ensureFileReadableAndExecutable(File file) {
        if (!file.canRead()) file.setReadable(true);
        if (!file.canExecute()) file.setExecutable(true);
    }
}
