package com.termux.boot;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PersistableBundle;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

/**
 * Hands one boot script over to {@link com.termux.app.TermuxService} to run in the background.
 *
 * <p>Merged from termux-boot. Upstream had to reach across a process boundary with hardcoded
 * strings; now that the service lives in the same app, the component and extras come from
 * {@link TermuxConstants}.
 */
public class BootJobService extends JobService {

    public static final String SCRIPT_FILE_PATH = "com.termux.boot.script_path";

    private static final String LOG_TAG = "BootJobService";

    @Override
    public boolean onStartJob(JobParameters params) {
        PersistableBundle extras = params.getExtras();
        String filePath = extras.getString(SCRIPT_FILE_PATH);
        if (filePath == null) {
            Logger.logError(LOG_TAG, "Job " + params.getJobId() + " has no script path");
            return false;
        }

        Logger.logInfo(LOG_TAG, "Running boot script " + filePath);

        Uri scriptUri = new Uri.Builder().scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path(filePath).build();
        Intent executeIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, scriptUri);
        executeIntent.setClassName(TermuxConstants.TERMUX_PACKAGE_NAME, TermuxConstants.TERMUX_APP.TERMUX_SERVICE_NAME);
        executeIntent.putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, true);

        Context context = getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // https://developer.android.com/about/versions/oreo/background.html
            context.startForegroundService(executeIntent);
        } else {
            context.startService(executeIntent);
        }

        return false; // Handed off to TermuxService; this job is done.
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Logger.logInfo(LOG_TAG, "Execution of job " + params.getJobId() + " has been cancelled.");
        return false; // Do not reschedule.
    }
}
