package com.smartexpense.manager.domain.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.smartexpense.manager.domain.model.BalanceSummary
import com.smartexpense.manager.domain.model.LedgerEntry
import com.smartexpense.manager.domain.model.TransactionType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfStatementGenerator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun generateStatement(
        userName: String,
        currencySymbol: String,
        startDate: String,
        endDate: String,
        summary: BalanceSummary,
        filteredEntries: List<LedgerEntry>
    ): File {
        val dir = File(context.cacheDir, "statements").apply { mkdirs() }
        val file = File(dir, "SmartExpense_Statement_${startDate}_to_${endDate}.pdf")

        val document = PdfDocument()
        val pageWidth = 595 // A4 points
        val pageHeight = 842
        val margin = 36f

        val titlePaint = Paint().apply {
            color = Color.parseColor("#0B0D0F")
            textSize = 20f
            isFakeBoldText = true
        }
        val subtitlePaint = Paint().apply {
            color = Color.parseColor("#4B5563")
            textSize = 10.5f
        }
        val headerPaint = Paint().apply {
            color = Color.parseColor("#0B0D0F")
            textSize = 11f
            isFakeBoldText = true
        }
        val bodyPaint = Paint().apply {
            color = Color.parseColor("#111827")
            textSize = 9.5f
        }
        val expensePaint = Paint().apply {
            color = Color.parseColor("#C2410C")
            textSize = 9.5f
            isFakeBoldText = true
        }
        val incomePaint = Paint().apply {
            color = Color.parseColor("#047857")
            textSize = 9.5f
            isFakeBoldText = true
        }
        val linePaint = Paint().apply {
            color = Color.parseColor("#E5E7EB")
            strokeWidth = 1f
        }

        val generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas
        var y = drawHeader(canvas, margin, titlePaint, subtitlePaint, userName, startDate, endDate, generatedAt)
        y = drawSummaryBox(canvas, margin, pageWidth - margin, y, headerPaint, bodyPaint, linePaint, currencySymbol, summary)
        y = drawTableHeader(canvas, margin, pageWidth - margin, y + 16f, headerPaint, linePaint)

        for (entry in filteredEntries) {
            if (y > pageHeight - 54f) {
                document.finishPage(page)
                pageNumber++
                page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                canvas = page.canvas
                y = drawTableHeader(canvas, margin, pageWidth - margin, 48f, headerPaint, linePaint)
            }
            val tx = entry.transaction
            canvas.drawText("${tx.transactionDate} ${tx.transactionTime}", margin, y, bodyPaint)
            canvas.drawText(tx.description.take(22), margin + 96f, y, bodyPaint)
            canvas.drawText(tx.categoryLabel().take(24), margin + 225f, y, bodyPaint)
            val amtPaint = if (tx.type == TransactionType.EXPENSE) expensePaint else incomePaint
            val sign = if (tx.type == TransactionType.EXPENSE) "-" else "+"
            canvas.drawText("$sign$currencySymbol${"%.2f".format(tx.amount)}", margin + 355f, y, amtPaint)
            canvas.drawText("$currencySymbol${"%.2f".format(entry.runningBalanceAfter)}", margin + 440f, y, bodyPaint)
            canvas.drawLine(margin, y + 6f, pageWidth - margin, y + 6f, linePaint)
            y += 20f
        }

        document.finishPage(page)
        FileOutputStream(file).use { out ->
            document.writeTo(out)
        }
        document.close()
        return file
    }

    private fun drawHeader(
        canvas: Canvas,
        margin: Float,
        titlePaint: Paint,
        subtitlePaint: Paint,
        userName: String,
        startDate: String,
        endDate: String,
        generatedAt: String
    ): Float {
        canvas.drawText("Smart Expense Manager — Financial Statement", margin, 46f, titlePaint)
        canvas.drawText("Account Holder: $userName", margin, 66f, subtitlePaint)
        canvas.drawText("Period: $startDate to $endDate   |   Generated: $generatedAt", margin, 82f, subtitlePaint)
        return 100f
    }

    private fun drawSummaryBox(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        headerPaint: Paint,
        bodyPaint: Paint,
        linePaint: Paint,
        currency: String,
        summary: BalanceSummary
    ): Float {
        canvas.drawLine(left, top, right, top, linePaint)
        val y1 = top + 18f
        canvas.drawText("Opening Balance: $currency${"%.2f".format(summary.openingBalance)}", left, y1, headerPaint)
        canvas.drawText("Total Income: +$currency${"%.2f".format(summary.totalIncome)}", left + 260f, y1, bodyPaint)
        val y2 = y1 + 18f
        canvas.drawText("Total Expenses: -$currency${"%.2f".format(summary.totalExpenses)}", left, y2, bodyPaint)
        canvas.drawText("Closing Balance: $currency${"%.2f".format(summary.availableBalance)}", left + 260f, y2, headerPaint)
        canvas.drawLine(left, y2 + 10f, right, y2 + 10f, linePaint)
        return y2 + 18f
    }

    private fun drawTableHeader(
        canvas: Canvas,
        left: Float,
        right: Float,
        y: Float,
        headerPaint: Paint,
        linePaint: Paint
    ): Float {
        canvas.drawText("Date & Time", left, y, headerPaint)
        canvas.drawText("Description", left + 96f, y, headerPaint)
        canvas.drawText("Category", left + 225f, y, headerPaint)
        canvas.drawText("Amount", left + 355f, y, headerPaint)
        canvas.drawText("Balance", left + 440f, y, headerPaint)
        canvas.drawLine(left, y + 6f, right, y + 6f, linePaint)
        return y + 22f
    }
}
