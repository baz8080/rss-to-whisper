package com.rsstowhisper.web

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** The only test that starts the container, and so the only one that resolves config properties. */
@QuarkusTest
class ApplicationStartupTest {
    @Inject
    lateinit var resource: SearchResource

    @Test
    fun `the application starts with a data URL resolved`() {
        assertFalse(resource.dataUrl.isBlank())
    }
}
