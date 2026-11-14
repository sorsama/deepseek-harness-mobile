package com.labteto.dshmobile.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the pre-install check needs to know about an APK: who it is, which version, and who
 * signed it ([signers] are SHA-256 digests of the signing certificates, lower-case hex; empty when
 * they could not be read).
 */
data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signers: Set<String>,
)

/** Why a downloaded APK is not handed to the system. */
sealed interface ApkRejection {
    /** A different app altogether. */
    data class WrongPackage(val found: String) : ApkRejection

    /** The running app is a debug build; a release APK has another id and key and cannot replace it. */
    data object DebugBuild : ApkRejection

    /** Not newer than what is installed: Android would call it a downgrade. */
    data class NotNewer(val candidateVersion: String) : ApkRejection

    /** Signed by someone else: Android would refuse it as a conflict. */
    data object SignatureMismatch : ApkRejection

    /** Android could not read the file as an APK at all. */
    data object Unreadable : ApkRejection
}

/**
 * Should [candidate] be handed to the package installer to replace [installed]?
 *
 * Android makes the same checks itself and refuses a mismatch, but only with a terse "app not
 * installed" or a raw conflict status; doing them first lets the update dialog say what is wrong.
 * Android's own signature check stays the final guard: when the candidate's signers cannot be read
 * (an old platform's archive parser), this check passes it on rather than refusing a file the
 * checksum already vouched for. A signer match is any overlap, so a key rotated through an APK
 * Signature Scheme v3 lineage, whose history still names the old key, still matches.
 *
 * @return null when the candidate may be installed.
 */
internal fun judgeCandidate(candidate: ApkFacts?, installed: ApkFacts, runningDebugBuild: Boolean): ApkRejection? {
    if (candidate == null) return ApkRejection.Unreadable
    if (candidate.packageName != installed.packageName) {
        val releaseIdOfThisBuild = installed.packageName.removeSuffix(DEBUG_SUFFIX)
        return if (runningDebugBuild && candidate.packageName == releaseIdOfThisBuild) {
            ApkRejection.DebugBuild
        } else {
            ApkRejection.WrongPackage(candidate.packageName)
        }
    }
    if (candidate.versionCode <= installed.versionCode) {
        return ApkRejection.NotNewer(candidate.versionName ?: candidate.versionCode.toString())
    }
    if (candidate.signers.isNotEmpty() && installed.signers.isNotEmpty() &&
        candidate.signers.intersect(installed.signers).isEmpty()
    ) {
        return ApkRejection.SignatureMismatch
    }
    return null
}

private const val DEBUG_SUFFIX = ".debug"

/** Reads [ApkFacts] for a file on disk and for the app that is running. A seam for tests. */
interface ApkInspector {
    fun inspectArchive(file: File): ApkFacts?
    fun installed(): ApkFacts
}

/** [ApkInspector] over [PackageManager.getPackageArchiveInfo] and the app's own [PackageInfo]. */
@Singleton
class PackageManagerApkInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) : ApkInspector {

    override fun inspectArchive(file: File): ApkFacts? = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(file.absolutePath, signingFlags()) ?: return null
        info.toFacts()
    }.getOrNull()

    override fun installed(): ApkFacts {
        val info = context.packageManager.getPackageInfo(context.packageName, signingFlags())
        return info.toFacts()
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun PackageInfo.toFacts(): ApkFacts {
        val signatures: List<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = signingInfo
            when {
                info == null -> emptyList()
                info.hasMultipleSigners() -> info.apkContentsSigners?.toList().orEmpty()
                else -> info.signingCertificateHistory?.toList().orEmpty()
            }
        } else {
            signatures?.toList().orEmpty()
        }
        return ApkFacts(
            packageName = packageName,
            versionCode = PackageInfoCompat.getLongVersionCode(this),
            versionName = versionName,
            signers = signatures.map { sha256Hex(it.toByteArray()) }.toSet(),
        )
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
