package com.noahwallet.localization

import com.noahwallet.services.storage.LegacyLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Activity's ⋯ → Refresh reloads the list, so it has its own string: the shared "Refresh" reads as "renew" in es, sv and ko. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActivityCopyTest {
    @Test fun activitysRefreshReadsAsAReload() {
        val context = RuntimeEnvironment.getApplication()
        for ((language, reload) in listOf("es" to "Actualizar actividad", "sv" to "Uppdatera aktivitet", "ko" to "활동 새로고침", "ja" to "アクティビティを更新")) {
            context.getSharedPreferences("gt_store", 0).edit().putString(LegacyLocale.key, language).commit()
            assertEquals(reload, L10n.text(context, "Refresh activity"))
            assertNotEquals(L10n.text(context, "Refresh"), L10n.text(context, "Refresh activity"))
        }
        context.getSharedPreferences("gt_store", 0).edit().clear().commit()
    }
}
