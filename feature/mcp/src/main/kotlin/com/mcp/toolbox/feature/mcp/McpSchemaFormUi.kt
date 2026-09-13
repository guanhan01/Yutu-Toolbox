package com.mcp.toolbox.feature.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mcp.toolbox.core.design.component.MiuixFilterChip
import com.mcp.toolbox.core.design.component.MiuixSegmentedButton
import com.mcp.toolbox.core.design.component.MiuixSlider
import com.mcp.toolbox.core.design.component.MiuixSwitch
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.theme.MiuixTheme

/**
 * Schema 驱动表单：按 JSON Schema 渲染控件并就地显示校验错误（提示词 5.8 重点）。
 * string -> 文本框、number/integer(有 min/max) -> 滑杆、boolean -> 开关、
 * enum -> 分段/胶囊、array/object -> JSON 文本域。
 */
@Composable
fun SchemaFields(
    fields: List<SchemaField>,
    values: Map<String, String>,
    errors: Map<String, String>,
    onChange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        if (fields.isEmpty()) {
            MiuixText(
                text = "该工具不需要参数，可直接调用。",
                style = MiuixTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            return@Column
        }
        fields.forEach { field ->
            SchemaFieldRow(field, values[field.name].orEmpty(), errors[field.name], onChange)
        }
    }
}

@Composable
private fun SchemaFieldRow(
    field: SchemaField,
    value: String,
    error: String?,
    onChange: (String, String) -> Unit,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiuixText(text = field.title, style = MiuixTheme.typography.labelLarge, color = colors.onSurface)
            if (field.required) {
                MiuixText(text = " *", style = MiuixTheme.typography.labelLarge, color = colors.error)
            }
            Spacer(Modifier.width(spacing.sm))
            MiuixText(
                text = field.kind.label,
                style = MiuixTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        if (field.description.isNotBlank()) {
            MiuixText(
                text = field.description,
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(spacing.xs))
        when (field.kind) {
            FieldKind.BOOLEAN -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiuixSwitch(checked = value.toBoolean(), onCheckedChange = { onChange(field.name, it.toString()) })
                Spacer(Modifier.width(spacing.md))
                MiuixText(
                    text = if (value.toBoolean()) "true" else "false",
                    style = MiuixTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            FieldKind.ENUM -> EnumPicker(field, value, onChange)
            FieldKind.SLIDER -> SlideField(field, value, onChange)
            else -> MiuixTextField(
                value = value,
                onValueChange = { onChange(field.name, it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = field.defaultText.ifBlank { field.name },
                isError = error != null,
                supportingText = error ?: hintOf(field),
                singleLine = field.kind == FieldKind.STRING,
                minLines = if (field.kind == FieldKind.STRING) 1 else 3,
            )
        }
    }
}

internal fun hintOf(field: SchemaField): String? = when (field.kind) {
    FieldKind.ARRAY -> "JSON 数组，例如 [\"a\",\"b\"]"
    FieldKind.OBJECT -> "JSON 对象，例如 {\"k\":\"v\"}"
    else -> null
}

/** 枚举字段：选项 <= 4 用分段按钮，更多用可换行胶囊。 */
@Composable
private fun EnumPicker(field: SchemaField, value: String, onChange: (String, String) -> Unit) {
    val spacing = MiuixTheme.dimens.spacing
    if (field.enumValues.size <= 4) {
        MiuixSegmentedButton(
            options = field.enumValues,
            selected = value.ifBlank { field.enumValues.first() },
            onSelect = { onChange(field.name, it) },
            label = { it },
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            field.enumValues.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    row.forEach { option ->
                        MiuixFilterChip(
                            label = option,
                            selected = option == value,
                            onClick = { onChange(field.name, option) },
                        )
                    }
                }
            }
        }
    }
}

/** 有 min/max 的数值字段用滑杆 + 数值气泡，步进按范围推导。 */
@Composable
private fun SlideField(field: SchemaField, value: String, onChange: (String, String) -> Unit) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val min = (field.minimum ?: 0.0).toFloat()
    val max = (field.maximum ?: 100.0).toFloat()
    val span = (max - min).coerceAtLeast(1f)
    val current = value.toFloatOrNull()?.coerceIn(min, max) ?: min
    val integer = field.kind == FieldKind.SLIDER && field.minimum != null &&
        field.minimum % 1.0 == 0.0 && field.maximum != null && field.maximum % 1.0 == 0.0
    Column {
        MiuixSlider(
            value = current,
            onValueChange = { raw ->
                val next = if (integer) raw.toInt().toString() else raw.toString()
                onChange(field.name, next)
            },
            valueRange = min..max,
            steps = if (integer && span <= 200f) span.toInt() - 1 else 0,
            showBubble = true,
            valueLabel = { if (integer) it.toInt().toString() else "%.2f".format(it) },
        )
        MiuixText(
            text = "$current（范围 $min ~ $max）",
            style = MiuixTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier,
        )
        Spacer(Modifier.height(spacing.xs))
    }
}
