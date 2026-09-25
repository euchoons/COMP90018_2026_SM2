package au.edu.unimelb.floraguide.data.catalog

import au.edu.unimelb.floraguide.domain.model.FloweringRecord

/** VicFlora flowering statements for common Parkville plants (#16), from tools/build-flowering-table.py. */
const val FLOWERING_TABLE_ASSET = "vicflora-flowering.tsv"

/** Documented flowering by exact scientific name; every other status stays unknown. */
fun parseFloweringTable(tsv: String): Map<String, FloweringRecord> {
    val rows = tsv.lines().filter { it.isNotBlank() }.map { it.split('\t') }
    val header = rows.first()
    val name = header.indexOf("scientific_name")
    val status = header.indexOf("status")
    val months = header.indexOf("flowering_months")
    val statement = header.indexOf("source_text")
    val url = header.indexOf("source_url")
    return rows.drop(1).filter { it[status] == "documented" }.associate {
        it[name] to FloweringRecord(it[months].split(',').map(String::toInt).toSet(), it[statement], it[url])
    }
}
