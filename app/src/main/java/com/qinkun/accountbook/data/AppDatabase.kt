package com.qinkun.accountbook.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Transaction::class, CaptureLog::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun txDao(): TransactionDao
    abstract fun logDao(): CaptureLogDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * 1 → 2：只新增一张抓取日志表。
         *
         * 两条铁律：
         * 1. 必须写迁移 —— 老用户库里有真实账目，用 fallbackToDestructiveMigration 会把账全清掉。
         * 2. 这段 DDL 必须和 CaptureLog 实体生成的语句**逐字对齐**（尤其不能随意加 DEFAULT），
         *    否则 Room 迁移后的 schema 校验失败，App 一启动就崩。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `capture_logs` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `at` INTEGER NOT NULL,
                        `sourceApp` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `success` INTEGER NOT NULL,
                        `reason` TEXT NOT NULL,
                        `amountCents` INTEGER NOT NULL
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_capture_logs_at` ON `capture_logs` (`at`)")
            }
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "accountbook.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { INSTANCE = it }
        }
    }
}
