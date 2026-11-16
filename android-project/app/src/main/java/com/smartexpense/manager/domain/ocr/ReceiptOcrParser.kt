package com.smartexpense.manager.domain.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.smartexpense.manager.domain.model.ExtractedReceiptDraft
import com.smartexpense.manager.domain.model.Transaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptOcrParser @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractFromImageUri(
        uri: Uri,
        existingTransactions: List<Transaction>
    ): ExtractedReceiptDraft {
        val image = InputImage.fromFilePath(context, uri)
        val result = recognizer.process(image).await()
        return parseText(result.text, existingTransactions)
    }

    suspend fun extractFromPdfUri(
        uri: Uri,
        existingTransactions: List<Transaction>
    ): ExtractedReceiptDraft {
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Cannot open PDF receipt")
        val combinedText = StringBuilder()

        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val pageCount = minOf(renderer.pageCount, 3)
                for (index in 0 until pageCount) {
                    renderer.openPage(index).use { page ->
                        val bitmap = Bitmap.createBitmap(
                            page.width * 2,
                            page.height * 2,
                            Bitmap.Config.ARGB_8888
                        )
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val inputImage = InputImage.fromBitmap(bitmap, 0)
                        val visionText = recognizer.process(inputImage).await()
                        combinedText.appendLine(visionText.text)
                        bitmap.recycle()
                    }
                }
            }
        }
        return parseText(combinedText.toString(), existingTransactions)
    }

    /**
     * Extracts fields only when clearly present in OCR text.
     * Never fabricates missing values; instead flags them in [ExtractedReceiptDraft.missingFields]
     * so the confirmation screen explicitly asks the user.
     */
    fun parseText(
        rawText: String,
        existingTransactions: List<Transaction>
    ): ExtractedReceiptDraft {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        val merchant = extractMerchant(lines)
        val amount = extractAmount(rawText)
        val date = extractDate(rawText)
        val time = extractTime(rawText)
        val paymentMethod = extractPaymentMethod(rawText)
        val referenceId = extractReferenceId(rawText)
        val invoiceNumber = extractInvoiceNumber(rawText)

        val missing = mutableListOf<String>()
        if (merchant.isBlank()) missing += "Merchant Name"
        if (amount.isBlank()) missing += "Amount"
        if (date.isBlank()) missing += "Transaction Date"
        if (time.isBlank()) missing += "Transaction Time"
        if (paymentMethod.isBlank()) missing += "Payment Method"

        val duplicateMatch = findDuplicate(
            referenceId = referenceId,
            amount = amount.toDoubleOrNull(),
            date = date,
            merchant = merchant,
            existing = existingTransactions
        )

        return ExtractedReceiptDraft(
            merchantName = merchant,
            amount = amount,
            transactionDate = date,
            transactionTime = time,
            paymentMethod = paymentMethod,
            referenceId = referenceId,
            invoiceNumber = invoiceNumber,
            rawText = rawText,
            missingFields = missing,
            isPossibleDuplicate = duplicateMatch != null,
            duplicateReason = duplicateMatch
        )
    }

    private fun extractMerchant(lines: List<String>): String {
        val ignoredPrefixes = listOf(
            "tax invoice", "receipt", "payment successful", "paid to", "upi",
            "gstin", "date", "time", "invoice", "bill"
        )
        val paidToLine = lines.firstOrNull { it.lowercase(Locale.ROOT).startsWith("paid to") }
        if (paidToLine != null) {
            return paidToLine.substringAfter(":").replace("Paid to", "", ignoreCase = true).trim()
        }
        return lines.firstOrNull { line ->
            val lower = line.lowercase(Locale.ROOT)
            line.length in 3..48 && ignoredPrefixes.none { lower.startsWith(it) } &&
                line.any { it.isLetter() }
        }.orEmpty()
    }

    private fun extractAmount(text: String): String {
        val totalRegex = Regex(
            """(?i)(?:grand\s*total|total\s*amount|amount\s*paid|net\s*payable|total|paid)\s*[:\-]?\s*(?:₹|INR|Rs\.?)?\s*([0-9]{1,3}(?:,[0-9]{2,3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)"""
        )
        val currencyRegex = Regex("""(?:₹|INR|Rs\.?)\s*([0-9]{1,3}(?:,[0-9]{2,3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)""")

        val totalMatch = totalRegex.findAll(text).lastOrNull()?.groupValues?.getOrNull(1)
        val candidate = totalMatch ?: currencyRegex.findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.replace(",", "")?.toDoubleOrNull() }
            .maxOrNull()
            ?.toString()

        return candidate?.replace(",", "").orEmpty()
    }

    private fun extractDate(text: String): String {
        val isoRegex = Regex("""\b(20[0-9]{2})[-/](0[1-9]|1[0-2])[-/](0[1-9]|[12][0-9]|3[01])\b""")
        isoRegex.find(text)?.let { m ->
            return "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}"
        }

        val dmyRegex = Regex("""\b(0?[1-9]|[12][0-9]|3[01])[/-](0?[1-9]|1[0-2])[/-](20[0-9]{2}|[0-9]{2})\b""")
        dmyRegex.find(text)?.let { m ->
            val day = m.groupValues[1].padStart(2, '0')
            val month = m.groupValues[2].padStart(2, '0')
            val yearRaw = m.groupValues[3]
            val year = if (yearRaw.length == 2) "20$yearRaw" else yearRaw
            return "$year-$month-$day"
        }

        val namedMonthRegex = Regex("""\b([0-3]?[0-9])\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+(20[0-9]{2})\b""", RegexOption.IGNORE_CASE)
        namedMonthRegex.find(text)?.let { m ->
            val parsed = runCatching {
                LocalDate.parse(
                    "${m.groupValues[1].padStart(2, '0')} ${m.groupValues[2].take(3)} ${m.groupValues[3]}",
                    DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH)
                ).toString()
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return ""
    }

    private fun extractTime(text: String): String {
        val amPmRegex = Regex("""\b(0?[1-9]|1[0-2]):([0-5][0-9])\s*(AM|PM)\b""", RegexOption.IGNORE_CASE)
        amPmRegex.find(text)?.let { m ->
            var hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2]
            val period = m.groupValues[3].uppercase(Locale.ROOT)
            if (period == "PM" && hour < 12) hour += 12
            if (period == "AM" && hour == 12) hour = 0
            return "%02d:%s".format(hour, minute)
        }

        val time24Regex = Regex("""\b([01]?[0-9]|2[0-3]):([0-5][0-9])\b""")
        time24Regex.find(text)?.let { m ->
            return "${m.groupValues[1].padStart(2, '0')}:${m.groupValues[2]}"
        }
        return ""
    }

    private fun extractPaymentMethod(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            "google pay" in lower || "gpay" in lower -> "Google Pay (UPI)"
            "phonepe" in lower -> "PhonePe (UPI)"
            "paytm" in lower -> "Paytm (UPI)"
            "upi" in lower -> "UPI"
            "credit card" in lower -> "Credit Card"
            "debit card" in lower -> "Debit Card"
            "cash" in lower -> "Cash"
            "netbanking" in lower || "net banking" in lower -> "Net Banking"
            else -> ""
        }
    }

    private fun extractReferenceId(text: String): String {
        val refRegex = Regex("""(?i)(?:upi\s*(?:transaction|ref(?:erence)?)\s*(?:id|no\.?)?|utr|rrn|txn\s*id|reference\s*id)\s*[:#\-]?\s*([A-Za-z0-9\-]{8,36})""")
        return refRegex.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun extractInvoiceNumber(text: String): String {
        val invRegex = Regex("""(?i)(?:invoice\s*(?:no\.?|number|#)|bill\s*(?:no\.?|#)|receipt\s*no\.?)\s*[:#\-]?\s*([A-Za-z0-9\-/]{3,30})""")
        return invRegex.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun findDuplicate(
        referenceId: String,
        amount: Double?,
        date: String,
        merchant: String,
        existing: List<Transaction>
    ): String? {
        if (referenceId.isNotBlank()) {
            val match = existing.firstOrNull {
                it.referenceId.equals(referenceId, ignoreCase = true)
            }
            if (match != null) {
                return "Reference ID $referenceId already exists (${match.description} on ${match.transactionDate})."
            }
        }
        if (amount != null && date.isNotBlank() && merchant.isNotBlank()) {
            val normMerchant = merchant.lowercase(Locale.ROOT).trim()
            val match = existing.firstOrNull {
                it.transactionDate == date &&
                    kotlin.math.abs(it.amount - amount) < 0.01 &&
                    it.merchant.lowercase(Locale.ROOT).trim() == normMerchant
            }
            if (match != null) {
                return "A matching receipt for $merchant of $amount on $date already exists."
            }
        }
        return null
    }
}
