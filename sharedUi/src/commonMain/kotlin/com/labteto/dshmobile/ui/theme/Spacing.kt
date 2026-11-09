package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing tokens for consistent layout rhythm throughout the app.
 * Use these tokens instead of hardcoded dp values to maintain visual consistency.
 */
object DsSpacing {
    /** 4dp - Minimal spacing between tightly related elements (e.g., icon and label) */
    val tiny = 4.dp
    
    /** 6dp - Extra small spacing */
    val xsmall = 6.dp
    
    /** 8dp - Small spacing for related items within a component */
    val small = 8.dp
    
    /** 8dp - Compact spacing for related items within a component (alias for small) */
    val compact = 8.dp
    
    /** 12dp - Medium spacing between component elements */
    val medium = 12.dp
    
    /** 12dp - Standard spacing between component elements (alias for medium) */
    val standard = 12.dp
    
    /** 16dp - Comfortable spacing for screen padding and section content */
    val comfortable = 16.dp
    
    /** 20dp - Large spacing between major UI sections */
    val large = 20.dp
    
    /** 24dp - Extra large spacing for distinct content blocks */
    val xlarge = 24.dp
    
    /** 32dp - Major section spacing for clear visual separation */
    val xxlarge = 32.dp
    
    /** 48dp - Minimum touch target size per Material Design guidelines */
    val touchTarget = 48.dp
}
