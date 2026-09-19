package com.fpink.capture.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EndpointValidationTest {
    @Test
    fun `only secure resource roots are accepted`() {
        assertTrue(isValidAzureEndpoint("https://journal.cognitiveservices.azure.com"))
        assertTrue(isValidAzureEndpoint("https://journal.cognitiveservices.azure.com/"))
        assertTrue(isValidAzureEndpoint("https://eastus.api.cognitive.microsoft.com"))
        assertFalse(isValidAzureEndpoint("https://journal.openai.azure.com"))
        assertFalse(isValidAzureEndpoint("https://example.com"))
        assertFalse(isValidAzureEndpoint("http://journal.cognitiveservices.azure.com"))
        assertFalse(isValidAzureEndpoint("https://user:password@journal.cognitiveservices.azure.com"))
        assertFalse(isValidAzureEndpoint("https://journal.cognitiveservices.azure.com/analyze"))
        assertFalse(isValidAzureEndpoint("https://journal.cognitiveservices.azure.com?apiKey=secret"))
        assertFalse(isValidAzureEndpoint("https://journal.cognitiveservices.azure.com#fragment"))
        assertFalse(isValidAzureEndpoint("file:///some/image"))
        assertFalse(isValidAzureEndpoint("https://journal.cognitiveservices.azure.com:1234"))
    }
}
