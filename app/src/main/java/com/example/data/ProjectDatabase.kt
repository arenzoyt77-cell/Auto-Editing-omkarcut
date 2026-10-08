package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recent_projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sourceUri: String,
    val localVideoPath: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val fileSizeBytes: Long,
    val segmentCount: Int,
    val segmentsJson: String,
    val lastExportedPath: String? = null,
    val lastExportedUri: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "export_history")
data class ExportHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val exportedFilePath: String,
    val mediaStoreUri: String,
    val resolution: String,
    val durationMs: Long,
    val segmentCount: Int,
    val fileSizeBytes: Long,
    val exportedAt: Long = System.currentTimeMillis()
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM recent_projects ORDER BY updatedAt DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(project: ProjectEntity): Long

    @Query("DELETE FROM recent_projects WHERE id = :id")
    suspend fun deleteProjectById(id: Long)

    @Query("SELECT * FROM export_history ORDER BY exportedAt DESC")
    fun getAllExports(): Flow<List<ExportHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExport(exportItem: ExportHistoryEntity): Long

    @Query("DELETE FROM export_history WHERE id = :id")
    suspend fun deleteExportById(id: Long)
}

@Database(
    entities = [ProjectEntity::class, ExportHistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AutoCutDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile
        private var INSTANCE: AutoCutDatabase? = null

        fun getInstance(context: Context): AutoCutDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AutoCutDatabase::class.java,
                    "omkar_autocut_studio.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class ProjectRepository(private val dao: ProjectDao) {
    val recentProjects: Flow<List<ProjectEntity>> = dao.getAllProjects()
    val exportHistory: Flow<List<ExportHistoryEntity>> = dao.getAllExports()

    suspend fun saveProject(project: ProjectEntity): Long = dao.upsertProject(project)
    suspend fun deleteProject(id: Long) = dao.deleteProjectById(id)
    suspend fun recordExport(exportItem: ExportHistoryEntity): Long = dao.insertExport(exportItem)
    suspend fun deleteExport(id: Long) = dao.deleteExportById(id)
}
