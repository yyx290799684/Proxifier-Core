package com.yangyx.proxy.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yangyx.proxy.data.model.ProxyRule
import kotlinx.coroutines.flow.Flow

@Dao
interface ProxyRuleDao {
    @Query("SELECT * FROM proxy_rules ORDER BY priority ASC, id ASC")
    fun getAllRules(): Flow<List<ProxyRule>>

    @Query("SELECT * FROM proxy_rules WHERE isEnabled = 1 ORDER BY priority ASC, id ASC")
    suspend fun getEnabledRulesSync(): List<ProxyRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: ProxyRule): Long

    @Update
    suspend fun updateRule(rule: ProxyRule)

    @Delete
    suspend fun deleteRule(rule: ProxyRule)

    @Query("DELETE FROM proxy_rules WHERE id = :id")
    suspend fun deleteRuleById(id: Long)

    @Query("UPDATE proxy_rules SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun setRuleEnabled(id: Long, isEnabled: Boolean)
}
