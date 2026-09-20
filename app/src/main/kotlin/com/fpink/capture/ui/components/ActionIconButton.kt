package com.fpink.capture.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionTooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        content = content,
    )
}

@Composable
fun ActionIconButton(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val description = stringResource(label)
    // Keep placement on the outer box so callers can align camera controls.
    androidx.compose.foundation.layout.Box(modifier) {
        ActionTooltip(description) {
            val content: @Composable () -> Unit = { Icon(painterResource(icon), contentDescription = description) }
            if (filled) {
                FilledIconButton(onClick, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), enabled, content = content)
            } else {
                IconButton(onClick, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), enabled, content = content)
            }
        }
    }
}

@Composable
fun ActionFloatingButton(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
    val description = stringResource(label)
    ActionTooltip(description) {
        FloatingActionButton(onClick = onClick) {
            Icon(painterResource(icon), contentDescription = description)
        }
    }
}
