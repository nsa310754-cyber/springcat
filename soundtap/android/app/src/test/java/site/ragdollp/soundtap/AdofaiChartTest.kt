package site.ragdollp.soundtap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AdofaiChartTest {
    private fun chart(angles: String, actions: String = "", bpm: Int = 120, offset: Int = 0) =
        AdofaiChart.parse(
            """
            {
              "angleData": [$angles],
              "settings": { "bpm": $bpm, "offset": $offset, "pitch": 100, "song": "<color=#fff>Test</color>", },
              "actions": [ $actions ],
            }
            """.trimIndent()
        )

    @Test fun straightLineIsOneBeatPerTile() {
        // 直線 = 180° = 1拍。120BPM なら 0.5 秒ごと
        val c = chart("0, 0, 0, 0")
        assertArrayEquals(doubleArrayOf(0.0, 0.5, 1.0, 1.5), c.times, 1e-9)
        assertEquals("Test", c.title)
    }

    @Test fun rightAngleTurns() {
        // 0 → 90 (上へ曲がる): 時計回りなら 入射方向 180 から 90 まで 90° = 半拍
        // 0 → 270 (下へ曲がる): 180 → 270 を時計回り = 270° = 1.5拍
        val up = chart("0, 90, 90")
        assertEquals(0.25, up.times[1], 1e-9)
        val down = chart("0, 270, 270")
        assertEquals(0.75, down.times[1], 1e-9)
    }

    @Test fun twirlReversesDirection() {
        val c = chart("0, 270, 270", """{ "floor": 1, "eventType": "Twirl" }""")
        assertEquals(0.25, c.times[1], 1e-9)
    }

    @Test fun setSpeedBpmAndMultiplier() {
        val c = chart(
            "0, 0, 0, 0",
            """{ "floor": 2, "eventType": "SetSpeed", "speedType": "Bpm", "beatsPerMinute": 240 },
               { "floor": 3, "eventType": "SetSpeed", "speedType": "Multiplier", "bpmMultiplier": 0.5 }"""
        )
        assertArrayEquals(doubleArrayOf(0.0, 0.5, 0.75, 1.25), c.times, 1e-9)
    }

    @Test fun uTurnIsFullCircle() {
        // 0 の次に 180 (真後ろ) は 0° 差 → 360° = 2拍
        val c = chart("0, 180, 180")
        assertEquals(1.0, c.times[1], 1e-9)
    }

    @Test fun midspinMergesIntoOneTap() {
        val c = chart("0, 0, 999, 180, 180")
        // floor 3 はミッドスピンで floor 4 と同時 → 1回のタップ
        assertEquals(4, c.times.size)
    }

    @Test fun pauseAddsBeats() {
        val c = chart("0, 0, 0", """{ "floor": 1, "eventType": "Pause", "duration": 2 }""")
        assertEquals(0.5 + 1.0, c.times[1], 1e-9)
    }

    @Test fun holdBecomesLongPress() {
        val c = chart("0, 0, 0, 0", """{ "floor": 1, "eventType": "Hold", "duration": 1 }""")
        // floor1 で押して 1拍 + 1回転(2拍) = 1.5 秒後の floor2 で離す。floor2 のタップは無し
        assertEquals(0.0, c.times[0], 1e-9)
        assertEquals(1.5, c.releases[0], 1e-9)
        assertEquals(3, c.times.size)
        assertEquals(2.0, c.times[1], 1e-9)
    }

    @Test fun pathDataAndLenientJson() {
        val c = AdofaiChart.parse(
            "﻿{ \"pathData\": \"RRRR\", \"settings\": { \"bpm\": 60, \"offset\": 3000 } \"actions\": [ ] }"
        )
        assertArrayEquals(doubleArrayOf(0.0, 1.0, 2.0, 3.0), c.times, 1e-9)
        assertEquals(3.0, c.leadSec, 1e-9)
    }
}
