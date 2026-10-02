package com.fpink.recognition.models

import com.fpink.recognition.runtime.BundledAsset

/** Pinned packaged files; must match artifacts.lock.json (verified at build time). */
internal object ModelAssets {
    val PP_OCRV6_SMALL_DET = BundledAsset(
        path = "recognition/ppocrv6/PP-OCRv6_small_det.onnx",
        bytes = 9_880_512L,
        sha256 = "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",
    )
    val PP_OCRV6_MEDIUM_REC = BundledAsset(
        path = "recognition/ppocrv6/PP-OCRv6_medium_rec.onnx",
        bytes = 76_554_979L,
        sha256 = "9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba",
    )
    val PP_OCRV6_MEDIUM_REC_DICT = BundledAsset(
        path = "recognition/ppocrv6/PP-OCRv6_medium_rec_dict.txt",
        bytes = 74_947L,
        sha256 = "b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d",
    )
    val KRAKEN_REC = BundledAsset(
        path = "recognition/kraken/ppocrv6-medium-recognition.onnx",
        bytes = 64_204_317L,
        sha256 = "12cfdbffa5e7243519120f177f26c716b924d9865e201b0fa459a0163979c95e",
    )
    val KRAKEN_ALPHABET = BundledAsset(
        path = "recognition/kraken/ppocrv6-medium-alphabet.txt",
        bytes = 5_615L,
        sha256 = "0b7c71199be609f1ceedb20d0e2bc136ad8f2a0beee4bb80e6903b444b0560f9",
    )
}

/** One label per line, ordered by class id; the trailing newline does not add a label. */
internal fun labels(text: String): List<String> = text.split('\n').let { if (it.lastOrNull() == "") it.dropLast(1) else it }