package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.ContextBreakdownView
import com.labteto.dshmobile.core.wire.dto.ContextPressureView
import com.labteto.dshmobile.core.wire.dto.FULL_ACCESS_PRESET
import com.labteto.dshmobile.core.wire.dto.EncodedImageAttachment
import com.labteto.dshmobile.core.wire.dto.PermissionSelect
import com.labteto.dshmobile.core.wire.dto.displayPermissionPreset
import com.labteto.dshmobile.ui.components.ContextMeter
import com.labteto.dshmobile.ui.components.skeleton
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * A picked image waiting to be sent, held with its decoded preview.
 *
 * [bytes], [width] and [height] are the encoded size and intrinsic dimensions the picker already
 * had to learn to admit the image; keeping them means the *next* pick can be measured against the
 * message's running totals without decoding everything already attached a second time.
 */
internal data class PendingAttachment(
    val mediaType: String,
    val base64: String,
    val preview: ImageBitmap?,
    val bytes: Int,
    val width: Int,
    val height: Int,
    val name: String? = null,
) {
    /** The wire form `session.prompt` and `commands/execute` both carry. */
    fun encoded(): EncodedImageAttachment = EncodedImageAttachment(mediaType, base64, name)
}

/**
 * The message composer, laid out like the harness's own: the `+` and the permission chip on the
 * left, the send affordance on the right.
 *
 * The model selector is deliberately *not* here — it moved to the top bar, which leaves this row
 * for the two controls you change mid-conversation and keeps the composer from wrapping on a
 * narrow phone.
 */
@Composable
internal fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    attachments: List<PendingAttachment>,
    onRemoveAttachment: (Int) -> Unit,
    permissions: PermissionSelect?,
    pendingPermission: String?,
    onPermissionPick: (String) -> Unit,
    contextBreakdown: ContextBreakdownView?,
    contextPressure: ContextPressureView?,
    running: Boolean,
    enabled: Boolean,
    onOpenSheet: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val haptics = LocalHapticFeedback.current
    val canSend = enabled && (draft.isNotBlank() || attachments.isNotEmpty())
    val currentDraft by rememberUpdatedState(draft)
    val currentOnDraftChange by rememberUpdatedState(onDraftChange)
    val currentOnSend by rememberUpdatedState(onSend)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small)
            .animateContentSize(),
        shape = DsShapes.composer,
        color = colors.composerCard,
        border = BorderStroke(1.dp, colors.borderL1),
    ) {
        Column(
            Modifier.padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            TextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                placeholder = {
                    Text(
                        stringResource(R.string.chat_composer_hint),
                        style = DsType.std14,
                        color = colors.labelTertiary,
                    )
                },
                minLines = 1,
                maxLines = 8,
                textStyle = DsType.std14,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = colors.accent,
                    focusedTextColor = colors.labelPrimary,
                    unfocusedTextColor = colors.labelPrimary,
                ),
            )

            AnimatedVisibility(visible = attachments.isNotEmpty()) {
                AttachmentStrip(attachments, onRemoveAttachment)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.compact),
            ) {
                CircleAction(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.chat_composer_commands),
                    size = 30,
                    background = colors.hoverSolid,
                    tint = colors.labelPrimary,
                    enabled = enabled,
                    onClick = onOpenSheet,
                )

                PermissionChip(
                    select = permissions,
                    pending = pendingPermission,
                    enabled = enabled,
                    onPick = onPermissionPick,
                )

                Spacer(Modifier.weight(1f))

                ContextMeter(contextBreakdown, contextPressure)

                // Send and stop occupy the same slot: the affordance changes meaning during a turn
                // rather than the row re-flowing around a second button appearing.
                AnimatedContent(
                    targetState = running,
                    transitionSpec = {
                        (fadeIn(DsAnimations.fade) + scaleIn(initialScale = 0.85f))
                            .togetherWith(fadeOut(DsAnimations.fade) + scaleOut(targetScale = 0.85f))
                    },
                    label = "sendStop",
                ) { isRunning ->
                    if (isRunning) {
                        CircleAction(
                            icon = null,
                            contentDescription = stringResource(R.string.chat_composer_stop),
                            size = 36,
                            background = colors.error,
                            tint = Color.White,
                            enabled = true,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onStop()
                            },
                        ) {
                            Box(
                                Modifier
                                    .size(11.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color.White),
                            )
                        }
                    } else {
                        CircleAction(
                            icon = Icons.Filled.ArrowUpward,
                            contentDescription = stringResource(R.string.chat_composer_send),
                            size = 36,
                            background = if (canSend) colors.buttonInfoFill else colors.buttonPrimaryDimmed,
                            tint = if (canSend) Color.White else colors.labelTertiary,
                            enabled = canSend,
                            onClick = {
                                val text = currentDraft
                                currentOnDraftChange("")
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentOnSend(text)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The permission preset chip.
 *
 * Renders nothing when the projection key is absent: that means the harness composes no permission
 * service at all, and a dead control would be worse than none. Labels come from the wire, because
 * the preset table is deployment-configurable — mapping ids to local strings would mislabel any
 * deployment that renamed one.
 */
@Composable
private fun PermissionChip(
    select: PermissionSelect?,
    pending: String?,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    val colors = DsTheme.colors
    if (select == null) return
    var menuOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<String?>(null) }
    val effective = pending ?: select.currentValue
    val option = select.options.firstOrNull { it.value == effective }
    val label = if (option != null) {
        displayPermissionPreset(option.value, option.name)
    } else {
        stringResource(R.string.permission_custom)
    }

    Box {
        Row(
            modifier = Modifier
                .clip(DsShapes.cube)
                .clickable(enabled = enabled && pending == null) { menuOpen = true }
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .then(
                    if (pending != null) {
                        Modifier.skeleton(colors.bgLayer2, colors.hover, DsShapes.cube)
                    } else {
                        Modifier
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Outlined.Shield,
                contentDescription = stringResource(R.string.permission_preset),
                tint = if (effective == FULL_ACCESS_PRESET) colors.warnLabel else colors.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                label,
                style = DsType.small13,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier.size(12.dp),
            )
        }

        if (menuOpen) {
            PermissionMenu(
                select = select,
                current = effective,
                onDismiss = { menuOpen = false },
                onPick = { value ->
                    menuOpen = false
                    // Full access removes the approval prompt entirely, so it gets an explicit
                    // acknowledgement the way the desktop client does.
                    if (value == FULL_ACCESS_PRESET) confirming = value else onPick(value)
                },
            )
        }
    }

    confirming?.let { target ->
        FullAccessConfirmDialog(
            onDismiss = { confirming = null },
            onConfirm = {
                confirming = null
                onPick(target)
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Attachments
// ---------------------------------------------------------------------------

@Composable
private fun AttachmentStrip(attachments: List<PendingAttachment>, onRemove: (Int) -> Unit) {
    val colors = DsTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        attachments.forEachIndexed { index, attachment ->
            Box {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(DsShapes.block)
                        .background(colors.bgModulePlatform),
                ) {
                    attachment.preview?.let {
                        Image(
                            bitmap = it,
                            contentDescription = attachment.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(colors.toastBg)
                        .clickable { onRemove(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.chat_composer_remove_image),
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared circular affordance
// ---------------------------------------------------------------------------

@Composable
private fun CircleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    contentDescription: String,
    size: Int,
    background: Color,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size.dp),
        enabled = enabled,
        shape = CircleShape,
        color = background,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            when {
                content != null -> content()
                icon != null -> Icon(
                    icon,
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier.size((size * 0.46f).dp),
                )
            }
        }
    }
}
