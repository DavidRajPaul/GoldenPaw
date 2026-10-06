package com.goldenpaw.report

import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.WeightUnit
import com.goldenpaw.domain.repository.AppFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** Creates the platform's [ReportCanvas] (PdfDocument on Android, [SimplePdfCanvas] elsewhere). */
fun interface ReportCanvasFactory {
    fun create(): ReportCanvas
}

/** Generates the vet report PDF and writes it to the cache directory. */
class VetReportService(
    private val dataSource: VetReportDataSource,
    private val canvasFactory: ReportCanvasFactory,
    private val files: AppFiles,
) {
    /** Returns the absolute path of the generated PDF. */
    suspend fun generate(pet: Pet, options: ReportOptions, unit: WeightUnit, ownerName: String): String {
        val data = dataSource.load(pet, options, unit, ownerName)
        return withContext(Dispatchers.IO) {
            val bytes = VetReportLayout(canvasFactory.create()).render(data)
            val dir = "${files.cacheDir}/reports"
            files.deleteRecursively(dir)
            val safeName = pet.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "pet" }
            val path = "$dir/GoldenPaw_${safeName}_${options.to}.pdf"
            files.writeBytes(path, bytes)
            path
        }
    }
}
