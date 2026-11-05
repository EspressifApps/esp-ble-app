package com.espressif.espbleapp.settings

enum class AppLanguage(val storageTag: String) {
    System("system"),
    English("en"),
    Chinese("zh-CN"),
    ;

    companion object {
        fun fromStorageTag(tag: String?): AppLanguage =
            entries.find { it.storageTag == tag } ?: System
    }
}
