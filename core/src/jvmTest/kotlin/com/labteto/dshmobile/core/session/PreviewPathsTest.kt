package com.labteto.dshmobile.core.session

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewPathsTest {
    @Test fun resolvesHostPathsAcrossPlatforms() {
        assertEquals("docs/images/a.png", resolvePreviewReference("docs/chapter/page.md", "../images/a.png"))
        assertEquals("G:/work/docs/a.md", resolvePreviewReference("G:\\work\\readme.md", "docs/a.md"))
        assertEquals("/tmp/a.pdf", resolvePreviewReference("docs/page.md", "/tmp/a.pdf"))
        assertEquals("../a.png", relativePreviewResource("/workspace/docs/index.html", "/workspace/a.png"))
        assertEquals("assets/a.png", relativePreviewResource("/workspace/docs/index.html", "/workspace/docs/assets/a.png"))
    }
}
