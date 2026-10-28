package com.nvivio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {
    @Test
    fun stringsComeBackAsTheyWere() {
        val s = "quote \" backslash \\ slash / newline \n tab \t bell \u0007 é › 😀"
        val json = Json.encode(mapOf("s" to s))

        assertEquals(s, JSONObject(json).getString("s"))
        assertTrue(json.contains("\\u0007"))
    }

    @Test
    fun numbersBooleansListsAndMapsNullsLeftOut() {
        val value = linkedMapOf(
            "i" to 2,
            "l" to 9_007_199_254_740_993L,
            "d" to 9.5,
            "b" to true,
            "n" to null,
            "a" to listOf(1, "x", false),
            "m" to mapOf("k" to "v"),
        )

        assertEquals("""{"i":2,"l":9007199254740993,"d":9.5,"b":true,"a":[1,"x",false],"m":{"k":"v"}}""", Json.encode(value))
    }

    @Test
    fun numbersThatJsonHasNoWordForAreNull() {
        assertEquals("[null,null]", Json.encode(listOf(Double.NaN, Float.POSITIVE_INFINITY)))
    }
}
