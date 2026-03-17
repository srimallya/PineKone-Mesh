@file:OptIn(ExperimentalSerializationApi::class)

package com.pinekone.app.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.cbor.Cbor

object PkFormats {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = false
    }

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

fun PkControlFrame.toCbor(): ByteArray =
    PkFormats.cbor.encodeToByteArray(PkControlFrame.serializer(), this)

fun ByteArray.toControlFrameFromCbor(): PkControlFrame =
    PkFormats.cbor.decodeFromByteArray(PkControlFrame.serializer(), this)

fun PkControlFrame.toJson(): String =
    PkFormats.json.encodeToString(PkControlFrame.serializer(), this)

fun String.toControlFrameFromJson(): PkControlFrame =
    PkFormats.json.decodeFromString(PkControlFrame.serializer(), this)

fun DecisionReceipt.toCbor(): ByteArray =
    PkFormats.cbor.encodeToByteArray(DecisionReceipt.serializer(), this)

fun ByteArray.toDecisionReceiptFromCbor(): DecisionReceipt =
    PkFormats.cbor.decodeFromByteArray(DecisionReceipt.serializer(), this)

fun CustodyReceiptV2.toCbor(): ByteArray =
    PkFormats.cbor.encodeToByteArray(CustodyReceiptV2.serializer(), this)

fun ByteArray.toCustodyReceiptFromCbor(): CustodyReceiptV2 =
    PkFormats.cbor.decodeFromByteArray(CustodyReceiptV2.serializer(), this)

fun <T> encodeDiagnosticJson(serializer: KSerializer<T>, value: T): String =
    PkFormats.json.encodeToString(serializer, value)

fun <T> decodeDiagnosticJson(serializer: KSerializer<T>, value: String): T =
    PkFormats.json.decodeFromString(serializer, value)
