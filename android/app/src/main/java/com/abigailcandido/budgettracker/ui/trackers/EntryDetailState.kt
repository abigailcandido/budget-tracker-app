package com.abigailcandido.budgettracker.ui.trackers

import com.abigailcandido.budgettracker.data.remote.TransactionResponse
import com.abigailcandido.budgettracker.data.remote.isAdjusted
import com.abigailcandido.budgettracker.ui.pin.PIN_LENGTH

/** The length of the code emailed for a deletion. */
const val EMAIL_CODE_LENGTH = 6

/**
 * The details page for one entry: the PIN check in front of Edit, the
 * Delete and Restore dialogs, and the dialog that gives an Others entry a
 * category of its own. Like the form, it lives in the tracker screen's
 * ViewModel.
 */
data class EntryDetailState(
    val entry: TransactionResponse,
    /**
     * Every version of the entry and every reversal, oldest first. Fetched
     * only for an entry that has been corrected, deleted or restored; any
     * other entry's history is just itself.
     */
    val history: List<TransactionResponse> = listOf(entry),
    val isLoadingHistory: Boolean = false,
    val historyError: String? = null,
    // ---- The PIN check in front of Edit ----
    val isPinOpen: Boolean = false,
    val pin: String = "",
    val isCheckingPin: Boolean = false,
    val pinError: String? = null,
    // ---- Deleting: a reason, then the code emailed for it ----
    val isDeleteOpen: Boolean = false,
    val deleteReason: String = "",
    /** Where the code went, such as "ab•••@gmail.com", once it has been sent. */
    val codeSentTo: String? = null,
    val codeMinutes: Int = 0,
    val deleteCode: String = "",
    val isSendingCode: Boolean = false,
    val isDeleting: Boolean = false,
    val deleteError: String? = null,
    // ---- Restoring a deleted entry ----
    val isRestoreOpen: Boolean = false,
    val restoreReason: String = "",
    val isRestoring: Boolean = false,
    val restoreError: String? = null,
    // ---- Adding an Others entry's name to the categories ----
    val isPromoteOpen: Boolean = false,
    val promoteName: String = "",
    val promoteWithSubcategory: Boolean = false,
    val promoteSubcategoryName: String = "",
    val promoteAsGlobal: Boolean = false,
    val isPromoting: Boolean = false,
    val promoteError: String? = null,
) {
    val canCheckPin: Boolean
        get() = !isCheckingPin && pin.length == PIN_LENGTH

    /** Whether something is on its way to the server, so the page must stay open. */
    val isBusy: Boolean
        get() = isCheckingPin || isSendingCode || isDeleting || isRestoring || isPromoting

    /** Whether the entry has a history worth showing: corrections, a deletion or a restore. */
    val hasHistory: Boolean
        get() = entry.isAdjusted || entry.isDeleted

    /** Only a current entry under Others can be given a category of its own. */
    val canBePromoted: Boolean
        get() = entry.otherName != null && !entry.isDeleted

    /**
     * Only an entry under the top-level Others can become a new category with
     * a sub-category. One under "Bills › Others" becomes a sub-category itself.
     */
    val canPromoteWithSubcategory: Boolean
        get() = entry.parentCategoryName == null

    /**
     * Whether the new category may be global: always under the top-level
     * Others, and under "Bills › Others" only when Bills itself is global.
     */
    val canPromoteAsGlobal: Boolean
        get() = entry.parentCategoryName == null || entry.parentCategoryIsGlobal == true

    /** The reversal that deleted the entry, once the history has arrived. */
    val deletion: TransactionResponse?
        get() = if (entry.isDeleted) history.firstOrNull { it.reversesId == entry.id } else null

    val canSendCode: Boolean
        get() = !isSendingCode && !isDeleting && deleteReason.isNotBlank()

    val canConfirmDelete: Boolean
        get() = !isDeleting &&
                codeSentTo != null &&
                deleteReason.isNotBlank() &&
                deleteCode.length == EMAIL_CODE_LENGTH

    val canConfirmPromote: Boolean
        get() = !isPromoting &&
                promoteName.isNotBlank() &&
                !isOthersName(promoteName) &&
                (!promoteWithSubcategory ||
                        (promoteSubcategoryName.isNotBlank() && !isOthersName(promoteSubcategoryName)))
}

/** How a version stopped being the one in use. */
enum class VersionEnd { Current, Replaced, Deleted }

/** One version of an entry, and when and why it stopped being the current one. */
data class EntryVersion(
    val entry: TransactionResponse,
    /** "Original", "Correction 2", or "Restored". */
    val name: String,
    val end: VersionEnd,
    /** When the version was corrected or deleted, or null for the one in use now. */
    val endedAt: String?,
    /** The reason given for the correction or the deletion, if any. */
    val reason: String?,
)

/**
 * The versions in a history, oldest first. Each reversal is folded into the
 * version it cancelled, as the time that version was replaced or deleted and
 * the reason given. A version followed by a restoration was deleted, then
 * brought back: the restoration is the next version, with its own reason.
 */
fun versionsOf(history: List<TransactionResponse>): List<EntryVersion> {
    val reversals = history
        .filter { it.reversesId != null }
        .associateBy { it.reversesId }
    val successors = history
        .filter { it.reversesId == null && it.replacesId != null }
        .associateBy { it.replacesId }

    var corrections = 0
    return history
        .filter { it.reversesId == null }
        .mapIndexed { index, version ->
            val reversal = reversals[version.id]
            val successor = successors[version.id]
            val name = when {
                index == 0 -> "Original"
                version.isRestoration -> "Restored"
                else -> "Correction ${++corrections}"
            }
            EntryVersion(
                entry = version,
                name = name,
                end = when {
                    reversal == null -> VersionEnd.Current
                    successor == null || successor.isRestoration -> VersionEnd.Deleted
                    else -> VersionEnd.Replaced
                },
                endedAt = reversal?.createdAt,
                reason = reversal?.reason,
            )
        }
}