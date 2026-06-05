package com.ricedotwho.dtmap.gui

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Tab(val tabName: String)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Setting(
    val name: String,
    val dontRenderName: Boolean = false,
    val renderNameLeft: Boolean = false,
    val lineAfter: Boolean = false,
    val lineBefore: Boolean = false,
    val sameLineAfter: Boolean = false,
    val sameLineBefore: Boolean = false,
    val max: Double = 0.0,
    val min: Double = 0.0,
    val combo: Array<String> = []
)