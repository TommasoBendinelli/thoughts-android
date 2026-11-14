// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import android.content.Context
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.Callable

@Entity(tableName = "ReviewRecord", indices = [Index(value = ["cardId"]), Index(value = ["kind", "source"])])
internal data class ReviewRecordEntity(
    @PrimaryKey val path: String,
    val kind: String,
    val source: Int,
    val entityId: String,
    val cardId: String,
    val payload: String,
)

@Entity(tableName = "ReviewState", indices = [Index(value = ["active", "eligibleAt", "pdfStem"])])
internal data class ReviewStateEntity(
    @PrimaryKey val cardId: String,
    val pdfStem: String,
    val active: Boolean,
    val eligibleAt: Long,
    val dirty: Boolean,
    val documentId: String,
    val dueAt: Long?,
    val reviewCount: Int,
    val currentMark: String,
    val deleted: Boolean,
    val retained: Boolean,
)

@Entity(tableName = "ReviewMeta")
internal data class ReviewMetaEntity(@PrimaryKey val id: Int, val generation: Long)

/** Rebuildable per-card projection; ReviewRecord remains the durable event authority. */
@Entity(tableName = "ReviewSnapshot")
internal data class ReviewSnapshotEntity(@PrimaryKey val cardId: String, val version: Int, val payload: String)

/** Rebuildable dashboard fields, deliberately separate from full history snapshots. */
@Entity(tableName = "ReviewDashboard")
internal data class ReviewDashboardEntity(@PrimaryKey val cardId: String, val version: Int, val payload: String)

/** Durable review authority. Files are read once on upgrade, never on a review tap. */
internal class ReviewDatabase(context: Context) {
    private val room = AppDatabase.getInstance(context)
    private val db get() = room.openHelper.writableDatabase
    fun <T> transaction(block: () -> T): T = room.runInTransaction(Callable { block() })
    fun generation(): Long = db.query("SELECT generation FROM ReviewMeta WHERE id=1").use { if (it.moveToFirst()) it.getLong(0) else -1 }

    fun importOnce(roots: List<Triple<File, String, Int>>) = transaction {
        if (generation() >= 0) return@transaction
        db.execSQL("INSERT INTO ReviewMeta VALUES (1, 0)")
        // Do not mark a partial/unreadable migration complete; the transaction retries safely.
        for ((directory, kind, source) in roots) {
            if (!directory.exists()) continue
            check(directory.isDirectory && directory.canRead()) { "Review history is unavailable: $directory" }
            directory.walkTopDown().filter { it.isFile && it.extension.equals("json", true) }.forEach { file ->
                val payload = JsonParser.parseString(file.readText()).asJsonObject
                put(file.path, kind, source, payload)
            }
        }
        // Older clients may already have queued/acknowledged an operation whose transport file
        // disappeared. Preserve the durable database copy, including its original event identity.
        db.query("SELECT operationId,entityType,payloadJson,deliveryState FROM ThoughtsOutbox WHERE entityType IN ('review_card','review_event') AND deliveryState IN ('pending','blocked','accepted','reconciled')").use { cursor ->
            while (cursor.moveToNext()) {
                val type = cursor.getString(1)
                val pending = cursor.getString(3) in setOf("pending", "blocked")
                if (!pending && type != "review_event") continue
                put("database/outbox/${cursor.getString(0)}", if (type == "review_event") "events" else "cards",
                    if (pending) 2 else 0, JsonParser.parseString(cursor.getString(2)).asJsonObject)
            }
        }
        val eventRoot = roots.firstOrNull { it.second == "events" && it.third == 1 }?.first
        if (eventRoot != null) db.query("SELECT entityId,payloadJson FROM ThoughtsProjection WHERE entityType='review_event' AND deleted=0").use { cursor ->
            while (cursor.moveToNext()) {
                put(File(eventRoot, "server/${cursor.getString(0)}.json").path, "events", 1,
                    JsonParser.parseString(cursor.getString(1)).asJsonObject)
            }
        }
    }

    fun records(cardId: String? = null): List<ReviewRecordEntity> {
        val sql = if (cardId == null) "SELECT * FROM ReviewRecord" else "SELECT * FROM ReviewRecord WHERE cardId=? OR kind='exclusions'"
        return db.query(sql, if (cardId == null) emptyArray() else arrayOf<Any>(cardId)).use { c ->
            buildList { while (c.moveToNext()) add(ReviewRecordEntity(c.getString(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4), c.getString(5))) }
        }
    }

    fun savedSnapshots(version: Int): Map<String, String> = db.query(
        "SELECT p.cardId,p.payload FROM ReviewSnapshot p JOIN ReviewState s ON s.cardId=p.cardId WHERE s.dirty=0 AND p.version=?",
        arrayOf<Any>(version),
    ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }

