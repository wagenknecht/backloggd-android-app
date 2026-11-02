package de.wagenknecht.backloggd.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class UsernameHelperTest {

    @Test
    public void readsTheNameFromAJsonString() {
        assertEquals("Tysk", UsernameHelper.fromJsResult("\"Tysk\""));
        assertEquals("some_user_42", UsernameHelper.fromJsResult("\"some_user_42\""));
    }

    @Test
    public void nothingWhenThePageHasNoUsernameField() {
        assertNull(UsernameHelper.fromJsResult("null"));
        assertNull(UsernameHelper.fromJsResult(null));
    }

    @Test
    public void nothingForAnEmptyName() {
        assertNull(UsernameHelper.fromJsResult("\"\""));
    }

    @Test
    public void nothingForTextThatNeedsEscaping() {
        // Not a valid username, and unescaping it is not worth getting wrong.
        assertNull(UsernameHelper.fromJsResult("\"a\\\"b\""));
    }
}
