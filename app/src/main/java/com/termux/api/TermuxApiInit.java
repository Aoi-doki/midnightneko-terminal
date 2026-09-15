package com.termux.api;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences;

/**
 * Startup hooks for the merged Termux:API half of the app.
 *
 * <p>Upstream these lived in {@code TermuxAPIApplication}, but a merged app has exactly one
 * {@link Application}, so they are called from {@code TermuxApplication.onCreate()} instead. The
 * crash handler and app-wide log config are already installed by then, so only the two pieces of
 * API-specific setup remain.
 */
public final class TermuxApiInit {

    private TermuxApiInit() {}

    /** Called once from {@code TermuxApplication.onCreate()}. */
    public static void init(@NonNull Application application) {
        // ResultReturner writes API results back over the sockets named in the broadcast, and
        // needs an application context to resolve them.
        ResultReturner.setContext(application);

        // The abstract socket the termux-api CLI connects to on Android < 14. Its address is
        // "com.termux.api://listen", which is compiled into $PREFIX/libexec/termux-api and must
        // not change -- unlike the broadcast component, it is not something a same-length patch
        // could fix, and it does not need fixing: SocketListener derives it from
        // TERMUX_API_PACKAGE_NAME, which the merge deliberately leaves alone.
        SocketListener.createSocketListener(application);
    }

    /**
     * Apply the Termux:API log level from its own preferences.
     *
     * <p>Kept separate from the app's log config because Settings still exposes an independent
     * "Termux:API -> Debugging -> Log Level" preference, and the API half is noisy enough to be
     * worth turning up on its own.
     */
    public static void setLogConfig(@NonNull Context context, boolean commitToFile) {
        Logger.setDefaultLogTag(TermuxConstants.TERMUX_API_APP_NAME.replaceAll("[: ]", ""));

        TermuxAPIAppSharedPreferences preferences = TermuxAPIAppSharedPreferences.build(context);
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel(true), commitToFile);
    }
}
