package com.fieldlog.powerdebug.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 工程/项目：调试任务的根单位，一个项目包含一台或多台柜子 */
@Entity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val code: String = "",
    val remark: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** 柜子类型模板：绑定预选测试项候选池，与具体柜子名字无关 */
@Entity(tableName = "cabinet_types")
data class CabinetType(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val remark: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** 预选测试项候选池条目，属于某个柜子类型 */
@Entity(
    tableName = "candidate_items",
    indices = [
        Index("typeId"),
        Index(value = ["typeId", "content"], unique = true)
    ]
)
data class CandidateItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val typeId: Long,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** 柜子实例：现场的实设备，隶属一个项目，引用一个柜子类型 */
@Entity(
    tableName = "instances",
    foreignKeys = [
        ForeignKey(
            entity = Project::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CabinetType::class,
            parentColumns = ["id"],
            childColumns = ["typeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index("typeId")]
)
data class CabinetInstance(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val typeId: Long,
    val name: String,
    val deviceCode: String = "",
    val location: String = "",
    val installer: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** 调试日志；circuit 为空表示整柜测试 */
@Entity(
    tableName = "debug_logs",
    foreignKeys = [
        ForeignKey(
            entity = CabinetInstance::class,
            parentColumns = ["id"],
            childColumns = ["instanceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("instanceId"), Index("createdAt")]
)
data class DebugLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val instanceId: Long,
    val circuit: String = "",
    val testContent: String,
    val tester: String = "",
    val remark: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** 故障记录：status 0=待处理 1=已解决；resolvedAt=0 表示未解决 */
@Entity(
    tableName = "fault_records",
    foreignKeys = [
        ForeignKey(
            entity = DebugLog::class,
            parentColumns = ["id"],
            childColumns = ["logId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("logId")]
)
data class FaultRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var logId: Long = 0,
    var circuit: String = "",
    var symptom: String = "",
    var solution: String = "",
    var occurredAt: Long = System.currentTimeMillis(),
    var resolvedAt: Long = 0,
    var status: Int = STATUS_PENDING
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_RESOLVED = 1
    }
}

// ---------- 查询结果 POJO ----------

data class LogListItem(
    @Embedded val log: DebugLog,
    val projectName: String,
    val typeName: String,
    val instanceName: String,
    val deviceCode: String,
    val installer: String,
    val pendingCount: Int,
    val resolvedCount: Int
)

data class ProjectListItem(
    @Embedded val project: Project,
    val cabinetCount: Int,
    val logCount: Int
)

data class TypeListItem(
    @Embedded val type: CabinetType,
    val instanceCount: Int,
    val itemCount: Int
)

data class InstanceRow(
    @Embedded val instance: CabinetInstance,
    val projectName: String
)

data class FaultExportRow(
    @Embedded val fault: FaultRecord,
    val projectName: String,
    val instanceName: String,
    val deviceCode: String
)
