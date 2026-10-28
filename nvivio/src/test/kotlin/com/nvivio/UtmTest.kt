package com.nvivio

import org.junit.Assert.assertEquals
import org.junit.Test

class UtmTest {
    @Test
    fun theUtmParametersOfADeepLinkDecoded() {
        assertEquals(
            mapOf("utm_source" to "news letter", "utm_medium" to "e-mail", "utm_campaign" to "spring sale"),
            utmOf("myshop://product/7?utm_source=news+letter&utm_medium=e-mail&id=7&utm_campaign=spring%20sale#top"),
        )
    }

    @Test
    fun theFirstCountsEmptyAndBrokenValuesAreLeftOut() {
        assertEquals(
            mapOf("utm_source" to "a"),
            utmOf("https://shop.example/p?utm_source=a&utm_source=b&utm_medium=&utm_term=%zz&utm_content=%20"),
        )
    }

    @Test
    fun noQueryNoUtm() {
        assertEquals(emptyMap<String, String>(), utmOf("myshop://home"))
        assertEquals(emptyMap<String, String>(), utmOf("myshop://home#utm_source=x"))
    }
}
