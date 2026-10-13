package com.westly.neribovault.feature.coder.files

/** Hard limits that keep a Coder project small enough for a phone. */
object CoderLimits {
    const val MAX_EDITABLE_BYTES = 300_000L
    const val MAX_FILES_PER_PROJECT = 2_000
    const val MAX_PROJECT_BYTES = 50L * 1024 * 1024
    const val MAX_PATH_LENGTH = 200
    const val MAX_SEGMENT_LENGTH = 100
    const val MAX_FOLDER_DEPTH = 12
    const val MAX_PROJECT_NAME_LENGTH = 60
}
