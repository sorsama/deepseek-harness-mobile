package com.labteto.dshmobile.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shared screens draw Material icons from `DsIcons`, copies of the androidx paths written by
 * `tools/icons/generate.mjs` (Compose Multiplatform's icon artifact cannot sit on the Android classpath next
 * to androidx's). This holds each copy equal to the icon the Android app drew before the screen moved: the
 * same path commands with the same numbers, the same fill, size and mirroring. A wrong copy would draw a
 * different glyph, or a blank hole, only on the screens that moved.
 */
class DsIconsMatchMaterialTest {

    private val pairs: List<Triple<String, ImageVector, ImageVector>> = listOf(
        Triple("ChevronRight", DsIcons.ChevronRight, Icons.AutoMirrored.Filled.KeyboardArrowRight),
        Triple("ExpandMore", DsIcons.ExpandMore, Icons.Filled.ExpandMore),
        Triple("Settings", DsIcons.Settings, Icons.Filled.Settings),
        Triple("Warning", DsIcons.Warning, Icons.Filled.Warning),
        Triple("ArrowBack", DsIcons.ArrowBack, Icons.AutoMirrored.Filled.ArrowBack),
        Triple("Check", DsIcons.Check, Icons.Filled.Check),
        Triple("Close", DsIcons.Close, Icons.Filled.Close),
        Triple("Delete", DsIcons.Delete, Icons.Outlined.Delete),
        Triple("MoreVert", DsIcons.MoreVert, Icons.Outlined.MoreVert),
        Triple("QrCodeScanner", DsIcons.QrCodeScanner, Icons.Outlined.QrCodeScanner),
        Triple("Shield", DsIcons.Shield, Icons.Outlined.Shield),
        Triple("ContentPaste", DsIcons.ContentPaste, Icons.Outlined.ContentPaste),
        Triple("Add", DsIcons.Add, Icons.Filled.Add),
        Triple("AddOutlined", DsIcons.AddOutlined, Icons.Outlined.Add),
        Triple("ArrowUpward", DsIcons.ArrowUpward, Icons.Filled.ArrowUpward),
        Triple("KeyboardArrowDown", DsIcons.KeyboardArrowDown, Icons.Filled.KeyboardArrowDown),
        Triple("Archive", DsIcons.Archive, Icons.Outlined.Archive),
        Triple("CallSplit", DsIcons.CallSplit, Icons.AutoMirrored.Outlined.CallSplit),
        Triple("ContentCopy", DsIcons.ContentCopy, Icons.Outlined.ContentCopy),
        Triple("Edit", DsIcons.Edit, Icons.Outlined.Edit),
        Triple("PushPin", DsIcons.PushPin, Icons.Outlined.PushPin),
        Triple("Refresh", DsIcons.Refresh, Icons.Outlined.Refresh),
        Triple("Restore", DsIcons.Restore, Icons.Outlined.Restore),
        Triple("SettingsOutlined", DsIcons.SettingsOutlined, Icons.Outlined.Settings),
        Triple("SwapVert", DsIcons.SwapVert, Icons.Outlined.SwapVert),
        Triple("ContentCopyFilled", DsIcons.ContentCopyFilled, Icons.Filled.ContentCopy),
        Triple("KeyboardArrowUp", DsIcons.KeyboardArrowUp, Icons.Filled.KeyboardArrowUp),
        Triple("Search", DsIcons.Search, Icons.Filled.Search),
        Triple("TextFields", DsIcons.TextFields, Icons.Outlined.TextFields),
        Triple("ThumbUp", DsIcons.ThumbUp, Icons.Filled.ThumbUp),
        Triple("ThumbDown", DsIcons.ThumbDown, Icons.Filled.ThumbDown),
        Triple("Description", DsIcons.Description, Icons.Outlined.Description),
        Triple("Undo", DsIcons.Undo, Icons.AutoMirrored.Outlined.Undo),
        Triple("AlternateEmail", DsIcons.AlternateEmail, Icons.Outlined.AlternateEmail),
        Triple("Code", DsIcons.Code, Icons.Outlined.Code),
        Triple("FolderOpen", DsIcons.FolderOpen, Icons.Outlined.FolderOpen),
        Triple("Stop", DsIcons.Stop, Icons.Filled.Stop),
        Triple("ErrorOutline", DsIcons.ErrorOutline, Icons.Outlined.ErrorOutline),
        Triple("ArrowDownward", DsIcons.ArrowDownward, Icons.Filled.ArrowDownward),
    )

    @Test
    fun everySharedIconIsTheMaterialIconItCopies() {
        pairs.forEach { (name, copy, material) ->
            assertEquals("$name: name", material.name, copy.name)
            assertEquals("$name: width", material.defaultWidth, copy.defaultWidth)
            assertEquals("$name: height", material.defaultHeight, copy.defaultHeight)
            assertEquals("$name: viewport width", material.viewportWidth, copy.viewportWidth, 0f)
            assertEquals("$name: viewport height", material.viewportHeight, copy.viewportHeight, 0f)
            assertEquals("$name: mirroring", material.autoMirror, copy.autoMirror)
            assertEquals("$name: paths", paths(material.root), paths(copy.root))
        }
    }

    @Test
    fun everyIconInDsIconsIsChecked() {
        // A property added to DsIcons without a line above would go unchecked.
        val declared = DsIcons::class.java.declaredMethods
            .map { it.name }
            .filter { it.startsWith("get") && it.length > 3 }
            .map { it.removePrefix("get") }
            .toSet()
        assertEquals(declared, pairs.map { it.first }.toSet())
    }

    /** Every path in a vector, flattened: its commands, fill, alphas and fill type. */
    private fun paths(node: VectorNode): List<String> = when (node) {
        is VectorGroup -> node.flatMap { paths(it) }
        is VectorPath -> listOf(
            "${node.pathData} fill=${node.fill} fillAlpha=${node.fillAlpha} stroke=${node.stroke} " +
                "strokeAlpha=${node.strokeAlpha} strokeWidth=${node.strokeLineWidth} cap=${node.strokeLineCap} " +
                "join=${node.strokeLineJoin} miter=${node.strokeLineMiter} type=${node.pathFillType}",
        )
        else -> emptyList()
    }
}
