package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * The app's compact top bar (design `.top`, 52–56dp): an optional leading back/close action, a
 * 17sp title with ellipsis, and trailing actions that never push the bar past the screen edge.
 *
 * The Ink top bars keep the model/status pills on their own row on the chat surface; this primitive
 * is for the simpler screens (settings, pairing, workspace, plugins, archived) that only need a
 * title and one or two actions.
 */
@Composable
fun DsTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(DsSpacing.tiny))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = DsType.title17,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = DsType.small13,
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.let { Row(verticalAlignment = Alignment.CenterVertically, content = it) }
    }
}

/**
 * A tappable row with a leading icon, a title/subtitle column and optional trailing content.
 *
 * Rows are the app's largest family (settings, workspace tree, models, permissions, automations,
 * archived) and were each hand-rolled. One primitive keeps their height, padding, chevron and
 * haptics consistent; the 56dp minimum is the touch target, not a visual height to pad around.
 */
@Composable
fun DsRowItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(DsShapes.row)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null) {
                        haptics.tap()
                        onClick()
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = DsSpacing.small, vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = colors.labelSecondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(DsSpacing.medium))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                style = DsType.rowTitle,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = DsType.small13,
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.let { Row(verticalAlignment = Alignment.CenterVertically, content = it) }
        if (showChevron) {
            Spacer(Modifier.width(DsSpacing.tiny))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The Ink input: 48dp, radius 12, `bgLayer2` fill and a hairline that turns accent on focus. */
@Composable
fun DsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = DsTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = singleLine,
        placeholder = placeholder?.let {
            {
                Text(it, style = DsType.body15, color = colors.labelCaption)
            }
        },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = DsShapes.control,
        textStyle = DsType.body15.copy(color = colors.labelPrimary),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onDone = { onImeAction?.invoke() },
            onGo = { onImeAction?.invoke() },
            onSend = { onImeAction?.invoke() },
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.bgLayer2,
            unfocusedContainerColor = colors.bgLayer2,
            disabledContainerColor = colors.bgLayer1,
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.borderL2,
            disabledBorderColor = colors.borderL1,
            cursorColor = colors.accent,
            focusedTextColor = colors.labelPrimary,
            unfocusedTextColor = colors.labelPrimary,
            focusedPlaceholderColor = colors.labelCaption,
            unfocusedPlaceholderColor = colors.labelCaption,
        ),
    )
}

/**
 * A thin progress bar — determinate when [value] is 0..1, otherwise an indeterminate travelling
 * segment. Three screens previously drew three different progress indicators inline.
 */
@Composable
fun DsProgress(
    modifier: Modifier = Modifier,
    value: Float? = null,
) {
    val colors = DsTheme.colors
    if (value == null) {
        val fraction by animateFloatAsState(
            targetValue = 1f,
            animationSpec = DsAnimations.fade,
            label = "progress",
        )
        Box(modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(colors.borderL1)) {
            Box(
                Modifier
                    .fillMaxWidth(0.35f * fraction)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(colors.accent),
            )
        }
    } else {
        Box(modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(colors.borderL1)) {
            Box(
                Modifier
                    .fillMaxWidth(value.coerceIn(0f, 1f))
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(colors.accent),
            )
        }
    }
}

/** A 1dp hairline in the theme's card-divider tone. */
@Composable
fun DsDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(DsTheme.colors.borderL1),
    )
}
