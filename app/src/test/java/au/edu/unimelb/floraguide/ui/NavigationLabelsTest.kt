package au.edu.unimelb.floraguide.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationLabelsTest {
    @Test fun `large text uses short visible labels without removing destinations`() {
        assertEquals("Account", navigationLabel("Account", 1f))
        assertEquals("Me", navigationLabel("Account", 2f))
        assertEquals("Scan", navigationLabel("Observe", 2f))
        assertEquals("Guide", navigationLabel("Field guide", 2f))
        assertEquals("Home", navigationLabel("Home", 2f))
    }
}
