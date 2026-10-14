package dev.quinntyx.charon.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class,
        MerchantEntity::class,
        TagEntity::class,
        ReceiptReferenceEntity::class,
        LedgerTransactionEntity::class,
        TransactionTagEntity::class,
        RecurringPaymentEntity::class,
        RecurringPaymentTagEntity::class,
        LoggedOccurrenceEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
internal abstract class CharonDatabase : RoomDatabase() {
    abstract fun charonDao(): CharonDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recurring_payments (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        currency_code TEXT NOT NULL,
                        folder_account_id INTEGER NOT NULL,
                        merchant_id INTEGER,
                        starts_on_epoch_day INTEGER NOT NULL,
                        rule_type TEXT NOT NULL,
                        rule_interval INTEGER NOT NULL,
                        logging_policy TEXT NOT NULL,
                        amount_revisions_json TEXT NOT NULL,
                        skipped_dates_json TEXT NOT NULL,
                        pause_windows_json TEXT NOT NULL,
                        paused_since_epoch_day INTEGER,
                        cancelled_from_epoch_day INTEGER,
                        FOREIGN KEY(folder_account_id) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
                        FOREIGN KEY(merchant_id) REFERENCES merchants(id) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_payments_folder_account_id ON recurring_payments(folder_account_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_payments_merchant_id ON recurring_payments(merchant_id)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recurring_payment_tags (
                        recurring_payment_id TEXT NOT NULL,
                        tag_id INTEGER NOT NULL,
                        PRIMARY KEY(recurring_payment_id, tag_id),
                        FOREIGN KEY(recurring_payment_id) REFERENCES recurring_payments(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(tag_id) REFERENCES tags(id) ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_payment_tags_tag_id ON recurring_payment_tags(tag_id)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS logged_occurrences (
                        recurring_payment_id TEXT NOT NULL,
                        due_epoch_day INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        currency_code TEXT NOT NULL,
                        amount_minor INTEGER NOT NULL,
                        folder_account_id INTEGER NOT NULL,
                        merchant_id INTEGER,
                        tag_ids_json TEXT NOT NULL,
                        logging_policy TEXT NOT NULL,
                        logged_at_epoch_millis INTEGER NOT NULL,
                        origin TEXT NOT NULL,
                        ledger_transaction_id INTEGER NOT NULL,
                        PRIMARY KEY(recurring_payment_id, due_epoch_day),
                        FOREIGN KEY(ledger_transaction_id) REFERENCES ledger_transactions(id) ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_logged_occurrences_ledger_transaction_id ON logged_occurrences(ledger_transaction_id)")
            }
        }

        fun open(context: Context, databaseName: String): CharonDatabase =
            Room.databaseBuilder(context.applicationContext, CharonDatabase::class.java, databaseName)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
