package au.edu.unimelb.floraguide.data.firebase

import java.io.InputStream

/** Small signature check, not a full image decoder. Independent of Android for regression tests. */
internal object PhotoContentValidation {
    fun contentType(input: InputStream): String {
        val header = ByteArray(8)
        var total = 0
        while (total < header.size) {
            val count = input.read(header, total, header.size - total)
            if (count < 0) break
            if (count == 0) {
                // Defensive progress for unusual streams; never spin on a zero-byte read.
                val next = input.read()
                if (next < 0) break
                header[total++] = next.toByte()
            } else {
                total += count
            }
        }
        if (total >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        require(total == png.size && header.contentEquals(png)) { "Only JPEG and PNG photos are supported." }
        return "image/png"
    }
}
