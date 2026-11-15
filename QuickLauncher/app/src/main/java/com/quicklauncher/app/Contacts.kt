package com.quicklauncher.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone as P

data class ContactHit(val name: String, val number: String)

/** Looks contacts up by name or by (part of) a phone number. */
object Contacts {
    fun granted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun search(ctx: Context, query: String, limit: Int = 6): List<ContactHit> {
        if (!granted(ctx)) return emptyList()
        val q = query.trim()
        val digits = q.filter { it.isDigit() }
        val out = ArrayList<ContactHit>()
        val seen = HashSet<String>()
        runCatching {
            val sel: String?; val args: Array<String>?
            if (q.isEmpty()) { sel = null; args = null }
            else if (digits.isNotEmpty() && digits.length >= q.count { it.isLetterOrDigit() }) {
                // looks like a number
                sel = "${P.NUMBER} LIKE ? OR ${P.NORMALIZED_NUMBER} LIKE ?"; args = arrayOf("%$digits%", "%$digits%")
            } else {
                sel = "${P.DISPLAY_NAME} LIKE ?"; args = arrayOf("%$q%")
            }
            ctx.contentResolver.query(P.CONTENT_URI, arrayOf(P.DISPLAY_NAME, P.NUMBER), sel, args, "${P.DISPLAY_NAME} COLLATE NOCASE ASC")?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val name = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    if (seen.add(name + "|" + num.filter { it.isDigit() })) out.add(ContactHit(name, num.trim()))
                }
            }
        }
        return out
    }
}

/** Opens a chat for any phone number (saved or not) in SMS, WhatsApp or Telegram. */
object Chat {
    private val CODES = mapOf("IN" to "91", "US" to "1", "CA" to "1", "GB" to "44", "AE" to "971", "PK" to "92", "BD" to "880",
        "NP" to "977", "LK" to "94", "SA" to "966", "AU" to "61", "DE" to "49", "FR" to "33", "SG" to "65", "MY" to "60", "ID" to "62",
        "PH" to "63", "BR" to "55", "MX" to "52", "ZA" to "27", "NG" to "234", "KE" to "254", "EG" to "20", "TR" to "90", "RU" to "7")

    /** Digits with country code (no '+'), guessing the SIM / locale country when the number has none. */
    fun intl(ctx: Context, raw: String): String {
        val t = raw.trim()
        val d = t.filter { it.isDigit() }
        if (t.startsWith("+")) return d
        if (d.startsWith("00")) return d.drop(2)
        val iso = runCatching {
            val tm = ctx.getSystemService(android.telephony.TelephonyManager::class.java)
            tm.simCountryIso.ifBlank { tm.networkCountryIso }
        }.getOrNull().orEmpty().ifBlank { java.util.Locale.getDefault().country }.uppercase()
        val cc = CODES[iso] ?: "91"
        val national = d.trimStart('0')
        return if (national.startsWith(cc) && national.length > 10) national else cc + national
    }

    fun sms(ctx: Context, number: String) =
        ctx.startSafe(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))))

    fun whatsapp(ctx: Context, number: String): Boolean {
        val uri = Uri.parse("https://wa.me/" + intl(ctx, number))
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            if (installed(ctx, pkg) && ctx.startSafe(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg), "")) return true
        }
        return ctx.startSafe(Intent(Intent.ACTION_VIEW, uri), "WhatsApp not found")
    }

    fun telegram(ctx: Context, number: String): Boolean {
        val uri = Uri.parse("https://t.me/+" + intl(ctx, number))
        for (pkg in listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")) {
            if (installed(ctx, pkg) && ctx.startSafe(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg), "")) return true
        }
        return ctx.startSafe(Intent(Intent.ACTION_VIEW, uri), "Telegram not found")
    }

    private fun installed(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}
