package com.zhuanggui.pda.excel

import android.content.Context
import android.net.Uri
import com.zhuanggui.pda.data.entity.ScanLog
import com.zhuanggui.pda.data.entity.ScanResult
import com.zhuanggui.pda.data.entity.WorkOrderItem
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.OutputStream

data class ImportedItem(val barcode: String, val quantity: Int)

class ExcelService {

    /**
     * 读取工单清单 Excel（.xlsx / .xls）。
     * 约定表头：条码/条形码/Barcode | 数量/Quantity（或仅一列条码）。
     * 返回去重后的条目列表。
     */
    fun importItems(context: Context, uri: Uri): List<ImportedItem> {
        val result = mutableListOf<ImportedItem>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val workbook: Workbook = WorkbookFactory.create(input)
            val sheet: Sheet = workbook.getSheetAt(0)
            val header = sheet.getRow(0)
            if (header == null) {
                workbook.close()
                return emptyList()
            }
            val (barcodeCol, qtyCol) = locateColumns(header)

            for (i in 1..sheet.lastRowNum) {
                val row = sheet.getRow(i) ?: continue
                val barcode = getString(row, barcodeCol).trim()
                if (barcode.isEmpty()) continue
                val qty = if (qtyCol >= 0) getInt(row, qtyCol) else 0
                result.add(ImportedItem(barcode, qty))
            }
            workbook.close()
        }
        // 去重（同一条码取最大数量）
        return result.groupBy { it.barcode }.map { (barcode, list) ->
            ImportedItem(barcode, list.maxOf { it.quantity })
        }
    }

    /**
     * 导出扫码日志到 Excel：条码 | 结果 | 时间
     */
    fun exportLogs(
        output: OutputStream,
        orderName: String,
        logs: List<ScanLog>,
        items: List<WorkOrderItem>
    ) {
        XSSFWorkbook().use { workbook ->
            val itemLookup = items.associateBy { it.barcode }
            val sheet = workbook.createSheet("扫码日志")

            val header = sheet.createRow(0)
            header.createCell(0).setCellValue("条码")
            header.createCell(1).setCellValue("产品名称")
            header.createCell(2).setCellValue("结果")
            header.createCell(3).setCellValue("扫码时间")

            var rowIdx = 1
            for (log in logs) {
                val row = sheet.createRow(rowIdx++)
                val item = itemLookup[log.barcode]
                row.createCell(0).setCellValue(log.barcode)
                row.createCell(1).setCellValue(item?.productName ?: "")
                row.createCell(2).setCellValue(resultText(log.result))
                row.createCell(3).setCellValue(
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(log.scannedAt)
                )
            }
            for (c in 0..3) sheet.autoSizeColumn(c)
            workbook.write(output)
        }
    }

    private fun locateColumns(header: Row): Pair<Int, Int> {
        var barcodeCol = -1
        var qtyCol = -1
        for (cell in header) {
            val title = getString(header, cell.columnIndex).trim().lowercase()
            when {
                barcodeCol < 0 && listOf("条码", "条形码", "barcode", "码").any { title.contains(it) } ->
                    barcodeCol = cell.columnIndex
                qtyCol < 0 && listOf("数量", "quantity", "数", "件数").any { title.contains(it) } ->
                    qtyCol = cell.columnIndex
            }
        }
        if (barcodeCol < 0) barcodeCol = 0
        return Pair(barcodeCol, qtyCol)
    }

    private fun getString(row: Row, col: Int): String {
        val cell = row.getCell(col) ?: return ""
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> cell.numericCellValue.toLong().toString()
            else -> cell.toString()
        }
    }

    private fun getInt(row: Row, col: Int): Int {
        val cell = row.getCell(col) ?: return 0
        return when (cell.cellType) {
            CellType.NUMERIC -> cell.numericCellValue.toInt()
            CellType.STRING -> cell.stringCellValue.trim().toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun resultText(r: ScanResult): String = when (r) {
        ScanResult.OK -> "允许"
        ScanResult.NOT_IN_LIST -> "不在清单"
        ScanResult.OVER_LIMIT -> "超量"
        ScanResult.SERIAL_MISMATCH -> "串号"
        ScanResult.DUPLICATE -> "重复"
    }
}
