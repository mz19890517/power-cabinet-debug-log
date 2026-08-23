package com.fieldlog.powerdebug.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun watchAllAsFlow(): Flow<List<Project>>

    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    suspend fun allOnce(): List<Project>

    @Query(
        """SELECT pr.*, 
        (SELECT COUNT(*) FROM instances i WHERE i.projectId = pr.id) AS cabinetCount,
        (SELECT COUNT(*) FROM debug_logs l INNER JOIN instances i2 ON l.instanceId = i2.id WHERE i2.projectId = pr.id) AS logCount
        FROM projects pr ORDER BY pr.createdAt DESC"""
    )
    fun watchListItemsAsFlow(): Flow<List<ProjectListItem>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getByIdOnce(id: String): Project?

    @Insert
    suspend fun insert(p: Project)

    @Insert
    suspend fun insertAll(list: List<Project>)

    @Update
    suspend fun update(p: Project)

    @Update
    suspend fun updateAll(list: List<Project>)

    @Delete
    suspend fun delete(p: Project)

    @Query("DELETE FROM projects")
    suspend fun wipe()

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun count(): Int
}

@Dao
interface CabinetTypeDao {
    @Query("SELECT * FROM cabinet_types ORDER BY name")
    fun watchAllAsFlow(): Flow<List<CabinetType>>

    @Query("SELECT * FROM cabinet_types ORDER BY name")
    suspend fun allOnce(): List<CabinetType>

    @Query(
        """SELECT t.*,
        (SELECT COUNT(*) FROM instances i WHERE i.typeId = t.id) AS instanceCount,
        (SELECT COUNT(*) FROM candidate_items c WHERE c.typeId = t.id) AS itemCount
        FROM cabinet_types t ORDER BY t.name"""
    )
    fun watchListItemsAsFlow(): Flow<List<TypeListItem>>

    @Query("SELECT * FROM cabinet_types WHERE id = :id")
    suspend fun getByIdOnce(id: String): CabinetType?

    @Insert
    suspend fun insert(t: CabinetType)

    @Insert
    suspend fun insertAll(list: List<CabinetType>)

    @Update
    suspend fun update(t: CabinetType)

    @Update
    suspend fun updateAll(list: List<CabinetType>)

    @Delete
    suspend fun delete(t: CabinetType)

    @Query("DELETE FROM cabinet_types")
    suspend fun wipe()

    @Query("SELECT COUNT(*) FROM cabinet_types")
    suspend fun count(): Int
}

@Dao
interface CandidateItemDao {
    @Query("SELECT * FROM candidate_items WHERE typeId = :typeId ORDER BY createdAt, id")
    fun watchByTypeAsFlow(typeId: String): Flow<List<CandidateItem>>

    @Query("SELECT * FROM candidate_items WHERE typeId = :typeId ORDER BY createdAt, id")
    suspend fun byTypeOnce(typeId: String): List<CandidateItem>

    @Query("SELECT content FROM candidate_items WHERE typeId = :typeId")
    suspend fun contentsOnce(typeId: String): List<String>

    @Query("SELECT * FROM candidate_items ORDER BY createdAt, id")
    suspend fun allOnce(): List<CandidateItem>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: CandidateItem)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<CandidateItem>)

    @Delete
    suspend fun delete(item: CandidateItem)

    @Query("DELETE FROM candidate_items")
    suspend fun wipe()
}

@Dao
interface InstanceDao {
    @Query("SELECT * FROM instances WHERE projectId = :projectId ORDER BY name")
    fun watchByProjectAsFlow(projectId: String): Flow<List<CabinetInstance>>

    @Query("SELECT * FROM instances WHERE projectId = :projectId ORDER BY name")
    suspend fun byProjectOnce(projectId: String): List<CabinetInstance>

    @Query(
        "SELECT * FROM instances WHERE (:projectId = '' OR projectId = :projectId) " +
            "AND (:typeId = '' OR typeId = :typeId) ORDER BY name"
    )
    suspend fun byProjectAndTypeOnce(projectId: String, typeId: String): List<CabinetInstance>

