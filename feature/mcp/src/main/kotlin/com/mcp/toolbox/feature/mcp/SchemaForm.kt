package com.mcp.toolbox.feature.mcp

import org.json.JSONArray
import org.json.JSONObject

enum class FieldKind(val label: String) {
    STRING("文本"),
    TEXT("多行文本"),
    NUMBER("数值"),
    SLIDER("滑杆"),
    BOOLEAN("开关"),
    ENUM("枚举"),
    ARRAY("数组"),
    OBJECT("对象"),
}

/** 从 JSON Schema 推导出的一个表单字段。 */
data class SchemaField(
    val name: String,
    val kind: FieldKind,
    val title: String,
    val description: String,
    val required: Boolean,
    val defaultText: String,
    val enumValues: List<String> = emptyList(),
    val minimum: Double? = null,
    val maximum: Double? = null,
    val format: String? = null,
)

/**
 * JSON Schema -> 表单模型 / 校验 / 参数构造（提示词 5.8 重点）。
 * 覆盖 string(format/enum)、number/integer(min/max -> 滑杆)、boolean、enum、array、object。
 */
object SchemaForm {

    fun parse(schemaJson: String): List<SchemaField> {
        val schema = runCatching { JSONObject(schemaJson) }.getOrNull() ?: return emptyList()
        val props = schema.optJSONObject("properties") ?: return emptyList()
        val required = schema.optJSONArray("required")?.let { array ->
            (0 until array.length()).map { array.optString(it) }.toSet()
        } ?: emptySet()
        return props.keys().asSequence().mapNotNull { name ->
            props.optJSONObject(name)?.let { field(name, it, name in required) }
        }.toList()
    }

    private fun field(name: String, spec: JSONObject, required: Boolean): SchemaField {
        val type = spec.optString("type", "string")
        val enumValues = spec.optJSONArray("enum")?.let { array ->
            (0 until array.length()).map { array.optString(it) }
        }.orEmpty()
        val min = if (spec.has("minimum")) spec.optDouble("minimum") else null
        val max = if (spec.has("maximum")) spec.optDouble("maximum") else null
        val format = spec.optString("format").ifBlank { null }
        val kind = when {
            enumValues.isNotEmpty() -> FieldKind.ENUM
            type == "boolean" -> FieldKind.BOOLEAN
            (type == "integer" || type == "number") && min != null && max != null -> FieldKind.SLIDER
            type == "integer" || type == "number" -> FieldKind.NUMBER
            type == "array" -> FieldKind.ARRAY
            type == "object" -> FieldKind.OBJECT
            format == "uri" -> FieldKind.TEXT
            else -> FieldKind.STRING
        }
        val default = when {
            spec.has("default") -> spec.opt("default")?.toString().orEmpty()
            kind == FieldKind.BOOLEAN -> "false"
            enumValues.isNotEmpty() -> enumValues.first()
            else -> ""
        }
        return SchemaField(
            name = name,
            kind = kind,
            title = spec.optString("title").ifBlank { name },
            description = spec.optString("description"),
            required = required,
            defaultText = if (default == "null") "" else default,
            enumValues = enumValues,
            minimum = min,
            maximum = max,
            format = format,
        )
    }

    fun initialValues(fields: List<SchemaField>): Map<String, String> =
        fields.associate { it.name to it.defaultText }

    /** 就地校验；返回 字段名 -> 错误文案。 */
    fun validate(fields: List<SchemaField>, values: Map<String, String>): Map<String, String> {
        val errors = mutableMapOf<String, String>()
        fields.forEach { field ->
            val raw = values[field.name].orEmpty().trim()
            if (field.required && raw.isEmpty()) {
                errors[field.name] = "必填"
                return@forEach
            }
            if (raw.isEmpty()) return@forEach
            when (field.kind) {
                FieldKind.NUMBER, FieldKind.SLIDER -> {
                    val number = raw.toDoubleOrNull()
                    if (number == null) {
                        errors[field.name] = "需要数值"
                    } else {
                        field.minimum?.let { if (number < it) errors[field.name] = "不能小于 $it" }
                        field.maximum?.let { if (number > it) errors[field.name] = "不能大于 $it" }
                    }
                }
                FieldKind.ENUM -> if (field.enumValues.isNotEmpty() && raw !in field.enumValues) {
                    errors[field.name] = "取值必须是 ${field.enumValues.joinToString(" / ")}"
                }
                FieldKind.ARRAY -> if (runCatching { JSONArray(raw) }.isFailure) {
                    errors[field.name] = "需要合法 JSON 数组，例如 [\"a\",\"b\"]"
                }
                FieldKind.OBJECT -> if (runCatching { JSONObject(raw) }.isFailure) {
                    errors[field.name] = "需要合法 JSON 对象，例如 {\"k\":\"v\"}"
                }
                else -> Unit
            }
        }
        return errors
    }

    /** 用表单值构造 tools/call 的 arguments；类型按 schema 还原。 */
    fun buildArguments(fields: List<SchemaField>, values: Map<String, String>): JSONObject {
        val args = JSONObject()
        fields.forEach { field ->
            val raw = values[field.name].orEmpty()
            if (raw.isBlank()) return@forEach
            val text = raw.trim()
            when (field.kind) {
                FieldKind.BOOLEAN -> args.put(field.name, text.toBoolean())
                FieldKind.NUMBER, FieldKind.SLIDER ->
                    if (field.kind == FieldKind.SLIDER || text.toLongOrNull() == null) {
                        args.put(field.name, text.toDouble())
                    } else {
                        args.put(field.name, text.toLong())
                    }
                FieldKind.ARRAY -> args.put(field.name, runCatching { JSONArray(text) }.getOrDefault(JSONArray()))
                FieldKind.OBJECT -> args.put(field.name, runCatching { JSONObject(text) }.getOrDefault(JSONObject()))
                else -> args.put(field.name, raw)
            }
        }
        return args
    }
}
