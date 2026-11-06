package com.labteto.dshmobile.mockharness

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * Sample files for trying the app's file viewer by hand (or in tests) against the mock harness.
 *
 * They are opt-in: the default scenario holds exactly two files and a contract test counts them.
 * Start the CLI with `--viewer-fixtures` to serve these, then open them from the Files tab.
 * Everything is generated here, so there are no binary blobs in the repository.
 */
fun PanelScenario.addViewerFixtures() {
    val png = samplePng(96, 64)
    val text = { value: String -> value.toByteArray(Charsets.UTF_8) }

    files["data.csv"] = text(
        "id,name,note\n1,Ada,\"Likes, commas\"\n2,Grace,\"Says \"\"hi\"\"\"\n3,Linus,\"two\nlines\"\n4,Margaret,\n",
    )
    files["wide.csv"] = text(
        (listOf((1..8).joinToString(",") { "column_$it" }) +
            (1..3000).map { row -> (1..8).joinToString(",") { column -> "r${row}c$column" } }).joinToString("\n"),
    )
    files["scores.tsv"] = text("name\tscore\nAda\t10\nGrace\t12\n")
    files["settings.json"] = text(
        """{"name":"demo","version":1,"tags":["a","b"],"nested":{"ok":true,"none":null,"pi":3.14,"long":"${"word ".repeat(80)}"}}""",
    )
    files["broken.json"] = text("{\n  \"name\": \"x\",\n  \"oops\" 1\n}\n")
    files["events.jsonl"] = text((1..40).joinToString("\n") { """{"id":$it,"event":"tick","ok":${it % 2 == 0}}""" })
    files["places.geojson"] = text(
        """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":"Null Island"},"geometry":{"type":"Point","coordinates":[0,0]}}]}""",
    )
    files["notebook.ipynb"] = text(sampleNotebook(png))
    files["change.patch"] = text(
        "diff --git a/src/app.kt b/src/app.kt\nindex 83db48f..bf2a3c1 100644\n--- a/src/app.kt\n+++ b/src/app.kt\n" +
            "@@ -1,5 +1,6 @@ fun main()\n fun main() {\n-    println(\"hello\")\n+    println(\"hello, world\")\n+    println(\"bye\")\n }\n \n",
    )
    files["logo.svg"] = text(
        """<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg" width="400" height="200" viewBox="0 0 400 200"><rect width="400" height="200" fill="#2a2c33"/><circle cx="200" cy="100" r="60" fill="#6e9bff"/><script>alert(1)</script></svg>""",
    )
    files["Dockerfile"] = text("FROM node:20-alpine\nWORKDIR /app\n# install\nRUN npm ci --omit=dev\nCMD [\"node\", \"server.js\"]\n")
    files["config.yaml"] = text("name: build\non:\n  push:\n    branches: [main] # only main\nsteps:\n  - run: make test\n")
    files["settings.ini"] = text("; app\n[server]\nport = 8080\nhost = \"localhost\"\n")
    files["query.sql"] = text("-- active users\nSELECT id, name FROM users WHERE active = 1 ORDER BY name;\n")
    files["long.txt"] = text((1..3000).joinToString("\n") { "Line $it: the quick brown fox jumps over the lazy dog" })
    files["image.png"] = png
    // Same picture, no extension: found by its first bytes.
    files["image-noext"] = png
    files["blob.bin"] = ByteArray(6000) { ((it * 31 + it / 7) and 0xFF).toByte() }
    files["bundle.zip"] = zipOf(
        "docs/" to null,
        "docs/readme.txt" to text("hello from inside a zip"),
        "src/main.kt" to text("fun main() = println(1)"),
        "assets/data.bin" to ByteArray(2048),
    )
}

private fun samplePng(width: Int, height: Int): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until height) for (x in 0 until width) {
        image.setRGB(x, y, ((x * 255 / width) shl 16) or ((y * 255 / height) shl 8) or 0x80)
    }
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}

private fun zipOf(vararg entries: Pair<String, ByteArray?>): ByteArray {
    val buffer = ByteArrayOutputStream()
    ZipOutputStream(buffer).use { zip ->
        for ((name, data) in entries) {
            zip.putNextEntry(ZipEntry(name))
            if (data != null) zip.write(data)
            zip.closeEntry()
        }
    }
    return buffer.toByteArray()
}

private fun sampleNotebook(png: ByteArray): String {
    val picture = Base64.getEncoder().encodeToString(png)
    return """
        {"nbformat":4,"nbformat_minor":5,
         "metadata":{"kernelspec":{"language":"python","name":"python3"},"language_info":{"name":"python"}},
         "cells":[
          {"cell_type":"markdown","metadata":{},"source":["# Notebook fixture\n","Some **markdown** and a list:\n","\n","- one\n","- two"]},
          {"cell_type":"code","execution_count":1,"metadata":{},"source":["import math\n","print(math.pi)"],
           "outputs":[{"output_type":"stream","name":"stdout","text":["3.141592653589793\n"]}]},
          {"cell_type":"code","execution_count":2,"metadata":{},"source":["1 / 0"],
           "outputs":[{"output_type":"error","ename":"ZeroDivisionError","evalue":"division by zero",
                       "traceback":["\u001b[31mZeroDivisionError\u001b[0m: division by zero"]}]},
          {"cell_type":"code","execution_count":3,"metadata":{},"source":["show_picture()"],
           "outputs":[{"output_type":"display_data","metadata":{},"data":{"image/png":"$picture","text/plain":["<Figure>"]}}]},
          {"cell_type":"raw","metadata":{},"source":["raw cell"]}
         ]}
    """.trimIndent()
}
