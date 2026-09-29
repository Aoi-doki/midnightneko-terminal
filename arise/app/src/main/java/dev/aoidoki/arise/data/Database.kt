package dev.aoidoki.arise.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.Upsert
import dev.aoidoki.arise.engine.QuestKind
import dev.aoidoki.arise.engine.QuestStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromList(value: List<String>): String = json.encodeToString(value)

    @TypeConverter
    fun toList(value: String): List<String> = runCatching { json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())
}

@Dao
interface PlayerDao {
    @Query("SELECT * FROM player WHERE id = 1")
    fun observe(): Flow<PlayerEntity?>

    @Query("SELECT * FROM player WHERE id = 1")
    suspend fun get(): PlayerEntity?

    @Upsert
    suspend fun save(player: PlayerEntity)

    @Query("SELECT * FROM custom_stat ORDER BY position, id")
    fun observeCustomStats(): Flow<List<CustomStatEntity>>

    @Query("SELECT * FROM custom_stat ORDER BY position, id")
    suspend fun customStats(): List<CustomStatEntity>

    @Query("DELETE FROM custom_stat")
    suspend fun clearCustomStats()

    @Insert
    suspend fun insertCustomStats(stats: List<CustomStatEntity>)

    @Query("SELECT * FROM inventory WHERE count > 0")
    fun observeInventory(): Flow<List<InventoryEntity>>

    @Query("SELECT * FROM inventory WHERE itemId = :id")
    suspend fun inventoryItem(id: String): InventoryEntity?

    @Upsert
    suspend fun saveInventory(item: InventoryEntity)

    @Query("SELECT * FROM inventory")
    suspend fun inventory(): List<InventoryEntity>

    @Transaction
    suspend fun replaceCustomStats(stats: List<CustomStatEntity>) {
        clearCustomStats()
        insertCustomStats(stats.mapIndexed { i, s -> s.copy(id = 0, position = i) })
    }
}

@Dao
interface QuestDao {
    @Transaction
    @Query("SELECT * FROM quest WHERE status = 'ACTIVE' ORDER BY kind DESC, id")
    fun observeActive(): Flow<List<QuestWithObjectives>>

    @Transaction
    @Query("SELECT * FROM quest WHERE status = 'ACTIVE' ORDER BY id")
    suspend fun active(): List<QuestWithObjectives>

    @Transaction
    @Query("SELECT * FROM quest WHERE day = :day AND kind = :kind ORDER BY id DESC LIMIT 1")
    suspend fun forDay(day: Long, kind: QuestKind): QuestWithObjectives?

    @Transaction
    @Query("SELECT * FROM quest WHERE day = :day AND kind = 'DAILY' ORDER BY id DESC LIMIT 1")
    fun observeDaily(day: Long): Flow<QuestWithObjectives?>

    @Transaction
    @Query("SELECT * FROM quest WHERE id = :id")
    suspend fun byId(id: Long): QuestWithObjectives?

    @Transaction
    @Query("SELECT * FROM quest WHERE day >= :sinceDay ORDER BY day DESC, id DESC")
    suspend fun since(sinceDay: Long): List<QuestWithObjectives>

    @Transaction
    @Query("SELECT * FROM quest ORDER BY day DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<QuestWithObjectives>>

    @Query("SELECT COUNT(*) FROM quest WHERE kind = :kind AND status = :status")
    suspend fun count(kind: QuestKind, status: QuestStatus): Int

    @Insert
    suspend fun insert(quest: QuestEntity): Long

    @Insert
    suspend fun insertObjectives(objectives: List<ObjectiveEntity>)

    @Update
    suspend fun update(quest: QuestEntity)

    @Update
    suspend fun updateObjective(objective: ObjectiveEntity)

    @Query("DELETE FROM objective WHERE questId = :questId")
    suspend fun deleteObjectives(questId: Long)

    @Transaction
    suspend fun insertWithObjectives(quest: QuestEntity, objectives: List<ObjectiveEntity>): Long {
        val id = insert(quest)
        insertObjectives(objectives.map { it.copy(questId = id) })
        return id
    }

    @Transaction
    suspend fun replaceObjectives(questId: Long, objectives: List<ObjectiveEntity>) {
        deleteObjectives(questId)
        insertObjectives(objectives.map { it.copy(id = 0, questId = questId) })
    }

    @Query("SELECT * FROM quest")
    suspend fun allQuests(): List<QuestEntity>

    @Query("SELECT * FROM objective")
    suspend fun allObjectives(): List<ObjectiveEntity>
}

@Dao
interface LogDao {
    @Query("SELECT * FROM day_log WHERE day = :day")
    suspend fun day(day: Long): DayLogEntity?

