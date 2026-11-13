// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.usage

import android.content.Context
import android.util.Log
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.annotation.SourceKey
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingUsageDocument
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingUsagePageDay
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingUsagePageTotal
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReadingUsageRange {
    TODAY,
    LAST_SEVEN_DAYS,
    ALL_TIME,
}

data class ReadingUsageContext(
    val documentKey: String,
    val uri: String,
    val title: String,
    val pageCount: Int,
    val visiblePages: List<Int>,
)

data class ReadingUsageInterval(
    val context: ReadingUsageContext,
    val epochDay: Long,
    val durationMillis: Long,
    val recordedAtMillis: Long,
)

data class ReadingPageUsageSnapshot(
    val pageIndex: Int,
    val todayMillis: Long,
    val lastSevenDaysMillis: Long,
    val totalMillis: Long,
) {
    fun duration(range: ReadingUsageRange): Long = when (range) {
        ReadingUsageRange.TODAY -> todayMillis
        ReadingUsageRange.LAST_SEVEN_DAYS -> lastSevenDaysMillis
        ReadingUsageRange.ALL_TIME -> totalMillis
    }
}

data class ReadingDocumentUsageSnapshot(
    val documentKey: String,
    val uri: String,
    val title: String,
    val pageCount: Int,
    val lastReadAtMillis: Long,
    val pages: List<ReadingPageUsageSnapshot>,
) {
    fun duration(range: ReadingUsageRange): Long = pages.sumOf { it.duration(range) }
}

data class ReadingUsageSnapshot(
    val documents: List<ReadingDocumentUsageSnapshot>,
)

internal fun splitReadingDuration(durationMillis: Long, pages: List<Int>): Map<Int, Long> {
    val normalizedPages = pages.distinct().filter { it >= 0 }.sorted()
    if (durationMillis <= 0L || normalizedPages.isEmpty()) return emptyMap()
    val perPage = durationMillis / normalizedPages.size
    var remainder = durationMillis % normalizedPages.size
    return normalizedPages.associateWith {
        perPage + if (remainder > 0L) {
            remainder--
            1L
        } else {
            0L
        }
    }
}

