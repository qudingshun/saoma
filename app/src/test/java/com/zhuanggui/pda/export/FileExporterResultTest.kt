package com.zhuanggui.pda.export

import com.zhuanggui.pda.data.entity.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Test

/** 装柜明细结果列（v1.11.1）：正常 / 辅助 / 异常。 */
class FileExporterResultTest {

    @Test
    fun mapsScanResultToExportLabel() {
        assertEquals("正常", FileExporter.resultLabel(ScanResult.OK))
        assertEquals("辅助", FileExporter.resultLabel(ScanResult.AUX))
        assertEquals("异常", FileExporter.resultLabel(ScanResult.DUPLICATE))
        assertEquals("异常", FileExporter.resultLabel(ScanResult.SERIAL_MISMATCH))
        assertEquals("异常", FileExporter.resultLabel(ScanResult.OVER_LIMIT))
        assertEquals("异常", FileExporter.resultLabel(ScanResult.NOT_IN_LIST))
    }
}
