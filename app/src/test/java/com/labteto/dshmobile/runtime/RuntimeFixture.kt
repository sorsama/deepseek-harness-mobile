package com.labteto.dshmobile.runtime

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress

/**
 * A runtime release served over HTTPS from a local server, signed with a test key, plus an app
 * storage layout on a temporary folder: what the installer and the engine tests run against.
 */
class RuntimeFixture(tmp: TemporaryFolder) {
    val key = TestSigningKey()
    val release = ReleaseServer()
    val fs = RecordingFsOps()
    val layout = RuntimeLayout(File(tmp.newFolder("no_backup"), "runtime"), File(tmp.newFolder("cache"), "runtime"))

    /** What [SpaceProbe] reports. */
    @Volatile var freeBytes: Long = 64L shl 30

    private val server = MockWebServer()
    val client: OkHttpClient

    init {
        val names = listOf("localhost", "127.0.0.1") +
            listOf("localhost", "127.0.0.1").map { InetAddress.getByName(it).canonicalHostName }
        val certificate = HeldCertificate.Builder().apply { names.distinct().forEach { addSubjectAlternativeName(it) } }.build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.dispatcher = release
        server.start()
        client = OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build()
    }

    fun close() = server.shutdown()

    val manifestUrl: String get() = server.url("/rel/manifest.json").toString()

    /** The manifest URL of a release published under [dir] (`publish(..., dir = ...)`). */
    fun manifestUrlOf(dir: String): String = server.url("/$dir/manifest.json").toString()

    data class Layer(val name: String, val id: String, val bytes: ByteArray, val entries: Int) {
        val file = "dsh-runtime-$name-x86_64-$id.tar.zst"
    }

    fun baseLayer(id: String = "trixie-test-r1"): Layer {
        val tar = TarBuilder()
            .dir("usr").dir("usr/bin").file("usr/bin/bash", "elf", "755".toInt(8)).symlink("bin", "usr/bin")
            .dir("etc").file("etc/resolv.conf", "nameserver 8.8.8.8\n").file("etc/hosts", "127.0.0.1 localhost\n")
            .dir("root", "700".toInt(8)).file("root/.bashrc", "# bashrc\n").dir("root/work")
            .dir("tmp", "1777".toInt(8))
            .dir("opt").dir("opt/dsh-runtime").dir("opt/dsh-runtime/proc")
            .file("opt/dsh-runtime/proc/stat", SHIPPED_STAT)
            .file("opt/dsh-runtime/proc/loadavg", "0.12 0.10 0.05 1/128 1\n")
            .file("opt/dsh-runtime/proc/uptime", "1000.00 7000.00\n")
            .dir("usr/share", "555".toInt(8)).file("usr/share/readonly", "y")
            .dir("usr/local").dir("usr/local/bin")
        return Layer(LAYER_BASE, id, tar.bytes(), 21)
    }

    fun harnessLayer(version: String, extra: TarBuilder.() -> Unit = {}): Layer {
        val tar = TarBuilder()
            .dir("opt").dir("opt/dsh").file("opt/dsh/layer.json", """{"harnessVersion":"$version"}""")
            .dir("usr").dir("usr/local").dir("usr/local/bin")
            .file("usr/local/bin/dsh", "#!/bin/sh\nexec node /opt/dsh/bin.js \"\$@\"\n", "755".toInt(8))
        tar.extra()
        return Layer(LAYER_HARNESS, "dsh-$version", tar.bytes(), 7)
    }

