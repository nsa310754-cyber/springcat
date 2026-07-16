package com.springcat.iso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [IsoReader] against a real ISO 9660 image (with a Joliet extension
 * and a subdirectory) bundled under test resources.
 */
class IsoReaderTest {

    private fun openTestIso(): IsoReader {
        val stream = javaClass.classLoader!!.getResourceAsStream("test.iso")
        assertNotNull("test.iso fixture missing", stream)
        return stream!!.use { IsoReader.open(it) }
    }

    @Test
    fun listsRootEntries() {
        val reader = openTestIso()
        val names = reader.list(reader.root()).map { it.name }

        // Joliet names should be preferred (lower-case, no ";1" version suffix).
        assertTrue("expected readme.txt, got $names", names.contains("readme.txt"))
        assertTrue("expected data.bin, got $names", names.contains("data.bin"))
        assertTrue("expected docs directory, got $names", names.contains("docs"))
    }

    @Test
    fun directoriesSortFirst() {
        val reader = openTestIso()
        val entries = reader.list(reader.root())
        assertTrue("docs should be a directory", entries.first { it.name == "docs" }.isDirectory)
        assertEquals("docs", entries.first().name) // directory sorted before files
    }

    @Test
    fun reportsFileSize() {
        val reader = openTestIso()
        val data = reader.list(reader.root()).first { it.name == "data.bin" }
        assertEquals(5000L, data.size)
        assertTrue(data.readableSize().endsWith("KB"))
    }

    @Test
    fun navigatesIntoSubdirectory() {
        val reader = openTestIso()
        val docs = reader.list(reader.root()).first { it.name == "docs" }
        val nested = reader.list(docs).map { it.name }
        assertTrue("expected note.txt inside docs, got $nested", nested.contains("note.txt"))
    }
}
