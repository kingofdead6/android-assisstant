package com.john.assistant.platform

import android.content.Context
import android.provider.ContactsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** One phone number belonging to a contact. */
data class ContactNumber(
    val displayName: String,
    val number: String,
    /** "Mobile", "Home", "Work" — what John reads out when asking which. */
    val typeLabel: String,
)

/** What resolving a spoken name produced. */
sealed interface ContactMatch {
    data class Single(val contact: ContactNumber) : ContactMatch

    /** One person, several numbers — "Mom has two numbers. Which one?" */
    data class MultipleNumbers(
        val displayName: String,
        val numbers: List<ContactNumber>,
    ) : ContactMatch

    /** Several people match the spoken name. */
    data class MultiplePeople(val candidates: List<ContactNumber>) : ContactMatch

    data object None : ContactMatch
}

/**
 * Turns "Mom" into a phone number.
 *
 * Reads only what it needs — display name, number and number type — and never
 * caches results: a contact list is the most sensitive thing on most phones,
 * and John has no reason to keep a copy of one. Every lookup goes to the
 * provider and the result is discarded once the call is placed.
 *
 * Requires READ_CONTACTS, which is requested when a contact tool is first used
 * rather than at launch.
 */
@Singleton
class ContactResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Look a spoken name up.
     *
     * The provider's own `LIKE` matching handles most of it. Exact
     * display-name matches are preferred over partial ones so that "Ali" picks
     * Ali rather than Alison when both exist.
     */
    suspend fun resolve(spokenName: String): ContactMatch = withContext(Dispatchers.IO) {
        val query = spokenName.trim()
        if (query.isEmpty()) return@withContext ContactMatch.None

        val rows = runCatching { queryNumbers(query) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return@withContext ContactMatch.None

        val normalisedQuery = query.lowercase(Locale.ROOT)
        val exact = rows.filter { it.displayName.lowercase(Locale.ROOT) == normalisedQuery }
        val candidates = exact.ifEmpty { rows }

        val people = candidates.groupBy { it.displayName }
        when {
            people.size > 1 ->
                // One number each, so the user is choosing a person, not a line.
                ContactMatch.MultiplePeople(people.values.map { it.first() }.take(MAX_CANDIDATES))

            candidates.size == 1 -> ContactMatch.Single(candidates.single())

            else -> ContactMatch.MultipleNumbers(
                displayName = candidates.first().displayName,
                numbers = candidates.take(MAX_CANDIDATES),
            )
        }
    }

    private fun queryNumbers(query: String): List<ContactNumber> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        )

        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("%$query%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC",
        ) ?: return emptyList()

        return cursor.use {
            val nameIndex = it.getColumnIndexOrThrow(projection[0])
            val numberIndex = it.getColumnIndexOrThrow(projection[1])
            val typeIndex = it.getColumnIndexOrThrow(projection[2])
            val labelIndex = it.getColumnIndexOrThrow(projection[3])

            buildList {
                while (it.moveToNext()) {
                    val name = it.getString(nameIndex) ?: continue
                    val number = it.getString(numberIndex) ?: continue
                    val customLabel = it.getString(labelIndex)
                    val typeLabel = ContactsContract.CommonDataKinds.Phone
                        .getTypeLabel(context.resources, it.getInt(typeIndex), customLabel)
                        .toString()

                    add(ContactNumber(name, number, typeLabel))
                }
            }
                // The same number often appears under several accounts.
                .distinctBy { row -> row.displayName to row.number.filter(Char::isDigit) }
        }
    }

    private companion object {
        const val MAX_CANDIDATES = 4
    }
}