    /**
     * Publish a release: the layers and a manifest describing them, signed by [signer]. [dir] is
     * where it is served (`rel` is "the latest"); [previous] is the release before it, by its dir
     * (it must have been published already), for the manifest's `previous` pointer.
     */
    fun publish(
        base: Layer,
        harness: Layer,
        sequence: Long,
        harnessVersion: String = harness.id.removePrefix("dsh-"),
        minAppVersion: String = "0.22.0",
        signer: TestSigningKey = key,
        servedBase: ByteArray = base.bytes,
        dir: String = "rel",
        previous: Pair<String, Long>? = null,
        releaseId: String = "test-$sequence",
    ) {
        release.files["rel/assets/${base.file}"] = servedBase
        release.files["rel/assets/${harness.file}"] = harness.bytes
        val pointer = previous?.let { (prevDir, prevSequence) ->
            """{"releaseId": "test-$prevSequence", "harnessVersion": "x", "sequence": $prevSequence, "manifestUrl": "${manifestUrlOf(prevDir)}"}"""
        }
        val json = manifestJson(base, harness, sequence, harnessVersion, minAppVersion, pointer, releaseId)
        release.files["$dir/manifest.json"] = json.toByteArray()
        release.files["$dir/manifest.json.sig"] = signer.signatureFile(json.toByteArray())
    }

    fun installer(appVersion: String = "0.22.0") = RuntimeInstaller(
        layout = layout,
        store = InstalledStore(layout),
        fs = fs,
        client = client,
        downloader = LayerDownloader(client, layout.downloads),
        space = SpaceProbe { freeBytes },
        manifestUrl = { manifestUrl },
        appVersion = { appVersion },
        deviceAbis = listOf("x86_64"),
        verifier = ManifestVerifier(key.rawPublicKey),
        decoder = { it },
    )

    fun assetRequests(layer: Layer) = release.requestsFor("rel/assets/${layer.file}")

    private fun manifestJson(
        base: Layer,
        harness: Layer,
        sequence: Long,
        harnessVersion: String,
        minAppVersion: String,
        previous: String? = null,
        releaseId: String = "test-$sequence",
    ): String {
        fun layer(l: Layer, extra: String) = """
            {"file": "${l.file}", "size": ${l.bytes.size}, "sha256": "${sha256Hex(l.bytes)}", "name": "${l.name}", "id": "${l.id}",
             "compression": "zstd", "zstdWindowLog": 27, "format": "tar (GNU)", "unpackedSize": ${l.bytes.size}, "entries": ${l.entries}$extra}
        """.trimIndent()
        return """
            {
              "schemaVersion": 1,
              "releaseId": "$releaseId",${if (previous != null) "\n  \"previous\": $previous," else ""}
              "sequence": $sequence,
              "createdAt": "2026-11-14T00:00:00Z",
              "minAppVersion": "$minAppVersion",
              "signing": {"algorithm": "Ed25519", "keyId": "${ManifestVerifier.PINNED_KEY_ID}", "signatureFile": "manifest.json.sig"},
              "assetBaseUrl": "${server.url("/rel/assets/")}",
              "harness": {"package": "@deepseek-ai/dsh", "version": "$harnessVersion"},
              "node": {"version": "24.22.3", "home": "/opt/node"},
              "runtime": {
                "entrypoint": "/usr/local/bin/dsh",
                "readinessPrefix": "dsh web: http://127.0.0.1:",
                "fakeProc": [
                  {"source": "/opt/dsh-runtime/proc/stat", "target": "/proc/stat"},
                  {"source": "/opt/dsh-runtime/proc/loadavg", "target": "/proc/loadavg"},
                  {"source": "/opt/dsh-runtime/proc/uptime", "target": "/proc/uptime"}
                ],
                "resolvConf": "/etc/resolv.conf"
              },
              "archs": {
                "x86_64": {
                  "debianArch": "amd64",
                  "layers": [
                    ${layer(base, ""","owns": ["/"], "excludes": ["/opt/dsh", "/usr/local/bin/dsh"]""")},
                    ${layer(harness, ""","owns": ["/opt/dsh", "/usr/local/bin/dsh"]""")}
                  ]
                }
              }
            }
        """.trimIndent()
    }

    companion object {
        val SHIPPED_STAT = buildString {
            append("cpu  4000 0 2000 64000 100 0 50 0 0 0\n")
            for (i in 0 until 8) append("cpu$i 500 0 250 8000 12 0 6 0 0 0\n")
            append("intr 0\nctxt 0\nbtime 1762732800\nprocesses 1\nprocs_running 1\nprocs_blocked 0\nsoftirq 0 0 0 0 0 0 0 0 0 0 0\n")
        }
    }
}
