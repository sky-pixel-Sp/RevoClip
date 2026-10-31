package com.revclip.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipSuggestionParserTest {
    @Test
    fun parsesStrictSuggestionArray() {
        val parsed = ClipSuggestionParser.parse(
            """[{"start_sec":12.5,"end_sec":27.0,"title":"Hook","reason":"Strong opening","confidence":0.92}]"""
        )
        assertEquals(1, parsed.size)
        assertEquals(12.5, parsed.single().startSec, 0.0001)
        assertEquals("Hook", parsed.single().title)
        assertEquals(0.92, parsed.single().confidence, 0.0001)
    }

    @Test
    fun parsesArrayInsideJsonCodeFence() {
        val fence = 96.toChar().toString().repeat(3)
        val response = fence + "json\n" +
            """[{"start_sec":0,"end_sec":8,"title":"Intro","reason":"Clear hook","confidence":0.8}]""" +
            "\n" + fence
        assertEquals(1, ClipSuggestionParser.parse(response).size)
    }

    @Test
    fun acceptsEmptyArrayWhenNoClipsAreFound() {
        assertTrue(ClipSuggestionParser.parse("[]").isEmpty())
    }

    @Test(expected = Exception::class)
    fun rejectsMalformedJson() {
        ClipSuggestionParser.parse("""[{"start_sec":0,"end_sec":""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidRange() {
        ClipSuggestionParser.parse(
            """[{"start_sec":10,"end_sec":10,"title":"Bad","reason":"No duration","confidence":0.5}]"""
        )
    }
}

class ClipTimeParserTest {
    @Test
    fun acceptsDecimalSeconds() {
        assertEquals(12.75, ClipTimeParser.parseSeconds("12.75")!!, 0.0001)
    }

    @Test
    fun acceptsMinuteSecondAndFraction() {
        assertEquals(125.5, ClipTimeParser.parseSeconds("02:05.5")!!, 0.0001)
    }

    @Test
    fun rejectsMalformedTimeAndSecondsOver59() {
        assertNull(ClipTimeParser.parseSeconds("1:70"))
        assertNull(ClipTimeParser.parseSeconds("abc"))
        assertNull(ClipTimeParser.parseSeconds("-1"))
        assertEquals(0.0, ClipTimeParser.parseSeconds("0")!!, 0.0001)
    }

    @Test
    fun validatesIncreasingFiniteRanges() {
        assertTrue(ClipRangeValidator.isValid(0.0, 0.1))
        assertFalse(ClipRangeValidator.isValid(2.0, 2.0))
        assertFalse(ClipRangeValidator.isValid(-1.0, 2.0))
        assertFalse(ClipRangeValidator.isValid(Double.NaN, 2.0))
    }
}
