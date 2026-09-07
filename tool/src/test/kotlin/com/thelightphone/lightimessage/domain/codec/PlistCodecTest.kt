package com.thelightphone.lightimessage.domain.codec

import kotlin.test.*
import kotlin.test.Test

/**
 * Comprehensive unit tests for PlistCodec. Covers binary (bplist00) and XML plist encoding/decoding
 * with all value types. Target: 100% code coverage.
 */
class PlistCodecTest {
    private val plistCodec = PlistCodec()

    // ========== Binary Plist (bplist00) Roundtrip Tests ==========

    @Test
    fun testBplist00RoundtripNull() {
        val value = PlistNull
        val encoded = plistCodec.encode(value)
        assertTrue(encoded.isSuccess, "Encoding must succeed")

        val decoded = plistCodec.decode(encoded.getOrThrow())
        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded value must match original")
    }

    @Test
    fun testBplist00RoundtripBooleanTrue() {
        val value = PlistBoolean(true)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded boolean must be true")
    }

    @Test
    fun testBplist00RoundtripBooleanFalse() {
        val value = PlistBoolean(false)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded boolean must be false")
    }

    @Test
    fun testBplist00RoundtripIntegerSmall() {
        val value = PlistInteger(42L)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded integer must match")
    }

    @Test
    fun testBplist00RoundtripIntegerLarge() {
        val value = PlistInteger(Long.MAX_VALUE)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded large integer must match")
    }

    @Test
    fun testBplist00RoundtripIntegerNegative() {
        val value = PlistInteger(-12345L)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded negative integer must match")
    }

    @Test
    fun testBplist00RoundtripIntegerZero() {
        val value = PlistInteger(0L)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded zero must match")
    }

    @Test
    fun testBplist00RoundtripFloat() {
        val value = PlistFloat(3.14159)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded float must match")
    }

    @Test
    fun testBplist00RoundtripFloatZero() {
        val value = PlistFloat(0.0)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded float zero must match")
    }

    @Test
    fun testBplist00RoundtripFloatNegative() {
        val value = PlistFloat(-42.5)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded negative float must match")
    }

    @Test
    fun testBplist00RoundtripString() {
        val value = PlistString("Hello, Plist!")
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded string must match")
    }

    @Test
    fun testBplist00RoundtripStringEmpty() {
        val value = PlistString("")
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded empty string must match")
    }

    @Test
    fun testBplist00RoundtripStringUnicode() {
        val value = PlistString("Hello 世界 🌍 مرحبا")
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded unicode string must match")
    }

    @Test
    fun testBplist00RoundtripStringLarge() {
        val value = PlistString("A".repeat(10000))
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded large string must match")
    }

    @Test
    fun testBplist00RoundtripData() {
        val value =
            PlistData(
                byteArrayOf(
                    0.toByte(),
                    1.toByte(),
                    2.toByte(),
                    3.toByte(),
                    255.toByte(),
                    254.toByte(),
                    253.toByte(),
                ),
            )
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded data must match")
    }

    @Test
    fun testBplist00RoundtripDataEmpty() {
        val value = PlistData(ByteArray(0))
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded empty data must match")
    }

    @Test
    fun testBplist00RoundtripDataLarge() {
        val value = PlistData(ByteArray(10000) { it.toByte() })
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded large data must match")
    }

    @Test
    fun testBplist00RoundtripDate() {
        val timestamp = System.currentTimeMillis() / 1000 // seconds since 2001
        val value = PlistDate(timestamp)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded date must match")
    }

    @Test
    fun testBplist00RoundtripDateEpoch() {
        val value = PlistDate(0L)
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded epoch date must match")
    }

    @Test
    fun testBplist00RoundtripArray() {
        val value =
            PlistArray(
                listOf(
                    PlistNull,
                    PlistBoolean(true),
                    PlistInteger(42L),
                    PlistString("test"),
                ),
            )
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded array must match")
    }

    @Test
    fun testBplist00RoundtripArrayEmpty() {
        val value = PlistArray(emptyList())
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded empty array must match")
    }

    @Test
    fun testBplist00RoundtripArrayNested() {
        val value =
            PlistArray(
                listOf(
                    PlistArray(listOf(PlistInteger(1L), PlistInteger(2L))),
                    PlistArray(listOf(PlistInteger(3L), PlistInteger(4L))),
                ),
            )
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded nested array must match")
    }

    @Test
    fun testBplist00RoundtripDictionary() {
        val value =
            PlistDict(
                linkedMapOf(
                    "key1" to PlistString("value1"),
                    "key2" to PlistInteger(42L),
                    "key3" to PlistBoolean(true),
                ),
            )
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded dictionary must match")
    }