    @Query(
        """SELECT i.*, p.name AS projectName FROM instances i 
        INNER JOIN projects p ON i.projectId = p.id 
        WHERE i.typeId = :typeId ORDER BY p.name COLLATE NOCASE, i.name"""
    )
    suspend fun byTypeWithProject(typeId: String): List<InstanceRow>

    @Query("SELECT * FROM instances ORDER BY name")
    suspend fun allOnce(): List<CabinetInstance>

    @Query("SELECT * FROM instances WHERE id = :id")
    suspend fun getByIdOnce(id: String): CabinetInstance?

    @Insert
    suspend fun insert(i: CabinetInstance)

    @Insert
    suspend fun insertAll(list: List<CabinetInstance>)

    @Update
    suspend fun update(i: CabinetInstance)

    @Update
    suspend fun updateAll(list: List<CabinetInstance>)

    @Delete
    suspend fun delete(i: CabinetInstance)

    @Query("DELETE FROM instances")
    suspend fun wipe()

    @Query("SELECT COUNT(*) FROM instances")
    suspend fun count(): Int
}

@Dao
interface DebugLogDao {
    @Query(
        """SELECT l.*, p.name AS projectName, t.name AS typeName, i.name AS instanceName,
        i.deviceCode AS deviceCode, i.installer AS installer,
        (SELECT COUNT(*) FROM fault_records f WHERE f.logId = l.id AND f.status = 0) AS pendingCount,
        (SELECT COUNT(*) FROM fault_records f WHERE f.logId = l.id AND f.status = 1) AS resolvedCount
        FROM debug_logs l
        INNER JOIN instances i ON l.instanceId = i.id
        INNER JOIN cabinet_types t ON i.typeId = t.id
        INNER JOIN projects p ON i.projectId = p.id
        WHERE (:projectId = '' OR i.projectId = :projectId)
          AND (:typeId = '' OR i.typeId = :typeId)
          AND (:instanceId = '' OR l.instanceId = :instanceId)
          AND (:status = -1
               OR (:status = 0 AND EXISTS(SELECT 1 FROM fault_records fp WHERE fp.logId = l.id AND fp.status = 0))
               OR (:status = 1 AND EXISTS(SELECT 1 FROM fault_records fr WHERE fr.logId = l.id AND fr.status = 1)
                    AND NOT EXISTS(SELECT 1 FROM fault_records fn WHERE fn.logId = l.id AND fn.status = 0)))
          AND (:circuit = '' OR l.circuit LIKE '%' || :circuit || '%')
          AND (:q = '' OR l.testContent LIKE '%' || :q || '%'
               OR IFNULL(l.remark, '') LIKE '%' || :q || '%'
               OR IFNULL(l.tester, '') LIKE '%' || :q || '%'
               OR i.name LIKE '%' || :q || '%'
               OR p.name LIKE '%' || :q || '%'
               OR EXISTS(SELECT 1 FROM fault_records fs WHERE fs.logId = l.id
                    AND (fs.symptom LIKE '%' || :q || '%' OR fs.solution LIKE '%' || :q || '%' OR fs.circuit LIKE '%' || :q || '%')))
        ORDER BY l.createdAt DESC"""
    )
    suspend fun search(
        projectId: String, typeId: String, instanceId: String,
        status: Int, circuit: String, q: String
    ): List<LogListItem>

    @Query(
        """SELECT l.*, p.name AS projectName, t.name AS typeName, i.name AS instanceName,
        i.deviceCode AS deviceCode, i.installer AS installer,
        (SELECT COUNT(*) FROM fault_records f WHERE f.logId = l.id AND f.status = 0) AS pendingCount,
        (SELECT COUNT(*) FROM fault_records f WHERE f.logId = l.id AND f.status = 1) AS resolvedCount
        FROM debug_logs l
        INNER JOIN instances i ON l.instanceId = i.id
        INNER JOIN cabinet_types t ON i.typeId = t.id
        INNER JOIN projects p ON i.projectId = p.id
        WHERE l.id = :id LIMIT 1"""
    )
    suspend fun getDetailOnce(id: String): LogListItem?

