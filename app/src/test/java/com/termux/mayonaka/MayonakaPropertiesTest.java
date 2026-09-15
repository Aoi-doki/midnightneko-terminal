package com.termux.mayonaka;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link MayonakaProperties} rewrites a file the user owns and may have edited by hand, so the
 * things worth pinning down are what it leaves alone, not just what it changes.
 *
 * <p>These run against the real path {@link TermuxConstants#TERMUX_PROPERTIES_PRIMARY_FILE}
 * points at. Under Robolectric that is an ordinary directory on the build machine, not a device.
 */
@RunWith(RobolectricTestRunner.class)
public class MayonakaPropertiesTest {

    private static final File FILE = TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE;

    private byte[] saved;

    @Before
    public void setUp() throws IOException {
        saved = FILE.isFile() ? Files.readAllBytes(FILE.toPath()) : null;
        File parent = FILE.getParentFile();
        if (parent != null) parent.mkdirs();
    }

    @After
    public void tearDown() throws IOException {
        if (saved != null) Files.write(FILE.toPath(), saved);
        else FILE.delete();
    }

    private void write(String content) throws IOException {
        Files.write(FILE.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private String read() throws IOException {
        return new String(Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8);
    }


    @Test
    public void replacesTheValueAndKeepsEverythingElse() throws IOException {
        write("# a comment\n"
            + "unrelated-key = leave me alone\n"
            + "terminal-cursor-style = block\n"
            + "\n"
            + "# trailing comment\n");

        assertTrue(MayonakaProperties.set(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE, "bar"));

        String result = read();
        assertTrue(result, result.contains("# a comment"));
        assertTrue(result, result.contains("unrelated-key = leave me alone"));
        assertTrue(result, result.contains("terminal-cursor-style = bar"));
        assertTrue(result, result.contains("# trailing comment"));
        assertTrue("the old value must be gone: " + result, !result.contains("= block"));
    }

    @Test
    public void appendsAKeyThatIsNotThereYet() throws IOException {
        write("existing = 1\n");

        assertTrue(MayonakaProperties.set(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE, "600"));

        String result = read();
        assertTrue(result, result.contains("existing = 1"));
        assertTrue(result, result.contains("terminal-cursor-blink-rate = 600"));
    }

    @Test
    public void createsTheFileWhenItIsMissing() throws IOException {
        FILE.delete();

        assertTrue(MayonakaProperties.set(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE, "bar"));
        assertEquals("bar", MayonakaProperties.get(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE));
    }

    /**
     * The value used to be shipped across several lines. Rewriting only the first of them would
     * leave the rest behind as stray text that breaks the whole file.
     */
    @Test
    public void replacesAContinuedValueWholesale() throws IOException {
        write("extra-keys = [ \\\n"
            + " ['ESC','TAB'], \\\n"
            + " ['CTRL','ALT'] \\\n"
            + "]\n"
            + "after = kept\n");

        assertTrue(MayonakaProperties.set(TermuxPropertyConstants.KEY_EXTRA_KEYS,
            MayonakaProperties.buildExtraKeys(2)));

        String result = read();
        assertTrue("continuation lines must not survive: " + result, !result.contains("['ESC','TAB']"));
        assertTrue("continuation lines must not survive: " + result, !result.contains("['CTRL','ALT']"));
        assertTrue(result, result.contains("after = kept"));
        assertEquals(MayonakaProperties.buildExtraKeys(2),
            MayonakaProperties.get(TermuxPropertyConstants.KEY_EXTRA_KEYS));
    }

    @Test
    public void readsAContinuedValue() throws IOException {
        write("extra-keys = [ \\\n"
            + " ['ESC','TAB'] \\\n"
            + "]\n");

        assertEquals("[['ESC','TAB']]", MayonakaProperties.get(TermuxPropertyConstants.KEY_EXTRA_KEYS));
    }

    @Test
    public void commentsOutADuplicateRatherThanLeavingItToContradict() throws IOException {
        write("terminal-cursor-style = block\n"
            + "terminal-cursor-style = underline\n");

        assertTrue(MayonakaProperties.set(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE, "bar"));

        String result = read();
        assertEquals("exactly one live assignment should remain:\n" + result,
            1, countLiveAssignments(result, "terminal-cursor-style"));
        assertTrue(result, result.contains("terminal-cursor-style = bar"));
    }

    @Test
    public void ignoresCommentedOutAndAbsentKeys() throws IOException {
        write("# terminal-cursor-style = block\n"
            + "! terminal-cursor-blink-rate = 100\n");

        assertNull(MayonakaProperties.get(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE));
        assertNull(MayonakaProperties.get(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE));
        assertNull(MayonakaProperties.get("no-such-key"));
    }

    @Test
    public void recognisesItsOwnExtraKeysLayouts() throws IOException {
        for (int rows = 0; rows <= MayonakaProperties.EXTRA_KEYS_ROWS.length; rows++) {
            write(TermuxPropertyConstants.KEY_EXTRA_KEYS + " = "
                + MayonakaProperties.buildExtraKeys(rows) + "\n");
            assertEquals("row count " + rows, rows, MayonakaProperties.currentExtraKeysRows());
        }
    }

    @Test
    public void reportsAHandEditedLayoutAsCustom() throws IOException {
        write(TermuxPropertyConstants.KEY_EXTRA_KEYS + " = [['ESC','CTRL','ALT','TAB']]\n");
        assertEquals(-1, MayonakaProperties.currentExtraKeysRows());

        FILE.delete();
        assertEquals(-1, MayonakaProperties.currentExtraKeysRows());
    }

    @Test
    public void setsSeveralKeysInOneRewrite() throws IOException {
        write("terminal-cursor-style = block\n");

        Map<String, String> values = new LinkedHashMap<>();
        values.put(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE, "bar");
        values.put(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE, "350");
        assertTrue(MayonakaProperties.set(values));

        assertEquals("bar", MayonakaProperties.get(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE));
        assertEquals("350", MayonakaProperties.get(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE));
    }

    /**
     * What the file says must be what {@link java.util.Properties} -- which is what Termux
     * actually reads it with -- thinks it says.
     */
    @Test
    public void theResultParsesAsAPropertiesFile() throws IOException {
        write("# comment\nexisting = 1\n");
        MayonakaProperties.set(TermuxPropertyConstants.KEY_EXTRA_KEYS, MayonakaProperties.buildExtraKeys(3));

        java.util.Properties properties = new java.util.Properties();
        try (java.io.Reader reader = Files.newBufferedReader(Paths.get(FILE.getAbsolutePath()))) {
            properties.load(reader);
        }

        assertEquals("1", properties.getProperty("existing"));
        assertEquals(MayonakaProperties.buildExtraKeys(3),
            properties.getProperty(TermuxPropertyConstants.KEY_EXTRA_KEYS));
    }

    private static int countLiveAssignments(String content, String key) {
        int count = 0;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.startsWith("!")) continue;
            if (trimmed.startsWith(key)) count++;
        }
        return count;
    }
}
