package com.qs.tallerxr.legal

import com.qs.tallerxr.BuildConfig

/**
 * Who publishes the app.  The values come from `legal.properties` at build time, so the Impressum,
 * the privacy policy inside the app and the one hosted for Google Play can never drift apart.
 */
data class LegalInfo(
    val name: String,
    val street: String,
    val city: String,
    val country: String,
    val email: String,
    val phone: String,
    val representative: String,
    val register: String,
    val vatId: String,
    val privacyUrl: String,
    val privacyDate: String,
) {
    /** Whether a complete postal identity has been supplied (independent of build validation). */
    val complete: Boolean
        get() = listOf(name, street, city, email).all(String::isNotBlank)

    companion object {
        fun fromBuild() = LegalInfo(
            name = BuildConfig.LEGAL_PROVIDER_NAME,
            street = BuildConfig.LEGAL_PROVIDER_STREET,
            city = BuildConfig.LEGAL_PROVIDER_CITY,
            country = BuildConfig.LEGAL_PROVIDER_COUNTRY,
            email = BuildConfig.LEGAL_PROVIDER_EMAIL,
            phone = BuildConfig.LEGAL_PROVIDER_PHONE,
            representative = BuildConfig.LEGAL_PROVIDER_REPRESENTATIVE,
            register = BuildConfig.LEGAL_PROVIDER_REGISTER,
            vatId = BuildConfig.LEGAL_PROVIDER_VATID,
            privacyUrl = BuildConfig.LEGAL_PRIVACY_URL,
            privacyDate = BuildConfig.LEGAL_PRIVACY_DATE,
        )
    }
}

sealed interface LegalBlock {
    data class Title(val text: String) : LegalBlock
    data class Heading(val text: String) : LegalBlock
    data class Paragraph(val text: String) : LegalBlock
    data class Bullet(val text: String) : LegalBlock
}

/**
 * The legal texts are plain text assets with a minimal markup (`# `, `## `, `- `, blank line) and
 * `{{…}}` placeholders.  One source feeds the screens of the app and the HTML page for the store.
 */
object LegalText {
    const val MISSING = "[Angabe fehlt – legal.properties ausfüllen]"

    fun fill(template: String, info: LegalInfo): String {
        val contact = listOfNotNull(
            "E-Mail: ${info.email.ifBlank { MISSING }}",
            info.phone.takeIf(String::isNotBlank)?.let { "Telefon: $it" },
        ).joinToString("\n")
        // Render only supplied identity fields. The owner explicitly asked for no postal
        // address, placeholder or missing-address notice in either public legal text.
        val identity = listOfNotNull(
            info.name.ifBlank { MISSING },
            info.street.takeIf(String::isNotBlank),
            info.city.takeIf(String::isNotBlank),
            info.country.takeIf { it.isNotBlank() && (info.street.isNotBlank() || info.city.isNotBlank()) },
        ).joinToString("\n")
        val optional = listOfNotNull(
            info.representative.takeIf(String::isNotBlank)?.let { "Vertreten durch: $it" },
            info.register.takeIf(String::isNotBlank)?.let { "Registereintrag: $it" },
            info.vatId.takeIf(String::isNotBlank)?.let { "Umsatzsteuer-Identifikationsnummer gemäß § 27a UStG: $it" },
        ).joinToString("\n")
        return template
            .replace("{{provider.block}}", identity)
            .replace("{{provider.identity}}", identity)
            .replace("{{provider.contact}}", contact)
            .replace("{{provider.optional}}", optional)
            .replace("{{privacy.date}}", info.privacyDate.ifBlank { MISSING })
    }

    fun parse(text: String): List<LegalBlock> {
        val blocks = ArrayList<LegalBlock>()
        val paragraph = ArrayList<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) blocks += LegalBlock.Paragraph(paragraph.joinToString("\n"))
            paragraph.clear()
        }
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> flush()
                line.startsWith("## ") -> {
                    flush()
                    blocks += LegalBlock.Heading(line.removePrefix("## "))
                }
                line.startsWith("# ") -> {
                    flush()
                    blocks += LegalBlock.Title(line.removePrefix("# "))
                }
                line.startsWith("- ") -> {
                    flush()
                    blocks += LegalBlock.Bullet(line.removePrefix("- "))
                }
                else -> paragraph += line
            }
        }
        flush()
        return blocks
    }
}
