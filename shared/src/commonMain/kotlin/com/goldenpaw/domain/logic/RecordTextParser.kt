package com.goldenpaw.domain.logic

import com.goldenpaw.domain.model.DocumentType

/*
 * Turns the text recognised on a photographed vet card / vaccine card into a suggested entry.
 *
 * It runs fully on the device (no cloud AI, no per-scan cost) and only *suggests*: the owner always
 * confirms every field before anything is saved. Pure Kotlin stdlib, so it's easy to unit test.
 *
 * Handles the layouts we see most on Indian, UK and US cards:
 *  - one row per vaccine with "given" and "next due" dates on the same line,
 *  - a vaccine name on one line with its dates on the next (table cells read column by column),
 *  - brand names (Nobivac, Megavac, Canigen, Rabisin, Defensor, Purevax, Feligen, ...),
 *  - day-first (12/03/2024) or month-first dates, ISO dates and "12 Mar 2024" / "Mar 12, 2024".
 */

/** A calendar date without a time zone (kept stdlib-only; mapped to LocalDate by the caller). */
data class Ymd(val year: Int, val month: Int, val day: Int) : Comparable<Ymd> {
    override fun compareTo(other: Ymd): Int = compareValuesBy(this, other, { it.year }, { it.month }, { it.day })
    override fun toString(): String = "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}

data class ParsedVaccine(
    val name: String,
    val given: Ymd? = null,
    val due: Ymd? = null,
    val batch: String = "",
)

data class ParsedRecord(
    val suggestedType: DocumentType?,
    val vaccines: List<ParsedVaccine>,
    val issuedOn: Ymd?,
    val clinic: String?,
    val vetName: String?,
    val allDates: List<Ymd>,
) {
    val isEmpty: Boolean get() = vaccines.isEmpty() && issuedOn == null && clinic == null && vetName == null
}

object RecordTextParser {

    /** Canonical name → patterns (case-insensitive, matched on word boundaries). Most specific first. */
    private val catalog: List<Pair<String, List<String>>> = listOf(
        "DHPPi + Lepto" to listOf(
            """dhlpp\w*""", """dhppi?\s*[+/&]?\s*l(?:epto)?\d?""", """da2pp\s*[+/]?\s*l\w*""", """dappl""",
            """(?:7|8|9|10)\s*-?\s*in\s*-?\s*1""", """megavac\s*-?\s*(?:7|9)""", """canigen\s*dhppi?\s*/\s*l""",
            """vanguard\s*plus\s*\d*""", """nobivac\s*dhppi?\s*\+\s*l\w*""",
        ),
        "DHPP" to listOf(
            """dhppi?""", """da2pp""", """dapp""", """(?:5|6)\s*-?\s*in\s*-?\s*1""", """nobivac\s*dhppi?""",
            """canigen(?:\s*dhppi?)?""", """eurican\s*\w*""", """duramune\s*\w*""", """recombitek\s*\w*""",
            """megavac\s*-?\s*6""", """vanguard""",
        ),
        "Rabies" to listOf(
            """anti\s*-?\s*rabies""", """rabies""", """rabisin""", """raksharab""", """rabivac""", """defensor\s*\d*""",
            """nobivac\s*r(?:abies)?""", """\bARV\b""", """imrab\s*\d*""",
        ),
        "Leptospirosis" to listOf("""leptospirosis""", """lepto\s*\d?""", """\bL4\b""", """nobivac\s*lepto\w*"""),
        "Parvovirus" to listOf("""parvo\s*virus""", """parvo""", """\bCPV\b"""),
        "Distemper" to listOf("""distemper""", """\bCDV\b"""),
        "Coronavirus" to listOf("""corona\s*virus""", """corona""", """\bCCo?V\b"""),
        "Kennel cough (Bordetella)" to listOf("""bordetella""", """kennel\s*cough""", """\bKC\b""", """nobivac\s*kc""", """bronchi\s*-?\s*shield"""),
        "Canine influenza" to listOf("""(?<!para)influenza""", """\bCIV\b""", """h3n2"""),
        "Lyme" to listOf("""lyme""", """borrelia"""),
        "FVRCP" to listOf("""fvrcp""", """\bRCP\b""", """tricat""", """feligen\s*\w*""", """purevax\s*rcp\w*""", """(?:3|4)\s*-?\s*in\s*-?\s*1"""),
        "FeLV" to listOf("""fe\s*lv""", """feline\s*leuk\w*""", """leucogen"""),
        "FIV" to listOf("""\bFIV\b"""),
        "Deworming" to listOf("""de\s*-?\s*worm\w*""", """drontal\s*\w*""", """fenbendazole""", """praziquantel""", """pyrantel""", """milbemax""", """albendazole"""),
        "Tick & flea" to listOf("""tick""", """flea""", """nexgard\w*""", """bravecto""", """frontline\w*""", """simparica\w*""", """revolution"""),
    )

