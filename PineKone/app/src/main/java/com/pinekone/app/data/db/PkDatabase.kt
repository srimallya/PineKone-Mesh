package com.pinekone.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ContactEntity::class,
        MessageEntity::class,
        PublicMessageEntity::class,
        DecisionEventEntity::class,
        MutationEventEntity::class,
        AliasBindingEntity::class,
        InviteAttestationEntity::class,
        RoleAttestationEntity::class,
        RevocationEntity::class
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(PkTypeConverters::class)
abstract class PkDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun publicMessageDao(): PublicMessageDao
    abstract fun routingTelemetryDao(): RoutingTelemetryDao
    abstract fun governanceDao(): GovernanceDao

    companion object {
        fun build(context: Context): PkDatabase =
            Room.databaseBuilder(context, PkDatabase::class.java, "pk.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
