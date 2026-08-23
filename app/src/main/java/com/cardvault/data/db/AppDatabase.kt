package com.cardvault.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CardEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun cardDao(): CardDao

    companion object {
        private const val DB_NAME = "cardvault.db"

        @Volatile private var instance: AppDatabase? = null

        // v2 adds CardEntity.cardType. Existing rows default to UNKNOWN so migrated
        // installs keep every stored card and simply render without a type badge.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE cards ADD COLUMN cardType TEXT NOT NULL DEFAULT 'UNKNOWN'"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                // No destructive-migration fallback: for a vault, silently wiping
                // card data on a schema mismatch is worse than crashing. Any future
                // schema bump must ship an explicit Migration.
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Called by "Reset app" and by "Forgot password" flows. */
        fun wipe(context: Context) {
            synchronized(this) {
                instance?.close()
                instance = null
                context.applicationContext.deleteDatabase(DB_NAME)
            }
        }
    }
}