    fun savedDashboards(version: Int): Map<String, String> = db.query(
        "SELECT p.cardId,p.payload FROM ReviewDashboard p JOIN ReviewState s ON s.cardId=p.cardId WHERE s.dirty=0 AND p.version=?",
        arrayOf<Any>(version),
    ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }

    fun saveDashboard(id: String, version: Int, payload: String) {
        db.execSQL("INSERT OR REPLACE INTO ReviewDashboard VALUES (?,?,?)", arrayOf<Any>(id, version, payload))
    }

    fun cardRecords(): List<ReviewRecordEntity> = db.query("SELECT * FROM ReviewRecord WHERE kind='cards'").use { c ->
        buildList { while (c.moveToNext()) add(ReviewRecordEntity(c.getString(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4), c.getString(5))) }
    }

    fun saveSnapshot(id: String, version: Int, payload: String) {
        db.execSQL("INSERT OR REPLACE INTO ReviewSnapshot VALUES (?,?,?)", arrayOf<Any>(id, version, payload))
    }

    fun pending(kind: String): List<JsonObject> = db.query("SELECT payload FROM ReviewRecord WHERE kind=? AND source=2", arrayOf<Any>(kind)).use { c ->
        buildList { while (c.moveToNext()) add(JsonParser.parseString(c.getString(0)).asJsonObject) }
    }

    fun put(path: String, kind: String, source: Int, payload: JsonObject) {
        val raw = payload.toString()
        val unchanged = db.query("SELECT payload,source FROM ReviewRecord WHERE path=?", arrayOf<Any>(path)).use { it.moveToFirst() && it.getString(0) == raw && it.getInt(1) == source }
        if (unchanged) return
        val cardId = payload.get("card_id")?.asString.orEmpty()
        val id = payload.get(if (kind == "events") "event_id" else if (kind == "cards") "card_id" else "exclusion_id")?.asString ?: path
        db.execSQL("INSERT OR REPLACE INTO ReviewRecord VALUES (?,?,?,?,?,?)", arrayOf<Any>(path, kind, source, id, cardId, raw))
        if (kind == "exclusions") db.execSQL("UPDATE ReviewState SET dirty=1") else {
            db.execSQL("INSERT OR IGNORE INTO ReviewState VALUES (?, '', 0, 0, 1, '', NULL, 0, '', 0, 0)", arrayOf<Any>(cardId))
            db.execSQL("UPDATE ReviewState SET dirty=1 WHERE cardId=?", arrayOf<Any>(cardId))
        }
        db.execSQL("UPDATE ReviewMeta SET generation=generation+1 WHERE id=1")
    }

    fun invalidateLocations() { db.execSQL("UPDATE ReviewState SET dirty=1") }
    fun dirtyCards(): List<String> = db.query("SELECT cardId FROM ReviewState WHERE dirty=1").use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
    fun cardIds(): List<String> = db.query("SELECT DISTINCT cardId FROM ReviewRecord WHERE kind='cards'").use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
    fun saveState(id: String, card: ReviewCard?, active: Boolean, eligibleAt: Long, reviewCount: Int, mark: String) =
        saveState(id, reviewStateRow(card, active, eligibleAt, reviewCount, mark))

    /** The stored state of every card whose records have not changed since it was written. */
    fun cleanStateRows(): Map<String, ReviewStateRow> = db.query(
        "SELECT cardId,pdfStem,active,eligibleAt,documentId,dueAt,reviewCount,currentMark,deleted,retained FROM ReviewState WHERE dirty=0",
    ).use { c ->
        buildMap {
            while (c.moveToNext()) put(
                c.getString(0),
                ReviewStateRow(
                    pdfStem = c.getString(1), active = c.getInt(2) != 0, eligibleAt = c.getLong(3),
                    documentId = c.getString(4), dueAt = if (c.isNull(5)) null else c.getLong(5),
                    reviewCount = c.getInt(6), currentMark = c.getString(7),
                    deleted = c.getInt(8) != 0, retained = c.getInt(9) != 0,
                ),
            )
        }
    }

    /** Writes the card's state and clears its dirty flag. */
    fun saveState(id: String, row: ReviewStateRow) {
        db.execSQL("INSERT OR REPLACE INTO ReviewState VALUES (?,?,?,?,0,?,?,?,?,?,?)", arrayOf<Any?>(id, row.pdfStem, if (row.active) 1 else 0, row.eligibleAt,
            row.documentId, row.dueAt, row.reviewCount, row.currentMark, if (row.deleted) 1 else 0, if (row.retained) 1 else 0))
    }
    fun dueIds(now: Long, stem: String?): Set<String> {
        val sql = "SELECT cardId FROM ReviewState WHERE active=1 AND eligibleAt<=?" + if (stem == null) "" else " AND pdfStem=?"
        return db.query(sql, if (stem == null) arrayOf<Any>(now) else arrayOf<Any>(now, stem)).use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
    }

