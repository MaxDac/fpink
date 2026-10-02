package com.fpink.capture.data

import com.fpink.core.ai.RecognitionStrategyId

/** A recognition strategy the user can pick, as shown in Settings and on the camera screen. */
data class RecognitionOption(
    val id: RecognitionStrategyId,
    val label: String,
    val requiresNetwork: Boolean,
)