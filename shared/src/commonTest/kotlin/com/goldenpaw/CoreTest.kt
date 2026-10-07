package com.goldenpaw

import com.goldenpaw.core.Fmt
import com.goldenpaw.core.newInviteCode
import com.goldenpaw.core.normalizeInviteCode
import com.goldenpaw.core.toFixed
import com.goldenpaw.domain.model.CarePermission
import com.goldenpaw.domain.model.CaregiverRole
import com.goldenpaw.domain.model.Caregiver
import com.goldenpaw.domain.model.MemberStatus
import com.goldenpaw.report.ReportOptions
import com.goldenpaw.report.SimplePdfCanvas
import com.goldenpaw.report.VetReportData
import com.goldenpaw.report.VetReportLayout
import com.goldenpaw.domain.model.WeightUnit
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class CoreTest {
    @Test
    fun fixedDecimals() {
        assertEquals("3.14", 3.14159.toFixed(2))
        assertEquals("-0.5", (-0.5).toFixed(1))
        assertEquals("0.0", (-0.01).toFixed(1))
        assertEquals("10", 9.6.toFixed(0))
    }

    @Test
    fun dateFormats() {
        assertEquals("6 Oct 2026", Fmt.dayMonthYear(LocalDate(2026, 10, 6)))
        assertEquals("Tuesday, 6 October", Fmt.weekdayDayMonth(LocalDate(2026, 10, 6)))
        assertEquals("28 Sep – 4 Oct", Fmt.range(LocalDate(2026, 9, 28), LocalDate(2026, 10, 4)))
    }

    @Test
    fun inviteCodesAreReadable() {
        val code = newInviteCode()
        assertEquals(9, code.length)
        assertEquals(code, normalizeInviteCode(code.lowercase().replace("-", " ")))
    }

    @Test
    fun sitterCanLogButNotEditMedsAndExpires() {
        val now = TestData.epoch + 10.days
        val sitter = Caregiver("s", "h", "u", "Sam", CaregiverRole.SITTER, 1, now + 2.days, MemberStatus.ACTIVE, TestData.epoch)
        assertTrue(sitter.can(CarePermission.LOG_CARE, now))
        assertFalse(sitter.can(CarePermission.EDIT_MEDS, now))
        assertFalse(sitter.can(CarePermission.LOG_CARE, now + 3.days))
    }

    @Test
    fun simplePdfIsWellFormed() {
        val data = VetReportData(
            pet = TestData.pet(), options = ReportOptions(LocalDate(2026, 9, 1), LocalDate(2026, 9, 30)),
            medications = listOf(TestData.med()), slots = emptyList(), weights = emptyList(),
            checkIns = listOf(TestData.checkIn(LocalDate(2026, 9, 10), notes = "Ate well (all of it) → happy")),
            symptoms = emptyList(), unit = WeightUnit.KG, ownerName = "Priya", generatedOn = LocalDate(2026, 10, 1),
            caregivers = listOf("Priya"),
        )
        val bytes = VetReportLayout(SimplePdfCanvas()).render(data)
        val text = bytes.decodeToString()
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertTrue(text.contains("/Type /Page "))
        assertTrue(text.contains("Bruno"))
        // xref offset must point at the xref table
        val startxref = text.substringAfter("startxref\n").substringBefore("\n").trim().toInt()
        assertTrue(text.substring(startxref).startsWith("xref"))
    }
}
