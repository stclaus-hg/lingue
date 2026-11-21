package lingue.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.openspacedrepetition.Rating
import lingue.audio.Pronouncer
import lingue.app.Drill
import lingue.app.Feedback
import lingue.app.Progress
import lingue.app.Trainer
import lingue.core.Diff
import lingue.core.DiffKind
import lingue.core.Grading
import lingue.core.Question
import java.time.Instant
import kotlinx.coroutines.delay

/** Long enough to register the green flash, short enough not to feel like waiting. */
private const val AUTO_ADVANCE_MILLIS = 700L

/** A beat of silence after the word finishes, so the next card does not tread on it. */
private const val AFTER_AUDIO_MILLIS = 350L

@Composable
fun TrainScreen(trainer: Trainer, pronouncer: Pronouncer, soundOn: Boolean) {
    val question = trainer.question
    val feedback = trainer.feedback
    val progress = trainer.progress

    // Only a Choice question may be pronounced on sight: its prompt IS the English
    // term. On a Typing question the English word is the answer, so speaking it
    // before submission would hand it over.
    LaunchedEffect(question) {
        if (soundOn && question is Question.Choice) pronouncer.play(question.word.id)
    }

    // A correct answer carries no information worth pausing on, so it advances on
    // its own. A wrong one does — the right answer is on screen and has to be
    // read — so that case waits for a deliberate keypress.
    LaunchedEffect(feedback) {
        if (feedback == null) return@LaunchedEffect

        if (soundOn && question is Question.Typing) pronouncer.play(question.word.id)

        // Only an answer with nothing to read moves on by itself. A near miss puts
        // a letter-level diff on screen, and a pending spelling exercise owns the
        // screen until it is done or skipped.
        if (feedback.correct && trainer.drill == null) {
            // Hearing the word is half the point of playing it, so the card waits
            // for the clip to finish. On a recognition card it started back when
            // the question appeared and may have a second still to run.
            val audioTail = if (soundOn) pronouncer.remainingMillis() + AFTER_AUDIO_MILLIS else 0L
            delay(maxOf(AUTO_ADVANCE_MILLIS, audioTail))
            trainer.advance()
        }
    }

    val content = rememberScrollState()

    // A new question starts from the top; a panel that appears below the fold is
    // scrolled to, since the answer field inside it is about to take focus.
    LaunchedEffect(question) { content.scrollTo(0) }
    LaunchedEffect(feedback, trainer.drill) {
        if (feedback == null && trainer.drill == null) return@LaunchedEffect
        // Two frames: the panel has to be measured before its extent is known.
        withFrameNanos { }
        withFrameNanos { }
        content.animateScrollTo(content.maxValue)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SessionHeader(progress)
        Spacer(Modifier.height(18.dp))

        // The card area scrolls while the header stays put. Without this the
        // feedback panel — and the button that dismisses a wrong answer — falls
        // off the bottom on a short window.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(content)
                .widthIn(max = 620.dp)
                .fillMaxWidth(),
        ) {
            if (question == null) {
                SessionFinished(progress, trainer::reload, trainer::takeAnotherDay)
            } else {
                Prompt(question, introducing = trainer.introducing)
                // Replaying is always allowed on a Choice card; on a Typing card
                // only once the answer has been submitted.
                if (pronouncer.has(question.word.id) &&
                    (question is Question.Choice || feedback != null)
                ) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        TextButton(onClick = { pronouncer.play(question.word.id) }) {
                            Text("Прослушать")
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                when (question) {
                    is Question.Choice ->
                        if (trainer.introducing) Introduction(trainer) else ChoiceAnswer(question, feedback, trainer)
                    // While the exercise is open the answered field is dead weight:
                    // what was typed is already visible in the diff below.
                    is Question.Typing -> if (trainer.drill == null) {
                        TypingAnswer(question, feedback, trainer)
                    }
                }
                if (feedback != null) {
                    Spacer(Modifier.height(if (trainer.drill == null) 20.dp else 14.dp))
                    FeedbackPanel(
                        feedback = feedback,
                        showDiff = question is Question.Typing,
                        canAdvance = trainer.drill == null,
                        compact = trainer.drill != null,
                        onNext = trainer::advance,
                    )
                }

                val candidate = trainer.synonymCandidate
                if (candidate != null) {
                    Spacer(Modifier.height(12.dp))
                    SynonymOffer(candidate.term, trainer::acceptSynonym)
                }

                val drill = trainer.drill
                if (drill != null) {
                    Spacer(Modifier.height(16.dp))
                    DrillPanel(drill, trainer)
                }
            }
        }
    }
}

