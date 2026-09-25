package au.edu.unimelb.floraguide.data.catalog

/** VicFlora flowering statements for common Parkville plants (#16), from tools/build-flowering-table.py. */
const val FLOWERING_TABLE_ASSET = "vicflora-flowering.tsv"

/** Documented flowering months by exact scientific name; every other status stays unknown. */
fun parseFloweringTable(tsv: String): Map<String, Set<Int>> {
    val rows = tsv.lines().filter { it.isNotBlank() }.map { it.split('\t') }
    val header = rows.first()
    val name = header.indexOf("scientific_name")
    val status = header.indexOf("status")
    val months = header.indexOf("flowering_months")
    return rows.drop(1).filter { it[status] == "documented" }
        .associate { it[name] to it[months].split(',').map(String::toInt).toSet() }
}