    @Query(
        """SELECT l.*, p.name AS projectName, t.name AS typeName, i.name AS instanceName,
        i.deviceCode AS deviceCode, i.installer AS installer,
        (SELECT COUNT(*) FROM fault_records f WHERE f.logId = l.id AND f.status = 0) AS pendingCount,
        (SELECT COUNT(*) FROM fault_records f WHERE f.logId = l.id AND f.status = 1) AS resolvedCount
        FROM debug_logs l
        INNER JOIN instances i ON l.instanceId = i.id
        INNER JOIN cabinet_types t ON i.typeId = t.id
        INNER JOIN projects p ON i.projectId = p.id
        ORDER BY p.name COLLATE NOCASE, i.name COLLATE NOCASE, l.createdAt"""
    )
    suspend fun exportAll(): List<LogListItem>

    @Query(
        """SELECT DISTINCT l.circuit FROM debug_logs l
        INNER JOIN instances i ON l.instanceId = i.id
        WHERE IFNULL(l.circuit, '') <> ''
          AND (:typeId = '' OR i.typeId = :typeId)
          AND (:projectId = '' OR i.projectId = :projectId)
        ORDER BY l.circuit LIMIT 40"""
    )
    suspend fun distinctCircuits(projectId: String, typeId: String): List<String>

    @Query("SELECT * FROM debug_logs ORDER BY createdAt, id")
    suspend fun allOnce(): List<DebugLog>

    @Query("SELECT * FROM debug_logs WHERE id = :id")
    suspend fun getByIdOnce(id: String): DebugLog?

    @Query("SELECT COUNT(*) FROM debug_logs WHERE instanceId = :instanceId")
    suspend fun countLogsOf(instanceId: String): Int

    @Insert
    suspend fun insert(l: DebugLog)

    @Insert
    suspend fun insertAll(list: List<DebugLog>)

    @Update
    suspend fun update(l: DebugLog)

    @Update
    suspend fun updateAll(list: List<DebugLog>)

    @Delete
    suspend fun delete(l: DebugLog)

    @Query("DELETE FROM debug_logs")
    suspend fun wipe()

    @Query("SELECT COUNT(*) FROM debug_logs")
    suspend fun count(): Int
}

@Dao
interface FaultRecordDao {
    @Query("SELECT * FROM fault_records WHERE logId = :logId ORDER BY occurredAt")
    suspend fun forLogOnce(logId: String): List<FaultRecord>

    @Query(
        """SELECT f.*, p.name AS projectName, i.name AS instanceName, i.deviceCode AS deviceCode
        FROM fault_records f
        INNER JOIN debug_logs l ON f.logId = l.id
        INNER JOIN instances i ON l.instanceId = i.id
        INNER JOIN projects p ON i.projectId = p.id
        ORDER BY p.name COLLATE NOCASE, i.name COLLATE NOCASE, f.occurredAt"""
    )
    suspend fun exportAll(): List<FaultExportRow>

    @Query("SELECT * FROM fault_records ORDER BY occurredAt, id")
    suspend fun allOnce(): List<FaultRecord>

    @Insert
    suspend fun insert(f: FaultRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<FaultRecord>)

    @Update
    suspend fun updateAll(list: List<FaultRecord>)

    @Query("DELETE FROM fault_records WHERE logId = :logId")
    suspend fun deleteForLog(logId: String)

    @Query("DELETE FROM fault_records")
    suspend fun wipe()

    @Query("SELECT COUNT(*) FROM fault_records WHERE status = 0")
    suspend fun countPending(): Int
}

@Dao
interface TesterAccountDao {
    @Query("SELECT * FROM tester_accounts ORDER BY createdAt")
    suspend fun allOnce(): List<TesterAccount>

    @Query("SELECT * FROM tester_accounts WHERE username = :username LIMIT 1")
    suspend fun byUsername(username: String): TesterAccount?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(a: TesterAccount)

    @Query("UPDATE tester_accounts SET source = :source WHERE username = :username")
    suspend fun updateSource(username: String, source: String)
}