    private val vaccineRegexes: List<Pair<String, Regex>> = catalog.map { (name, patterns) ->
        val body = patterns.joinToString("|") { p -> if (p.startsWith("\\b")) p else "(?<![a-z0-9])(?:$p)(?![a-z0-9])" }
        name to Regex(body, RegexOption.IGNORE_CASE)
    }

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MONTH = """(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?"""

    private val isoDate = Regex("""(?<!\d)(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)""")
    private val numericDate = Regex("""(?<!\d)(\d{1,2})\s?[-/.]\s?(\d{1,2})\s?[-/.]\s?(\d{4}|\d{2})(?!\d)""")
    private val dayMonthName = Regex("""(?<!\d)(\d{1,2})(?:st|nd|rd|th)?[\s\-/.,]*$MONTH[\s\-/.,]*(\d{4}|\d{2})(?!\d)""", RegexOption.IGNORE_CASE)
    private val monthNameDay = Regex("""(?<![a-z])$MONTH\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})(?!\d)""", RegexOption.IGNORE_CASE)

    private val dueHint = Regex("""\b(due|next|booster|revacc\w*|re-?vacc\w*|valid\s+till|expir\w*)\b""", RegexOption.IGNORE_CASE)
    private val batchRegex = Regex("""\b(?:batch|lot|b\.?\s?no|lot\s?no)\.?\s*[:#-]?\s*([A-Z0-9][A-Z0-9\-/]{2,14})""", RegexOption.IGNORE_CASE)
    private val clinicHint = Regex("""\b(clinic|hospital|veterinary|vet\s*care|pet\s*care|animal\s*care|polyclinic|pet\s*hospital|vets?)\b""", RegexOption.IGNORE_CASE)
    /** Qualifications printed after a vet's name ("Dr. S. Karthik BVSc & AH"). */
    private val degree = Regex("""(?i)(b\.?v\.?sc\w*|m\.?v\.?sc\w*|d\.?v\.?m\.?|bvms?|mrcvs|ph\.?d\.?|b\.?v\.?m\.?)""")
    private val doctor = Regex("""\bDr\.?\s+([A-Z][A-Za-z.]*(?:\s+[A-Z][A-Za-z.]*){0,3})""")

