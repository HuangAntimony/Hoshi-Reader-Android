package moe.antimony.hoshi.features.diagnostics

import kotlinx.serialization.Serializable

@Serializable
enum class DiagnosticsRecordKind { Crashes, RuntimeErrors, ProcessExits }
