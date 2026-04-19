package com.konarsubhojit.synckro.domain.provider

/**
 * Thrown by provider stubs to signal that the requested operation hasn't been
 * implemented yet. Distinct from `kotlin.NotImplementedError` (an [Error])
 * because callers MAY catch it and treat it as a terminal sync result.
 */
class NotYetImplementedException(message: String) : UnsupportedOperationException(message)
