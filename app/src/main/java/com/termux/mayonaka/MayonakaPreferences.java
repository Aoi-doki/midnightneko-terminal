package com.termux.mayonaka;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

/**
 * Mayonaka's own settings -- the ones that have no home in {@code termux.properties}.
 *
 * <p>The split is deliberate. Anything Termux already understands (the extra keys layout, the
 * cursor, margins) is written to {@code ~/.termux/termux.properties} by {@link MayonakaProperties},
 * so it stays editable by hand and survives being changed from either side. What lives here is
 * only what Termux has no property for: how the extra keys are <em>drawn</em>, and the terminal
 * opacity.
 */
public final class MayonakaPreferences {

    private MayonakaPreferences() {}

    /** How the extra keys row is drawn. */
    public enum KeyboardStyle {
        /** Bordered violet chips. The Mayonaka default. */
        BORDERED,
        /** The stock Termux look: flat, borderless, edge to edge. */
        FLAT,
        /** No extra keys row at all. */
        HIDDEN;

        @NonNull
        static KeyboardStyle from(String value) {
            if (value != null) {
                for (KeyboardStyle style : values()) {
                    if (style.name().equalsIgnoreCase(value)) return style;
                }
            }
            return BORDERED;
        }
    }

    public static final String KEY_KEYBOARD_STYLE = "mayonaka_keyboard_style";
    public static final String KEY_EXTRA_KEYS_ROWS = "mayonaka_extra_keys_rows";
    public static final String KEY_CURSOR_STYLE = "mayonaka_cursor_style";
    public static final String KEY_CURSOR_BLINK_RATE = "mayonaka_cursor_blink_rate";
    public static final String KEY_TERMINAL_OPACITY = "mayonaka_terminal_opacity";
    public static final String KEY_COLOR_SCHEME = "mayonaka_color_scheme";
    public static final String KEY_FONT = "mayonaka_font";
    public static final String KEY_RERUN_PROVISIONING = "mayonaka_rerun_provisioning";
    public static final String KEY_RESTORE_DEFAULTS = "mayonaka_restore_defaults";
    public static final String KEY_WIDGET_SHORTCUTS = "mayonaka_widget_shortcuts";
    public static final String KEY_BOOT_SCRIPTS = "mayonaka_boot_scripts";
    public static final String KEY_DEVICE_API = "mayonaka_device_api";

    /** Percent, 20..100. Below about 20 the text stops being readable over a busy wallpaper. */
    public static final int MIN_TERMINAL_OPACITY = 20;
    public static final int MAX_TERMINAL_OPACITY = 100;
    public static final int DEFAULT_TERMINAL_OPACITY = 100;

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    @NonNull
    public static KeyboardStyle getKeyboardStyle(@NonNull Context context) {
        return KeyboardStyle.from(prefs(context).getString(KEY_KEYBOARD_STYLE, KeyboardStyle.BORDERED.name()));
    }

    public static int getTerminalOpacity(@NonNull Context context) {
        int opacity = prefs(context).getInt(KEY_TERMINAL_OPACITY, DEFAULT_TERMINAL_OPACITY);
        return Math.max(MIN_TERMINAL_OPACITY, Math.min(MAX_TERMINAL_OPACITY, opacity));
    }

    /**
     * Re-alpha a terminal background colour for the configured opacity.
     *
     * <p>The terminal's colour parser forces every colour to full alpha, so opacity cannot come
     * from {@code colors.properties}; it is applied here, on the window background, which is the
     * only surface that paints the empty parts of the terminal.
     */
    public static int applyTerminalOpacity(@NonNull Context context, int color) {
        int opacity = getTerminalOpacity(context);
        if (opacity >= MAX_TERMINAL_OPACITY) return color;

        int alpha = Math.round(255f * opacity / 100f);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