@Composable
private fun SessionHeader(progress: Progress) {
    val done = progress.answeredThisSession
    val total = (done + progress.remaining).coerceAtLeast(1)

    Column(modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Split rather than one total: an unseen production card of a word
            // learned last week is "new", arrives as typing, and looked like the
            // app ignoring the setting when it was counted with the repeats.
            Text(
                text = buildString {
                    append("Осталось: ")
                    append("${progress.newRemaining} новых")
                    append(" · ${progress.repeatRemaining} повторов")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                // Shows the allowance as currently extended, not the setting, so
                // the numbers still add up after taking another day.
                text = "Сегодня: ${progress.newToday}/${progress.allowedNew} новых слов · " +
                    "${progress.reviewsToday}/${progress.allowedReviews} повторений",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { done.toFloat() / total.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Prompt(question: Question, introducing: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = when {
                introducing -> "Новое слово"
                question is Question.Choice -> "Выберите перевод"
                question is Question.Typing -> typingHeading(question.expected)
                else -> ""
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = question.prompt,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // The one screen where the translation sits next to the term: the word is
        // being taught, not tested.
        if (introducing) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = question.word.translation,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val example = question.word.example
        if (example != null && question is Question.Choice) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = example,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The first meeting with a word: read it, hear it, then be asked.
 *
 * "Уже знаю" has no shortcut on purpose. Letter keys depend on the keyboard
 * layout, Enter and Space already mean "понятно", and the digits pick answers on
 * the next screen — a stray press must not claim knowledge.
 */
@Composable
private fun Introduction(trainer: Trainer) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val continues = event.key == Key.Enter || event.key == Key.Spacebar
                if (continues) trainer.understood()
                continues
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = trainer::understood) { Text("Понятно  ⏎") }
            TextButton(onClick = trainer::alreadyKnown) { Text("Уже знаю") }
        }
    }
}

@Composable
private fun ChoiceAnswer(question: Question.Choice, feedback: Feedback?, trainer: Trainer) {
    val focus = remember { FocusRequester() }
    // Also re-requested once feedback arrives: clicking an option disables that
    // Surface, which drops focus and would otherwise leave Enter doing nothing.
    LaunchedEffect(question, feedback) { focus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (feedback != null) {
                    val advances = event.key == Key.Enter || event.key == Key.Spacebar
                    if (advances) trainer.advance()
                    advances
                } else if (event.key == Key.Zero || event.key == Key.NumPad0) {
                    trainer.dontKnow()
                    true
                } else {
                    val index = digitIndex(event.key)
                    val hit = index != null && index < question.options.size
                    if (hit) trainer.answerChoice(index!!)
                    hit
                }
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Two columns rather than four stacked rows: it halves the tallest block
        // on the screen, which is what pushed the feedback below the fold.
        question.labels.chunked(2).forEachIndexed { row, pair ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                pair.forEachIndexed { column, option ->
                    Option(
                        index = row * 2 + column,
                        label = option,
                        question = question,
                        feedback = feedback,
                        onPick = trainer::answerChoice,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                // Keeps a lone option in the last row at half width instead of
                // stretching it across and breaking the grid.
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        // Owning up beats guessing: a lucky pick would be scheduled as knowledge.
        if (feedback == null) {
            TextButton(onClick = trainer::dontKnow, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Не знаю  0")
            }
        }
    }
}

@Composable
private fun Option(
    index: Int,
    label: String,
    question: Question.Choice,
    feedback: Feedback?,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val correct = index == question.correctIndex
    val picked = feedback != null && label == feedback.given
    val background = when {
        feedback == null -> MaterialTheme.colorScheme.surfaceVariant
        correct -> Verdict.CorrectContainer
        picked -> Verdict.WrongContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when {
        feedback == null -> MaterialTheme.colorScheme.onSurface
        correct -> Verdict.Correct
        picked -> Verdict.Wrong
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = { onPick(index) },
        enabled = feedback == null,
        shape = RoundedCornerShape(12.dp),
        color = background,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
            Text(text = label, style = MaterialTheme.typography.titleMedium, color = foreground)
        }
    }
}

@Composable
private fun TypingAnswer(question: Question.Typing, feedback: Feedback?, trainer: Trainer) {
    var typed by remember(question) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(question) { focus.requestFocus() }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = typed,
            onValueChange = { if (feedback == null) typed = it },
            singleLine = true,
            readOnly = feedback != null,
            label = { Text(typingFieldLabel(question.expected)) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.key != Key.Enter) {
                        return@onPreviewKeyEvent false
                    }
                    when {
                        feedback == null -> trainer.answerTyping(typed)
                        // Enter belongs to the drill's own field while it is open.
                        trainer.drill == null -> trainer.advance()
                    }
                    true
                },
        )
        if (feedback == null) {
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { trainer.answerTyping(typed) },
                enabled = typed.isNotBlank(),
            ) {
                Text("Проверить  ⏎")
            }
        }
    }
}

@Composable
private fun FeedbackPanel(
    feedback: Feedback,
    showDiff: Boolean,
    canAdvance: Boolean,
    compact: Boolean,
    onNext: () -> Unit,
) {
    val accent = if (feedback.correct) Verdict.Correct else Verdict.Wrong
    val container = if (feedback.correct) Verdict.CorrectContainer else Verdict.WrongContainer

    Surface(shape = RoundedCornerShape(14.dp), color = container, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(if (compact) 14.dp else 20.dp)) {
            // With the exercise open the verdict is old news; the diff is the part
            // still worth the space.
            if (!compact) {
                Text(
                    text = if (feedback.correct) "Верно" else "Неверно",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                )
            }

            // Printing the right answer next to a wrong one leaves the reader to
            // hunt for the difference — which is the step that actually fixes a
            // spelling, so it is done here instead.
            if (showDiff && !feedback.correct) {
                if (!compact) Spacer(Modifier.height(10.dp))
                SpellingDiff(expected = feedback.expected, typed = feedback.given)
            } else if (!feedback.correct) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Правильный ответ: ${feedback.expected}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (!compact) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Повтор ${formatNextDue(Instant.now(), feedback.nextDue)} · оценка «${ratingLabel(feedback.rating)}»",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Shown whenever the card is waiting for a keypress — which is any
            // answer that left something on screen worth reading.
            if (!feedback.correct && canAdvance) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onNext) { Text("Дальше  ⏎") }
            }
        }
    }
}

