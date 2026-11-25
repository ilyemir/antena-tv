package com.ilyemir.antena.data.db

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
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Mantıksal kanal numarası (001, 002, ...) — zap ve NumPad bu numarayı kullanır. */
    val number: Int,
    val name: String,
    /** Birincil yayın adresi. */
    val url: String,
    /** Yedek yayın adresi; birincil 3 sn içinde yanıt vermezse otomatik geçilir. */
    val backupUrl: String? = null,
    val group: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    /** Misafir/Yaşlı Kilit Modu ve Remote panelinden gizlenebilir. */
    val hidden: Boolean = false,
    /** Özel Liste (Ana Akış) üyesi; zap varsayılan olarak bu listenin içinde döner. */
    val isFavorite: Boolean = false,
    /** Özel Liste içindeki sıra (1..n); yalnızca isFavorite=true iken anlam taşır. */
    val favoriteOrder: Int = 0,
)

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE hidden = 0 ORDER BY number ASC")
    fun observeVisible(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels ORDER BY number ASC")
    suspend fun getAll(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun getById(id: Long): ChannelEntity?

    @Query("SELECT COUNT(*) FROM channels")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels")
    suspend fun clear()

    @Update
    suspend fun update(channel: ChannelEntity)

    @Update
    suspend fun updateAll(channels: List<ChannelEntity>)

    @Transaction
    suspend fun replaceAll(channels: List<ChannelEntity>) {
        clear()
        insertAll(channels)
    }
}

@Database(entities = [ChannelEntity::class], version = 2, exportSchema = false)
abstract class AntenaDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao

    companion object {
        @Volatile
        private var instance: AntenaDatabase? = null

        fun get(context: Context): AntenaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AntenaDatabase::class.java,
                    "antena.db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
