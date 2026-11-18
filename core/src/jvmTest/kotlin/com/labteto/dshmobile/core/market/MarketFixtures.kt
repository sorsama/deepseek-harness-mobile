package com.labteto.dshmobile.core.market

import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.RpcError
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Real answers of the `pluginMarket` methods and of `pluginManager/inspect`, captured from the harness checkout at
 * 687f282ef (0.2.1-rc.2) booted with the optional plugin-market bundle and three index sources: a valid
 * index, an invalid one and a missing one, all served from a local server (`market/index.source.json` is the
 * valid one, written after the format note `2026-10-02-plugin-market-index-format.md`). Each file is the
 * whole `server-response` envelope the harness sent; [value] and [error] open it.
 */
internal object MarketFixtures {
    fun envelope(name: String): JsonObject =
        WireJson.parseToJsonElement(MarketFixtures::class.java.getResource("/market/$name.json")!!.readText()).jsonObject

    fun value(name: String): JsonElement = envelope(name).getValue("result").jsonObject.getValue("value")

    fun error(name: String): RpcError =
        WireJson.decodeFromJsonElement(RpcError.serializer(), envelope(name).getValue("result").jsonObject.getValue("error"))

    fun page(name: String): MarketPage = checkNotNull(parseMarketSearch(value(name))) { "fixture $name is not a search answer" }

    /** One synthetic version row shaped like the host's (an npm source, no registry). */
    fun version(name: String, version: String, extra: String = ""): String =
        """{"version":"$version","publishTimestamp":1790000000,"source":{"type":"npm"},"engines":{"dsh":"*"},"install":{"spec":"$name@$version"},"compatibility":{"compatible":true}$extra}"""

    /** One synthetic entry; [fields] are extra members, [versions] the raw version rows. */
    fun entry(
        name: String = "@example/dsh-x",
        fields: String = "",
        versions: List<String>? = null,
        installation: String = """{"status":"not-installed"}""",
        defaultVersion: String? = "1.0.0",
    ): String {
        val rows = versions ?: listOf(version(name, "1.0.0"))
        val default = defaultVersion?.let { """"defaultVersion":"$it",""" }.orEmpty()
        return """{"name":"$name","metadata":{"keywords":[],"labels":[],"sortKeys":{}},"versions":[${rows.joinToString(",")}],$default"installation":$installation${if (fields.isEmpty()) "" else ",$fields"}}"""
    }

    /** A synthetic search answer around raw entry rows; [metadata] is the raw declarations object. */
    fun answer(
        entries: List<String>,
        metadata: String = """{"categories":{},"labels":{},"sortKeys":{}}""",
        fetchedAt: Long = 1000,
        total: Int = entries.size,
        nextOffset: Int? = null,
    ): JsonElement = WireJson.parseToJsonElement(
        """{"url":"https://market.example/index.json","title":{"en":"Market"},"metadata":$metadata,"fetchedAt":$fetchedAt,"entries":[${entries.joinToString(",")}],"total":$total,"nextOffset":${nextOffset ?: "null"}}""",
    )
}
