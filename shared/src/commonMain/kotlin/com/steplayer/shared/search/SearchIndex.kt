package com.steplayer.shared.search

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.steplayer.shared.db.StePlayerDatabase
import com.steplayer.shared.util.normalizeForSearch

/**
 * Per-source contentless FTS4 indexes. Contentless = only the inverted index is stored (no copy
 * of the text), which keeps the DB small even with hundreds of thousands of EPG programmes.
 *
 * Text is normalised in Kotlin (lowercase, no accents) before indexing, so SQLite's built-in
 * "simple" tokenizer is enough — it exists on every Android/iOS SQLite build.
 *
 * catalog_fts_<id>: docid = rowid * 4 + kind (0 channel, 1 movie, 2 series)
 * epg_fts_<id>:     docid = epg_program.rowid
 */
internal class SearchIndex(
    private val driver: SqlDriver,
    private val db: StePlayerDatabase,
) {
    private fun catalog(id: Long) = "catalog_fts_$id"
    private fun catalogTerms(id: Long) = "catalog_terms_$id"
    private fun epg(id: Long) = "epg_fts_$id"
    private fun epgTerms(id: Long) = "epg_terms_$id"

    private fun exec(sql: String) {
        driver.execute(null, sql, 0)
    }

    /** Stable statement-cache identifier for hot, repeated raw statements. */
    private fun idFor(sql: String) = sql.hashCode() xor 0x5EA4C11

    fun exists(table: String): Boolean = driver.executeQuery(
        null, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
        { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) }, 1,
    ) { bindString(0, table) }.value > 0

    fun hasCatalog(sourceId: Long) = exists(catalog(sourceId))
    fun hasEpg(sourceId: Long) = exists(epg(sourceId))

    // ------------------------------------------------------------------ build

    fun rebuildCatalog(sourceId: Long) {
        val t = catalog(sourceId)
        db.transaction {
            exec("DROP TABLE IF EXISTS ${catalogTerms(sourceId)}")
            exec("DROP TABLE IF EXISTS $t")
            exec("CREATE VIRTUAL TABLE $t USING fts4(content=\"\", text)")
            // Channels: name + channel number. Movies: name. (Already normalised in search_name.)
            exec("INSERT INTO $t(docid, text) SELECT rowid * 4, search_name || ' ' || COALESCE(num, '') FROM channel WHERE source_id = $sourceId")
            exec("INSERT INTO $t(docid, text) SELECT rowid * 4 + 1, search_name FROM movie WHERE source_id = $sourceId")
            // Series: name + genre + plot, normalised here.
            val insert = "INSERT INTO $t(docid, text) VALUES (?, ?)"
            var last = 0L
            while (true) {
                val page = page(
                    "SELECT rowid, search_name, genre, plot FROM series WHERE source_id = ? AND rowid > ? ORDER BY rowid LIMIT $PAGE",
                    sourceId, last,
                ) { c -> c.getString(1).orEmpty() + " " + normalizeForSearch(c.getString(2).orEmpty() + " " + c.getString(3).orEmpty().take(600)) }
                if (page.isEmpty()) break
                page.forEach { (rowid, text) ->
                    driver.execute(idFor(insert + t), insert, 2) { bindLong(0, rowid * 4 + 2); bindString(1, text) }
                }
                last = page.last().first
            }
        }
    }

    fun rebuildEpg(sourceId: Long) {
        val t = epg(sourceId)
        db.transaction {
            exec("DROP TABLE IF EXISTS ${epgTerms(sourceId)}")
            exec("DROP TABLE IF EXISTS $t")
            exec("CREATE VIRTUAL TABLE $t USING fts4(content=\"\", text)")
        }
        val insert = "INSERT INTO $t(docid, text) VALUES (?, ?)"
        var last = 0L
        while (true) {
            val page = page(
                "SELECT rowid, title, description FROM epg_program WHERE source_id = ? AND rowid > ? ORDER BY rowid LIMIT $PAGE",
                sourceId, last,
            ) { c -> normalizeForSearch(c.getString(1).orEmpty() + " " + c.getString(2).orEmpty().take(400)) }
            if (page.isEmpty()) break
            // Short transactions so foreground writes (e.g. progress saves) are never blocked for long.
            db.transaction {
                page.forEach { (rowid, text) ->
                    driver.execute(idFor(insert + t), insert, 2) { bindLong(0, rowid); bindString(1, text) }
                }
            }
            last = page.last().first
        }
    }

    /** Keyset-paged read of (rowid, text) pairs; [text] builds the indexed text from the cursor. */
    private fun page(sql: String, sourceId: Long, after: Long, text: (app.cash.sqldelight.db.SqlCursor) -> String): List<Pair<Long, String>> =
        driver.executeQuery(null, sql, { c ->
            val out = ArrayList<Pair<Long, String>>()
            while (c.next().value) out += (c.getLong(0) ?: 0L) to text(c)
            QueryResult.Value(out)
        }, 2) { bindLong(0, sourceId); bindLong(1, after) }.value

    fun drop(sourceId: Long) {
        listOf(catalogTerms(sourceId), catalog(sourceId), epgTerms(sourceId), epg(sourceId))
            .forEach { exec("DROP TABLE IF EXISTS $it") }
    }

    // ------------------------------------------------------------------ query

    private fun longs(sql: String, binder: app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit, params: Int): List<Long> =
        driver.executeQuery(null, sql, { c ->
            val out = ArrayList<Long>()
            while (c.next().value) c.getLong(0)?.let(out::add)
            QueryResult.Value(out)
        }, params, binder).value

    fun matchCatalog(sourceId: Long, expression: String, limit: Int): List<Long> {
        if (!hasCatalog(sourceId)) return emptyList()
        return longs("SELECT docid FROM ${catalog(sourceId)} WHERE text MATCH ? LIMIT $limit", { bindString(0, expression) }, 1)
    }

    /** Matching programme rowids inside [from, to): live/upcoming first (chronological), then most recent past. */
    fun matchPrograms(sourceId: Long, expression: String, from: Long, to: Long, now: Long, limit: Int): List<Long> {
        if (!hasEpg(sourceId)) return emptyList()
        val sql = """
            SELECT p.rowid FROM ${epg(sourceId)} f JOIN epg_program p ON p.rowid = f.docid
            WHERE f.text MATCH ? AND p.end_at > ? AND p.start_at < ?
            ORDER BY CASE WHEN p.end_at > ? THEN 0 ELSE 1 END,
                     CASE WHEN p.end_at > ? THEN p.start_at ELSE -p.start_at END
            LIMIT $limit
        """.trimIndent()
        return longs(sql, {
            bindString(0, expression); bindLong(1, from); bindLong(2, to); bindLong(3, now); bindLong(4, now)
        }, 5)
    }

    // ------------------------------------------------------------------ vocabulary (typo correction)

    private fun ensureTerms(sourceId: Long): List<String> {
        val out = ArrayList<String>()
        if (hasCatalog(sourceId)) {
            exec("CREATE VIRTUAL TABLE IF NOT EXISTS ${catalogTerms(sourceId)} USING fts4aux(${catalog(sourceId)})")
            out += catalogTerms(sourceId)
        }
        if (hasEpg(sourceId)) {
            exec("CREATE VIRTUAL TABLE IF NOT EXISTS ${epgTerms(sourceId)} USING fts4aux(${epg(sourceId)})")
            out += epgTerms(sourceId)
        }
        return out
    }

    private fun anyTermWithPrefix(tables: List<String>, prefix: String): Boolean = tables.any { t ->
        longs("SELECT 1 FROM $t WHERE col = '*' AND term >= ? AND term < ? LIMIT 1", {
            bindString(0, prefix); bindString(1, prefix + '￿')
        }, 2).isNotEmpty()
    }

    /**
     * For every token that matches nothing, finds the closest indexed word (same first letter,
     * Damerau-Levenshtein ≤ 1 for short words, ≤ 2 for longer ones; ties → most frequent word).
     * Returns null if nothing could be corrected.
     */
    fun correct(sourceId: Long, tokens: List<String>): List<String>? = runCatching {
        val tables = ensureTerms(sourceId)
        if (tables.isEmpty()) return null
        var changed = false
        val corrected = tokens.map { token ->
            if (token.length < 3 || token.all { it.isDigit() } || anyTermWithPrefix(tables, QueryParser.stem(token))) {
                return@map token
            }
            val max = if (token.length <= 4) 1 else 2
            val first = token.first()
            var best: String? = null
            var bestDist = Int.MAX_VALUE
            var bestDocs = -1L
            tables.forEach { t ->
                driver.executeQuery(null,
                    "SELECT term, documents FROM $t WHERE col = '*' AND term >= ? AND term < ? AND length(term) BETWEEN ? AND ? LIMIT 50000",
                    { c ->
                        while (c.next().value) {
                            val term = c.getString(0) ?: continue
                            val docs = c.getLong(1) ?: 0
                            val d = editDistance(token, term, max)
                            if (d <= max && (d < bestDist || (d == bestDist && docs > bestDocs))) {
                                best = term; bestDist = d; bestDocs = docs
                            }
                        }
                        QueryResult.Unit
                    }, 4,
                ) {
                    bindString(0, first.toString()); bindString(1, (first + 1).toString())
                    bindLong(2, (token.length - max).toLong()); bindLong(3, (token.length + max).toLong())
                }
            }
            best?.also { changed = true } ?: token
        }
        if (changed) corrected else null
    }.getOrNull()

    private companion object {
        const val PAGE = 5000L
    }
}
