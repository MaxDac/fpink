package com.fpink.capture.ui

import com.fpink.core.model.Note

internal fun applyTextEdit(note: Note, text: String): Note =
    note.copy(
        text = text,
        userEdited = note.userEdited || text != note.text,
    )
