package com.termux.mayonaka;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.termux.shared.termux.extrakeys.ExtraKeyButton;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;

import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The extra keys layout is a string that gets parsed at runtime, and a malformed one fails
 * quietly -- the keyboard simply comes up with the stock layout and no error anywhere obvious.
 * The symbol row in particular is full of characters that fight with both the properties format
 * and the JSON parser: quotes, backticks, brackets and braces.
 *
 * <p>So parse every row Mayonaka can write, through the real {@link ExtraKeysInfo}, and check the
 * keys that come out are the ones that went in.
 */
@RunWith(RobolectricTestRunner.class)
public class MayonakaExtraKeysTest {

    private static ExtraKeyButton[][] parse(String value) throws JSONException {
        return new ExtraKeysInfo(value, "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES).getMatrix();
    }

    @Test
    public void defaultTwoRowLayoutParses() throws JSONException {
        ExtraKeyButton[][] matrix = parse(MayonakaProperties.buildExtraKeys(2));

        assertEquals(2, matrix.length);
        assertEquals(8, matrix[0].length);
        assertEquals(8, matrix[1].length);

        assertEquals("ESC", matrix[0][0].getKey());
        assertEquals("|", matrix[0][1].getKey());
        assertEquals("/", matrix[0][2].getKey());
        assertEquals("HOME", matrix[0][3].getKey());
        assertEquals("UP", matrix[0][4].getKey());
        assertEquals("END", matrix[0][5].getKey());
        assertEquals("PGUP", matrix[0][6].getKey());
        assertEquals("DEL", matrix[0][7].getKey());

        assertEquals("TAB", matrix[1][0].getKey());
        assertEquals("CTRL", matrix[1][1].getKey());
        assertEquals("ALT", matrix[1][2].getKey());
        assertEquals("LEFT", matrix[1][3].getKey());
        assertEquals("DOWN", matrix[1][4].getKey());
        assertEquals("RIGHT", matrix[1][5].getKey());
        assertEquals("PGDN", matrix[1][6].getKey());
        assertEquals("BKSP", matrix[1][7].getKey());
    }

    /** The row that is most likely to break: quotes, a backtick, brackets and braces. */
    @Test
    public void symbolRowParsesEveryCharacter() throws JSONException {
        ExtraKeyButton[][] matrix = parse(MayonakaProperties.buildExtraKeys(3));

        assertEquals(3, matrix.length);

        String[] expected = {"-", "_", "=", "+", "{", "}", "[", "]", ";", "'", "\"", "`", "~", "<", ">"};
        assertEquals(expected.length, matrix[2].length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("symbol row position " + i, expected[i], matrix[2][i].getKey());
        }
    }

    @Test
    public void rowCountIsHonouredAndClamped() throws JSONException {
        assertEquals(1, parse(MayonakaProperties.buildExtraKeys(1)).length);
        assertEquals(3, parse(MayonakaProperties.buildExtraKeys(3)).length);

        // Out of range values must not produce something unparseable.
        assertEquals(3, parse(MayonakaProperties.buildExtraKeys(9)).length);
        assertEquals(0, parse(MayonakaProperties.buildExtraKeys(-1)).length);
    }

    /**
     * java.util.Properties treats a trailing backslash as a line continuation and unescapes
     * sequences like \t, so a value containing either would not survive the round trip through
     * termux.properties.
     */
    @Test
    public void generatedLayoutSurvivesThePropertiesFormat() {
        String value = MayonakaProperties.buildExtraKeys(3);
        assertTrue("the layout must not contain a backslash: " + value, value.indexOf('\\') < 0);
        assertTrue("the layout must be a single line: " + value, value.indexOf('\n') < 0);
    }
}