    @Query("SELECT * FROM day_log WHERE day = :day")
    fun observeDay(day: Long): Flow<DayLogEntity?>

    @Query("SELECT * FROM day_log WHERE day >= :sinceDay ORDER BY day")
    suspend fun since(sinceDay: Long): List<DayLogEntity>

    @Query("SELECT * FROM day_log ORDER BY day DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DayLogEntity>>

    @Upsert
    suspend fun upsert(log: DayLogEntity)

    @Query("SELECT * FROM weight ORDER BY time")
    fun observeWeights(): Flow<List<WeightEntity>>

    @Query("SELECT * FROM weight ORDER BY time")
    suspend fun weights(): List<WeightEntity>

    @Query("SELECT * FROM weight WHERE source = :source AND time = :time LIMIT 1")
    suspend fun weightAt(source: String, time: Long): WeightEntity?

    @Insert
    suspend fun insertWeight(weight: WeightEntity): Long

    @Insert
    suspend fun insertEvent(event: EventEntity): Long

    @Query("SELECT * FROM event ORDER BY time DESC, id DESC LIMIT :limit")
    fun observeEvents(limit: Int): Flow<List<EventEntity>>

    @Query("SELECT * FROM event ORDER BY time")
    suspend fun events(): List<EventEntity>

    @Query("SELECT * FROM day_log ORDER BY day")
    suspend fun allDays(): List<DayLogEntity>
}

@Dao
interface MaintenanceDao {
    @Query("DELETE FROM player") suspend fun clearPlayer()
    @Query("DELETE FROM quest") suspend fun clearQuests()
    @Query("DELETE FROM day_log") suspend fun clearDays()
    @Query("DELETE FROM weight") suspend fun clearWeights()
    @Query("DELETE FROM event") suspend fun clearEvents()
    @Query("DELETE FROM custom_stat") suspend fun clearCustom()
    @Query("DELETE FROM inventory") suspend fun clearInventory()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertInventory(i: List<InventoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPlayer(p: PlayerEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCustom(s: List<CustomStatEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertQuests(q: List<QuestEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertObjectives(o: List<ObjectiveEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertDays(d: List<DayLogEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertWeights(w: List<WeightEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEvents(e: List<EventEntity>)

    @Transaction
    suspend fun wipe() {
        clearQuests(); clearDays(); clearWeights(); clearEvents(); clearCustom(); clearInventory(); clearPlayer()
    }
}

@Database(
    entities = [
        PlayerEntity::class, CustomStatEntity::class, QuestEntity::class, ObjectiveEntity::class,
        DayLogEntity::class, WeightEntity::class, EventEntity::class, InventoryEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
@TypeConverters(Converters::class)
abstract class AriseDatabase : RoomDatabase() {
    abstract fun player(): PlayerDao
    abstract fun quests(): QuestDao
    abstract fun logs(): LogDao
    abstract fun maintenance(): MaintenanceDao

    companion object {
        fun create(context: Context): AriseDatabase =
            Room.databaseBuilder(context, AriseDatabase::class.java, "arise.db").build()

        fun inMemory(context: Context): AriseDatabase =
            Room.inMemoryDatabaseBuilder(context, AriseDatabase::class.java).allowMainThreadQueries().build()
    }
}
