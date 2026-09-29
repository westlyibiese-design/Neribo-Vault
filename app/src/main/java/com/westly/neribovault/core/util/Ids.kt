package com.westly.neribovault.core.util

import java.util.UUID

/** A new random id for any entity. */
fun newId(): String = UUID.randomUUID().toString()
