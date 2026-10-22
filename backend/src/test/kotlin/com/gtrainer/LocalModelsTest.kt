package com.gtrainer

import kotlin.test.*

class LocalModelsTest {
    @Test fun `local configuration retains endpoint artifact and catalogue restrictions`() {
        for (endpoint in listOf("https://hosted.invalid", "http://127.0.0.1:11434/", "http://localhost:11434",
            "http://user:secret@127.0.0.1:11434", "http://192.168.1.1:11434", "http://127.0.0.1:11434?proxy=remote")) {
            assertFailsWith<IllegalArgumentException> { LocalModelConfiguration(endpoint, listOf(syntheticModelOption)) }
        }
        assertFailsWith<IllegalArgumentException> { syntheticModelOption.copy(tag = "test:cloud") }
        assertFailsWith<IllegalArgumentException> { syntheticModelOption.copy(digest = "bad") }
        assertFailsWith<IllegalArgumentException> { syntheticModelOption.copy(id = "bad/id") }
        assertFailsWith<IllegalArgumentException> { syntheticModelOption.copy(label = "<script>") }
        assertFailsWith<IllegalArgumentException> { syntheticModelOption.copy(experimental = false) }
        assertFailsWith<IllegalArgumentException> { LocalModelConfiguration("http://127.0.0.1:11434", listOf(syntheticModelOption, syntheticModelOption)) }
        assertFailsWith<IllegalArgumentException> { LocalModelConfiguration("http://127.0.0.1:11434", emptyList()) }
        assertEquals("0.35.0", LocalModelConfiguration.RUNTIME_VERSION)
    }
}