    /** Drop a local card edit Fedora refused; the server copy stays authoritative. */
    fun discardRejected(kind: String, id: String, payload: String): Boolean = transaction {
        val expected = JsonParser.parseString(payload)
        val matches = db.query("SELECT path,payload FROM ReviewRecord WHERE source=2 AND kind=? AND entityId=?", arrayOf<Any>(kind, id)).use { c ->
            buildList { while(c.moveToNext()) if(JsonParser.parseString(c.getString(1)) == expected) add(c.getString(0)) }
        }
        for (path in matches) {
            db.execSQL("DELETE FROM ReviewRecord WHERE path=?", arrayOf<Any>(path))
            db.execSQL("UPDATE ReviewState SET dirty=1 WHERE cardId=?", arrayOf<Any>(id))
            db.execSQL("UPDATE ReviewMeta SET generation=generation+1 WHERE id=1")
        }
        matches.isNotEmpty()
    }

    /** Replace this device's refused pending record with [replacement], which Sync captures as a new operation. */
    fun replaceRejected(kind: String, id: String, payload: String, replacement: JsonObject): Boolean = transaction {
        val expected = JsonParser.parseString(payload)
        val matches = db.query("SELECT path,payload FROM ReviewRecord WHERE source=2 AND kind=? AND entityId=?", arrayOf<Any>(kind, id)).use { c ->
            buildList { while(c.moveToNext()) if(JsonParser.parseString(c.getString(1)) == expected) add(c.getString(0)) }
        }
        for (path in matches) put(path, kind, 2, replacement)
        matches.isNotEmpty()
    }

    fun acknowledge(kind: String, id: String, payload: String, serverPath: String) = transaction {
        // Keep acknowledged local history until and after Sync. A newer edit must stay pending.
        val expected = JsonParser.parseString(payload)
        val matches = db.query("SELECT path,payload FROM ReviewRecord WHERE source=2 AND kind=? AND entityId=?", arrayOf<Any>(kind, id)).use { c ->
            buildList { while(c.moveToNext()) if(JsonParser.parseString(c.getString(1)) == expected) add(c.getString(0)) }
        }
        for (path in matches) {
            if (kind == "events") put(path, kind, 0, expected.asJsonObject)
            else {
                val currentRevision = db.query("SELECT payload FROM ReviewRecord WHERE path=?", arrayOf<Any>(serverPath)).use {
                    if (it.moveToFirst()) JsonParser.parseString(it.getString(0)).asJsonObject.get("revision")?.asInt ?: 0 else -1
                }
                if (currentRevision < (expected.asJsonObject.get("revision")?.asInt ?: 0)) put(serverPath, kind, 1, expected.asJsonObject)
                db.execSQL("DELETE FROM ReviewRecord WHERE path=?", arrayOf<Any>(path))
                db.execSQL("UPDATE ReviewState SET dirty=1 WHERE cardId=?", arrayOf<Any>(id))
                db.execSQL("UPDATE ReviewMeta SET generation=generation+1 WHERE id=1")
            }
        }
    }

    fun replaceServer(records: List<Triple<String, String, JsonObject>>, retainedPaths: Set<String>) = transaction {
        val paths = records.mapTo(hashSetOf()) { it.first }.apply { addAll(retainedPaths) }
        val removed = db.query("SELECT path,cardId,kind FROM ReviewRecord WHERE source=1").use { c ->
            buildList { while(c.moveToNext()) if(c.getString(0) !in paths) add(Triple(c.getString(0), c.getString(1), c.getString(2))) }
        }
        for ((path, cardId, kind) in removed) {
            db.execSQL("DELETE FROM ReviewRecord WHERE path=?", arrayOf<Any>(path))
            if(kind == "exclusions") invalidateLocations() else db.execSQL("UPDATE ReviewState SET dirty=1 WHERE cardId=?", arrayOf<Any>(cardId))
            db.execSQL("UPDATE ReviewMeta SET generation=generation+1 WHERE id=1")
        }
        records.forEach { (path, kind, payload) ->
            val olderCard = kind == "cards" && db.query("SELECT payload FROM ReviewRecord WHERE path=?", arrayOf<Any>(path)).use {
                it.moveToFirst() && (JsonParser.parseString(it.getString(0)).asJsonObject.get("revision")?.asInt ?: 0) > (payload.get("revision")?.asInt ?: 0)
            }
            if (!olderCard) put(path, kind, 1, payload)
        }
    }
}
