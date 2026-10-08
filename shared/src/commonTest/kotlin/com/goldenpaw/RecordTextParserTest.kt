package com.goldenpaw

import com.goldenpaw.domain.logic.RecordTextParser
import com.goldenpaw.domain.logic.Ymd
import com.goldenpaw.domain.model.DocumentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordTextParserTest {

    @Test
    fun indianVaccineCard_rowsWithGivenAndDue() {
        val text = """
            PAWS & CLAWS VETERINARY CLINIC
            Anna Nagar, Chennai
            Dr. S. Karthik  BVSc & AH
            VACCINATION RECORD
            Name: Bruno   Breed: Labrador
            DHPPi+L   12/03/2024   12/03/2025   Batch: A2341
            Anti Rabies (Rabisin) 12-03-2024  12-03-2025
            Deworming  02.02.2024   02.05.2024
        """.trimIndent()
        val r = RecordTextParser.parse(text)
        assertEquals(DocumentType.VACCINE_CARD, r.suggestedType)
        assertEquals(listOf("DHPPi + Lepto", "Rabies", "Deworming"), r.vaccines.map { it.name })
        val dhpp = r.vaccines[0]
        assertEquals(Ymd(2024, 3, 12), dhpp.given)
        assertEquals(Ymd(2025, 3, 12), dhpp.due)
        assertEquals("A2341", dhpp.batch)
        assertEquals(Ymd(2024, 5, 2), r.vaccines[2].due)
        assertEquals("PAWS & CLAWS VETERINARY CLINIC", r.clinic)
        assertEquals("Dr. S. Karthik", r.vetName)
        assertEquals(Ymd(2024, 3, 12), r.issuedOn)
    }

    @Test
    fun comboVaccineIsNotAlsoCountedAsLepto() {
        assertEquals(listOf("DHPPi + Lepto"), RecordTextParser.findVaccines("Nobivac DHPPi + L4"))
        assertEquals(listOf("DHPPi + Lepto"), RecordTextParser.findVaccines("Megavac-9 booster"))
        assertEquals(listOf("DHPP"), RecordTextParser.findVaccines("DA2PP"))
        assertEquals(listOf("Leptospirosis"), RecordTextParser.findVaccines("Lepto 4"))
    }

    @Test
    fun parainfluenzaIsNotCanineInfluenza() {
        assertTrue(RecordTextParser.findVaccines("Parainfluenza").isEmpty())
        assertEquals(listOf("Canine influenza"), RecordTextParser.findVaccines("Canine Influenza H3N2"))
    }

    @Test
    fun tableLayout_datesOnNextLine() {
        val text = """
            Vaccine
            Rabies
            14 Jan 2025    Next due 14 Jan 2026
            FVRCP
            Jan 20, 2025
        """.trimIndent()
        val r = RecordTextParser.parse(text)
        assertEquals(Ymd(2025, 1, 14), r.vaccines.first { it.name == "Rabies" }.given)
        assertEquals(Ymd(2026, 1, 14), r.vaccines.first { it.name == "Rabies" }.due)
        assertEquals(Ymd(2025, 1, 20), r.vaccines.first { it.name == "FVRCP" }.given)
    }

    @Test
    fun singleDateWithDueHintIsTheDueDate() {
        val r = RecordTextParser.parse("Rabies booster due: 05/11/2026")
        assertNull(r.vaccines.single().given)
        assertEquals(Ymd(2026, 11, 5), r.vaccines.single().due)
    }

    @Test
    fun dateFormats() {
        assertEquals(listOf(Ymd(2024, 3, 12)), RecordTextParser.findDates("12/03/2024"))
        assertEquals(listOf(Ymd(2024, 12, 3)), RecordTextParser.findDates("12/03/2024", dayFirst = false))
        assertEquals(listOf(Ymd(2024, 3, 25)), RecordTextParser.findDates("03/25/2024")) // unambiguous month-first
        assertEquals(listOf(Ymd(2024, 3, 12)), RecordTextParser.findDates("2024-03-12"))
        assertEquals(listOf(Ymd(2024, 3, 12)), RecordTextParser.findDates("12th March 2024"))
        assertEquals(listOf(Ymd(2024, 3, 12)), RecordTextParser.findDates("12-Mar-24"))
        assertEquals(listOf(Ymd(2024, 3, 12)), RecordTextParser.findDates("Mar 12, 2024"))
        assertTrue(RecordTextParser.findDates("31/02/2024").isEmpty())
        assertTrue(RecordTextParser.findDates("Phone 98401 23456").isEmpty())
    }

    @Test
    fun prescriptionAndLabReports() {
        assertEquals(DocumentType.PRESCRIPTION, RecordTextParser.parse("Rx\nTab. Meloxicam 1.5mg BID x 5 days").suggestedType)
        assertEquals(DocumentType.LAB_REPORT, RecordTextParser.parse("CBC\nHaemoglobin 13.2 g/dL\nCreatinine 1.1").suggestedType)
        assertEquals(DocumentType.VET_CARD, RecordTextParser.parse("Happy Tails Pet Hospital\nDr. Meera Rao").suggestedType)
    }

    @Test
    fun emptyTextGivesEmptyRecord() {
        val r = RecordTextParser.parse("")
        assertTrue(r.isEmpty)
        assertNull(r.suggestedType)
    }
}

class TextLineAssemblerTest {
    @Test
    fun joinsColumnsBackIntoRows() {
        val boxes = listOf(
            com.goldenpaw.domain.logic.TextBox("12/03/2025", 300f, 101f, 119f),
            com.goldenpaw.domain.logic.TextBox("Rabies", 10f, 100f, 120f),
            com.goldenpaw.domain.logic.TextBox("12/03/2024", 150f, 102f, 118f),
            com.goldenpaw.domain.logic.TextBox("DHPPi+L", 10f, 140f, 160f),
            com.goldenpaw.domain.logic.TextBox("05/01/2024", 150f, 141f, 159f),
        )
        assertEquals("Rabies   12/03/2024   12/03/2025\nDHPPi+L   05/01/2024", com.goldenpaw.domain.logic.TextLineAssembler.assemble(boxes))
        val r = RecordTextParser.parse(com.goldenpaw.domain.logic.TextLineAssembler.assemble(boxes))
        assertEquals(Ymd(2025, 3, 12), r.vaccines.first { it.name == "Rabies" }.due)
    }
}
