package com.gtrainer

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ExactHashReuseTest {
    @Test fun sharedUtf8HashPreservesBothFormerHexEncodingsWithoutRecanonicalization() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",AnalysisClaims.hash(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",AnalysisClaims.hash("abc"))
        for(value in listOf("Synthetic source", "Attribution 🏊 — příklad", "-1:0:\n3:run", "\u0000\n\r", "é", "e\u0301")) {
            val bytes=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            assertEquals(bytes.joinToString("") { "%02x".format(it) },AnalysisClaims.hash(value))
            assertEquals(bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) },AnalysisClaims.hash(value))
        }
        assertNotEquals(AnalysisClaims.hash("é"),AnalysisClaims.hash("e\u0301"))
    }

    @Test fun sourceTupleIdentityRetainsExactJsonEncodingAndOpaqueDelimiters() {
        for((source,id) in listOf("source" to "record", "a:b" to "c", "a" to "b:c", "quoted\"" to "🛶\n"))
            assertEquals(AnalysisClaims.hash(Json.encodeToString(listOf(source,id))),scopedActivityId(source,id))
        assertNotEquals(scopedActivityId("a:b","c"),scopedActivityId("a","b:c"))
    }
}
