package dev.quinntyx.charon

import android.app.Application
import androidx.room.Room
import dev.quinntyx.charon.dashboard.RoomDashboardRepository
import dev.quinntyx.charon.data.local.CharonDatabase
import dev.quinntyx.charon.data.local.RoomBackupAdapter
import dev.quinntyx.charon.data.local.RoomLedgerRepository
import dev.quinntyx.charon.data.local.RoomRecurrenceRepository
import dev.quinntyx.charon.entry.RoomTransactionEntryAdapter
import dev.quinntyx.charon.recurrence.RecurrenceService

class CharonApplication : Application() {
    val database: CharonDatabase by lazy {
        Room.databaseBuilder(this, CharonDatabase::class.java, "charon.db").build()
    }

    val ledgerRepository: RoomLedgerRepository by lazy {
        RoomLedgerRepository(database, System::currentTimeMillis)
    }

    val recurrenceService: RecurrenceService by lazy {
        RecurrenceService(RoomRecurrenceRepository(database))
    }

    val dashboardRepository: RoomDashboardRepository by lazy {
        RoomDashboardRepository(ledgerRepository, recurrenceService)
    }

    val transactionEntryAdapter: RoomTransactionEntryAdapter by lazy {
        RoomTransactionEntryAdapter(ledgerRepository)
    }

    val backupAdapter: RoomBackupAdapter by lazy {
        RoomBackupAdapter(database, contentResolver, filesDir, cacheDir)
    }
}