/** Two monospaced lines: what was typed, and what it should have been. */
@Composable
private fun SpellingDiff(expected: String, typed: String) {
    val operations = remember(expected, typed) { Diff.of(expected.lowercase(), typed.lowercase()) }
    val normal = MaterialTheme.colorScheme.onSurface
    val faded = MaterialTheme.colorScheme.onSurfaceVariant

    Column {
        DiffLine(
            label = "вы написали",
            annotated = buildAnnotatedString {
                Diff.typedSide(operations).forEach { operation ->
                    val letter = operation.typed ?: return@forEach
                    if (operation.kind == DiffKind.MATCH) {
                        withStyle(SpanStyle(color = normal)) { append(letter) }
                    } else {
                        withStyle(
                            SpanStyle(color = Verdict.Wrong, textDecoration = TextDecoration.LineThrough),
                        ) { append(letter) }
                    }
                }
            },
            labelColor = faded,
        )
        Spacer(Modifier.height(4.dp))
        DiffLine(
            label = "правильно",
            annotated = buildAnnotatedString {
                Diff.expectedSide(operations).forEach { operation ->
                    val letter = operation.expected ?: return@forEach
                    if (operation.kind == DiffKind.MATCH) {
                        withStyle(SpanStyle(color = normal)) { append(letter) }
                    } else {
                        withStyle(
                            SpanStyle(color = Verdict.Correct, fontWeight = FontWeight.Bold),
                        ) { append(letter) }
                    }
                }
            },
            labelColor = faded,
        )
    }
}

