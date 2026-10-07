package com.goldenpaw.core

import kotlin.uuid.Uuid

/** Random UUID string. Every row is UUID-keyed so devices can create records offline and sync later. */
fun newId(): String = Uuid.random().toString()

private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O/1/I

/** Human-friendly invite code, e.g. "K7PQ-M3XA". */
fun newInviteCode(random: kotlin.random.Random = kotlin.random.Random.Default): String {
    val chars = List(8) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }
    return chars.take(4).joinToString("") + "-" + chars.drop(4).joinToString("")
}

fun normalizeInviteCode(input: String): String {
    val clean = input.uppercase().filter { it in CODE_ALPHABET }
    return if (clean.length == 8) clean.take(4) + "-" + clean.drop(4) else clean
}
