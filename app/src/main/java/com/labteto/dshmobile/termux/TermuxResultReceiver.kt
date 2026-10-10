package com.labteto.dshmobile.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Where a Termux command's result lands.
 *
 * Declared in the manifest, not registered at runtime, so it is there even when the process that
 * issued the command has since been killed. In that case nobody is waiting in [TermuxBridge] and
 * the result goes to [TermuxHarnessController.onOrphanResult] instead — which matters for a
 * start, whose output carries the launch token a sign-in needs.
 *
 * Reaches Hilt through an entry point, as [com.labteto.dshmobile.connection.KeepAliveWorker]
 * does, rather than `@AndroidEntryPoint`: a receiver's `onReceive` is abstract on the platform
 * class, so the injected form cannot call through to it from Kotlin.
 */
class TermuxResultReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun bridge(): TermuxBridge
        fun controller(): TermuxHarnessController
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESULT) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val bundle = intent.getBundleExtra(TermuxPaths.RESULT_BUNDLE)
        val fields = TermuxResultFields(
            stdout = bundle?.getString(TermuxPaths.RESULT_STDOUT).orEmpty(),
            stderr = bundle?.getString(TermuxPaths.RESULT_STDERR).orEmpty(),
            exitCode = bundle?.getInt(TermuxPaths.RESULT_EXIT_CODE, -1) ?: -1,
            err = bundle?.getInt(TermuxPaths.RESULT_ERR, TermuxPaths.ERR_NONE) ?: TermuxPaths.ERR_NONE,
            errmsg = bundle?.getString(TermuxPaths.RESULT_ERRMSG),
        )
        val result = classifyResult(fields)
        val dependencies = EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java)
        if (!dependencies.bridge().complete(requestId, result)) {
            val kind = intent.getStringExtra(EXTRA_KIND)
                ?.let { name -> TermuxCommandKind.entries.firstOrNull { it.name == name } }
            val port = intent.getIntExtra(EXTRA_PORT, -1).takeIf { it > 0 }
            dependencies.controller().onOrphanResult(kind, port, result)
        }
    }

    companion object {
        const val ACTION_RESULT = "com.labteto.dshmobile.termux.RESULT"
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_KIND = "kind"
        const val EXTRA_PORT = "port"
    }
}
