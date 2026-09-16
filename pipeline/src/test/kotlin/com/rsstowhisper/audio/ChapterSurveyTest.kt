package com.rsstowhisper.audio

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterSurveyTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `audioChapterJson round-trips a survey exactly, ignoring dump-limit`() {
        val survey =
            ChapterSurvey(
                scanned = 3,
                unreadable = 1,
                chaptered =
                    listOf(
                        ChapteredEpisode(
                            "Show/2024-01-01-abcd1234-Episode",
                            listOf(
                                Id3Chapter("chp0", 0, 12_000, "Intro"),
                                Id3Chapter("chp1", 12_000, 90_000, "Content"),
                            ),
                        ),
                    ),
            )

        val roundTripped: ChapterSurvey = mapper.readValue(audioChapterJson(survey))

        assertEquals(survey, roundTripped)
    }

    @Test
    fun `an empty survey serialises rather than throwing`() {
        val json = audioChapterJson(ChapterSurvey(scanned = 0, unreadable = 0, chaptered = emptyList()))

        assertEquals(ChapterSurvey(0, 0, emptyList()), mapper.readValue(json))
    }
}
