package com.kivan.tether.dibs

import org.junit.Assert.assertEquals
import org.junit.Test

class CardTextTest {
    private val github = "Let the home server read your GitHub: enter code 070B-16D2 at github.com/login/device, then tap Done"

    private fun urls(s: String) = webLinks(s).map { it.url }
    private fun codes(s: String) = signInCodes(s).map { it.code }

    @Test
    fun aDeviceLoginHasItsLinkAndItsCode() {
        assertEquals(listOf("https://github.com/login/device"), urls(github))
        val l = webLinks(github).single()
        assertEquals("github.com/login/device", github.substring(l.start, l.end))
        assertEquals(listOf("070B-16D2"), codes(github))
        val c = signInCodes(github).single()
        assertEquals("070B-16D2", github.substring(c.start, c.end))
    }

    @Test
    fun fullAddressesAndWww() {
        assertEquals(listOf("https://example.com/a?b=1"), urls("Open https://example.com/a?b=1."))
        assertEquals(listOf("https://www.example.org"), urls("See www.example.org, then"))
        assertEquals(listOf("http://x.dev/p"), urls("(http://x.dev/p)"))
    }

    @Test
    fun bareHostsOnlyWithAWellKnownEnding() {
        assertEquals(listOf("https://claude.ai"), urls("Sign in at claude.ai."))
        assertEquals(listOf("https://news.ycombinator.com/item?id=1"), urls("on news.ycombinator.com/item?id=1"))
        assertEquals(listOf("https://gov.il/he"), urls("gov.il/he"))
        // File names, paths and e-mail addresses are not links.
        assertEquals(emptyList<String>(), urls("edit docs/plan/server.md and setup.sh in Cargo.toml"))
        assertEquals(emptyList<String>(), urls("mail me@example.com"))
        assertEquals(emptyList<String>(), urls("in ~/Projects/foo.com/src"))
    }

    @Test
    fun anAddressIsTakenOnce() {
        assertEquals(listOf("https://github.com/login/device"), urls("go to https://github.com/login/device now"))
        assertEquals(1, webLinks("www.github.com/x").size)
    }

    @Test
    fun namedCodes() {
        assertEquals(listOf("482913"), codes("Your code: 482913"))
        assertEquals(listOf("AB12CD"), codes("the code is AB12CD."))
        assertEquals(listOf("ABC-123-XY9"), codes("Enter code ABC-123-XY9"))
    }

    @Test
    fun notCodes() {
        assertEquals(emptyList<String>(), codes("review the code DIBS"))
        assertEquals(emptyList<String>(), codes("code review please"))
        assertEquals(emptyList<String>(), codes("on 2026-10-08, SHA-256 and CVE-2026-1234"))
        assertEquals(emptyList<String>(), codes("https://example.com/ABCD-1234"))
    }

    @Test
    fun aCodeFoundBothWaysIsOne() {
        assertEquals(listOf("070B-16D2", "070B-16D2"), codes("code 070B-16D2, again 070B-16D2"))
        assertEquals(listOf("070B-16D2"), codes("code 070B-16D2"))
    }
}
