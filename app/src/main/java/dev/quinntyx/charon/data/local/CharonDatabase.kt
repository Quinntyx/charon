package dev.quinntyx.charon.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        MerchantEntity::class,
        TagEntity::class,
        ReceiptReferenceEntity::class,
        LedgerTransactionEntity::class,
        TransactionTagEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
internal abstract class CharonDatabase : RoomDatabase() {
    abstract fun charonDao(): CharonDao

    companion object {
        fun open(context: Context, databaseName: String): CharonDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                CharonDatabase::class.java,
                databaseName,
            ).build()
    }
}
