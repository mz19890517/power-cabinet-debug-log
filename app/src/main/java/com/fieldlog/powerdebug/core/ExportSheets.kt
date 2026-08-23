package com.fieldlog.powerdebug.core

import android.content.Context
import com.fieldlog.powerdebug.R
import com.fieldlog.powerdebug.data.db.FaultExportRow
import com.fieldlog.powerdebug.data.db.FaultRecord
import com.fieldlog.powerdebug.data.db.LogListItem
import com.fieldlog.powerdebug.util.DT

/**
 * Excel 双表（调试日志+故障记录）构建器。
 * 全量导出与项目/单柜定向导出共用同一格式，保证 A4 打印适配一致。
 */
object ExportSheets {

    fun build(
        ctx: Context,
        logs: List<LogListItem>,
        faults: List<FaultExportRow>
    ): List<XlsxWriter.SheetDef> {
        val logRows = logs.mapIndexed { i, it ->
            listOf(
                (i + 1).toString(),
                it.projectName,
                it.typeName,
                it.instanceName,
                it.deviceCode,
                it.log.circuit.ifEmpty { ctx.getString(R.string.whole_cabinet) },
                it.log.testContent,
                it.log.tester,
                it.log.remark,
                if (it.installer.isBlank()) "" else "${it.installer}",
                it.log.createdBy,
                it.log.updatedBy,
                it.pendingCount.toString(),
                it.resolvedCount.toString(),
                DT.full(it.log.createdAt),
                DT.full(it.log.updatedAt)
            )
        }

        val faultRows = faults.mapIndexed { i, f ->
            listOf(
                (i + 1).toString(),
                f.projectName,
                f.instanceName,
                f.deviceCode,
                f.fault.circuit.ifEmpty { ctx.getString(R.string.whole_cabinet) },
                f.fault.symptom,
                f.fault.solution,
                DT.full(f.fault.occurredAt),
                if (f.fault.status == FaultRecord.STATUS_RESOLVED) DT.full(f.fault.resolvedAt) else "",
                ctx.getString(
                    if (f.fault.status == FaultRecord.STATUS_RESOLVED) R.string.fault_status_resolved
                    else R.string.fault_status_pending
                ),
                DT.full(f.fault.occurredAt).ifEmpty { "-" }
            )
        }

        return listOf(
            XlsxWriter.SheetDef(
                name = "调试日志",
                headers = listOf(
                    "序号", "项目", "柜子类型", "实例名称", "设备编号", "回路",
                    "测试内容", "测试人员", "备注", "安装人员", "创建账号", "修改账号",
                    "待处理故障数", "已解决故障数", "记录时间", "更新时间"
                ),
                rows = logRows,
                wrapCols = setOf(6, 8)
            ),
            XlsxWriter.SheetDef(
                name = "故障记录",
                headers = listOf(
                    "序号", "项目", "柜子实例", "设备编号", "故障回路",
                    "问题现象", "解决方法", "发生时间", "解决完成时间", "状态", "关联日志时间"
                ),
                rows = faultRows,
                wrapCols = setOf(5, 6),
                landscape = true
            )
        )
    }
}
