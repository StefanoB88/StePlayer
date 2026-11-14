package com.steplayer.shared.zap

import com.steplayer.shared.model.Channel

/**
 * Pure zapping logic shared by phone, tablet and TV players:
 * next/previous in the current list (wrapping), "ultimo canale" toggle, and remote-control
 * number entry ("2", "0", "1" → channel 201 after a short pause).
 *
 * It only decides WHICH channel; the player decides WHEN to open it (debounced).
 */
class ChannelZapper(
    channels: List<Channel>,
    start: Channel,
    private val numberEntryTimeoutMs: Long = 1_800,
    private val maxDigits: Int = 4,
) {
    private var list: List<Channel> = channels.ifEmpty { listOf(start) }

    var current: Channel = start
        private set

    /** The channel watched before [current] ("canale precedente"), if any. */
    var lastChannel: Channel? = null
        private set

    private var digits = StringBuilder()
    private var lastDigitAt = 0L

    /** Digits typed so far, for the on-screen "2 0 _" indicator. Empty when not typing. */
    val pendingNumber: String get() = digits.toString()

    fun updateList(channels: List<Channel>) {
        if (channels.isNotEmpty()) list = channels
    }

    fun next(): Channel = moveBy(1)
    fun previous(): Channel = moveBy(-1)

    private fun moveBy(delta: Int): Channel {
        val idx = list.indexOfFirst { it.id == current.id }
        val target = if (idx < 0) list.first() else list[(idx + delta).mod(list.size)]
        return select(target)
    }

    /** Toggles between the current and the previously watched channel. Null if none. */
    fun back(): Channel? = lastChannel?.let { select(it) }

    /** Explicit selection (channel list, guide, search). */
    fun select(channel: Channel): Channel {
        if (channel.id != current.id) {
            lastChannel = current
            current = channel
        }
        clearNumber()
        return current
    }

    /**
     * Adds a digit typed on the remote. Returns true when the number is complete (max digits)
     * and should be committed immediately; otherwise call [commitIfDue] from a timer.
     */
    fun typeDigit(digit: Int, nowMs: Long): Boolean {
        require(digit in 0..9)
        if (digits.isNotEmpty() && nowMs - lastDigitAt > numberEntryTimeoutMs) digits.clear()
        if (digits.isEmpty() && digit == 0) return false // leading zero: ignore
        digits.append(digit)
        lastDigitAt = nowMs
        return digits.length >= maxDigits
    }

    /** The typed number if the pause after the last digit elapsed (or [force]); clears the entry. */
    fun commitIfDue(nowMs: Long, force: Boolean = false): Int? {
        if (digits.isEmpty()) return null
        if (!force && nowMs - lastDigitAt < numberEntryTimeoutMs) return null
        val n = digits.toString().toIntOrNull()
        clearNumber()
        return n
    }

    /** Resolves a typed number against the current list first (by channel number, then position). */
    fun channelForNumber(number: Int): Channel? =
        list.firstOrNull { it.num == number } ?: list.getOrNull(number - 1)?.takeIf { list.none { c -> c.num != null } }

    fun clearNumber() {
        digits.clear()
    }
}
