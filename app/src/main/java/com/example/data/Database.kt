package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.OnConflictStrategy
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "ble_logs")
data class BleLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val text: String,
    val type: String // "info", "success", "warning", "error", "rx", "tx"
)

@Entity(tableName = "album_items")
data class AlbumItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val type: String, // "photo", "video"
    val filePath: String,
    val timestamp: Long = System.currentTimeMillis(),
    val duration: String = "" // e.g. "00:34", "04:10"
)

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val sender: String, // "user", "ai"
    val text: String
)

@Dao
interface BleLogDao {
    @Query("SELECT * FROM ble_logs ORDER BY timestamp ASC")
    fun getAllLogs(): Flow<List<BleLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: BleLog)

    @Query("DELETE FROM ble_logs")
    suspend fun clearLogs()
}

@Dao
interface AlbumDao {
    @Query("SELECT * FROM album_items ORDER BY timestamp DESC")
    fun getAllItems(): Flow<List<AlbumItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: AlbumItem)

    @Query("DELETE FROM album_items WHERE id = :id")
    suspend fun deleteItem(id: Int)
}

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<ChatMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage)

    @Query("DELETE FROM chat_messages")
    suspend fun clearChat()
}

@Database(entities = [BleLog::class, AlbumItem::class, ChatMessage::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bleLogDao(): BleLogDao
    abstract fun albumDao(): AlbumDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "glass_controller_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
