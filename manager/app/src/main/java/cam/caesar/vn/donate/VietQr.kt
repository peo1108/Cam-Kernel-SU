package cam.su.kernel.donate

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * VietQR (NAPAS 247) payload: an EMVCo merchant-presented QR that every Vietnamese banking
 * app and MoMo can scan to fill in a transfer to [account] at the bank with code [bankBin].
 */
object VietQr {
    private const val NAPAS_GUID = "A000000727"
    private const val SERVICE_TO_ACCOUNT = "QRIBFTTA"

    fun payload(bankBin: String, account: String, amount: Long? = null, message: String? = null): String {
        require(bankBin.length == 6 && bankBin.all(Char::isDigit)) { "bank BIN must be 6 digits" }
        require(account.isNotEmpty() && account.length <= 19) { "account must be 1..19 chars" }
        val beneficiary = field("00", bankBin) + field("01", account)
        val merchant = field("00", NAPAS_GUID) + field("01", beneficiary) + field("02", SERVICE_TO_ACCOUNT)
        val body = buildString {
            append(field("00", "01"))
            // 11 = static (reusable, payer types the amount), 12 = dynamic (amount fixed)
            append(field("01", if (amount != null) "12" else "11"))
            append(field("38", merchant))
            append(field("53", "704")) // VND
            if (amount != null) append(field("54", amount.toString()))
            append(field("58", "VN"))
            if (!message.isNullOrEmpty()) append(field("62", field("08", message)))
            append("6304")
        }
        return body + crc16(body)
    }

    fun bitmap(payload: String, size: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 0,
        )
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size) { i ->
            if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun field(id: String, value: String): String {
        require(value.length <= 99) { "EMV field $id too long" }
        return id + value.length.toString().padStart(2, '0') + value
    }

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF), as EMVCo field 63 requires. */
    internal fun crc16(data: String): String {
        var crc = 0xFFFF
        for (byte in data.toByteArray(Charsets.UTF_8)) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
            }
            crc = crc and 0xFFFF
        }
        return crc.toString(16).uppercase().padStart(4, '0')
    }
}
