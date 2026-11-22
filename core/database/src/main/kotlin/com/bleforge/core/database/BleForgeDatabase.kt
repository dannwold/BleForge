/*
 * ==============================================================================
 * FILE: BleForgeDatabase.kt
 * CURRENT VERSION: v1.0.22
 * CHANGELOG:
 *   - v1.0.1: Initial Room v1 database with DeviceProfileEntity & TelemetryLogEntity.
 *   - v1.0.6: Upgraded to Room v2; added KnownUuidEntity + UuidRegistrySeed onCreate callback.
 *   - v1.0.8: Upgraded to Room v3; added BleMacroEntity & BleMacroDao for conditional scripts.
 *   - v1.0.9: Appended detailed KDoc & inline comments explaining tables & migration strategy.
 *   - v1.0.21: Removed fallbackToDestructiveMigration(dropAllTables = true) so
 *              user macros, discovered UUIDs, and telemetry logs are never wiped
 *              on future schema updates.
 *   - v1.0.22: Added observeAllLogs() to DeviceProfileDao so CSV export works even
 *              when no device is currently connected.
 *
 * HOW THIS DATABASE WORKS:
 *   - Uses Room (SQLite) + KSP code generation.
 *   - Tables:
 *       1. device_profiles: Saved hardware fingerprints, UI categories, & heartbeat intervals.
 *       2. telemetry_logs: Timestamped decoded values + raw hex packets.
 *       3. known_uuids: Pre-seeded Bluetooth SIG + Nordic UART + user-discovered UUIDs.
 *       4. ble_macros: Saved JSON-encoded conditional WHEN/IF/ELSE macro scripts.
 * ==============================================================================
 */

package com.bleforge.core.database

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Entity(tableName = "device_profiles")
data class DeviceProfileEntity(
    @PrimaryKey val profileId: String,
    val displayName: String,
    val category: String,
    val manufacturerId: Int?,
    val primaryServiceUuid: String,
    val charMappingsJson: String,
    val heartbeatIntervalMs: Long = 0L
)

@Entity(tableName = "telemetry_logs")
data class TelemetryLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val deviceAddress: String,
    val timestampMs: Long,
    val label: String,
    val valueText: String,
    val rawHex: String
)

@Entity(tableName = "known_uuids")
data class KnownUuidEntity(
    @PrimaryKey val uuid: String,
    val shortCode: String?,
    val kind: String,
    val name: String,
    val category: String,
    val decodeFormat: String,
    val functionNotes: String,
    val isUserDiscovered: Boolean = false
)

@Dao
interface DeviceProfileDao {
    @Query("SELECT * FROM device_profiles ORDER BY displayName ASC")
    fun observeProfiles(): Flow<List<DeviceProfileEntity>>

    @Query("SELECT * FROM device_profiles WHERE primaryServiceUuid = :serviceUuid LIMIT 1")
    suspend fun findByService(serviceUuid: String): DeviceProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: DeviceProfileEntity)

    @Insert
    suspend fun insertLog(log: TelemetryLogEntity)

    @Query("SELECT * FROM telemetry_logs WHERE deviceAddress = :address ORDER BY timestampMs DESC LIMIT 200")
    fun observeLogs(address: String): Flow<List<TelemetryLogEntity>>

    @Query("SELECT * FROM telemetry_logs ORDER BY timestampMs DESC LIMIT 500")
    fun observeAllLogs(): Flow<List<TelemetryLogEntity>>
}

@Dao
interface KnownUuidDao {
    @Query("SELECT * FROM known_uuids ORDER BY isUserDiscovered DESC, name ASC")
    fun observeAllUuids(): Flow<List<KnownUuidEntity>>

    @Query("SELECT * FROM known_uuids WHERE uuid = :normalizedUuid LIMIT 1")
    suspend fun findByUuid(normalizedUuid: String): KnownUuidEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUuid(entry: KnownUuidEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<KnownUuidEntity>)
}

@Database(
    entities = [DeviceProfileEntity::class, TelemetryLogEntity::class, KnownUuidEntity::class, BleMacroEntity::class],
    version = 3,
    exportSchema = false
)
abstract class BleForgeDatabase : RoomDatabase() {
    abstract fun profileDao(): DeviceProfileDao
    abstract fun knownUuidDao(): KnownUuidDao
    abstract fun macroDao(): BleMacroDao

    companion object {
        @Volatile private var INSTANCE: BleForgeDatabase? = null

        fun get(context: Context): BleForgeDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                BleForgeDatabase::class.java,
                "bleforge.db"
            )
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            INSTANCE?.knownUuidDao()?.insertAll(UuidRegistrySeed.defaultEntries())
                        }
                    }
                })
                .build()
                .also { INSTANCE = it }
        }
    }
}
