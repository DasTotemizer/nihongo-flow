package app.nihongoflow.ui.importer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.nihongoflow.core.anki.AnkiImportException
import app.nihongoflow.core.anki.AnkiImportOptions
import app.nihongoflow.core.anki.AnkiPreview
import app.nihongoflow.core.anki.DelimitedText
import app.nihongoflow.core.anki.DuplicatePolicy
import app.nihongoflow.core.anki.ImportCheck
import app.nihongoflow.core.anki.ImportResult
import app.nihongoflow.core.anki.MappingGuesser
import app.nihongoflow.core.anki.NoteTypeMapping
import app.nihongoflow.core.anki.ProgressMode
import app.nihongoflow.core.study.NoteKind
import app.nihongoflow.importer.ImportService
import app.nihongoflow.importer.OpenedImport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ImportUi {
    data object Opening : ImportUi
    /** [error] is kept so the learner can send the details if the cause isn't obvious. */
    data class Failed(val message: String, val error: Throwable? = null) : ImportUi

    data class AnkiReady(
        val fileName: String,
        val preview: AnkiPreview,
        val mappings: Map<Long, NoteTypeMapping>,
        val progress: ProgressMode,
        val duplicates: DuplicatePolicy,
        val check: ImportCheck?,
    ) : ImportUi

    data class TextReady(
        val fileName: String,
        val text: DelimitedText,
        val firstRowIsHeader: Boolean,
        val mapping: NoteTypeMapping,
        val deckName: String,
        val duplicates: DuplicatePolicy,
    ) : ImportUi {
        val columns: List<String> get() = if (firstRowIsHeader) text.rows.firstOrNull().orEmpty().map { it.trim() } else text.columns
        val rows: List<List<String>> get() = if (firstRowIsHeader) text.rows.drop(1) else text.rows
    }

    data object Importing : ImportUi
    data class Done(val result: ImportResult, val keptProgress: Boolean) : ImportUi
}

/** Runs one import: open, preview with choices, import. Holds the opened file until done. */
class ImportViewModel(private val service: ImportService, uri: Uri) : ViewModel() {

    private val _ui = MutableStateFlow<ImportUi>(ImportUi.Opening)
    val ui: StateFlow<ImportUi> = _ui.asStateFlow()

    private var opened: OpenedImport? = null

