package com.adobe.clawdea.profiling.model

data class Frame(
    val className: String,
    val methodName: String,
    val lineNumber: Int,
    val isNative: Boolean,
) {
    val fqn: String get() = "$className.$methodName"
}
