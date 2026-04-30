package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Immutable
@Serializable
data class CredentialEntry(
    val id: String,
    val label: String,
    val fields: Map<String, String> = emptyMap(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@OptIn(ExperimentalTime::class)
class CredentialStore(private val appSettings: AppSettings) {

    private val json = SharedJson
    private val mutex = Mutex()

    private fun load(): MutableList<CredentialEntry> {
        val raw = appSettings.getCredentialsJson()
        if (raw.isBlank()) return mutableListOf()
        return try {
            json.decodeFromString<List<CredentialEntry>>(raw).toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun save(entries: List<CredentialEntry>) =
        appSettings.setCredentialsJson(json.encodeToString(entries))

    fun getAll(): List<CredentialEntry> = load()
    fun getById(id: String): CredentialEntry? = load().firstOrNull { it.id == id }
    fun getByLabel(label: String): CredentialEntry? =
        load().firstOrNull { it.label.equals(label, ignoreCase = true) }

    suspend fun upsert(id: String, label: String, fields: Map<String, String>): CredentialEntry = mutex.withLock {
        val entries = load()
        val now = Clock.System.now().toEpochMilliseconds()
        val idx = entries.indexOfFirst { it.id == id }
        val entry = if (idx >= 0) {
            val updated = entries[idx].copy(label = label, fields = fields, updatedAt = now)
            entries[idx] = updated
            updated
        } else {
            val new = CredentialEntry(id = id, label = label, fields = fields, createdAt = now, updatedAt = now)
            entries.add(new)
            new
        }
        save(entries)
        entry
    }

    suspend fun updateField(id: String, key: String, value: String): CredentialEntry? = mutex.withLock {
        val entries = load()
        val idx = entries.indexOfFirst { it.id == id }
        if (idx < 0) return@withLock null
        val now = Clock.System.now().toEpochMilliseconds()
        val updated = entries[idx].copy(fields = entries[idx].fields + (key to value), updatedAt = now)
        entries[idx] = updated
        save(entries)
        updated
    }

    suspend fun delete(id: String): Boolean = mutex.withLock {
        val entries = load()
        val removed = entries.removeAll { it.id == id }
        if (removed) save(entries)
        removed
    }
}
