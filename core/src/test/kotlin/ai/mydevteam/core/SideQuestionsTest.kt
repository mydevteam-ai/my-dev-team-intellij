package ai.mydevteam.core

import ai.mydevteam.core.chat.SideQuestions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SideQuestionsTest {
    @Test
    fun `a leading btw marker carries the rest as the side question`() {
        assertEquals(
            "how do I sort an array in Python?",
            SideQuestions.questionOf("btw how do I sort an array in Python?"),
        )
    }

    @Test
    fun `the marker matches any case with optional punctuation`() {
        assertEquals("which is faster?", SideQuestions.questionOf("BTW, which is faster?"))
        assertEquals("which is faster?", SideQuestions.questionOf("Btw: which is faster?"))
    }

    @Test
    fun `surrounding whitespace is tolerated and the question is trimmed`() {
        assertEquals("what is a tuple?", SideQuestions.questionOf("  btw   what is a tuple?  "))
    }

    @Test
    fun `an explicit ask command carries its argument`() {
        assertEquals("what is a tuple?", SideQuestions.questionOf("/ask what is a tuple?"))
    }

    @Test
    fun `a prompt mentioning btw mid-sentence is not a side question`() {
        assertNull(SideQuestions.questionOf("the btw flag is broken, fix it"))
    }

    @Test
    fun `a word merely starting with btw is not a side question`() {
        assertNull(SideQuestions.questionOf("btware needs an update"))
    }

    @Test
    fun `a bare marker or bare command with no question falls through`() {
        assertNull(SideQuestions.questionOf("btw"))
        assertNull(SideQuestions.questionOf("btw,"))
        assertNull(SideQuestions.questionOf("/ask"))
        assertNull(SideQuestions.questionOf("/ask   "))
    }

    @Test
    fun `a command merely starting with ask is not the ask command`() {
        assertNull(SideQuestions.questionOf("/askme anything"))
    }
}