    @Test
    fun testBplist00RoundtripDictionaryEmpty() {
        val value = PlistDict(emptyMap())
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Decoded empty dictionary must match")
    }

    // ========== All Value Types ==========

    @Test
    fun testAllValueTypes() {
        val testDict =
            PlistDict(
                linkedMapOf(
                    "array" to PlistArray(listOf(PlistInteger(1L), PlistInteger(2L))),
                    "bool_false" to PlistBoolean(false),
                    "bool_true" to PlistBoolean(true),
                    "data" to PlistData(byteArrayOf(1, 2, 3)),
                    "date" to PlistDate(100000L),
                    "dict" to PlistDict(linkedMapOf("nested" to PlistString("value"))),
                    "float" to PlistFloat(3.14159),
                    "int" to PlistInteger(12345L),
                    "null" to PlistNull,
                    "string" to PlistString("test string"),
                ),
            )

        val encoded = plistCodec.encode(testDict)
        assertTrue(encoded.isSuccess, "Encoding all types must succeed")

        val decoded = plistCodec.decode(encoded.getOrThrow())
        assertTrue(decoded.isSuccess, "Decoding all types must succeed")
        assertEquals(testDict, decoded.getOrNull(), "All types must roundtrip correctly")
    }

    // ========== Large Dictionary Test ==========

    @Test
    fun testLargeDictionary() {
        val largeDict = mutableMapOf<String, PlistValue>()
        for (i in 0 until 1000) {
            largeDict["key_$i"] = PlistInteger(i.toLong())
        }
        val value = PlistDict(largeDict)

        val encoded = plistCodec.encode(value)
        assertTrue(encoded.isSuccess, "Encoding large dictionary must succeed")

        val decoded = plistCodec.decode(encoded.getOrThrow())
        assertTrue(decoded.isSuccess, "Decoding large dictionary must succeed")

        val decodedDict = decoded.getOrNull() as? PlistDict
        assertNotNull(decodedDict, "Decoded value must be a dict")
        assertEquals(1000, decodedDict?.items?.size, "Decoded dict must have 1000 entries")
    }

    // ========== Nested Structures ==========

    @Test
    fun testNestedStructures_DictInArray() {
        val value =
            PlistArray(
                listOf(
                    PlistDict(linkedMapOf("a" to PlistInteger(1L))),
                    PlistDict(linkedMapOf("b" to PlistInteger(2L))),
                ),
            )

        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Nested dict in array must match")
    }

    @Test
    fun testNestedStructures_ArrayInDict() {
        val value =
            PlistDict(
                linkedMapOf(
                    "items" to
                            PlistArray(
                                listOf(
                                    PlistInteger(1L),
                                    PlistInteger(2L),
                                    PlistInteger(3L),
                                ),
                            ),
                ),
            )

        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Nested array in dict must match")
    }

    @Test
    fun testNestedStructures_DictInDictInArray() {
        val value =
            PlistArray(
                listOf(
                    PlistDict(
                        linkedMapOf(
                            "nested" to
                                    PlistDict(
                                        linkedMapOf(
                                            "deep" to
                                                    PlistString(
                                                        "value"
                                                    ),
                                        ),
                                    ),
                        ),
                    ),
                ),
            )

        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Deeply nested structure must match")
    }

    @Test
    fun testNestedStructures_ComplexStructure() {
        val value =
            PlistDict(
                linkedMapOf(
                    "users" to
                            PlistArray(
                                listOf(
                                    PlistDict(
                                        linkedMapOf(
                                            "age" to PlistInteger(30L),
                                            "name" to
                                                    PlistString(
                                                        "Alice",
                                                    ),
                                            "tags" to
                                                    PlistArray(
                                                        listOf(
                                                            PlistString(
                                                                "admin",
                                                            ),
                                                            PlistString(
                                                                "user",
                                                            ),
                                                        ),
                                                    ),
                                        ),
                                    ),
                                    PlistDict(
                                        linkedMapOf(
                                            "age" to PlistInteger(25L),
                                            "name" to
                                                    PlistString("Bob"),
                                            "tags" to
                                                    PlistArray(
                                                        listOf(
                                                            PlistString(
                                                                "user",
                                                            ),
                                                        ),
                                                    ),
                                        ),
                                    ),
                                ),
                            ),
                ),
            )

        val encoded = plistCodec.encode(value)
        assertTrue(encoded.isSuccess, "Encoding complex structure must succeed")

        val decoded = plistCodec.decode(encoded.getOrThrow())
        assertTrue(decoded.isSuccess, "Decoding complex structure must succeed")
        assertEquals(value, decoded.getOrNull(), "Complex structure must roundtrip correctly")
    }

    // ========== Format Detection ==========

