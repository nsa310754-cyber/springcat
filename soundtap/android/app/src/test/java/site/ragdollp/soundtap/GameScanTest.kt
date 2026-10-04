package site.ragdollp.soundtap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GameScanTest {
    private fun lvl(asset: String, title: String = "") = GameScan.Level(asset, title, "", 10, File("x"), "")

    @Test fun matchesXStages() {
        assertTrue(GameScan.isXLevel(lvl("AR-X")))
        assertTrue(GameScan.isXLevel(lvl("1-X")))
        assertTrue(GameScan.isXLevel(lvl("12-X")))
        assertTrue(GameScan.isXLevel(lvl("", "AR-X Libertas")))
        assertFalse(GameScan.isXLevel(lvl("1-1")))
        assertFalse(GameScan.isXLevel(lvl("AR-XL")))
        assertFalse(GameScan.isXLevel(lvl("1-X2")))
    }

    @Test fun worldOrder() {
        val sorted = listOf(lvl("AR-X"), lvl("12-X"), lvl("2-X"), lvl("1-X"), lvl("MN-X"))
            .sortedWith(GameScan.worldOrder).map { it.assetName }
        assertEquals(listOf("1-X", "2-X", "12-X", "AR-X", "MN-X"), sorted)
    }
}
