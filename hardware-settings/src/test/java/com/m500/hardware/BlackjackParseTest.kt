package com.m500.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** BlackjackMonitor.parse() against lines in the shape `logcat -b kernel -v epoch` prints on the M500. */
class BlackjackParseTest {
    private fun run(vararg lines: String): BlackjackMonitor.State =
        lines.fold(BlackjackMonitor.State()) { s, l -> BlackjackMonitor.parse(s, l) }

    private fun w(t: String, addr: String, reg: String, data: String, r: Int = 0) =
        "         $t  3870  3870 D blackjack-cs43198 0-0030: cs43198_i2c_write_reg addr=0x$addr reg=0x$reg data=0x$data r=$r"

    @Test fun firstBootBalanced() {
        val s = run(
            "         1791690000.652   515   515 I blackjack-cs43198 0-0030: blackjack codec: CS43198 at 0x30 and 0x33",
            "         1791690000.974   558   558 I blackjack-hooks soc: hiby,sound-plat: blackjack hooks: registered",
            "         1791690008.230  3870  3870 I hiby_output_switch: Balance -> target 3",
            "         1791690008.230  3870  3870 I blackjack-hooks soc: hiby,sound-plat: power on target 3",
            "         1791690008.324  3870  3870 I blackjack-cs43198 0-0030: 0x30: device id 431980 rev 0xa1",
            "         1791690008.325  3870  3870 I blackjack-cs43198 0-0030: 0x33: device id 431980 rev 0xa1",
            w("1791690008.327", "30", "01000b", "02"),
            w("1791690008.327", "30", "01000c", "00"),
            w("1791690008.329", "30", "090000", "22"),
            w("1791690008.330", "30", "0c0001", "01"),
            w("1791690008.331", "33", "01000c", "00"),
            w("1791690008.540", "30", "020000", "ae"),
            "         1791690008.379  3870  3870 I hiby_snd_hw_params: rate=192000 format=10 channels=2",
            w("1791690008.543", "30", "01000b", "06"),
            w("1791690008.562", "30", "090002", "72"),
            w("1791690008.563", "30", "090003", "b0"),
            w("1791690008.600", "33", "090001", "3c", r = -121),
            "         1791690020.013   880   880 W blackjack-cs43198 0-0030: 0x30: no PDN_DONE in 20 ms",
            "         1791690020.100   880   880 I some-other-driver: unrelated line",
        )
        assertEquals("CS43198 at 0x30 and 0x33", s.probeLine)
        assertTrue(s.hooksRegistered)
        assertEquals(3, s.selected)
        assertEquals(true, s.powered)
        assertEquals(3, s.poweredTarget)
        assertEquals("431980", s.chips[0x33]?.deviceId)
        assertEquals("0xa1", s.chips[0x30]?.rev)
        assertEquals(192000, s.dacRate)
        assertEquals(192000, s.hwRate)
        assertEquals("32-bit", BlackjackMonitor.wordLength(s.main.reg(0x01000C)))
        assertEquals("NOS", BlackjackMonitor.filterName(s.main.reg(0x090000)))
        assertEquals("on", BlackjackMonitor.dreText(s.main.reg(0x0C0001)))
        assertEquals("on", BlackjackMonitor.powerText(s.main.reg(0x020000)))
        assertEquals("-57.0 dB", BlackjackMonitor.volumeText(s.main.reg(0x090002)))
        assertEquals("not muted", BlackjackMonitor.muteText(s.main.reg(0x090003)))
        assertEquals(10, s.writes)
        assertEquals(1, s.failedWrites)
        // Failed write and the PDN_DONE warning are both problems; the warning text has no prefix.
        assertEquals(2, s.problems.size)
        assertEquals("0x30: no PDN_DONE in 20 ms", s.problems.last().text)
        assertTrue(s.events.any { it.text == "DAC rate 48 kHz" })
        assertTrue(s.events.any { it.text == "DAC rate 192 kHz" })
        assertEquals(19, s.linesSeen)
    }

    @Test fun decoders() {
        assertEquals(44100, BlackjackMonitor.rateFromIndex(1))
        assertEquals(384000, BlackjackMonitor.rateFromIndex(8))
        assertEquals(null, BlackjackMonitor.rateFromIndex(9))
        assertEquals("44.1 kHz", BlackjackMonitor.formatRate(44100))
        assertEquals("16-bit", BlackjackMonitor.wordLength(0x02))
        assertEquals("Fast, low latency", BlackjackMonitor.filterName(0x02))
        assertEquals("Slow, phase compensated", BlackjackMonitor.filterName(0xC2))
        assertEquals("mute", BlackjackMonitor.volumeText(0xFF))
        assertEquals("powered down", BlackjackMonitor.powerText(0xFE))
        assertEquals("serial port on, output off", BlackjackMonitor.powerText(0xBE))
        assertEquals("4.4 mm balanced", BlackjackMonitor.targetName(3))
    }
}
