package com.eta.aicybox.platform.coloros

import com.eta.aicybox.config.ModuleSettingsSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCatalogSnapshotTest {
    @Test
    fun lateCatalogCannotReplaceNewerSelection() {
        val requested = ModuleSettingsSnapshot()
        val completed = AppCatalogSnapshot(settings = requested)
        assertTrue(completed.matches(requested))
        assertFalse(completed.matches(requested.copy(pinsSaved = true)))
    }

    @Test
    fun unrelatedGestureSettingsDoNotInvalidatePreparedIcons() {
        val requested = ModuleSettingsSnapshot()
        val completed = AppCatalogSnapshot(settings = requested)
        assertTrue(completed.matches(requested.copy(cornerTriggerRangeDp = 100, enabled = true)))
    }
}
