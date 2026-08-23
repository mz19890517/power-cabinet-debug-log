package com.fieldlog.powerdebug.data

import androidx.room.withTransaction
import com.fieldlog.powerdebug.data.db.AppDatabase
import com.fieldlog.powerdebug.data.db.CabinetInstance
import com.fieldlog.powerdebug.data.db.CabinetType
import com.fieldlog.powerdebug.data.db.CandidateItem
import com.fieldlog.powerdebug.data.db.DebugLog
import com.fieldlog.powerdebug.data.db.FaultExportRow
import com.fieldlog.powerdebug.data.db.FaultRecord
import com.fieldlog.powerdebug.data.db.LogListItem
import com.fieldlog.powerdebug.data.db.Project
import com.fieldlog.powerdebug.data.db.ProjectListItem
import com.fieldlog.powerdebug.data.db.TesterAccount
import com.fieldlog.powerdebug.data.db.TypeListItem
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 数据统计（工具页展示） */
data class Stats(
    val projects: Int,
    val types: Int,
    val instances: Int,
    val logs: Int,
    val pendingFaults: Int
)

/** 智能合并结果（各表 新增/更新 条数） */
data class MergeResult(
    var newProjects: Int = 0, var updProjects: Int = 0,
    var newTypes: Int = 0, var updTypes: Int = 0,
    var newCands: Int = 0,
    var newInstances: Int = 0, var updInstances: Int = 0,
    var newLogs: Int = 0, var updLogs: Int = 0,
    var newFaults: Int = 0, var updFaults: Int = 0
)

/** 备份文件解析结果：已统一为本机String主键的实体列表 */
private class ParsedBackup {
    val projects = mutableListOf<Project>()
    val types = mutableListOf<CabinetType>()
    val cands = mutableListOf<CandidateItem>()
    val instances = mutableListOf<CabinetInstance>()
    val logs = mutableListOf<DebugLog>()
    val faults = mutableListOf<FaultRecord>()
}

/**
 * 业务逻辑统一入口。
 * 后期电脑端/网页端移植时，按同样的语义实现本层即可复用全部业务规则
 * （候选池自动沉淀、级联删除、JSON 备份格式、智能合并等）。
 */
class Repository(private val db: AppDatabase) {

    private val projectDao = db.projectDao()
    private val typeDao = db.cabinetTypeDao()
    private val candDao = db.candidateItemDao()
    private val instanceDao = db.instanceDao()
    private val logDao = db.debugLogDao()
    private val faultDao = db.faultRecordDao()

    private fun newId() = UUID.randomUUID().toString()
    private fun now() = System.currentTimeMillis()

    // ---------- 观察 ----------

    fun watchProjects(): Flow<List<Project>> = projectDao.watchAllAsFlow()
    fun watchProjectItems(): Flow<List<ProjectListItem>> = projectDao.watchListItemsAsFlow()
    fun watchTypeItems(): Flow<List<TypeListItem>> = typeDao.watchListItemsAsFlow()
    fun watchTypes(): Flow<List<CabinetType>> = typeDao.watchAllAsFlow()
    fun watchInstancesOf(projectId: String): Flow<List<CabinetInstance>> =
        instanceDao.watchByProjectAsFlow(projectId)
    fun watchPool(typeId: String): Flow<List<CandidateItem>> = candDao.watchByTypeAsFlow(typeId)

    // ---------- 项目 ----------

    suspend fun getProject(id: String): Project? = projectDao.getByIdOnce(id)

    /** 新增或更新；返回最终id */
    suspend fun saveProject(p: Project): String {
        val row =
            if (p.id.isBlank()) p.copy(id = newId(), createdAt = now(), updatedAt = now())
            else p.copy(updatedAt = now())
        if (projectDao.getByIdOnce(row.id) == null) projectDao.insert(row) else projectDao.update(row)
        return row.id
    }

    /** 返回受影响柜子数（用于确认弹窗），-1 表示项目不存在 */
    suspend fun deleteProject(id: String): Int {
        val p = projectDao.getByIdOnce(id) ?: return -1
        val cabinets = instanceDao.byProjectOnce(id).size
        projectDao.delete(p)
        return cabinets
    }