@Composable
private fun DiffLine(label: String, annotated: AnnotatedString, labelColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = labelColor,
            modifier = Modifier.width(104.dp),
        )
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SessionFinished(progress: Progress, onReload: () -> Unit, onAnotherDay: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when {
                progress.words == 0 -> "Список слов пуст"
                progress.budgetSpent -> "Дневная норма выбрана"
                else -> "Сейчас показывать нечего"
            },
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            // "Nothing to show" and "you are done for today" are different states,
            // and only one of them means come back tomorrow.
            text = when {
                progress.words == 0 ->
                    "Добавьте слова на вкладке «Слова» — и можно начинать."

                progress.budgetSpent ->
                    "Отвечено ${formatCount(progress.answeredThisSession, "карточка", "карточки", "карточек")} " +
                        "за сессию. " +
                        buildString {
                            append("Ждут ещё: ")
                            append("${progress.waitingNew} новых слов")
                            append(" · ${progress.waitingRepeats} повторов")
                            append(". Их можно взять сейчас, не дожидаясь завтра.")
                        }

                else ->
                    "Отвечено ${formatCount(progress.answeredThisSession, "карточка", "карточки", "карточек")}. " +
                        "Новые слова закреплены, следующие повторения — по расписанию."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onReload) { Text("Проверить ещё раз") }
            // The cap is a default, not a lock: whoever wants to keep going says so.
            if (progress.budgetSpent && progress.waiting > 0) {
                Button(onClick = onAnotherDay) { Text("Взять ещё одну норму") }
            }
        }
    }
}

/**
 * Offers to record the answer given as meaning the same as the one expected.
 *
 * The pair only becomes visible at the moment it is met, so this is where it is
 * captured. Once linked, the two words stop being offered as each other's wrong
 * option and each is accepted in place of the other.
 */
@Composable
private fun SynonymOffer(term: String, onAccept: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "«$term» значит то же самое?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Тогда эти слова перестанут предлагаться друг другу как неверный вариант, " +
                        "и каждое будет приниматься вместо другого. Текущий ответ останется ошибкой.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onAccept) { Text("Это синоним") }
        }
    }
}

/**
 * Writing the word out until it sticks.
 *
 * The exercise runs after the card has already been graded and never feeds back
 * into the scheduler — it trains the hand, not the memory model.
 */
@Composable
private fun DrillPanel(drill: Drill, trainer: Trainer) {
    var typed by remember(drill.attempts) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(drill.attempts) { focus.requestFocus() }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Закрепим написание",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "${drill.done + 1} из ${drill.required}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Copy it once, then reproduce it. Three copies with the model in
            // view would train the eyes and leave the memory untouched.
            if (drill.showsModel) {
                Text(
                    text = drill.expected,
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "спишите слово",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = drill.word.translation,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "теперь по памяти",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                label = { Text(drillFieldLabel(drill.expected)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || event.key != Key.Enter) {
                            return@onPreviewKeyEvent false
                        }
                        trainer.submitDrill(typed)
                        true
                    },
            )

            if (drill.lastAttemptWrong) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Не совпадает — наберите ещё раз",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { trainer.submitDrill(typed) }, enabled = typed.isNotBlank()) {
                    Text("Проверить  ⏎")
                }
                TextButton(onClick = trainer::skipDrill) { Text("Пропустить") }
            }
        }
    }
}

private fun ratingLabel(rating: Rating): String = when (rating) {
    Rating.AGAIN -> "снова"
    Rating.HARD -> "трудно"
    Rating.GOOD -> "хорошо"
    Rating.EASY -> "легко"
}

private fun digitIndex(key: Key): Int? = when (key) {
    Key.One, Key.NumPad1 -> 0
    Key.Two, Key.NumPad2 -> 1
    Key.Three, Key.NumPad3 -> 2
    Key.Four, Key.NumPad4 -> 3
    else -> null
}
