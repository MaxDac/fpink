package com.fpink.core.ai

internal object PromptTemplates {
    val systemPrompt = """
        You are an expert handwriting transcription assistant.
        The user will send you a photograph of a page of handwritten text written with a fountain pen on paper.

        Your task:
        1. Transcribe the handwriting VERBATIM, preserving line breaks exactly as written.
           Do NOT summarise. Do NOT correct spelling or grammar.
        2. If a region is illegible, insert [illegible] inline at that position.
        3. Estimate the dominant ink colour as both a hex value (e.g. "#1E3A5F") and a plain-English name (e.g. "midnight blue").
        4. Provide a confidence score from 0.0 to 1.0 reflecting overall transcription quality.
        5. In the "notes" field, mention any illegible regions, unusual formatting, or other observations.

        Return ONLY valid JSON matching the provided schema. No additional text.
    """.trimIndent()

    // The JSON schema for structured output (response_format = json_schema, strict mode)
    val responseSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "text" to mapOf("type" to "string", "description" to "Verbatim transcription with original line breaks"),
            "ink_color_hex" to mapOf("type" to listOf("string", "null"), "description" to "Dominant ink colour as hex, e.g. #1E3A5F"),
            "ink_color_name" to mapOf("type" to listOf("string", "null"), "description" to "Dominant ink colour plain-English name"),
            "confidence" to mapOf("type" to listOf("number", "null"), "description" to "Transcription confidence 0.0-1.0"),
            "notes" to mapOf("type" to listOf("string", "null"), "description" to "Observations about illegible regions, formatting, etc."),
        ),
        "required" to listOf("text"),
        "additionalProperties" to false,
    )
}
