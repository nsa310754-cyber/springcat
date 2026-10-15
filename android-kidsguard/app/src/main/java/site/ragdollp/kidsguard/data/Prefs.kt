package site.ragdollp.kidsguard.data

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * アプリ全体で使う設定ストア。
 *  - PIN はソルト付き SHA-256 で保存(平文は書かない)
 *  - ブロック対象パッケージ名の集合
 */
object Prefs {
    private const val FILE = "kidsguard_prefs"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_BLOCKED = "blocked_pkgs"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hasPin(ctx: Context): Boolean = sp(ctx).contains(KEY_PIN_HASH)

    fun setPin(ctx: Context, pin: String) {
        val salt = (1..16).map { ('a'..'z').random() }.joinToString("")
        val hash = hash(pin + salt)
        sp(ctx).edit().putString(KEY_PIN_SALT, salt).putString(KEY_PIN_HASH, hash).apply()
    }

    fun verifyPin(ctx: Context, pin: String): Boolean {
        val salt = sp(ctx).getString(KEY_PIN_SALT, null) ?: return false
        val expect = sp(ctx).getString(KEY_PIN_HASH, null) ?: return false
        return hash(pin + salt) == expect
    }

    fun getBlocked(ctx: Context): Set<String> =
        sp(ctx).getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()

    fun setBlocked(ctx: Context, pkgs: Set<String>) {
        sp(ctx).edit().putStringSet(KEY_BLOCKED, pkgs).apply()
    }

    private fun hash(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
