package au.edu.unimelb.floraguide.data.catalog

import au.edu.unimelb.floraguide.domain.model.FloweringRecord

/** VicFlora flowering statements for common Parkville plants (#16), from tools/build-flowering-table.py. */
const val FLOWERING_TABLE_ASSET = "vicflora-flowering.tsv"

/**
 * Documented flowering by exact scientific name, plus the WCVP name Pl@ntNet uses when WCVP files
 * VicFlora's name as a synonym. Every other status stays unknown.
 */
fun parseFloweringTable(tsv: String): Map<String, FloweringRecord> {
    val rows = tsv.lines().filter { it.isNotBlank() }.map { it.split('\t') }
    val header = rows.first()
    val name = header.indexOf("scientific_name")
    val wcvp = header.indexOf("wcvp_name")
    val status = header.indexOf("status")
    val months = header.indexOf("flowering_months")
    val statement = header.indexOf("source_text")
    val url = header.indexOf("source_url")
    val documented = rows.drop(1).filter { it[status] == "documented" }.map {
        it[wcvp] to FloweringRecord(it[name], it[months].split(',').map(String::toInt).toSet(), it[statement], it[url])
    }
    // Added last, a species' own row always wins over another species' alias.
    return documented.filter { it.first.isNotBlank() }.toMap() + documented.associate { it.second.sourceName to it.second }
}
