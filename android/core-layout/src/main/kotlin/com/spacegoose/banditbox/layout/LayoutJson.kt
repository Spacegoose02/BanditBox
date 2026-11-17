package com.spacegoose.banditbox.layout

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Reads and writes layout documents. */
object LayoutJson {
    /**
     * Lenient on read: a newer server may add optional fields the TV doesn't know yet.
     * The schema version check below still rejects documents with breaking changes.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = false
    }

    fun decode(text: String): LayoutDocument {
        val doc = json.decodeFromString(LayoutDocument.serializer(), text)
        if (doc.schema != LayoutDocument.SCHEMA_VERSION) {
            throw SerializationException(
                "Unsupported layout schema ${doc.schema}; this build reads ${LayoutDocument.SCHEMA_VERSION}",
            )
        }
        return doc
    }

    fun encode(doc: LayoutDocument): String = json.encodeToString(LayoutDocument.serializer(), doc)
}