class ReadingUsageRepository private constructor(
    private val database: AppDatabase,
) {
    private val dao = database.readingUsageDao()

    suspend fun record(interval: ReadingUsageInterval) = withContext(Dispatchers.IO) {
        val shares = splitReadingDuration(interval.durationMillis, interval.context.visiblePages)
        if (shares.isEmpty()) return@withContext
        database.runInTransaction {
            upsertDocument(interval)
            shares.forEach { (pageIndex, durationMillis) ->
                addPageTotal(interval.context.documentKey, pageIndex, durationMillis)
                addPageDay(interval.context.documentKey, pageIndex, interval.epochDay, durationMillis)
            }
            dao.pruneDaysBefore(interval.epochDay - (ROLLING_DAYS - 1))
        }
    }

    suspend fun snapshot(
        todayEpochDay: Long = LocalDate.now().toEpochDay(),
    ): ReadingUsageSnapshot = withContext(Dispatchers.IO) {
        val earliestEpochDay = todayEpochDay - (ROLLING_DAYS - 1)
        database.runInTransaction { dao.pruneDaysBefore(earliestEpochDay) }
        buildReadingUsageSnapshot(
            documents = dao.findAllDocuments(),
            totals = dao.findAllPageTotals(),
            days = dao.findPageDaysSince(earliestEpochDay),
            todayEpochDay = todayEpochDay,
        )
    }

    suspend fun deleteDocument(documentKey: String) = withContext(Dispatchers.IO) {
        database.runInTransaction { dao.deleteDocument(documentKey) }
        Unit
    }

    suspend fun deleteDocumentUri(uri: android.net.Uri) {
        deleteDocument(SourceKey.of(uri))
    }

    suspend fun mergeDocument(
        oldDocumentKey: String,
        newContext: ReadingUsageContext,
    ) = withContext(Dispatchers.IO) {
        if (oldDocumentKey == newContext.documentKey) return@withContext
        database.runInTransaction {
            val oldDocument = dao.findDocument(oldDocumentKey) ?: return@runInTransaction
            val metadataInterval = ReadingUsageInterval(
                context = newContext,
                epochDay = LocalDate.now().toEpochDay(),
                durationMillis = 0L,
                recordedAtMillis = maxOf(oldDocument.lastReadAtMillis, System.currentTimeMillis()),
            )
            upsertDocument(metadataInterval)
            dao.findPageTotals(oldDocumentKey).forEach { oldTotal ->
                addPageTotal(newContext.documentKey, oldTotal.pageIndex, oldTotal.durationMillis)
            }
            dao.findPageDays(oldDocumentKey).forEach { oldDay ->
                addPageDay(
                    newContext.documentKey,
                    oldDay.pageIndex,
                    oldDay.epochDay,
                    oldDay.durationMillis,
                )
            }
            dao.deleteDocument(oldDocumentKey)
        }
    }

    private fun upsertDocument(interval: ReadingUsageInterval) {
        val context = interval.context
        val document = ReadingUsageDocument(
            documentKey = context.documentKey,
            uri = context.uri,
            title = context.title,
            pageCount = context.pageCount,
            lastReadAtMillis = interval.recordedAtMillis,
        )
        if (dao.insertDocument(document) == -1L) {
            dao.updateDocument(
                documentKey = document.documentKey,
                uri = document.uri,
                title = document.title,
                pageCount = document.pageCount,
                lastReadAtMillis = document.lastReadAtMillis,
            )
        }
    }

    private fun addPageTotal(documentKey: String, pageIndex: Int, durationMillis: Long) {
        val updated = saturatedAdd(dao.findPageTotalMillis(documentKey, pageIndex) ?: 0L, durationMillis)
        val inserted = dao.insertPageTotal(
            ReadingUsagePageTotal(documentKey, pageIndex, updated)
        )
        if (inserted == -1L) dao.updatePageTotal(documentKey, pageIndex, updated)
    }

    private fun addPageDay(
        documentKey: String,
        pageIndex: Int,
        epochDay: Long,
        durationMillis: Long,
    ) {
        val updated = saturatedAdd(
            dao.findPageDayMillis(documentKey, pageIndex, epochDay) ?: 0L,
            durationMillis,
        )
        val inserted = dao.insertPageDay(
            ReadingUsagePageDay(documentKey, pageIndex, epochDay, updated)
        )
        if (inserted == -1L) dao.updatePageDay(documentKey, pageIndex, epochDay, updated)
    }

    private fun saturatedAdd(first: Long, second: Long): Long =
        if (second > 0L && Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second

    companion object {
        private const val ROLLING_DAYS = 7L

        @Volatile
        private var instance: ReadingUsageRepository? = null

        fun getInstance(context: Context): ReadingUsageRepository =
            instance ?: synchronized(this) {
                instance ?: ReadingUsageRepository(AppDatabase.getInstance(context.applicationContext))
                    .also { instance = it }
            }
    }
}

internal fun buildReadingUsageSnapshot(
    documents: List<ReadingUsageDocument>,
    totals: List<ReadingUsagePageTotal>,
    days: List<ReadingUsagePageDay>,
    todayEpochDay: Long,
): ReadingUsageSnapshot {
    val totalsByPage = totals.associateBy { it.documentKey to it.pageIndex }
    val daysByPage = days.groupBy { it.documentKey to it.pageIndex }
    return ReadingUsageSnapshot(
        documents.map { document ->
            val pageKeys = totalsByPage.keys.asSequence()
                .filter { it.first == document.documentKey }
                .map { it.second }
                .sorted()
                .toList()
            ReadingDocumentUsageSnapshot(
                documentKey = document.documentKey,
                uri = document.uri,
                title = document.title,
                pageCount = document.pageCount,
                lastReadAtMillis = document.lastReadAtMillis,
                pages = pageKeys.map { pageIndex ->
                    val pageDays = daysByPage[document.documentKey to pageIndex].orEmpty()
                    ReadingPageUsageSnapshot(
                        pageIndex = pageIndex,
                        todayMillis = pageDays
                            .filter { it.epochDay == todayEpochDay }
                            .sumOf { it.durationMillis },
                        lastSevenDaysMillis = pageDays.sumOf { it.durationMillis },
                        totalMillis = totalsByPage.getValue(
                            document.documentKey to pageIndex
                        ).durationMillis,
                    )
                },
            )
        },
    )
}

class ReadingUsageService private constructor(
    private val repository: ReadingUsageRepository,
) {
    private sealed interface Command {
        data class Record(val interval: ReadingUsageInterval) : Command
        data class Snapshot(val result: CompletableDeferred<ReadingUsageSnapshot>) : Command
        data class DeleteDocument(val documentKey: String, val result: CompletableDeferred<Unit>) : Command
        data class Merge(
            val oldDocumentKey: String,
            val newContext: ReadingUsageContext,
            val result: CompletableDeferred<Unit>,
        ) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for (command in commands) {
                try {
                    when (command) {
                        is Command.Record -> repository.record(command.interval)
                        is Command.Snapshot -> command.result.complete(repository.snapshot())
                        is Command.DeleteDocument -> {
                            repository.deleteDocument(command.documentKey)
                            command.result.complete(Unit)
                        }
                        is Command.Merge -> {
                            repository.mergeDocument(command.oldDocumentKey, command.newContext)
                            command.result.complete(Unit)
                        }
                    }
                } catch (error: Throwable) {
                    when (command) {
                        is Command.Record -> Log.e(TAG, "Failed to persist reading usage", error)
                        is Command.Snapshot -> command.result.completeExceptionally(error)
                        is Command.DeleteDocument -> command.result.completeExceptionally(error)
                        is Command.Merge -> command.result.completeExceptionally(error)
                    }
                }
            }
        }
    }

    fun record(interval: ReadingUsageInterval) {
        if (!commands.trySend(Command.Record(interval)).isSuccess) {
            Log.e(TAG, "Failed to queue reading usage")
        }
    }

    suspend fun snapshot(): ReadingUsageSnapshot = request { Command.Snapshot(it) }

    suspend fun deleteDocument(documentKey: String) {
        request<Unit> { Command.DeleteDocument(documentKey, it) }
    }

    suspend fun deleteDocumentUri(uri: android.net.Uri) {
        deleteDocument(SourceKey.of(uri))
    }

    suspend fun mergeDocument(oldDocumentKey: String, newContext: ReadingUsageContext) {
        request<Unit> { Command.Merge(oldDocumentKey, newContext, it) }
    }

    private suspend fun <Result> request(command: (CompletableDeferred<Result>) -> Command): Result {
        val result = CompletableDeferred<Result>()
        commands.send(command(result))
        return result.await()
    }

    companion object {
        private const val TAG = "ReadingUsageService"

        @Volatile
        private var instance: ReadingUsageService? = null

        fun getInstance(context: Context): ReadingUsageService =
            instance ?: synchronized(this) {
                instance ?: ReadingUsageService(ReadingUsageRepository.getInstance(context))
                    .also { instance = it }
            }
    }
}
