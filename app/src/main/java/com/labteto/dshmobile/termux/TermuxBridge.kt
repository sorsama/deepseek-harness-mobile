package com.labteto.dshmobile.termux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs one script inside Termux and waits for what it printed.
 *
 * Termux offers exactly one door to other apps: its `RunCommandService`, guarded by a permission
 * Termux itself declares. The command is delivered as a service intent; the result comes back
 * later through a `PendingIntent` this app minted, which is why the two halves meet in [pending]
 * rather than in a return value. The `PendingIntent` has to be mutable — Termux fills the result
 * bundle into it — and every command gets its own request code, because `PendingIntent` identity
 * ignores extras and a shared code would let a second command overwrite the first's id.
 *
 * `startForegroundService` rather than `startService`: Termux's service promotes itself to the
 * foreground at once, and from Android 8 a plain start into another app's background is refused.
 */
@Singleton
class TermuxBridge @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<TermuxResult>>()
    private val nextRequestCode = AtomicInteger(0x5A00)

    /** Whether Termux is on this phone. Needs the `<queries>` entry in the manifest to say yes. */
    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(TermuxPaths.PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, TermuxPaths.PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** An intent that opens Termux itself, or null when it is not installed. */
    fun launchIntent(): Intent? = context.packageManager
        .getLaunchIntentForPackage(TermuxPaths.PACKAGE)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Run [command] and wait for its result, or for [TermuxCommand.timeoutMs] to pass.
     *
     * Never throws: every way this can go wrong is a [TermuxResult] the screen has words for.
     */
    suspend fun run(command: TermuxCommand): TermuxResult {
        if (!isInstalled()) return TermuxResult.NotInstalled
        if (!hasPermission()) return TermuxResult.PermissionDenied

        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<TermuxResult>()
        pending[requestId] = deferred

        val resultIntent = Intent(context, TermuxResultReceiver::class.java)
            .setAction(TermuxResultReceiver.ACTION_RESULT)
            .putExtra(TermuxResultReceiver.EXTRA_REQUEST_ID, requestId)
            .putExtra(TermuxResultReceiver.EXTRA_KIND, command.kind.name)
            .putExtra(TermuxResultReceiver.EXTRA_PORT, command.port ?: -1)
        val resultPendingIntent = PendingIntentCompat.getBroadcast(
            context,
            nextRequestCode.getAndIncrement(),
            resultIntent,
            PendingIntent.FLAG_UPDATE_CURRENT,
            true,
        )
        val intent = Intent(TermuxPaths.ACTION_RUN_COMMAND)
            .setClassName(TermuxPaths.PACKAGE, TermuxPaths.RUN_COMMAND_SERVICE)
            .putExtra(TermuxPaths.EXTRA_COMMAND_PATH, TermuxPaths.BASH)
            .putExtra(TermuxPaths.EXTRA_ARGUMENTS, arrayOf("-c", command.script))
            .putExtra(TermuxPaths.EXTRA_WORKDIR, TermuxPaths.HOME)
            .putExtra(TermuxPaths.EXTRA_BACKGROUND, true)
            .putExtra(TermuxPaths.EXTRA_COMMAND_LABEL, command.label)
            .putExtra(TermuxPaths.EXTRA_PENDING_INTENT, resultPendingIntent)

        val refused: TermuxResult? = try {
            if (context.startForegroundService(intent) == null) TermuxResult.NotInstalled else null
        } catch (e: SecurityException) {
            TermuxResult.PermissionDenied
        } catch (e: IllegalStateException) {
            // Includes ForegroundServiceStartNotAllowedException on Android 12+: the app was not
            // visible when it asked, which only happens if a command is issued from the background.
            TermuxResult.ServiceUnavailable(e.message)
        } catch (e: RuntimeException) {
            TermuxResult.ServiceUnavailable(e.message)
        }
        if (refused != null) {
            pending.remove(requestId)
            resultPendingIntent?.cancel()
            return refused
        }
        return try {
            withTimeoutOrNull(command.timeoutMs) { deferred.await() } ?: run {
                resultPendingIntent?.cancel()
                TermuxResult.Timeout
            }
        } finally {
            pending.remove(requestId)
        }
    }

    /**
     * Deliver a result to whoever is waiting for [requestId].
     *
     * @return false when nobody is — the process that issued the command is gone, and the result
     *   is an orphan the caller may still want to act on.
     */
    internal fun complete(requestId: String, result: TermuxResult): Boolean =
        pending.remove(requestId)?.complete(result) ?: false
}
