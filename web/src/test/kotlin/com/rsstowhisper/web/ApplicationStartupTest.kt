package com.rsstowhisper.web

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The only test that starts the container, and so the only one that resolves config properties. */
@QuarkusTest
class ApplicationStartupTest {
    @Inject
    lateinit var resource: SearchResource

    @Test
    fun `the application starts with the default data URL`() {
        assertEquals("/audio", resource.dataUrl)
    }
}
