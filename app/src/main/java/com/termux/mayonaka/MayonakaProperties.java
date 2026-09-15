package com.termux.mayonaka;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Edits {@code ~/.termux/termux.properties} in place.
 *
 * <p>The settings Termux already understands are kept in that file rather than mirrored into app
 * preferences, so that changing one from Settings and changing it in an editor mean the same
 * thing. That makes writing it a line edit, not a serialise: the file is the user's, comments and
 * ordering and unknown keys included, and only the line for the key being changed is touched.
 */
public final class MayonakaProperties {

    private static final String LOG_TAG = "MayonakaProperties";

    private MayonakaProperties() {}

    /**
     * The extra keys layout, one entry per row.
     *
     * <p>Row 3 is the symbol row, off by default and turned on from Settings. Keys are quoted the
     * way the extra-keys parser expects: it reads the value as lenient JSON, so a single quote
     * goes inside double quotes and vice versa.
     */
    public static final String[] EXTRA_KEYS_ROWS = {
        "['ESC','|','/','HOME','UP','END','PGUP','DEL']",
        "['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN','BKSP']",
        "['-','_','=','+','{','}','[',']',';',\"'\",'\"','`','~','<','>']",
    };

    /** Build the {@code extra-keys} value for a given number of rows. */
    @NonNull
    public static String buildExtraKeys(int rows) {
        rows = Math.max(0, Math.min(EXTRA_KEYS_ROWS.length, rows));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows; i++) {
            if (i > 0) sb.append(",");
            sb.append(EXTRA_KEYS_ROWS[i]);
        }
        return sb.append("]").toString();
    }

    /**
     * Read one property's raw value from the file, or {@code null} if it is not set.
     *
     * <p>Whitespace around the value is trimmed, but nothing else is interpreted: this is for
     * recognising a value Mayonaka wrote, not for consuming it.
     */
    @Nullable
    public static String get(@NonNull String key) {
        List<String> lines = readLines(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE);
        if (lines == null) return null;

        for (int i = 0; i < lines.size(); i = logicalLineEnd(lines, i) + 1) {
            if (!key.equals(keyOf(lines.get(i)))) continue;

            StringBuilder value = new StringBuilder();
            int end = logicalLineEnd(lines, i);
            for (int j = i; j <= end; j++) {
                String part = j == i ? stripKey(lines.get(j), key) : lines.get(j).trim();
                if (j < end) part = part.substring(0, part.length() - 1); // drop the trailing "\\"
                value.append(part.trim());
            }
            return value.toString().trim();
        }
        return null;
    }

    /** Everything after the key and its separator on an assignment line. */
    @NonNull
    private static String stripKey(@NonNull String line, @NonNull String key) {
        String trimmed = line.trim().substring(key.length()).trim();
        if (trimmed.startsWith("=") || trimmed.startsWith(":")) trimmed = trimmed.substring(1);
        return trimmed.trim();
    }

    /**
     * Index of the last physical line of the logical line starting at {@code start}.
     *
     * <p>{@link java.util.Properties} continues a line when it ends with an odd number of
     * backslashes, so a value can span several lines -- which the shipped termux.properties used
     * to do for the extra keys. Rewriting only the first of those lines would leave the rest
     * behind as garbage, so both reading and writing work in logical lines.
     */
    private static int logicalLineEnd(@NonNull List<String> lines, int start) {
        int i = start;
        while (i < lines.size() - 1 && isContinued(lines.get(i))) i++;
        return i;
    }

    private static boolean isContinued(@NonNull String line) {
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) backslashes++;
        return backslashes % 2 == 1;
    }

    /**
     * How many Mayonaka rows the extra-keys layout currently in the file corresponds to.
     *
     * @return 0 to {@link #EXTRA_KEYS_ROWS}{@code .length}, or {@code -1} if the file holds a
     *         layout Mayonaka did not write -- in which case Settings must not silently claim it
     *         as one of its own, and must not overwrite it without being asked.
     */
    public static int currentExtraKeysRows() {
        String current = get(com.termux.shared.termux.settings.properties.TermuxPropertyConstants.KEY_EXTRA_KEYS);
        if (current == null) return -1;

        String normalised = current.replaceAll("\\s+", "");
        for (int rows = 0; rows <= EXTRA_KEYS_ROWS.length; rows++) {
            if (normalised.equals(buildExtraKeys(rows).replaceAll("\\s+", ""))) return rows;
        }
        return -1;
    }

    /**
     * Set one property, creating the file if it does not exist yet.
     *
     * @return {@code true} if the file was written.
     */
    public static boolean set(@NonNull String key, @NonNull String value) {
        Map<String, String> single = new LinkedHashMap<>();
        single.put(key, value);
        return set(single);
    }

    /**
     * Set several properties in one rewrite.
     *
     * <p>Every line that assigns one of the given keys is replaced; keys that do not appear yet
     * are appended under a marker comment. Everything else in the file is passed through
     * untouched, including comments, blank lines and keys Mayonaka knows nothing about.
     *
     * @return {@code true} if the file was written.
     */
    public static boolean set(@NonNull Map<String, String> values) {
        if (values.isEmpty()) return true;

        File file = TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE;
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Logger.logError(LOG_TAG, "Could not create " + parent);
            return false;
        }

        List<String> lines = readLines(file);
        if (lines == null) return false;

        List<String> remaining = new ArrayList<>(values.keySet());

        for (int i = 0; i < lines.size(); i++) {
            String key = keyOf(lines.get(i));
            if (key == null || !values.containsKey(key)) {
                i = logicalLineEnd(lines, i);
                continue;
            }

            // A continued value spans several physical lines; all of them go.
            int end = logicalLineEnd(lines, i);
            List<String> replacement = new ArrayList<>();

            if (remaining.remove(key)) {
                // The first assignment of a key is the one java.util.Properties honours, so that
                // is the one rewritten...
                replacement.add(key + " = " + values.get(key));
            } else {
                // ...and any later duplicate is commented out rather than left to contradict it.
                for (int j = i; j <= end; j++) replacement.add("# " + lines.get(j));
                replacement.add("# ^ superseded, commented out by Mayonaka settings");
            }

            for (int j = end; j >= i; j--) lines.remove(j);
            lines.addAll(i, replacement);
            i += replacement.size() - 1;
        }

        if (!remaining.isEmpty()) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).trim().isEmpty()) lines.add("");
            lines.add("### Added by Mayonaka settings");
            for (String key : remaining) {
                lines.add(key + " = " + values.get(key));
            }
        }

        return writeLines(file, lines);
    }

    /**
     * The key assigned by a properties line, or {@code null} if the line assigns nothing.
     *
     * <p>Follows {@link java.util.Properties}: {@code #} and {@code !} start a comment, and the
     * key is separated from the value by {@code =}, {@code :} or whitespace.
     */
    @Nullable
    private static String keyOf(@NonNull String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null;

        Matcher matcher = KEY_PATTERN.matcher(trimmed);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static final Pattern KEY_PATTERN = Pattern.compile("^([^\\s=:]+)\\s*[=:\\s]");

    @Nullable
    private static List<String> readLines(@NonNull File file) {
        List<String> lines = new ArrayList<>();
        if (!file.isFile()) return lines;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
            return lines;
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed reading " + file, e);
            return null;
        }
    }

    private static boolean writeLines(@NonNull File file, @NonNull List<String> lines) {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.write("\n");
            }
            return true;
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed writing " + file, e);
            return false;
        }
    }
}
