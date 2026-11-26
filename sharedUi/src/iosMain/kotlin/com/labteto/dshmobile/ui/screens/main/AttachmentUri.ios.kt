package com.labteto.dshmobile.ui.screens.main

/**
 * No file picker on iOS yet, so nothing builds one. When attachments come, this becomes the file URL a
 * `UIDocumentPickerViewController` hands over.
 */
actual abstract class AttachmentUri {
    actual abstract override fun toString(): String
}
