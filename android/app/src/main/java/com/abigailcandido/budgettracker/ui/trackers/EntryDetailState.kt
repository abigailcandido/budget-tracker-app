package com.abigailcandido.budgettracker.ui.trackers

import com.abigailcandido.budgettracker.data.remote.TransactionResponse
import com.abigailcandido.budgettracker.data.remote.isAdjusted
import com.abigailcandido.budgettracker.ui.pin.PIN_LENGTH

/**
 * The details page for one entry, the PIN check in front of its Edit button,
 * and the dialog that gives an Others entry a category of its own. Like the
 * form, it lives in the tracker screen's ViewModel.
 */
data class EntryDetailState(
    val entry: TransactionResponse,
    /**
     * Every version of the entry and every reversal, oldest first. Fetched
     * only for an entry that has been corrected or deleted; any other entry's
     * history is just itself.
     */
    val history: List<TransactionResponse> = listOf(entry),
    val isLoadingHistory: Boolean = false,
    val historyError: String? = null,
    // ---- The PIN check in front of Edit ----
    val isPinOpen: Boolean = false,
    val pin: String = "",
    val isCheckingPin: Boolean = false,
    val pinError: String? = null,
    // ---- Adding an Others entry's name to the categories ----
    val isPromoteOpen: Boolean = false,
    val promoteName: String = "",
    val promotePin: String = "",
    val isPromoting: Boolean = false,
    val promoteError: String? = null,
) {
    val canCheckPin: Boolean
        get() = !isCheckingPin && pin.length == PIN_LENGTH

    /** Whether the entry has a history worth showing: corrections, or its deletion. */
    val hasHistory: Boolean
        get() = entry.isAdjusted || entry.isDeleted

    /** Only a current entry under Others can be given a category of its own. */
    val canBePromoted: Boolean
        get() = entry.otherName != null && !entry.isDeleted

    /** The reversal that deleted the entry, once the history has arrived. */
    val deletion: TransactionResponse?
        get() = if (entry.isDeleted) history.firstOrNull { it.reversesId == entry.id } else null

    val canConfirmPromote: Boolean
        get() = !isPromoting &&
                promoteName.isNotBlank() &&
                !isOthersName(promoteName) &&
                promotePin.length == PIN_LENGTH
}

/** How a version stopped being the one in use. */
enum class VersionEnd { Current, Replaced, Deleted }

/** One version of an entry, and when and why it stopped being the current one. */
data class EntryVersion(
    val entry: TransactionResponse,
    val end: VersionEnd,
    /** When the version was corrected or deleted, or null for the one in use now. */
    val endedAt: String?,
    /** The reason given for the correction or the deletion, if any. */
    val reason: String?,
)

/**
 * The versions in a history, oldest first. Each reversal is folded into the
 * version it cancelled, as the time that version was replaced or deleted and
 * the reason given.
 */
fun versionsOf(history: List<TransactionResponse>): List<EntryVersion> {
    val reversals = history
        .filter { it.reversesId != null }
        .associateBy { it.reversesId }

    return history
        .filter { it.reversesId == null }
        .map { version ->
            val reversal = reversals[version.id]
            EntryVersion(
                entry = version,
                end = when {
                    reversal == null -> VersionEnd.Current
                    version.isDeleted -> VersionEnd.Deleted
                    else -> VersionEnd.Replaced
                },
                endedAt = reversal?.createdAt,
                reason = reversal?.reason,
            )
        }
}