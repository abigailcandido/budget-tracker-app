package com.abigailcandido.budgettracker.ui.trackers

import com.abigailcandido.budgettracker.data.remote.TransactionResponse
import com.abigailcandido.budgettracker.ui.pin.PIN_LENGTH

/**
 * The details page for one entry, and the PIN check in front of its Edit
 * button. Like the form, it lives in the tracker screen's ViewModel.
 */
data class EntryDetailState(
    val entry: TransactionResponse,
    /**
     * Every version of the entry and every reversal, oldest first. Fetched
     * only for an entry that has been corrected; any other entry's history is
     * just itself.
     */
    val history: List<TransactionResponse> = listOf(entry),
    val isLoadingHistory: Boolean = false,
    val historyError: String? = null,
    val isPinOpen: Boolean = false,
    val pin: String = "",
    val isCheckingPin: Boolean = false,
    val pinError: String? = null,
) {
    val canCheckPin: Boolean
        get() = !isCheckingPin && pin.length == PIN_LENGTH
}

/** One version of an entry, and when it stopped being the current one. */
data class EntryVersion(
    val entry: TransactionResponse,
    /** When the version was corrected or deleted, or null for the one in use now. */
    val replacedAt: String?,
)

/**
 * The versions in a history, oldest first. Each reversal is folded into the
 * version it cancelled, as the time that version was replaced.
 */
fun versionsOf(history: List<TransactionResponse>): List<EntryVersion> {
    val reversedAt = history
        .filter { it.reversesId != null }
        .associate { it.reversesId to it.createdAt }

    return history
        .filter { it.reversesId == null }
        .map { version -> EntryVersion(version, reversedAt[version.id]) }
}