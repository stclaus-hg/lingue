package lingue.ui

import lingue.core.Grading

/**
 * Wording of a production card. An irregular verb says up front that three
 * forms are expected — otherwise the prompt "идти, ходить" reads like an
 * ordinary word, and typing just "go" would fail for a reason the card never
 * stated.
 */
fun typingHeading(expected: String): String =
    if (Grading.isVerbForms(expected)) "Напишите три формы глагола" else "Напишите слово по-английски"

fun typingFieldLabel(expected: String): String =
    if (Grading.isVerbForms(expected)) "Три формы — через запятую или пробел" else "Ответ"

fun drillFieldLabel(expected: String): String =
    if (Grading.isVerbForms(expected)) "Три формы — через запятую или пробел" else "Наберите слово"
