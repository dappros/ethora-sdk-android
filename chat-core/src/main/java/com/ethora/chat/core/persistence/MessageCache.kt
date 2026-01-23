package com.ethora.chat.core.persistence

import androidx.room.*
import com.ethora.chat.core.models.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Message entity for Room database
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val userName: String?,
    val userEmail: String?,
    val userXmppUsername: String?,
    val date: Long,
    val body: String,
    val roomJid: String,
    val key: String? = null,
    val coinsInMessage: String? = null,
    val numberOfReplies: Int? = null,
    val isSystemMessage: String? = null,
    val isMediafile: String? = null,
    val locationPreview: String? = null,
    val mimetype: String? = null,
    val location: String? = null,
    val pending: Boolean? = null,
    val timestamp: Long? = null,
    val showInChannel: String? = null,
    val activeMessage: Boolean? = null,
    val isReply: Boolean? = null,
    val isDeleted: Boolean? = null,
    val mainMessage: String? = null,
    val fileName: String? = null,
    val langSource: String? = null,
    val originalName: String? = null,
    val size: String? = null,
    val xmppId: String? = null,
    val xmppFrom: String? = null,
    val waveForm: String? = null
) {
    fun toMessage(): Message {
        return Message(
            id = id,
            user = com.ethora.chat.core.models.User(
                id = userId,
                name = userName,
                email = userEmail,
                xmppUsername = userXmppUsername
            ),
            date = java.util.Date(date),
            body = body,
            roomJid = roomJid,
            key = key,
            coinsInMessage = coinsInMessage,
            numberOfReplies = numberOfReplies,
            isSystemMessage = isSystemMessage,
            isMediafile = isMediafile,
            locationPreview = locationPreview,
            mimetype = mimetype,
            location = location,
            pending = pending,
            timestamp = timestamp,
            showInChannel = showInChannel,
            activeMessage = activeMessage,
            isReply = isReply,
            isDeleted = isDeleted,
            mainMessage = mainMessage,
            fileName = fileName,
            langSource = langSource,
            originalName = originalName,
            size = size,
            xmppId = xmppId,
            xmppFrom = xmppFrom,
            waveForm = waveForm
        )
    }

    companion object {
        fun fromMessage(message: Message): MessageEntity {
            return MessageEntity(
                id = message.id,
                userId = message.user.id,
                userName = message.user.name,
                userEmail = message.user.email,
                userXmppUsername = message.user.xmppUsername,
                date = message.date.time,
                body = message.body,
                roomJid = message.roomJid,
                key = message.key,
                coinsInMessage = message.coinsInMessage,
                numberOfReplies = message.numberOfReplies,
                isSystemMessage = message.isSystemMessage,
                isMediafile = message.isMediafile,
                locationPreview = message.locationPreview,
                mimetype = message.mimetype,
                location = message.location,
                pending = message.pending,
                timestamp = message.timestamp,
                showInChannel = message.showInChannel,
                activeMessage = message.activeMessage,
                isReply = message.isReply,
                isDeleted = message.isDeleted,
                mainMessage = message.mainMessage,
                fileName = message.fileName,
                langSource = message.langSource,
                originalName = message.originalName,
                size = message.size,
                xmppId = message.xmppId,
                xmppFrom = message.xmppFrom,
                waveForm = message.waveForm
            )
        }
    }
}

/**
 * Message DAO
 */
@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE roomJid = :roomJid ORDER BY date ASC")
    fun getMessagesForRoom(roomJid: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE roomJid = :roomJid ORDER BY date DESC LIMIT :limit")
    suspend fun getLatestMessages(roomJid: String, limit: Int): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE roomJid = :roomJid")
    suspend fun deleteMessagesForRoom(roomJid: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
}

/**
 * Chat database
 */
@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: ChatDatabase? = null

        fun getDatabase(context: android.content.Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "chat_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * Message cache helper
 */
class MessageCache(private val database: ChatDatabase) {
    private val messageDao = database.messageDao()

    /**
     * Get messages for room
     */
    fun getMessagesForRoom(roomJid: String): Flow<List<Message>> {
        return messageDao.getMessagesForRoom(roomJid).map { entities ->
            entities.map { it.toMessage() }
        }
    }

    /**
     * Get latest messages
     */
    suspend fun getLatestMessages(roomJid: String, limit: Int): List<Message> {
        return messageDao.getLatestMessages(roomJid, limit).map { it.toMessage() }
    }

    /**
     * Save message
     */
    suspend fun saveMessage(message: Message) {
        messageDao.insertMessage(MessageEntity.fromMessage(message))
    }

    /**
     * Save messages
     */
    suspend fun saveMessages(messages: List<Message>) {
        messageDao.insertMessages(messages.map { MessageEntity.fromMessage(it) })
    }

    /**
     * Clear messages for room
     */
    suspend fun clearMessagesForRoom(roomJid: String) {
        messageDao.deleteMessagesForRoom(roomJid)
    }

    /**
     * Clear all messages
     */
    suspend fun clearAllMessages() {
        messageDao.deleteAllMessages()
    }
}
