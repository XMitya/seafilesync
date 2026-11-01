package com.xmitya.seafilesync.service

import com.xmitya.seafilesync.data.db.SyncedRepoEntity
import com.xmitya.seafilesync.data.prefs.Account
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/**
 * Whether the sync service has anything to do: someone is signed in and at least one library is
 * switched on.
 *
 * The service runs only while this holds. A dataSync foreground service whose notification
 * sits there with nothing behind it is exactly what Play review rejects, and it also spends the
 * daily foreground budget Android 15 gives that service type for nothing.
 */
class SyncDemand(
    account: Flow<Account?>,
    libraries: Flow<List<SyncedRepoEntity>>,
) {

    val hasWork: Flow<Boolean> = combine(account, libraries) { signedIn, enabled ->
        signedIn != null && enabled.isNotEmpty()
    }.distinctUntilChanged()

    suspend fun current(): Boolean = hasWork.first()
}