    init {
        viewModelScope.launch {
            _ui.value = try {
                when (val o = service.open(uri).also { opened = it }) {
                    is OpenedImport.Anki -> {
                        val preview = withContext(Dispatchers.Default) { service.anki.preview(o.pkg.collection) }
                        ImportUi.AnkiReady(
                            fileName = o.fileName,
                            preview = preview,
                            mappings = preview.noteTypes.associate { it.noteType.id to it.guess },
                            progress = if (preview.hasProgress) ProgressMode.KEEP_PROGRESS else ProgressMode.FRESH_START,
                            duplicates = DuplicatePolicy.SKIP,
                            check = null,
                        ).also { recheck(it) }
                    }
                    is OpenedImport.Text -> {
                        val header = looksLikeHeader(o.text)
                        val ready = ImportUi.TextReady(o.fileName, o.text, header, NoteTypeMapping(NoteKind.BASIC, emptyMap()),
                            o.fileName.substringBeforeLast('.'), DuplicatePolicy.SKIP)
                        val kind = MappingGuesser.guessKind(false, ready.columns)
                        ready.copy(mapping = NoteTypeMapping(kind, MappingGuesser.guessFields(kind, ready.columns)))
                    }
                }
            } catch (e: AnkiImportException) {
                ImportUi.Failed(e.message ?: "That file couldn't be imported.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Errors too (out of memory, a missing native library): a failed import must
                // never take the app down with it.
                ImportUi.Failed("That file couldn't be opened.", e)
            }
        }
    }

    private fun looksLikeHeader(text: DelimitedText): Boolean {
        val first = text.rows.firstOrNull() ?: return false
        if (text.columns.any { !it.startsWith("Column ") }) return false
        val known = setOf("word", "reading", "meaning", "front", "back", "english", "japanese", "kanji", "kana", "expression", "sentence", "notes", "tags")
        return first.any { it.trim().lowercase() in known }
    }

    // ---- Anki choices ----

    fun setMapping(noteTypeId: Long, mapping: NoteTypeMapping) = updateAnki { it.copy(mappings = it.mappings + (noteTypeId to mapping)) }

    fun setKind(noteTypeId: Long, kind: NoteKind?) = updateAnki { s ->
        val nt = s.preview.noteTypes.first { it.noteType.id == noteTypeId }.noteType
        val current = s.mappings.getValue(noteTypeId)
        val next = if (kind == null) current.copy(skip = true) else current.copy(skip = false).withKind(kind, nt.fields, nt.templates)
        s.copy(mappings = s.mappings + (noteTypeId to next))
    }

    fun setProgress(mode: ProgressMode) = updateAnki { it.copy(progress = mode) }
    fun setAnkiDuplicates(policy: DuplicatePolicy) = updateAnki { it.copy(duplicates = policy) }

    private fun updateAnki(change: (ImportUi.AnkiReady) -> ImportUi.AnkiReady) {
        val s = _ui.value as? ImportUi.AnkiReady ?: return
        val next = change(s)
        _ui.value = next
        if (next.mappings != s.mappings) recheck(next)
    }

    private fun recheck(state: ImportUi.AnkiReady) {
        val pkg = (opened as? OpenedImport.Anki)?.pkg ?: return
        viewModelScope.launch {
            val check = try {
                withContext(Dispatchers.Default) { service.anki.check(pkg.collection, state.mappings) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return@launch // The summary is optional; the import itself reports any real problem.
            }
            val now = _ui.value as? ImportUi.AnkiReady ?: return@launch
            if (now.mappings == state.mappings) _ui.value = now.copy(check = check)
        }
    }

    fun sample(noteTypeId: Long): Map<String, String> {
        val s = _ui.value as? ImportUi.AnkiReady ?: return emptyMap()
        val t = s.preview.noteTypes.firstOrNull { it.noteType.id == noteTypeId } ?: return emptyMap()
        val note = t.samples.firstOrNull() ?: return emptyMap()
        return runCatching { service.anki.sample(note, s.mappings.getValue(noteTypeId)) }.getOrDefault(emptyMap())
    }

    // ---- Text choices ----

    fun updateText(change: (ImportUi.TextReady) -> ImportUi.TextReady) {
        val s = _ui.value as? ImportUi.TextReady ?: return
        var next = change(s)
        if (next.firstRowIsHeader != s.firstRowIsHeader) {
            val kind = next.mapping.kind
            next = next.copy(mapping = NoteTypeMapping(kind, MappingGuesser.guessFields(kind, next.columns)))
        }
        _ui.value = next
    }

    fun setTextKind(kind: NoteKind) = updateText { s -> s.copy(mapping = s.mapping.withKind(kind, s.columns)) }

    fun textSample(): Map<String, String> {
        val s = _ui.value as? ImportUi.TextReady ?: return emptyMap()
        val row = s.rows.firstOrNull() ?: return emptyMap()
        return runCatching { service.text.sample(row, s.mapping, s.text.html) }.getOrDefault(emptyMap())
    }

    // ---- Import ----

    fun import() {
        val state = _ui.value
        val o = opened ?: return
        _ui.value = ImportUi.Importing
        viewModelScope.launch {
            _ui.value = try {
                withContext(Dispatchers.IO) {
                    when {
                        state is ImportUi.AnkiReady && o is OpenedImport.Anki -> ImportUi.Done(
                            service.anki.import(o.pkg, AnkiImportOptions(state.mappings, state.progress, state.duplicates), service.mediaDir, o.fileName.substringBeforeLast('.')),
                            keptProgress = state.progress == ProgressMode.KEEP_PROGRESS,
                        )
                        state is ImportUi.TextReady && o is OpenedImport.Text -> ImportUi.Done(
                            service.text.import(
                                o.text.copy(columns = state.columns, rows = state.rows),
                                state.mapping, state.deckName, state.duplicates, o.fileName,
                            ),
                            keptProgress = false,
                        )
                        else -> ImportUi.Failed("Nothing to import.")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ImportUi.Failed("The import stopped and nothing was saved. (${e.message ?: e.javaClass.simpleName})", e)
            }
            close()
        }
    }

    private fun close() {
        (opened as? OpenedImport.Anki)?.pkg?.close()
        opened = null
    }

    override fun onCleared() = close()

    companion object {
        fun factory(service: ImportService, uri: Uri): ViewModelProvider.Factory = viewModelFactory {
            initializer { ImportViewModel(service, uri) }
        }
    }
}
