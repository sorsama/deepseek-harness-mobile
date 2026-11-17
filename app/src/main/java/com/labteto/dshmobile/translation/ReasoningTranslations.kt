package com.labteto.dshmobile.translation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The process-wide [ReasoningTranslationEngine], with its cache kept in a file in the app's cache
 * directory so translations survive a restart. One instance serves every screen, which is what makes
 * manual and automatic translation, the transcript and the subagent sheet share one cache.
 */
@Singleton
class ReasoningTranslations @Inject constructor(@ApplicationContext context: Context) {
    val engine = ReasoningTranslationEngine(
        parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        storage = FileTranslationStorage(File(context.cacheDir, "reasoning-translations-v1.json")),
    )
}
