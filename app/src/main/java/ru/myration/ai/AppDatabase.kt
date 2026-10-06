package ru.myration.ai

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "diary_entries")
data class DiaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val calories: Int,
    val protein: Int,
    val fat: Int,
    val carbs: Int,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "profile_settings")
data class ProfileEntity(
    @PrimaryKey val id: Int = 1,
    val goal: String = "BALANCE",
    val displayName: String = ""
)

@Dao
interface AppDao {
    @Query("SELECT * FROM diary_entries ORDER BY createdAt DESC")
    fun observeDiary(): Flow<List<DiaryEntity>>

    @Insert
    suspend fun insertDiary(entry: DiaryEntity)

    @Delete
    suspend fun deleteDiary(entry: DiaryEntity)

    @Query("DELETE FROM diary_entries")
    suspend fun clearDiary()

    @Query("SELECT * FROM profile_settings WHERE id = 1 LIMIT 1")
    suspend fun getProfile(): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProfile(profile: ProfileEntity)
}

@Database(entities = [DiaryEntity::class, ProfileEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "myration.db"
            ).build().also { INSTANCE = it }
        }
    }
}