    // ---------- 柜子类型与候选池 ----------

    suspend fun getType(id: String): CabinetType? = typeDao.getByIdOnce(id)
    suspend fun allTypes(): List<CabinetType> = typeDao.allOnce()

    suspend fun saveType(t: CabinetType): String {
        val row =
            if (t.id.isBlank()) t.copy(id = newId(), createdAt = now(), updatedAt = now())
            else t.copy(updatedAt = now())
        if (typeDao.getByIdOnce(row.id) == null) typeDao.insert(row) else typeDao.update(row)
        return row.id
    }

    /** 返回使用该类型的实例数（用于确认弹窗），-1 表示类型不存在 */
    suspend fun deleteType(id: String): Int {
        val t = typeDao.getByIdOnce(id) ?: return -1
        val usage = instanceDao.byTypeWithProject(id).size
        typeDao.delete(t)
        return usage
    }

    /**
     * 向候选池追加条目：自动按整行去重。
     * @return 新增条数
     */
    suspend fun addCandidatesFromText(typeId: String, text: String): Int {
        val existing = candDao.contentsOnce(typeId).map { it.trim() }.toHashSet()
        var added = 0
        text.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && existing.add(it) }
            .forEach {
                candDao.insert(CandidateItem(id = newId(), typeId = typeId, content = it))
                added++
            }
        return added
    }

    suspend fun deleteCandidate(item: CandidateItem) = candDao.delete(item)

    suspend fun instanceUsageOfType(typeId: String) = instanceDao.byTypeWithProject(typeId)

    // ---------- 柜子实例 ----------

    suspend fun getInstance(id: String): CabinetInstance? = instanceDao.getByIdOnce(id)
    suspend fun instancesOfProjectOnce(projectId: String) = instanceDao.byProjectOnce(projectId)

    suspend fun saveInstance(i: CabinetInstance): String {
        val row =
            if (i.id.isBlank()) i.copy(id = newId(), createdAt = now(), updatedAt = now())
            else i.copy(updatedAt = now())
        if (instanceDao.getByIdOnce(row.id) == null) instanceDao.insert(row) else instanceDao.update(row)
        return row.id
    }

    /** 返回该柜子的日志条数（用于确认弹窗），-1 表示不存在 */
    suspend fun deleteInstance(id: String): Int {
        val inst = instanceDao.getByIdOnce(id) ?: return -1
        val logs = logDao.countLogsOf(inst.id)
        instanceDao.delete(inst)
        return logs
    }

    // ---------- 调试日志 ----------

    suspend fun searchLogs(
        projectId: String, typeId: String, instanceId: String,
        status: Int, circuit: String, q: String
    ): List<LogListItem> = logDao.search(projectId, typeId, instanceId, status, circuit.trim(), q.trim())

    suspend fun getLogDetail(id: String): LogListItem? = logDao.getDetailOnce(id)

    suspend fun distinctCircuits(projectId: String, typeId: String) =
        logDao.distinctCircuits(projectId, typeId)

    /**
     * 保存日志（新建或编辑）并同步故障记录；
     * 同时把测试内容中出现的新行自动沉淀进对应柜子类型的候选池。
     * @param actor 当前登录测试账号，未登录传空串
     */
    suspend fun saveLog(log: DebugLog, faults: List<FaultRecord>, actor: String = "") {
        val inst = instanceDao.getByIdOnce(log.instanceId)
            ?: throw IllegalArgumentException("柜子实例不存在")
        val t = now()
        var saved: DebugLog
        db.withTransaction {
            saved =
                if (log.id.isBlank())
                    log.copy(
                        id = newId(), createdAt = t, updatedAt = t,
                        createdBy = actor.ifBlank { log.createdBy },
                        updatedBy = actor.ifBlank { log.createdBy }
                    )
                else
                    log.copy(
                        updatedAt = t,
                        updatedBy = actor.ifBlank { log.updatedBy },
                        createdBy = log.createdBy.ifBlank { actor }
                    )
            if (log.id.isBlank()) logDao.insert(saved) else logDao.update(saved)
            faultDao.deleteForLog(saved.id)
            faults.forEach { f ->
                faultDao.insert(f.copy(id = f.id.ifBlank { newId() }, logId = saved.id, updatedAt = t))
            }
            // 候选池自动沉淀：测试内容逐行 trim、去重后追加
            val existing = candDao.contentsOnce(inst.typeId).map { it.trim() }.toHashSet()
            saved.testContent.split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() && existing.add(it) }
                .forEach { candDao.insert(CandidateItem(id = newId(), typeId = inst.typeId, content = it)) }
        }
    }

    suspend fun deleteLog(id: String) {
        val l = logDao.getByIdOnce(id) ?: return
        logDao.delete(l)
    }

    suspend fun faultsOf(logId: String) = faultDao.forLogOnce(logId)

    // ---------- 测试员账号 ----------

    suspend fun testerAccounts(): List<TesterAccount> = db.testerAccountDao().allOnce()

    /** 注册/刷新测试员，返回是否新注册 */
    suspend fun registerTester(username: String, source: String): Boolean {
        val dao = db.testerAccountDao()
        val existed = dao.byUsername(username) != null
        if (!existed) dao.insert(TesterAccount(id = newId(), username = username, source = source))
        else dao.updateSource(username, source)
        return !existed
    }

    // ---------- 统计 / 导出 / 备份 ----------

    suspend fun stats(): Stats = Stats(
        projects = projectDao.count(),
        types = typeDao.count(),
        instances = instanceDao.count(),
        logs = logDao.count(),
        pendingFaults = faultDao.countPending()
    )

    suspend fun collectExport(): Pair<List<LogListItem>, List<FaultExportRow>> =
        logDao.exportAll() to faultDao.exportAll()

    companion object {
        const val BACKUP_APP_TAG = "power-debug-log"
        const val BACKUP_SCHEMA = 2
    }

    /**
     * 备份为 JSON 字符串。该格式同时是后期 PC/网页端的官方数据交换格式：
     * 字段名即数据库列名，schemaVersion 变更时需提供迁移说明。
     * schemaVersion 2：全表UUID主键+updatedAt合并时钟；日志含创建/修改账号。
     */
    suspend fun backupJson(): String {
        val jo = JSONObject()
        jo.put("app", BACKUP_APP_TAG)
        jo.put("schemaVersion", BACKUP_SCHEMA)
        jo.put("exportedAt", now())

        fun arr(list: List<JSONObject>): JSONArray = JSONArray().apply { list.forEach(::put) }

        jo.put(
            "projects",
            arr(projectDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("code", it.code)
                    .put("remark", it.remark)
                    .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt)
            })
        )
        jo.put(
            "cabinetTypes",
            arr(typeDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("remark", it.remark)
                    .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt)
            })
        )
        jo.put(
            "candidateItems",
            arr(candDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("typeId", it.typeId).put("content", it.content)
                    .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt)
            })
        )
        jo.put(
            "instances",
            arr(instanceDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("projectId", it.projectId).put("typeId", it.typeId)
                    .put("name", it.name).put("deviceCode", it.deviceCode)
                    .put("location", it.location).put("installer", it.installer)
                    .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt)
            })
        )
        jo.put(
            "logs",
            arr(logDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("instanceId", it.instanceId)
                    .put("circuit", it.circuit).put("testContent", it.testContent)
                    .put("tester", it.tester).put("remark", it.remark)
                    .put("createdBy", it.createdBy).put("updatedBy", it.updatedBy)
                    .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt)
            })
        )
        jo.put(
            "faults",
            arr(faultDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("logId", it.logId).put("circuit", it.circuit)
                    .put("symptom", it.symptom).put("solution", it.solution)
                    .put("occurredAt", it.occurredAt).put("resolvedAt", it.resolvedAt)
                    .put("status", it.status).put("updatedAt", it.updatedAt)
            })
        )
        return jo.toString(2)
    }

    /**
     * 解析备份JSON，统一转换为本机实体。
     * 支持 schemaVersion 2（直接使用）；也支持 1（旧版int主键备份：生成新UUID并重映射全部引用，
     * 缺失的合并时钟字段以 createdAt 兜底）。
     */
    private fun parseBackup(text: String): ParsedBackup {
        val root = JSONObject(text)
        require(root.optString("app") == BACKUP_APP_TAG) { "不是本应用的备份文件" }
        val version = root.optInt("schemaVersion", 1)
        require(version in 1..BACKUP_SCHEMA) { "不支持的备份版本：$version" }
        val pb = ParsedBackup()

        if (version >= 2) {
            root.optJSONArray("projects")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.projects += Project(
                        id = getString("id"), name = getString("name"),
                        code = optString("code"), remark = optString("remark"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("cabinetTypes")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.types += CabinetType(
                        id = getString("id"), name = getString("name"),
                        remark = optString("remark"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("candidateItems")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.cands += CandidateItem(
                        id = getString("id"), typeId = getString("typeId"),
                        content = getString("content"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("instances")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.instances += CabinetInstance(
                        id = getString("id"), projectId = getString("projectId"),
                        typeId = getString("typeId"), name = getString("name"),
                        deviceCode = optString("deviceCode"), location = optString("location"),
                        installer = optString("installer"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("logs")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.logs += DebugLog(
                        id = getString("id"), instanceId = getString("instanceId"),
                        circuit = optString("circuit"), testContent = getString("testContent"),
                        tester = optString("tester"), remark = optString("remark"),
                        createdBy = optString("createdBy"), updatedBy = optString("updatedBy"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("faults")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.faults += FaultRecord(
                        id = getString("id"), logId = getString("logId"),
                        circuit = optString("circuit"), symptom = optString("symptom"),
                        solution = optString("solution"), occurredAt = optLong("occurredAt"),
                        resolvedAt = optLong("resolvedAt"), status = optInt("status"),
                        updatedAt = optLong("updatedAt")
                    )
                }
            }
            return pb
        }

        // ---- v1 旧格式：int主键 → UUID 重映射 ----
        val mapP = HashMap<Long, String>()
        val mapT = HashMap<Long, String>()
        val mapC = HashMap<Long, String>()
        val mapI = HashMap<Long, String>()
        val mapL = HashMap<Long, String>()
        val mapF = HashMap<Long, String>()

        fun idOf(map: MutableMap<Long, String>, old: Long): String =
            map.getOrPut(old) { newId() }

        root.optJSONArray("projects")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                val old = getLong("id"); val t = optLong("createdAt")
                pb.projects += Project(
                    id = idOf(mapP, old), name = getString("name"),
                    code = optString("code"), remark = optString("remark"),
                    createdAt = t, updatedAt = optLong("updatedAt", t)
                )
            }
        }
        root.optJSONArray("cabinetTypes")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                val old = getLong("id"); val t = optLong("createdAt")
                pb.types += CabinetType(
                    id = idOf(mapT, old), name = getString("name"),
                    remark = optString("remark"), createdAt = t, updatedAt = optLong("updatedAt", t)
                )
            }
        }
        root.optJSONArray("candidateItems")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                val old = getLong("id"); val t = optLong("createdAt")
                pb.cands += CandidateItem(
                    id = idOf(mapC, old), typeId = idOf(mapT, getLong("typeId")),
                    content = getString("content"), createdAt = t, updatedAt = optLong("updatedAt", t)
                )
            }
        }
        root.optJSONArray("instances")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                val old = getLong("id"); val t = optLong("createdAt")
                pb.instances += CabinetInstance(
                    id = idOf(mapI, old),
                    projectId = idOf(mapP, getLong("projectId")),
                    typeId = idOf(mapT, getLong("typeId")),
                    name = getString("name"),
                    deviceCode = optString("deviceCode"), location = optString("location"),
                    installer = optString("installer"),
                    createdAt = t, updatedAt = optLong("updatedAt", t)
                )
            }
        }
        root.optJSONArray("logs")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                val old = getLong("id"); val t = optLong("createdAt")
                pb.logs += DebugLog(
                    id = idOf(mapL, old),
                    instanceId = idOf(mapI, getLong("instanceId")),
                    circuit = optString("circuit"), testContent = getString("testContent"),
                    tester = optString("tester"), remark = optString("remark"),
                    createdBy = "", updatedBy = "",
                    createdAt = t, updatedAt = optLong("updatedAt", t)
                )
            }
        }
        root.optJSONArray("faults")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                val old = getLong("id"); val t = optLong("occurredAt")
                pb.faults += FaultRecord(
                    id = idOf(mapF, old),
                    logId = idOf(mapL, getLong("logId")),
                    circuit = optString("circuit"), symptom = optString("symptom"),
                    solution = optString("solution"), occurredAt = t,
                    resolvedAt = optLong("resolvedAt"), status = optInt("status"),
                    updatedAt = optLong("updatedAt", t)
                )
            }
        }
        return pb
    }

    /**
     * 从 JSON 恢复（整体覆盖）。解析失败会抛异常且不改动现有数据。
     * 支持 v1/v2 备份文件。
     */
    suspend fun restoreJson(text: String): Stats {
        val root = JSONObject(text)
        require(root.optString("app") == BACKUP_APP_TAG) { "不是本应用的备份文件" }
        val version = root.optInt("schemaVersion", 1)
        require(version in 1..BACKUP_SCHEMA) { "不支持的备份版本：$version" }

        val projects = mutableListOf<Project>()
        val types = mutableListOf<CabinetType>()
        val cands = mutableListOf<CandidateItem>()
        val instances = mutableListOf<CabinetInstance>()
        val logs = mutableListOf<DebugLog>()
        val faults = mutableListOf<FaultRecord>()

        if (version >= 2) {
            root.optJSONArray("projects")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    projects += Project(
                        id = getString("id"), name = getString("name"),
                        code = optString("code"), remark = optString("remark"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("cabinetTypes")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    types += CabinetType(
                        id = getString("id"), name = getString("name"),
                        remark = optString("remark"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("candidateItems")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    cands += CandidateItem(
                        id = getString("id"), typeId = getString("typeId"),
                        content = getString("content"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("instances")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    instances += CabinetInstance(
                        id = getString("id"), projectId = getString("projectId"),
                        typeId = getString("typeId"), name = getString("name"),
                        deviceCode = optString("deviceCode"), location = optString("location"),
                        installer = optString("installer"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("logs")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    logs += DebugLog(
                        id = getString("id"), instanceId = getString("instanceId"),
                        circuit = optString("circuit"), testContent = getString("testContent"),
                        tester = optString("tester"), remark = optString("remark"),
                        createdBy = optString("createdBy"), updatedBy = optString("updatedBy"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("faults")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    faults += FaultRecord(
                        id = getString("id"), logId = getString("logId"),
                        circuit = optString("circuit"), symptom = optString("symptom"),
                        solution = optString("solution"), occurredAt = optLong("occurredAt"),
                        resolvedAt = optLong("resolvedAt"), status = optInt("status"),
                        updatedAt = optLong("updatedAt")
                    )
                }
            }
        } else {
            // v1：解析后走同一套uuid重映射（复用parse逻辑）
            val pb = parseBackup(text)
            projects += pb.projects; types += pb.types; cands += pb.cands
            instances += pb.instances; logs += pb.logs; faults += pb.faults
        }

        db.withTransaction {
            faultDao.wipe(); logDao.wipe(); instanceDao.wipe()
            candDao.wipe(); typeDao.wipe(); projectDao.wipe()
            projectDao.insertAll(projects)
            typeDao.insertAll(types)
            candDao.insertAll(cands)
            instanceDao.insertAll(instances)
            logDao.insertAll(logs)
            faultDao.upsertAll(faults)
        }
        return Stats(projects.size, types.size, instances.size, logs.size, faults.count { it.status == FaultRecord.STATUS_PENDING })
    }

    /**
     * 智能合并远端数据到本机（按id去重，同id冲突时 updatedAt 新者胜，绝不删除本地数据）。
     */
    suspend fun mergeJson(text: String): MergeResult = applyMerge(parseBackup(text))

    /**
     * 合并预览（只读不写库）：返回与真实合并一致的统计结果。
     */
    suspend fun mergePreview(text: String): MergeResult {
        val pb = parseBackup(text)
        val lp = projectDao.allOnce().associateBy { it.id }
        val lt = typeDao.allOnce().associateBy { it.id }
        val lc = candDao.allOnce()
        val li = instanceDao.allOnce().associateBy { it.id }
        val ll = logDao.allOnce().associateBy { it.id }
        val lf = faultDao.allOnce().associateBy { it.id }

        fun newer(map: Map<String, Long>, id: String, ts: Long) =
            map[id]?.let { it < ts } == true

        val lcById = lc.associateBy { it.id }
        val lcPair = lc.map { it.typeId to it.content }.toHashSet()

        return MergeResult(
            newProjects = pb.projects.count { it.id !in lp },
            updProjects = pb.projects.count { newer(lp.mapValues { it.value.updatedAt }, it.id, it.updatedAt) },
            newTypes = pb.types.count { it.id !in lt },
            updTypes = pb.types.count { newer(lt.mapValues { it.value.updatedAt }, it.id, it.updatedAt) },
            newCands = pb.cands.count { it.id !in lcById && (it.typeId to it.content) !in lcPair },
            newInstances = pb.instances.count { it.id !in li },
            updInstances = pb.instances.count { newer(li.mapValues { it.value.updatedAt }, it.id, it.updatedAt) },
            newLogs = pb.logs.count { it.id !in ll },
            updLogs = pb.logs.count { newer(ll.mapValues { it.value.updatedAt }, it.id, it.updatedAt) },
            newFaults = pb.faults.count { it.id !in lf },
            updFaults = pb.faults.count { newer(lf.mapValues { it.value.updatedAt }, it.id, it.updatedAt) }
        )
    }

    private suspend fun applyMerge(pb: ParsedBackup): MergeResult {
        val r = MergeResult()
        db.withTransaction {
            // 父表在前，保证外键引用顺序；UPDATE不触发级联，父行更新安全
            val lp = projectDao.allOnce().associateBy { it.id }
            val insP = pb.projects.filter { it.id !in lp }
            val updP = pb.projects.filter { lp[it.id]?.let { l -> it.updatedAt > l.updatedAt } == true }
            projectDao.insertAll(insP); projectDao.updateAll(updP)
            r.newProjects = insP.size; r.updProjects = updP.size

            val lt = typeDao.allOnce().associateBy { it.id }
            val insT = pb.types.filter { it.id !in lt }
            val updT = pb.types.filter { lt[it.id]?.let { l -> it.updatedAt > l.updatedAt } == true }
            typeDao.insertAll(insT); typeDao.updateAll(updT)
            r.newTypes = insT.size; r.updTypes = updT.size

            val lc = candDao.allOnce()
            val lcById = lc.associateBy { it.id }
            val lcPair = lc.map { it.typeId to it.content }.toHashSet()
            val insC = pb.cands.filter { it.id !in lcById && (it.typeId to it.content) !in lcPair }
            val updC = pb.cands.filter { lcById[it.id]?.let { l -> it.updatedAt > l.updatedAt } == true }
            candDao.insertAll(insC); candDao.insertAll(updC) // IGNORE策略：内容重复时静默跳过
            r.newCands = insC.size

            val li = instanceDao.allOnce().associateBy { it.id }
            val insI = pb.instances.filter { it.id !in li }
            val updI = pb.instances.filter { li[it.id]?.let { l -> it.updatedAt > l.updatedAt } == true }
            instanceDao.insertAll(insI); instanceDao.updateAll(updI)
            r.newInstances = insI.size; r.updInstances = updI.size

            val ll = logDao.allOnce().associateBy { it.id }
            val insL = pb.logs.filter { it.id !in ll }
            val updL = pb.logs.filter { ll[it.id]?.let { l -> it.updatedAt > l.updatedAt } == true }
            logDao.insertAll(insL); logDao.updateAll(updL)
            r.newLogs = insL.size; r.updLogs = updL.size

            val lf = faultDao.allOnce().associateBy { it.id }
            val insF = pb.faults.filter { it.id !in lf }
            val updF = pb.faults.filter { lf[it.id]?.let { l -> it.updatedAt > l.updatedAt } == true }
            faultDao.upsertAll(insF); faultDao.updateAll(updF)
            r.newFaults = insF.size; r.updFaults = updF.size
        }
        return r
    }
}
