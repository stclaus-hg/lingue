package lingue.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class PromptTextTest {

    @Test
    fun `an irregular verb asks for its three forms`() {
        assertEquals("Напишите три формы глагола", typingHeading("go, went, gone"))
        assertEquals("Три формы — через запятую или пробел", typingFieldLabel("go, went, gone"))
    }

    @Test
    fun `any other word keeps the plain wording`() {
        assertEquals("Напишите слово по-английски", typingHeading("to get along with sb"))
        assertEquals("Ответ", typingFieldLabel("to get along with sb"))
    }

    @Test
    fun `the spelling drill names the three forms too`() {
        assertEquals("Три формы — через запятую или пробел", drillFieldLabel("go, went, gone"))
        assertEquals("Наберите слово", drillFieldLabel("to get along with sb"))
    }
}
