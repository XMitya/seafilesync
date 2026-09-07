package com.xmitya.seafilesync.ui.libraries

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xmitya.seafilesync.ui.theme.SeafileSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibrariesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun library(
        name: String = "My Library",
        state: SyncState = SyncState.NotSynced,
        encrypted: Boolean = false,
        error: String? = null,
    ) = LibraryUi(
        id = name,
        name = name,
        sizeBytes = 300_544,
        modifiedAtSeconds = 1_788_697_628,
        state = state,
        isEncrypted = encrypted,
        errorMessage = error,
    )

    private fun show(
        vararg libraries: LibraryUi,
        onSync: (LibraryUi) -> Unit = {},
        onStopSyncing: (LibraryUi) -> Unit = {},
        onRetry: (LibraryUi) -> Unit = {},
    ) {
        compose.setContent {
            SeafileSyncTheme {
                LibrariesScreen(
                    state = LibrariesUiState(libraries = libraries.toList()),
                    onSync = onSync,
                    onStopSyncing = onStopSyncing,
                    onRetry = onRetry,
                    onRefresh = {},
                    onOpenSettings = {},
                )
            }
        }
    }

    @Test
    fun a_synced_library_shows_the_cloud() {
        show(library(state = SyncState.Synced))

        compose.onNodeWithContentDescription("Synced").assertIsDisplayed()
    }

    @Test
    fun a_transferring_library_shows_the_sync_icon() {
        show(library(state = SyncState.Syncing))

        compose.onNodeWithContentDescription("Syncing").assertIsDisplayed()
    }

    @Test
    fun a_failed_library_shows_the_error_icon() {
        show(library(state = SyncState.Error, error = "Out of quota"))

        compose.onNodeWithContentDescription("Sync failed").assertIsDisplayed()
        compose.onNodeWithText("Out of quota").assertIsDisplayed()
    }

    @Test
    fun an_unsynced_library_shows_no_icon_at_all() {
        // Absence is the signal: the eye should be drawn to the libraries that are synced.
        show(library(state = SyncState.NotSynced))

        assertEquals(0, compose.onAllNodesWithTag(SYNC_STATUS_ICON_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun tapping_a_library_offers_to_sync_it() {
        show(library(state = SyncState.NotSynced))

        compose.onAllNodesWithTag(LIBRARY_ROW_TAG)[0].performClick()

        compose.onNodeWithText("Sync this library").assertIsDisplayed()
        // Nothing to stop or retry yet, so neither action is offered.
        assertEquals(0, compose.onAllNodesWithText("Stop syncing").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Retry now").fetchSemanticsNodes().size)
    }

    @Test
    fun a_failed_library_offers_a_retry() {
        show(library(state = SyncState.Error, error = "Network unreachable"))

        compose.onAllNodesWithTag(LIBRARY_ROW_TAG)[0].performClick()

        compose.onNodeWithText("Retry now").assertIsDisplayed()
        compose.onNodeWithText("Stop syncing").assertIsDisplayed()
    }

    @Test
    fun stopping_a_sync_is_confirmed_before_it_happens() {
        var stopped = false
        show(library(state = SyncState.Synced), onStopSyncing = { stopped = true })

        compose.onAllNodesWithTag(LIBRARY_ROW_TAG)[0].performClick()
        compose.onNodeWithText("Stop syncing").performClick()

        // The sheet action opens a confirmation rather than acting immediately.
        assertEquals(false, stopped)
        compose.onNodeWithText("Stop syncing My Library?").assertIsDisplayed()
    }

    @Test
    fun confirming_actually_stops_the_sync() {
        var stopped = false
        show(library(state = SyncState.Synced), onStopSyncing = { stopped = true })

        compose.onAllNodesWithTag(LIBRARY_ROW_TAG)[0].performClick()
        compose.onNodeWithText("Stop syncing").performClick()
        compose.onNodeWithText("Stop").performClick()

        assertEquals(true, stopped)
    }

    @Test
    fun encrypted_libraries_are_listed_but_cannot_be_synced() {
        show(library(name = "Secrets", encrypted = true))

        compose.onNodeWithText("Secrets").assertIsDisplayed()
        compose.onNodeWithText("Encrypted libraries are not supported yet").assertIsDisplayed()

        compose.onAllNodesWithTag(LIBRARY_ROW_TAG)[0].performClick()

        assertEquals(0, compose.onAllNodesWithTag(LIBRARY_ACTIONS_TAG).fetchSemanticsNodes().size)
    }
}
