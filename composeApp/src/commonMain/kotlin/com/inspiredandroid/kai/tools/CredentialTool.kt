package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.CredentialStore
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Gives the AI full CRUD access to the user's credential vault.
 * Stored in encrypted AppSettings. Operations: list, get, set, update_field, delete.
 */
class CredentialTool(private val store: CredentialStore) : Tool {

    companion object {
        val toolInfo = ToolInfo(
            id = "credentials",
            name = "Credential Vault",
            description = "Read, store and manage saved login info (usernames, passwords, tokens, URLs).",
        )
    }

    override val schema = ToolSchema(
        name = "credentials",
        description = """Manage the user's credential vault. Each entry has a label and arbitrary key-value fields.
Operations:
- list: Return all entries (labels + ids, no field values)
- get: Return all fields for a specific entry by id or label
- set: Create or fully replace an entry — provide label + fields map
- update_field: Update a single field on an existing entry by id
- delete: Remove an entry by id""",
        parameters = mapOf(
            "operation" to ParameterSchema("string", "list | get | set | update_field | delete", true),
            "id" to ParameterSchema("string", "Entry ID. Required for get/update_field/delete. Auto-generated for set if omitted.", false),
            "label" to ParameterSchema("string", "Human-readable name (e.g. GitHub). Required for set; also used as lookup in get.", false),
            "fields" to ParameterSchema("object", "Map of field names to values e.g. {"username":"alice","password":"s3cr3t"}. Required for set.", false),
            "field_key" to ParameterSchema("string", "Field key to update. Required for update_field.", false),
            "field_value" to ParameterSchema("string", "New value for the field. Required for update_field.", false),
        ),
    )

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(args: Map<String, Any>): Any {
        return when (val op = args["operation"]?.toString()) {
            "list" -> store.getAll().map {
                mapOf("id" to it.id, "label" to it.label, "field_keys" to it.fields.keys.toList())
            }
            "get" -> {
                val entry = args["id"]?.toString()?.let { store.getById(it) }
                    ?: args["label"]?.toString()?.let { store.getByLabel(it) }
                    ?: return mapOf("success" to false, "error" to "Entry not found")
                mapOf("success" to true, "id" to entry.id, "label" to entry.label, "fields" to entry.fields)
            }
            "set" -> {
                val label = args["label"]?.toString()
                    ?: return mapOf("success" to false, "error" to "label is required")
                @Suppress("UNCHECKED_CAST")
                val fields = (args["fields"] as? Map<String, String>) ?: emptyMap()
                val id = args["id"]?.toString() ?: Uuid.random().toString()
                val entry = store.upsert(id, label, fields)
                mapOf("success" to true, "id" to entry.id, "label" to entry.label)
            }
            "update_field" -> {
                val id = args["id"]?.toString() ?: return mapOf("success" to false, "error" to "id is required")
                val key = args["field_key"]?.toString() ?: return mapOf("success" to false, "error" to "field_key is required")
                val value = args["field_value"]?.toString() ?: return mapOf("success" to false, "error" to "field_value is required")
                val updated = store.updateField(id, key, value) ?: return mapOf("success" to false, "error" to "Entry $id not found")
                mapOf("success" to true, "id" to updated.id, "label" to updated.label, "updated_field" to key)
            }
            "delete" -> {
                val id = args["id"]?.toString() ?: return mapOf("success" to false, "error" to "id is required")
                mapOf("success" to store.delete(id))
            }
            else -> mapOf("success" to false, "error" to "Unknown operation: $op")
        }
    }
}