    fun parse(text: String, dayFirst: Boolean = true): ParsedRecord {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val vaccines = mutableListOf<ParsedVaccine>()
        val allDates = mutableListOf<Ymd>()

        lines.forEachIndexed { index, line ->
            val dates = findDates(line, dayFirst)
            allDates += dates
            val names = findVaccines(line)
            if (names.isEmpty()) return@forEachIndexed

            var lineDates = dates
            var hintSource = line
            // Table layout: the dates sit on the next line (when that line names no vaccine itself).
            if (lineDates.isEmpty() && index + 1 < lines.size && findVaccines(lines[index + 1]).isEmpty()) {
                lineDates = findDates(lines[index + 1], dayFirst)
                hintSource = line + " " + lines[index + 1]
            }
            val sorted = lineDates.distinct().sorted()
            val (given, due) = when {
                sorted.size >= 2 -> sorted.first() to sorted.last()
                sorted.size == 1 && dueHint.containsMatchIn(hintSource) && !hintSource.contains("given", ignoreCase = true) -> null to sorted.first()
                sorted.size == 1 -> sorted.first() to null
                else -> null to null
            }
            val batch = batchRegex.find(hintSource)?.groupValues?.get(1)?.uppercase().orEmpty()
            names.forEach { name -> vaccines += ParsedVaccine(name, given, due, batch) }
        }

        val unique = vaccines
            .groupBy { it.name to it.given }
            .map { (_, group) -> group.firstOrNull { it.due != null } ?: group.first() }

        val clinic = lines.firstOrNull { line ->
            clinicHint.containsMatchIn(line) && findVaccines(line).isEmpty() && findDates(line, dayFirst).isEmpty() &&
                line.length in 4..70 && line.count { it.isDigit() } < 6
        }?.trim(' ', ':', '-', ',')
        val vet = lines.firstNotNullOfOrNull { doctor.find(it)?.groupValues?.get(1) }
            ?.split(Regex("""\s+"""))
            ?.takeWhile { !degree.matches(it) }
            ?.joinToString(" ")
            ?.takeIf { it.isNotBlank() }
            ?.let { "Dr. " + it.trimEnd('.') }

        val lower = text.lowercase()
        val type = when {
            unique.any { it.name != "Deworming" && it.name != "Tick & flea" } || "vaccin" in lower || "immuni" in lower -> DocumentType.VACCINE_CARD
            Regex("""\b(rx|prescription|tab\.?|cap\.?|syrup|bid|tid|od|sos)\b""").containsMatchIn(lower) -> DocumentType.PRESCRIPTION
            Regex("""\b(cbc|haemoglobin|hemoglobin|creatinine|bun|alt|sgpt|platelet|wbc|rbc|lab\s+report|biochem\w*)\b""").containsMatchIn(lower) -> DocumentType.LAB_REPORT
            unique.isNotEmpty() -> DocumentType.VACCINE_CARD
            clinic != null || vet != null -> DocumentType.VET_CARD
            else -> null
        }

        val issued = unique.mapNotNull { it.given }.maxOrNull() ?: allDates.minOrNull()
        return ParsedRecord(
            suggestedType = type,
            vaccines = unique,
            issuedOn = issued,
            clinic = clinic,
            vetName = vet,
            allDates = allDates.distinct().sorted(),
        )
    }

    /** Canonical vaccine names on a line. Matched spans are masked so "DHPPi+L" isn't also "Lepto". */
    fun findVaccines(line: String): List<String> {
        var masked = line
        val found = mutableListOf<String>()
        for ((name, regex) in vaccineRegexes) {
            val match = regex.find(masked) ?: continue
            if (name !in found) found += name
            masked = regex.replace(masked) { m -> " ".repeat(m.value.length) }
        }
        return found
    }

    fun findDates(line: String, dayFirst: Boolean = true): List<Ymd> {
        val out = mutableListOf<Ymd>()
        var rest = line
        fun consume(regex: Regex, build: (MatchResult) -> Ymd?) {
            regex.findAll(rest).toList().forEach { m ->
                build(m)?.let { out += it }
                rest = rest.replaceRange(m.range, " ".repeat(m.value.length))
            }
        }
        consume(isoDate) { m -> valid(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        consume(dayMonthName) { m -> valid(year(m.groupValues[3]), month(m.groupValues[2]), m.groupValues[1].toInt()) }
        consume(monthNameDay) { m -> valid(m.groupValues[3].toInt(), month(m.groupValues[1]), m.groupValues[2].toInt()) }
        consume(numericDate) { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val y = year(m.groupValues[3])
            when {
                a > 12 -> valid(y, b, a)
                b > 12 -> valid(y, a, b)
                dayFirst -> valid(y, b, a)
                else -> valid(y, a, b)
            }
        }
        return out
    }

    private fun month(name: String): Int = months.indexOf(name.lowercase().take(3)) + 1

    private fun year(raw: String): Int = raw.toInt().let { if (raw.length == 2) 2000 + it else it }

    private fun valid(year: Int, month: Int, day: Int): Ymd? {
        if (year !in 1990..2100 || month !in 1..12 || day < 1) return null
        val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        val max = when (month) {
            2 -> if (leap) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return if (day <= max) Ymd(year, month, day) else null
    }
}
