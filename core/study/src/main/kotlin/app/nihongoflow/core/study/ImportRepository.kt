package app.nihongoflow.core.study

import app.nihongoflow.core.model.CardId
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.model.ReviewRecord

/**
 * Everything one import writes. It is saved in a single transaction, so an import either
 * lands completely or not at all.
 *
 * @property memory memory states rebuilt from imported review history (keep-progress imports only).
 * @property history the imported reviews themselves, labelled with their source.
 */
data class ImportBatch(
    val decks: List<Deck>,
    val notes: List<Note>,
    val createdCards: List<Card>,
    val updatedCards: List<Card> = emptyList(),
    val memory: Map<CardId, MemoryState> = emptyMap(),
    val history: List<ReviewRecord> = emptyList(),
)

interface ImportRepository {
    suspend fun importBatch(batch: ImportBatch)
}
