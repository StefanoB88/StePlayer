package com.steplayer.shared

import com.steplayer.shared.model.Channel
import com.steplayer.shared.pairing.PairingServer
import com.steplayer.shared.zap.ChannelZapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZapperTest {
    private fun ch(id: String, num: Int?) = Channel(1, id, num, "Canale $id", null, null, null, 0)
    private val list = listOf(ch("a", 1), ch("b", 2), ch("c", 201))

    @Test fun nextPreviousWrapAndLastChannel() {
        val z = ChannelZapper(list, list[0])
        assertEquals("b", z.next().id)
        assertEquals("c", z.next().id)
        assertEquals("a", z.next().id) // wraps
        assertEquals("c", z.previous().id)
        assertEquals("a", z.back()!!.id) // "ultimo canale"
        assertEquals("c", z.back()!!.id) // toggles
    }

    @Test fun numberEntry() {
        val z = ChannelZapper(list, list[0], numberEntryTimeoutMs = 1000)
        assertFalse(z.typeDigit(0, 0)) // leading zero ignored
        z.typeDigit(2, 100); z.typeDigit(0, 300); z.typeDigit(1, 600)
        assertEquals("201", z.pendingNumber)
        assertNull(z.commitIfDue(1000)) // still typing
        assertEquals(201, z.commitIfDue(1700))
        assertEquals("c", z.channelForNumber(201)!!.id)
        assertEquals("", z.pendingNumber)
        // a pause longer than the timeout starts a new number
        z.typeDigit(5, 5000); z.typeDigit(7, 7000)
        assertEquals("7", z.pendingNumber)
        assertTrue(ChannelZapper(list, list[0], maxDigits = 2).let { it.typeDigit(1, 0); it.typeDigit(2, 10) })
    }

    @Test fun privateAddresses() {
        fun ip(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
        assertTrue(PairingServer.isPrivateAddress(ip(192, 168, 1, 20)))
        assertTrue(PairingServer.isPrivateAddress(ip(10, 0, 0, 5)))
        assertTrue(PairingServer.isPrivateAddress(ip(172, 20, 1, 1)))
        assertFalse(PairingServer.isPrivateAddress(ip(172, 32, 1, 1)))
        assertFalse(PairingServer.isPrivateAddress(ip(8, 8, 8, 8)))
        assertTrue(PairingServer.isPrivateAddress(ip(0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
        assertFalse(PairingServer.isPrivateAddress(ip(0x20, 0x01, 0x48, 0x60, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x88, 0x88)))
    }
}
