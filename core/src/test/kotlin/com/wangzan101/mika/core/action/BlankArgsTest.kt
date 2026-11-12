package com.wangzan101.mika.core.action

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame

class BlankArgsTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun dropsBlankSelectorsButKeepsText() {
        val o = obj("""{"ref":"","element":" ","x":500,"y":230,"text":"","value":"","target":"search"}""")
        assertEquals(obj("""{"x":500,"y":230,"text":"","target":"search"}"""), withoutBlankStrings(o))
    }

    @Test
    fun unchangedWhenNothingBlank() {
        val o: JsonObject = obj("""{"package":"com.android.settings"}""")
        assertSame(o, withoutBlankStrings(o))
    }
}
