package com.xmitya.seafilesync.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnoreRulesTest {

    private fun rules(vararg lines: String) = IgnoreRules.parse(lines.joinToString("\n"))

    @Test
    fun `an extension rule matches at any depth`() {
        val ignore = rules("*.tmp")

        assertTrue(ignore.isIgnored("a.tmp", isDirectory = false))
        assertTrue(ignore.isIgnored("docs/deep/b.tmp", isDirectory = false))
        assertFalse(ignore.isIgnored("a.txt", isDirectory = false))
    }

    @Test
    fun `a leading slash anchors the rule to the library root`() {
        val ignore = rules("/build")

        assertTrue(ignore.isIgnored("build", isDirectory = true))
        assertFalse(ignore.isIgnored("app/build", isDirectory = true))
    }

    @Test
    fun `a trailing slash restricts the rule to directories`() {
        val ignore = rules("cache/")

        assertTrue(ignore.isIgnored("cache", isDirectory = true))
        assertFalse(ignore.isIgnored("cache", isDirectory = false))
    }

    @Test
    fun `comments and blank lines are skipped`() {
        val ignore = rules("# editor droppings", "", "*.swp")

        assertTrue(ignore.isIgnored("notes.swp", isDirectory = false))
        assertFalse(ignore.isIgnored("editor droppings", isDirectory = false))
    }

    @Test
    fun `regex characters in a rule are matched literally`() {
        // A filename is not a pattern. Without escaping, "report(final).doc" would turn into a
        // group and match things it should not.
        val ignore = rules("report(final).doc")

        assertTrue(ignore.isIgnored("report(final).doc", isDirectory = false))
        assertFalse(ignore.isIgnored("reportfinal.doc", isDirectory = false))
    }

    @Test
    fun `a question mark matches exactly one character`() {
        val ignore = rules("log?.txt")

        assertTrue(ignore.isIgnored("log1.txt", isDirectory = false))
        assertFalse(ignore.isIgnored("log12.txt", isDirectory = false))
    }

    @Test
    fun `nothing is ignored without rules`() {
        assertFalse(IgnoreRules.EMPTY.isIgnored("anything", isDirectory = false))
    }
}
