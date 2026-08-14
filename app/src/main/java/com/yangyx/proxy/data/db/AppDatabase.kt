package com.yangyx.proxy.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.yangyx.proxy.data.model.ConnectionLog
import com.yangyx.proxy.data.model.ProxyRule
import com.yangyx.proxy.data.model.ProxyServer

@Database(
    entities = [ProxyServer::class, ProxyRule::class, ConnectionLog::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun proxyDao(): ProxyDao
    abstract fun proxyRuleDao(): ProxyRuleDao
    abstract fun connectionLogDao(): ConnectionLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "proxifier_core.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
