package com.labteto.dshmobile.core.session

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewLinksTest {
    private val doc = "/workspace/README.md"

    @Test
    fun anAnchorInTheSamePageStaysInThePage() {
        assertEquals(PreviewLink.InPage, classifyPreviewLink("https://preview.invalid/workspace/README.md#install", doc))
    }

    @Test
    fun aRelativeLinkBecomesAWorkspacePath() {
        assertEquals(PreviewLink.Workspace("docs/guide.md"), classifyPreviewLink("https://preview.invalid/workspace/docs/guide.md", doc))
        assertEquals(PreviewLink.Workspace("docs/guide.md"), classifyPreviewLink("https://preview.invalid/workspace/docs/guide.md#top", doc))
        assertEquals(PreviewLink.Workspace("my notes/a b.md"), classifyPreviewLink("https://preview.invalid/workspace/my%20notes/a%20b.md", doc))
    }

    @Test
    fun anAnchorInAnotherFileIsAFileLink() {
        assertEquals(PreviewLink.Workspace("OTHER.md"), classifyPreviewLink("https://preview.invalid/workspace/OTHER.md#x", doc))
    }

    @Test
    fun webLinksAreExternalAndAskedAbout() {
        assertEquals(PreviewLink.External("https://example.com/a?b=1"), classifyPreviewLink("https://example.com/a?b=1", doc))
        assertEquals(PreviewLink.External("http://example.com"), classifyPreviewLink("http://example.com", doc))
        assertEquals(PreviewLink.External("mailto:a@b.co"), classifyPreviewLink("mailto:a@b.co", doc))
    }

    @Test
    fun everythingElseIsIgnored() {
        assertEquals(PreviewLink.Ignored, classifyPreviewLink("javascript:alert(1)", doc))
        assertEquals(PreviewLink.Ignored, classifyPreviewLink("file:///etc/passwd", doc))
        assertEquals(PreviewLink.Ignored, classifyPreviewLink("intent://x#Intent;end", doc))
        assertEquals(PreviewLink.Ignored, classifyPreviewLink("https://preview.invalid/other", doc))
        assertEquals(PreviewLink.Ignored, classifyPreviewLink("https://preview.invalid/workspace/", doc))
        assertEquals(PreviewLink.Ignored, classifyPreviewLink("not a url at all", doc))
    }
}
