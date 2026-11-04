package ru.bkphenix.app.data.track

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
)

@Entity(
    tableName = "track_points",
    foreignKeys = [ForeignKey(TrackEntity::class, ["id"], ["track_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("track_id", "time")],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "track_id") val trackId: Long,
    val time: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Double?,
    val integrity: String?,
)

/** Трек со сводкой для списка. */
data class TrackSummary(
    val id: Long,
    val name: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "point_count") val pointCount: Int,
)

@Dao
interface TrackDao {
    @Insert
    suspend fun insert(track: TrackEntity): Long

    @Query("UPDATE tracks SET ended_at = :endedAt WHERE id = :id")
    suspend fun finish(id: Long, endedAt: Long)

    @Insert
    suspend fun insertPoint(point: TrackPointEntity)

    @Query(
        "SELECT t.id, t.name, t.started_at, t.ended_at, COUNT(p.id) AS point_count FROM tracks t " +
            "LEFT JOIN track_points p ON p.track_id = t.id GROUP BY t.id ORDER BY t.started_at DESC",
    )
    fun summaries(): Flow<List<TrackSummary>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun track(id: Long): TrackEntity?

    @Query("SELECT * FROM track_points WHERE track_id = :trackId ORDER BY time")
    suspend fun points(trackId: Long): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE track_id = :trackId ORDER BY time")
    fun pointsFlow(trackId: Long): Flow<List<TrackPointEntity>>

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun delete(id: Long)

    /** Треки, не закрытые при прошлом запуске (процесс убит): закрываем временем последней точки. */
    @Query(
        "UPDATE tracks SET ended_at = COALESCE((SELECT MAX(time) FROM track_points WHERE track_id = tracks.id), started_at) " +
            "WHERE ended_at IS NULL",
    )
    suspend fun closeDangling()
}

@Database(entities = [TrackEntity::class, TrackPointEntity::class], version = 1, exportSchema = true)
abstract class TrackDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao

    companion object {
        fun create(context: Context): TrackDatabase =
            Room.databaseBuilder(context, TrackDatabase::class.java, "tracks.db").build()
    }
}