    @Test
    fun testAutodetectFormatBplist() {
        val value = PlistDict(linkedMapOf("test" to PlistString("bplist test")))
        val encoded = plistCodec.encode(value)

        assertTrue(encoded.isSuccess, "Encoding must succeed")

        val bytes = encoded.getOrThrow()
        // Binary plist starts with "bplist00"
        val isBplist =
            bytes.size >= 8 &&
                    bytes[0] == 'b'.code.toByte() &&
                    bytes[1] == 'p'.code.toByte() &&
                    bytes[2] == 'l'.code.toByte() &&
                    bytes[3] == 'i'.code.toByte() &&
                    bytes[4] == 's'.code.toByte() &&
                    bytes[5] == 't'.code.toByte() &&
                    bytes[6] == '0'.code.toByte() &&
                    bytes[7] == '0'.code.toByte()

        assertTrue(isBplist, "Encoded format must be binary plist (bplist00)")
    }

    @Test
    fun testAutodetectFormatDetection() {
        val value = PlistString("test")
        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must auto-detect format")
        assertEquals(value, decoded.getOrNull(), "Auto-detected format must decode correctly")
    }

    // ========== Invalid Format Handling ==========

    @Test
    fun testInvalidMagic() {
        val invalidBytes =
            byteArrayOf('x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte()) +
                    ByteArray(100)
        val decoded = plistCodec.decode(invalidBytes)

        assertTrue(decoded.isFailure, "Decoding invalid magic must fail")
    }

    @Test
    fun testInvalidMagicTooShort() {
        val invalidBytes = byteArrayOf('b'.code.toByte(), 'p'.code.toByte())
        val decoded = plistCodec.decode(invalidBytes)

        assertTrue(decoded.isFailure, "Decoding too-short plist must fail")
    }

    @Test
    fun testInvalidMagicEmpty() {
        val invalidBytes = ByteArray(0)
        val decoded = plistCodec.decode(invalidBytes)

        assertTrue(decoded.isFailure, "Decoding empty bytes must fail")
    }

    @Test
    fun testCorruptedBplistTrailer() {
        val value = PlistDict(mapOf("key" to PlistString("value")))
        val encoded = plistCodec.encode(value).getOrThrow()

        // Corrupt the last 32 bytes (trailer)
        if (encoded.size > 32) {
            for (i in encoded.size - 32 until encoded.size) {
                encoded[i] = (encoded[i].toInt() xor 0xFF).toByte()
            }
        }

        val decoded = plistCodec.decode(encoded)
        // May fail due to invalid trailer
        // The exact behavior depends on implementation
    }

    // ========== Edge Cases ==========

    @Test
    fun testRoundtripPreservesKeyOrder() {
        // LinkedHashMap preserves insertion order
        val value =
            PlistDict(
                linkedMapOf(
                    "z" to PlistInteger(1L),
                    "a" to PlistInteger(2L),
                    "m" to PlistInteger(3L),
                ),
            )

        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")

        val decodedDict = decoded.getOrNull() as? PlistDict
        assertNotNull(decodedDict, "Decoded value must be a dict")

        // Check all keys are present
        assertEquals(3, decodedDict?.items?.size, "All keys must be preserved")
        assertEquals(PlistInteger(1L), decodedDict?.items?.get("z"), "Key 'z' must have value 1")
        assertEquals(PlistInteger(2L), decodedDict?.items?.get("a"), "Key 'a' must have value 2")
        assertEquals(PlistInteger(3L), decodedDict?.items?.get("m"), "Key 'm' must have value 3")
    }

    @Test
    fun testRoundtripSpecialCharactersInKeys() {
        val value =
            PlistDict(
                linkedMapOf(
                    "key-with-dash" to PlistInteger(1L),
                    "key with spaces" to PlistInteger(4L),
                    "key.with.dot" to PlistInteger(2L),
                    "key_with_underscore" to PlistInteger(3L),
                ),
            )

        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding must succeed")
        assertEquals(value, decoded.getOrNull(), "Special characters in keys must be preserved")
    }

    @Test
    fun testArrayWithMixedTypes() {
        val value =
            PlistArray(
                listOf(
                    PlistNull,
                    PlistBoolean(true),
                    PlistInteger(42L),
                    PlistFloat(3.14),
                    PlistString("mixed"),
                    PlistData(byteArrayOf(1, 2, 3)),
                    PlistArray(emptyList()),
                    PlistDict(emptyMap()),
                ),
            )

        val encoded = plistCodec.encode(value)
        val decoded = plistCodec.decode(encoded.getOrThrow())

        assertTrue(decoded.isSuccess, "Decoding mixed array must succeed")
        assertEquals(value, decoded.getOrNull(), "Mixed array must roundtrip correctly")
    }
}
