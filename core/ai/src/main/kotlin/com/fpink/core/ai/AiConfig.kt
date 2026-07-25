package com.fpink.core.ai

data class AiConfig(
    val endpoint: String,      // e.g. "https://my-resource.openai.azure.com"
    val deployment: String,    // e.g. "gpt-4o"
    val apiVersion: String,    // e.g. "2024-12-01-preview"
    val apiKey: String,
)
