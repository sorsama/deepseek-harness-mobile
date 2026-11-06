package com.labteto.dshmobile.ui.screens.main.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

class FileTypesTest {

    @Test
    fun `the extension decides first and is case blind`() {
        assertEquals(PreviewKind.Image, detectFileType("photos/Cat.PNG")!!.kind)
        assertEquals(PreviewKind.Image, detectFileType("a.jpeg")!!.kind)
        assertEquals(PreviewKind.Pdf, detectFileType("docs/spec.pdf")!!.kind)
        assertEquals(PreviewKind.Video, detectFileType("clip.mov")!!.kind)
        assertEquals(PreviewKind.Audio, detectFileType("song.flac")!!.kind)
        assertEquals(PreviewKind.Svg, detectFileType("logo.svg")!!.kind)
        assertEquals(PreviewKind.Markdown, detectFileType("README.md")!!.kind)
        assertEquals(PreviewKind.Html, detectFileType("index.htm")!!.kind)
        assertEquals(PreviewKind.Notebook, detectFileType("lab.ipynb")!!.kind)
        assertEquals(PreviewKind.Diff, detectFileType("fix.patch")!!.kind)
    }

    @Test
    fun `tables and json variants keep their own badge`() {
        assertEquals("CSV", detectFileType("a.csv")!!.label)
        assertEquals("TSV", detectFileType("a.tsv")!!.label)
        assertEquals("JSON", detectFileType("a.json")!!.label)
        assertEquals("GeoJSON", detectFileType("map.geojson")!!.label)
        assertEquals("JSON Lines", detectFileType("log.jsonl")!!.label)
        assertEquals(PreviewKind.Json, detectFileType("log.ndjson")!!.kind)
    }

    @Test
    fun `source and config files are code with a language label`() {
        val kotlin = detectFileType("src/Main.kt")!!
        assertEquals(PreviewKind.Code, kotlin.kind)
        assertEquals("Kotlin", kotlin.label)
        assertEquals("kt", kotlin.extension)
        for (name in listOf("a.yaml", "a.toml", "a.ini", "a.xml", "a.env", "a.properties", "a.sql", "a.sh", "build.gradle", "a.gradle")) {
            assertEquals(name, PreviewKind.Code, detectFileType(name)!!.kind)
        }
    }

    @Test
    fun `well known bare names are source`() {
        assertEquals("dockerfile", detectFileType("Dockerfile")!!.extension)
        assertEquals("makefile", detectFileType("build/Makefile")!!.extension)
        assertEquals("env", detectFileType(".env")!!.extension)
        assertEquals("env", detectFileType("config/.env.local")!!.extension)
        assertEquals("gitignore", detectFileType(".gitignore")!!.extension)
        assertEquals(PreviewKind.Text, detectFileType("LICENSE")!!.kind)
        assertEquals("cmake", detectFileType("CMakeLists.txt")!!.extension)
        assertEquals("rb", detectFileType("Gemfile")!!.extension)
    }

    @Test
    fun `only the last extension counts`() {
        assertEquals(PreviewKind.Binary, detectFileType("backup.tar.gz")!!.kind)
        assertEquals(PreviewKind.Code, detectFileType("app.test.ts")!!.kind)
        assertEquals(PreviewKind.Json, detectFileType("tsconfig.build.json")!!.kind)
    }

    @Test
    fun `text and binary extensions map to their sources`() {
        assertEquals(PreviewKind.Text, detectFileType("notes.txt")!!.kind)
        assertEquals(PreviewKind.Text, detectFileType("run.log")!!.kind)
        assertEquals(PreviewSource.Text, detectFileType("a.csv")!!.source)
        assertEquals(PreviewSource.Bytes, detectFileType("a.png")!!.source)
        assertEquals(PreviewSource.Bytes, detectFileType("a.zip")!!.source)
        assertEquals(PreviewSource.Head, detectFileType("a.exe")!!.source)
        assertEquals(PreviewSource.Head, detectFileType("a.woff2")!!.source)
    }

    @Test
    fun `json and notebooks need the whole text`() {
        assertTrue(detectFileType("a.json")!!.needsWholeText)
        assertTrue(detectFileType("a.ipynb")!!.needsWholeText)
        assertFalse(detectFileType("a.csv")!!.needsWholeText)
        assertFalse(detectFileType("a.kt")!!.needsWholeText)
    }

