package com.pinekone.app.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.cbor.Cbor

object PkFormats {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = false
    }

    @OptIn(ExperimentalSerializationApi::class)
    val cbor: Cbor = Cbor {
        ignoreUnknownKeys = true
    }
}

fun PkEnvelope.toJson(): String = PkFormats.json.encodeToString(this)

fun PkEnvelope.toCbor(): ByteArray = PkFormats.cbor.encodeToByteArray(PkEnvelope.serializer(), this)

fun ByteArray.toEnvelopeFromCbor(): PkEnvelope =
    PkFormats.cbor.decodeFromByteArray(PkEnvelope.serializer(), this)

fun String.toEnvelopeFromJson(): PkEnvelope =
    PkFormats.json.decodeFromString(PkEnvelope.serializer(), this)
