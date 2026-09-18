package com.sonicspot.player.util

import java.security.MessageDigest
import kotlin.random.Random

object AuthUtil {
    fun generateSalt(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..12).map { chars.random() }.joinToString("")
    }

    fun generateToken(password: String, salt: String): String {
        return md5(password + salt)
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
