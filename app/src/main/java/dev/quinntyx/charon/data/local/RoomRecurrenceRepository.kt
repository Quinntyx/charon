package dev.quinntyx.charon.data.local

import androidx.room.withTransaction
import dev.quinntyx.charon.recurrence.AmountRevision
import dev.quinntyx.charon.recurrence.LoggedOccurrence
import dev.quinntyx.charon.recurrence.LoggingOrigin
import dev.quinntyx.charon.recurrence.LoggingPolicy
import dev.quinntyx.charon.recurrence.OccurrenceKey
import dev.quinntyx.charon.recurrence.PauseWindow
import dev.quinntyx.charon.recurrence.RecurrenceRepository
import dev.quinntyx.charon.recurrence.RecurrenceRule
import dev.quinntyx.charon.recurrence.RecurrenceTransaction
import dev.quinntyx.charon.recurrence.RecurringPayment
import dev.quinntyx.charon.recurrence.ScheduledOccurrence
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

class RoomRecurrenceRepository internal constructor(
    private val database: CharonDatabase,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : RecurrenceRepository {
    private val dao = database.charonDao()

    override fun observePayments(): Flow<List<RecurringPayment>> = dao.observeRecurringPayments().map { rows ->
        database.withTransaction { rows.map { mapPayment(it) } }
    }

    override fun observeLoggedOccurrences(): Flow<List<LoggedOccurrence>> =
        dao.observeLoggedOccurrences().map { rows -> rows.map(LoggedOccurrenceEntity::toDomain) }

    override suspend fun <T> inTransaction(block: suspend RecurrenceTransaction.() -> T): T =
        database.withTransaction {
            block(
                object : RecurrenceTransaction {
                    override suspend fun getPayment(id: String): RecurringPayment? =
                        dao.getRecurringPayment(id)?.let { mapPayment(it) }

                    override suspend fun getPayments(): List<RecurringPayment> =
                        dao.getRecurringPayments().map { mapPayment(it) }

                    override suspend fun savePayment(payment: RecurringPayment) {
                        validateReferences(payment)
                        dao.upsertRecurringPayment(payment.toEntity())
                        dao.deleteRecurringPaymentTags(payment.id)
                        val tagIds = payment.tagIds.map(String::toLong)
                        if (tagIds.isNotEmpty()) {
                            dao.insertRecurringPaymentTags(tagIds.map { RecurringPaymentTagEntity(payment.id, it) })
                        }
                    }

                    override suspend fun getLoggedOccurrences(): List<LoggedOccurrence> =
                        dao.getLoggedOccurrences().map(LoggedOccurrenceEntity::toDomain)

                    override suspend fun insertLoggedOccurrenceIfAbsent(occurrence: LoggedOccurrence): Boolean {
                        val scheduled = occurrence.occurrence
                        if (dao.countLoggedOccurrence(
                                scheduled.key.recurringPaymentId,
                                scheduled.key.dueDate.toEpochDay(),
                            ) > 0
                        ) return false

                        val folderId = scheduled.folderId.toLong()
                        val folder = requireNotNull(dao.getAccount(folderId)) { "Unknown folder id $folderId" }
                        require(!folder.archived) { "Archived folder id $folderId cannot log a recurring expense" }
                        require(folder.currencyCode == scheduled.currency) {
                            "Recurring currency ${scheduled.currency} does not match folder currency ${folder.currencyCode}"
                        }
                        val merchantId = scheduled.merchantId?.toLong()
                        merchantId?.let { requireNotNull(dao.getMerchant(it)) { "Unknown merchant id $it" } }
                        val tagIds = scheduled.tagIds.map(String::toLong).toSet()
                        val tags = if (tagIds.isEmpty()) emptyList() else dao.getTags(tagIds).also { found ->
                            require(found.size == tagIds.size) { "One or more recurring tag ids do not exist" }
                            require(found.none(TagEntity::archived)) { "Archived tags cannot be logged" }
                        }
                        val transactionId = dao.insertTransaction(
                            LedgerTransactionEntity(
                                kind = "EXPENSE",
                                amountMinor = scheduled.amountMinorUnits,
                                currencyCode = scheduled.currency,
                                occurredAtEpochMillis = scheduled.key.dueDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                                sourceAccountId = folderId,
                                destinationAccountId = null,
                                merchantId = merchantId,
                                receiptId = null,
                                note = "Recurring: ${scheduled.title} (${scheduled.key.dueDate})",
                                createdAtEpochMillis = occurrence.loggedAtEpochMillis,
                            ),
                        )
                        if (tags.isNotEmpty()) {
                            dao.insertTransactionTags(tags.map { TransactionTagEntity(transactionId, it.id) })
                        }
                        val inserted = dao.insertLoggedOccurrence(occurrence.toEntity(transactionId))
                        check(inserted != -1L) { "Occurrence was concurrently logged" }
                        return true
                    }
                },
            )
        }

    private suspend fun validateReferences(payment: RecurringPayment) {
        val folderId = payment.folderId.toLong()
        val folder = requireNotNull(dao.getAccount(folderId)) { "Unknown folder id $folderId" }
        require(!folder.archived) { "Archived folder id $folderId cannot be used by a recurrence" }
        require(folder.currencyCode == payment.currency) {
            "Recurring currency ${payment.currency} does not match folder currency ${folder.currencyCode}"
        }
        payment.merchantId?.toLong()?.let { requireNotNull(dao.getMerchant(it)) { "Unknown merchant id $it" } }
        val tagIds = payment.tagIds.map(String::toLong).toSet()
        if (tagIds.isNotEmpty()) {
            val tags = dao.getTags(tagIds)
            require(tags.size == tagIds.size) { "One or more recurring tag ids do not exist" }
            require(tags.none(TagEntity::archived)) { "Archived tags cannot be used by a recurrence" }
        }
    }

    private suspend fun mapPayment(entity: RecurringPaymentEntity): RecurringPayment = entity.toDomain(
        tagIds = dao.getRecurringPaymentTagIds(entity.id).mapTo(linkedSetOf()) { it.toString() },
    )
}

private fun RecurringPayment.toEntity(): RecurringPaymentEntity {
    val (ruleType, interval) = when (val value = rule) {
        is RecurrenceRule.Weekly -> "WEEKLY" to value.everyWeeks
        is RecurrenceRule.Monthly -> "MONTHLY" to value.everyMonths
        is RecurrenceRule.Yearly -> "YEARLY" to value.everyYears
        is RecurrenceRule.CustomDays -> "CUSTOM_DAYS" to value.everyDays
    }
    return RecurringPaymentEntity(
        id = id,
        title = title,
        currencyCode = currency,
        folderAccountId = folderId.toLong(),
        merchantId = merchantId?.toLong(),
        startsOnEpochDay = startsOn.toEpochDay(),
        ruleType = ruleType,
        ruleInterval = interval,
        loggingPolicy = loggingPolicy.name,
        amountRevisionsJson = JSONArray().apply {
            amountRevisions.forEach { put(JSONObject().put("date", it.effectiveFrom.toEpochDay()).put("amount", it.minorUnits)) }
        }.toString(),
        skippedDatesJson = JSONArray().apply { skippedDates.sorted().forEach { put(it.toEpochDay()) } }.toString(),
        pauseWindowsJson = JSONArray().apply {
            pauseWindows.forEach { put(JSONObject().put("from", it.from.toEpochDay()).put("until", it.untilExclusive.toEpochDay())) }
        }.toString(),
        pausedSinceEpochDay = pausedSince?.toEpochDay(),
        cancelledFromEpochDay = cancelledFrom?.toEpochDay(),
    )
}

private fun RecurringPaymentEntity.toDomain(tagIds: Set<String>): RecurringPayment {
    val rule = when (ruleType) {
        "WEEKLY" -> RecurrenceRule.Weekly(ruleInterval)
        "MONTHLY" -> RecurrenceRule.Monthly(ruleInterval)
        "YEARLY" -> RecurrenceRule.Yearly(ruleInterval)
        "CUSTOM_DAYS" -> RecurrenceRule.CustomDays(ruleInterval)
        else -> error("Unknown recurrence rule type $ruleType")
    }
    val revisionArray = JSONArray(amountRevisionsJson)
    val revisions = List(revisionArray.length()) { index ->
        revisionArray.getJSONObject(index).let {
            AmountRevision(LocalDate.ofEpochDay(it.getLong("date")), it.getLong("amount"))
        }
    }
    val skippedArray = JSONArray(skippedDatesJson)
    val skipped = List(skippedArray.length()) { LocalDate.ofEpochDay(skippedArray.getLong(it)) }.toSet()
    val pausesArray = JSONArray(pauseWindowsJson)
    val pauses = List(pausesArray.length()) { index ->
        pausesArray.getJSONObject(index).let {
            PauseWindow(LocalDate.ofEpochDay(it.getLong("from")), LocalDate.ofEpochDay(it.getLong("until")))
        }
    }
    return RecurringPayment(
        id = id,
        title = title,
        currency = currencyCode,
        folderId = folderAccountId.toString(),
        merchantId = merchantId?.toString(),
        tagIds = tagIds,
        startsOn = LocalDate.ofEpochDay(startsOnEpochDay),
        rule = rule,
        loggingPolicy = LoggingPolicy.valueOf(loggingPolicy),
        amountRevisions = revisions,
        skippedDates = skipped,
        pauseWindows = pauses,
        pausedSince = pausedSinceEpochDay?.let(LocalDate::ofEpochDay),
        cancelledFrom = cancelledFromEpochDay?.let(LocalDate::ofEpochDay),
    )
}

private fun LoggedOccurrence.toEntity(transactionId: Long) = LoggedOccurrenceEntity(
    recurringPaymentId = occurrence.key.recurringPaymentId,
    dueEpochDay = occurrence.key.dueDate.toEpochDay(),
    title = occurrence.title,
    currencyCode = occurrence.currency,
    amountMinor = occurrence.amountMinorUnits,
    folderAccountId = occurrence.folderId.toLong(),
    merchantId = occurrence.merchantId?.toLong(),
    tagIdsJson = JSONArray().apply { occurrence.tagIds.sorted().forEach(::put) }.toString(),
    loggingPolicy = occurrence.loggingPolicy.name,
    loggedAtEpochMillis = loggedAtEpochMillis,
    origin = origin.name,
    ledgerTransactionId = transactionId,
)

private fun LoggedOccurrenceEntity.toDomain(): LoggedOccurrence {
    val tags = JSONArray(tagIdsJson).let { array ->
        List(array.length()) { array.getString(it) }.toSet()
    }
    return LoggedOccurrence(
        occurrence = ScheduledOccurrence(
            key = OccurrenceKey(recurringPaymentId, LocalDate.ofEpochDay(dueEpochDay)),
            title = title,
            currency = currencyCode,
            amountMinorUnits = amountMinor,
            folderId = folderAccountId.toString(),
            merchantId = merchantId?.toString(),
            tagIds = tags,
            loggingPolicy = LoggingPolicy.valueOf(loggingPolicy),
        ),
        loggedAtEpochMillis = loggedAtEpochMillis,
        origin = LoggingOrigin.valueOf(origin),
    )
}