    @Test
    fun `an unknown or missing extension asks for the bytes`() {
        assertNull(detectFileType("data.weird"))
        assertNull(detectFileType("noextension"))
        assertNull(detectFileType("build/output"))
        assertNull(typeFromName("trailing."))
    }

    @Test
    fun `the harness mime is used when the name says nothing`() {
        assertEquals(PreviewKind.Image, detectFileType("blob", mime = "image/png")!!.kind)
        assertEquals(PreviewKind.Image, detectFileType("blob", mime = "image/jpeg; charset=binary")!!.kind)
        assertEquals(PreviewKind.Svg, detectFileType("blob", mime = "image/svg+xml")!!.kind)
        assertEquals(PreviewKind.Video, detectFileType("blob", mime = "video/webm")!!.kind)
        assertEquals(PreviewKind.Audio, detectFileType("blob", mime = "audio/mpeg")!!.kind)
        assertEquals(PreviewKind.Json, detectFileType("blob", mime = "application/json")!!.kind)
        assertEquals(PreviewKind.Json, detectFileType("blob", mime = "application/vnd.api+json")!!.kind)
        assertEquals("GeoJSON", detectFileType("blob", mime = "application/geo+json")!!.label)
        assertEquals(PreviewKind.Notebook, detectFileType("blob", mime = "application/x-ipynb+json")!!.kind)
        assertEquals(PreviewKind.Table, detectFileType("blob", mime = "text/csv")!!.kind)
        assertEquals(PreviewKind.Text, detectFileType("blob", mime = "text/plain")!!.kind)
        assertEquals(PreviewKind.Pdf, detectFileType("blob", mime = "application/pdf")!!.kind)
        assertNull(detectFileType("blob", mime = "application/x-unknown-thing"))
    }

    @Test
    fun `an extension beats a conflicting mime`() {
        assertEquals(PreviewKind.Markdown, detectFileType("a.md", mime = "text/plain")!!.kind)
    }

    @Test
    fun `an unusual image mime still renders as an image`() {
        val tiff = detectFileType("blob", mime = "image/tiff")!!
        assertEquals(PreviewKind.Image, tiff.kind)
        assertEquals("TIFF", tiff.label)
    }

