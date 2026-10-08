package com.qs.tallerxr.legal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalTextTest {
    private val provider = LegalInfo(
        name = "Muster Stahlbau GmbH",
        street = "Werkstraße 1",
        city = "12345 Musterstadt",
        country = "Deutschland",
        email = "info@example.de",
        phone = "",
        representative = "Erika Muster",
        register = "",
        vatId = "DE123456789",
        privacyUrl = "https://example.de/datenschutz",
        privacyDate = "18.09.2026",
    )

    private fun asset(name: String): String =
        listOf("src/main/assets/legal/$name", "app/src/main/assets/legal/$name")
            .map(::File).first(File::isFile).readText(Charsets.UTF_8)

    @Test
    fun `every placeholder of the shipped legal texts is filled from the provider details`() {
        listOf("datenschutz.txt", "impressum.txt", "lizenzen.txt").forEach { name ->
            val filled = LegalText.fill(asset(name), provider)
            assertFalse("$name: Platzhalter offen", "{{" in filled)
            assertFalse("$name: Angabe fehlt", LegalText.MISSING in filled)
        }
        val imprint = LegalText.fill(asset("impressum.txt"), provider)
        assertTrue("Muster Stahlbau GmbH\nWerkstraße 1\n12345 Musterstadt\nDeutschland" in imprint)
        assertTrue("Vertreten durch: Erika Muster" in imprint)
        assertTrue("DE123456789" in imprint)
        assertFalse("Telefon" in imprint)
        assertFalse("Registereintrag" in imprint)
    }

    @Test
    fun `missing provider details are shown as missing instead of as an empty imprint`() {
        val empty = provider.copy(name = "", street = "", city = "", email = "")
        assertFalse(empty.complete)
        assertTrue(LegalText.MISSING in LegalText.fill(asset("impressum.txt"), empty))
    }

    @Test
    fun `the privacy policy states what Google Play and ARCore require`() {
        val policy = LegalText.fill(asset("datenschutz.txt"), provider)
        assertTrue("info@example.de" in policy)
        assertTrue("keine Internetberechtigung" in policy)
        assertTrue("Kamera" in policy)
        assertTrue("which is provided by Google LLC and governed by the Google Privacy Policy" in policy)
        assertTrue("https://policies.google.com/privacy" in policy)
    }

    @Test
    fun `privacy identifies the supplied contact without inventing an address or completing the imprint`() {
        val contactOnly = provider.copy(street = "", city = "")
        val policy = LegalText.fill(asset("datenschutz.txt"), contactOnly)
        assertTrue(contactOnly.name in policy)
        assertTrue(contactOnly.email in policy)
        assertFalse(LegalText.MISSING in policy)
        assertFalse(contactOnly.complete)
        val imprint = LegalText.fill(asset("impressum.txt"), contactOnly)
        assertTrue(contactOnly.name in imprint)
        assertTrue(contactOnly.email in imprint)
        assertFalse("Anbieteranschrift" in imprint)
        assertFalse(LegalText.MISSING in imprint)
        assertFalse("Deutschland" in imprint)
    }

    @Test
    fun `partial postal address stays visible without implying a complete imprint`() {
        val partial = provider.copy(city = "")
        val imprint = LegalText.fill(asset("impressum.txt"), partial)
        assertTrue(partial.name in imprint)
        assertTrue(partial.street in imprint)
        assertFalse("Anbieteranschrift" in imprint)
        assertFalse(partial.complete)
    }

    @Test
    fun `the markup becomes titles, headings, bullets and paragraphs`() {
        val blocks = LegalText.parse("# Titel\n\nErste Zeile\nzweite Zeile\n\n## Abschnitt\n- Punkt A\n- Punkt B\nSchluss")
        assertEquals(
            listOf(
                LegalBlock.Title("Titel"),
                LegalBlock.Paragraph("Erste Zeile\nzweite Zeile"),
                LegalBlock.Heading("Abschnitt"),
                LegalBlock.Bullet("Punkt A"),
                LegalBlock.Bullet("Punkt B"),
                LegalBlock.Paragraph("Schluss"),
            ),
            blocks,
        )
    }
}
