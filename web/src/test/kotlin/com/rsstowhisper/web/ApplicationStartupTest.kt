package com.rsstowhisper.web

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The only test that starts the container.
 *
 * Everything else here calls resources directly on a hand-built object, which
 * never resolves a config property -- and an optional one declared the wrong
 * way stops the server booting at all rather than failing a request. No
 * `app.data.directory` is set for this module's tests, so reaching the body of
 * this test at all is the assertion that matters.
 */
@QuarkusTest
class ApplicationStartupTest {
    @Inject
    lateinit var resource: SearchResource

    @Test
    fun `the application starts with no data directory configured`() {
        assertTrue(resource.dataDirectory.orElse("").isBlank())
    }
}
