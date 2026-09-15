package com.termux.app.fragments.settings;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;

import com.termux.R;
import com.termux.api.activities.TermuxAPIMainActivity;
import com.termux.app.TermuxActivity;
import com.termux.boot.BootActivity;
import com.termux.mayonaka.MayonakaDefaults;
import com.termux.mayonaka.MayonakaPreferences;
import com.termux.mayonaka.MayonakaProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.styling.StyleAsset;
import com.termux.styling.TermuxStyleActivity;
import com.termux.widget.activities.TermuxWidgetMainActivity;

/**
 * The Mayonaka settings section.
 *
 * <p>Two kinds of setting live here. Some are Mayonaka's own -- how the extra keys are drawn, the
 * terminal opacity -- and go to {@link MayonakaPreferences}. The rest are things Termux already
 * has properties for, and are written straight into {@code ~/.termux/termux.properties} by
 * {@link MayonakaProperties} so that Settings and a text editor cannot disagree about them.
 *
 * <p>Everything applies immediately: each change reloads the activity styling rather than waiting
 * for a restart.
 */
@Keep
public class MayonakaPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setPreferencesFromResource(R.xml.mayonaka_preferences, rootKey);

        configureStylePreferences(context);
        configureKeyboardPreferences(context);
        configureTerminalPreferences(context);
        configureActionPreferences(context);
        configureMergedAppPreferences(context);
    }

    @Override
    public void onResume() {
        super.onResume();
        Context context = getContext();
        if (context != null) updateStyleSummaries(context);
    }


    // -----------------------------------------------------------------------------------------
    // Colour scheme and font
    // -----------------------------------------------------------------------------------------

    private void configureStylePreferences(@NonNull Context context) {
        Preference colors = findPreference(MayonakaPreferences.KEY_COLOR_SCHEME);
        if (colors != null) {
            colors.setOnPreferenceClickListener(preference -> {
                TermuxStyleActivity.start(context, StyleAsset.Kind.COLORS);
                return true;
            });
        }

        Preference font = findPreference(MayonakaPreferences.KEY_FONT);
        if (font != null) {
            font.setOnPreferenceClickListener(preference -> {
                TermuxStyleActivity.start(context, StyleAsset.Kind.FONT);
                return true;
            });
        }

        updateStyleSummaries(context);
    }

    /** Show what is currently applied, refreshed on return from the picker. */
    private void updateStyleSummaries(@NonNull Context context) {
        Preference colors = findPreference(MayonakaPreferences.KEY_COLOR_SCHEME);
        if (colors != null)
            colors.setSummary(StyleAsset.currentDisplayName(context, StyleAsset.Kind.COLORS));

        Preference font = findPreference(MayonakaPreferences.KEY_FONT);
        if (font != null)
            font.setSummary(StyleAsset.currentDisplayName(context, StyleAsset.Kind.FONT));
    }


    // -----------------------------------------------------------------------------------------
    // Keyboard
    // -----------------------------------------------------------------------------------------

    private void configureKeyboardPreferences(@NonNull Context context) {
        ListPreference style = findPreference(MayonakaPreferences.KEY_KEYBOARD_STYLE);
        if (style != null) {
            style.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            style.setOnPreferenceChangeListener((preference, value) -> {
                // The chip geometry is read from the theme when the view is inflated, so the
                // activity has to be rebuilt for a style change to take.
                reloadStyling(context, true);
                return true;
            });
        }

        ListPreference rows = findPreference(MayonakaPreferences.KEY_EXTRA_KEYS_ROWS);
        if (rows != null) {
            rows.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());

            // termux.properties is the source of truth, and it is editable by hand, so show what
            // is actually in the file. A layout Mayonaka did not write is left alone and said so,
            // rather than being quietly relabelled as one of the three presets.
            int current = MayonakaProperties.currentExtraKeysRows();
            if (current >= 0) {
                rows.setValue(String.valueOf(current));
            } else {
                rows.setSummaryProvider(null);
                rows.setSummary(R.string.mayonaka_settings_extra_keys_custom);
            }

            rows.setOnPreferenceChangeListener((preference, value) -> {
                int count;
                try {
                    count = Integer.parseInt(String.valueOf(value));
                } catch (NumberFormatException e) {
                    return false;
                }
                if (!MayonakaProperties.set(TermuxPropertyConstants.KEY_EXTRA_KEYS,
                        MayonakaProperties.buildExtraKeys(count))) {
                    toast(R.string.mayonaka_settings_write_failed);
                    return false;
                }
                reloadStyling(context, false);
                return true;
            });
        }
    }


    // -----------------------------------------------------------------------------------------
    // Terminal
    // -----------------------------------------------------------------------------------------

    private void configureTerminalPreferences(@NonNull Context context) {
        ListPreference cursorStyle = findPreference(MayonakaPreferences.KEY_CURSOR_STYLE);
        if (cursorStyle != null) {
            cursorStyle.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            cursorStyle.setOnPreferenceChangeListener((preference, value) ->
                writeProperty(context, TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE, String.valueOf(value)));
        }

        ListPreference blinkRate = findPreference(MayonakaPreferences.KEY_CURSOR_BLINK_RATE);
        if (blinkRate != null) {
            blinkRate.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            blinkRate.setOnPreferenceChangeListener((preference, value) ->
                writeProperty(context, TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE, String.valueOf(value)));
        }

        SeekBarPreference opacity = findPreference(MayonakaPreferences.KEY_TERMINAL_OPACITY);
        if (opacity != null) {
            opacity.setMin(MayonakaPreferences.MIN_TERMINAL_OPACITY);
            opacity.setMax(MayonakaPreferences.MAX_TERMINAL_OPACITY);
            opacity.setShowSeekBarValue(true);
            opacity.setOnPreferenceChangeListener((preference, value) -> {
                // Opacity is not a Termux property: it is applied to the window background, so
                // only the activity styling needs to be reloaded.
                reloadStyling(context, false);
                return true;
            });
        }
    }

    private boolean writeProperty(@NonNull Context context, @NonNull String key, @NonNull String value) {
        if (!MayonakaProperties.set(key, value)) {
            toast(R.string.mayonaka_settings_write_failed);
            return false;
        }
        reloadStyling(context, false);
        return true;
    }


    // -----------------------------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------------------------

    private void configureActionPreferences(@NonNull Context context) {
        Preference rerun = findPreference(MayonakaPreferences.KEY_RERUN_PROVISIONING);
        if (rerun != null) {
            rerun.setOnPreferenceClickListener(preference -> {
                new AlertDialog.Builder(context)
                    .setTitle(R.string.mayonaka_settings_rerun_provisioning_title)
                    .setMessage(R.string.mayonaka_settings_rerun_provisioning_confirm)
                    .setPositiveButton(R.string.mayonaka_provisioning_run, (dialog, which) -> runProvisioning(context))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return true;
            });
        }

        Preference restore = findPreference(MayonakaPreferences.KEY_RESTORE_DEFAULTS);
        if (restore != null) {
            restore.setOnPreferenceClickListener(preference -> {
                new AlertDialog.Builder(context)
                    .setTitle(R.string.mayonaka_settings_restore_defaults_title)
                    .setMessage(R.string.mayonaka_settings_restore_defaults_confirm)
                    .setPositiveButton(R.string.mayonaka_settings_restore, (dialog, which) -> {
                        MayonakaDefaults.reinstallAll(context);
                        reloadStyling(context, true);
                        toast(R.string.mayonaka_settings_restored);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return true;
            });
        }
    }

    /**
     * Hand the terminal back the foreground and ask it to type the provisioning command.
     *
     * <p>The script has to run in a real session -- it installs packages and changes the login
     * shell -- so this is a request to {@link TermuxActivity}, not something Settings can do.
     */
    private void runProvisioning(@NonNull Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.putExtra(TermuxActivity.EXTRA_RUN_PROVISIONING, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }


    // -----------------------------------------------------------------------------------------
    // The merged add-ons
    // -----------------------------------------------------------------------------------------

    private void configureMergedAppPreferences(@NonNull Context context) {
        Preference widget = findPreference(MayonakaPreferences.KEY_WIDGET_SHORTCUTS);
        if (widget != null) {
            widget.setOnPreferenceClickListener(preference -> {
                context.startActivity(new Intent(context, TermuxWidgetMainActivity.class));
                return true;
            });
        }

        Preference boot = findPreference(MayonakaPreferences.KEY_BOOT_SCRIPTS);
        if (boot != null) {
            boot.setOnPreferenceClickListener(preference -> {
                context.startActivity(new Intent(context, BootActivity.class));
                return true;
            });
        }

        Preference api = findPreference(MayonakaPreferences.KEY_DEVICE_API);
        if (api != null) {
            api.setOnPreferenceClickListener(preference -> {
                context.startActivity(new Intent(context, TermuxAPIMainActivity.class));
                return true;
            });
        }
    }


    // -----------------------------------------------------------------------------------------

    private void reloadStyling(@NonNull Context context, boolean recreateActivity) {
        TermuxActivity.updateTermuxActivityStyling(context, recreateActivity);
    }

    private void toast(int stringId) {
        Context context = getContext();
        if (context != null) Toast.makeText(context, stringId, Toast.LENGTH_SHORT).show();
    }
}