    @Test
    fun `magic bytes identify common formats`() {
        assertEquals("png", sniffFileType(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0))!!.extension)
        assertEquals("jpg", sniffFileType(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10))!!.extension)
        assertEquals("gif", sniffFileType(ascii("GIF89a\u0001\u0000"))!!.extension)
        assertEquals("webp", sniffFileType(ascii("RIFF\u0010\u0000\u0000\u0000WEBPVP8 "))!!.extension)
        assertEquals("wav", sniffFileType(ascii("RIFF\u0010\u0000\u0000\u0000WAVEfmt "))!!.extension)
        assertEquals("pdf", sniffFileType(ascii("%PDF-1.7\n%"))!!.extension)
        assertEquals("zip", sniffFileType(bytes(0x50, 0x4B, 0x03, 0x04, 0x14, 0))!!.extension)
        assertEquals("gz", sniffFileType(bytes(0x1F, 0x8B, 0x08, 0))!!.extension)
        assertEquals("ogg", sniffFileType(ascii("OggS\u0000\u0002"))!!.extension)
        assertEquals("flac", sniffFileType(ascii("fLaC\u0000\u0000"))!!.extension)
        assertEquals("mp3", sniffFileType(ascii("ID3\u0004\u0000\u0000"))!!.extension)
        // "ID3" at the start of prose is not a tag.
        assertEquals(PreviewKind.Text, sniffFileType(ascii("ID3 tags are described below."))!!.kind)
        assertEquals("otf", sniffFileType(ascii("OTTO\u0000\u000a"))!!.extension)
        assertTrue(sniffFileType(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0))!!.sniffed)
    }

    @Test
    fun `an mp4 family box is told apart by its brand`() {
        fun box(brand: String) = bytes(0, 0, 0, 0x18) + ascii("ftyp$brand") + bytes(0, 0, 0, 0)
        assertEquals("mp4", sniffFileType(box("isom"))!!.extension)
        assertEquals("mp4", sniffFileType(box("mp42"))!!.extension)
        assertEquals("heic", sniffFileType(box("heic"))!!.extension)
        assertEquals("heic", sniffFileType(box("mif1"))!!.extension)
        assertEquals("avif", sniffFileType(box("avif"))!!.extension)
        assertEquals("mov", sniffFileType(box("qt  "))!!.extension)
        assertEquals("m4a", sniffFileType(box("M4A "))!!.extension)
        assertEquals("3gp", sniffFileType(box("3gp4"))!!.extension)
    }

    @Test
    fun `matroska is split into webm and mkv by its doctype`() {
        val webm = bytes(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x82, 0x84) + ascii("webm") + ByteArray(8)
        val mkv = bytes(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x82, 0x88) + ascii("matroska") + ByteArray(8)
        assertEquals("webm", sniffFileType(webm)!!.extension)
        assertEquals("mkv", sniffFileType(mkv)!!.extension)
    }

    @Test
    fun `bmp and ico need more than two letters or four zero bytes`() {
        val bmp = ByteArray(40).also {
            it[0] = 'B'.code.toByte(); it[1] = 'M'.code.toByte(); it[14] = 40
        }
        assertEquals("bmp", sniffFileType(bmp)!!.extension)
        // Prose that merely starts with "BM" is text, not a bitmap.
        assertEquals(PreviewKind.Text, sniffFileType(ascii("BMW makes cars in several countries and plants."))!!.kind)
        val ico = ByteArray(30).also { it[2] = 1; it[4] = 1 }
        assertEquals("ico", sniffFileType(ico)!!.extension)
    }

    @Test
    fun `text is told from binary by its bytes`() {
        assertEquals(PreviewKind.Text, sniffFileType("just words\nmore words\n".toByteArray())!!.kind)
        assertEquals(PreviewKind.Text, sniffFileType("naïve café ☕".toByteArray(Charsets.UTF_8))!!.kind)
        assertEquals(PreviewKind.Binary, sniffFileType(bytes(0x01, 0x02, 0x00, 0x04, 0x05))!!.kind)
        assertEquals(PreviewKind.Binary, sniffFileType(ByteArray(64) { (it * 7).toByte() })!!.kind)
    }

    @Test
    fun `a character cut off by the window is still text`() {
        val whole = "héllo wörld ☕".toByteArray(Charsets.UTF_8)
        assertTrue(looksLikeText(whole.copyOf(whole.size - 1)))
        assertTrue(looksLikeText(ByteArray(0)))
        assertFalse(looksLikeText(bytes(0x68, 0x00, 0x69)))
    }

    @Test
    fun `text content with a recognisable start gets a specific type`() {
        assertEquals(PreviewKind.Svg, sniffFileType(ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"))!!.kind)
        assertEquals(PreviewKind.Svg, sniffFileType(ascii("<?xml version=\"1.0\"?>\n<svg width=\"1\"/>"))!!.kind)
        assertEquals(PreviewKind.Html, sniffFileType(ascii("<!DOCTYPE html><html></html>"))!!.kind)
        assertEquals(PreviewKind.Diff, sniffFileType(ascii("diff --git a/x b/x\n"))!!.kind)
        assertEquals("xml", sniffFileType(ascii("<?xml version=\"1.0\"?><root/>"))!!.extension)
        val script = sniffFileType(ascii("#!/usr/bin/env python3\nprint(1)\n"))!!
        assertEquals(PreviewKind.Code, script.kind)
        assertEquals("py", script.extension)
        assertEquals("sh", sniffFileType(ascii("#!/bin/bash\nls\n"))!!.extension)
    }

    @Test
    fun `detect falls back to text for an empty file when bytes were read`() {
        assertEquals(PreviewKind.Text, detectFileType("empty", head = ByteArray(0))!!.kind)
        assertNotNull(detectFileType("blob", head = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
    }

    @Test
    fun `name and bytes both give an answer when the extension lies`() {
        // The name wins: a .txt that is really a PNG is still opened as text by name. Sniffing is only for names that say nothing.
        assertEquals(PreviewKind.Text, detectFileType("oops.txt", head = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))!!.kind)
        assertEquals(PreviewKind.Image, detectFileType("oops", head = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))!!.kind)
    }
}
