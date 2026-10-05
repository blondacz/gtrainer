package com.gtrainer

import kotlin.test.Test
import kotlin.test.assertFailsWith

class DevelopmentReviewConfigurationTest {
    @Test fun requiresExplicitLoopbackAndValidModelIdentity() {
        assertFailsWith<IllegalArgumentException> { configuration(bind = "0.0.0.0") }
        assertFailsWith<IllegalArgumentException> { configuration(digest = "bad") }
        assertFailsWith<IllegalArgumentException> { configuration(temperature = Double.NaN) }
    }
    private fun configuration(bind:String="127.0.0.1", digest:String="a".repeat(64), temperature:Double=0.2) =
        DevelopmentReviewConfiguration(bind, java.nio.file.Path.of("/var/tmp/dev-review.db"), "ollama", "qwen3",
            "local test", "qwen3:8b", digest, "0.6.0", 2048, 1536, 3, 23, temperature,
            DevelopmentReviewPolicyBinding(900_000, 1_840_000))
}
