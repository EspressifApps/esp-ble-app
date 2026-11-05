package com.espressif.espbleapp.viewmodel

import android.content.Context
import androidx.annotation.StringRes
import com.espressif.espbleapp.R

data class UiMessage(
    @StringRes val resId: Int = 0,
    val formatArgs: Array<Any> = emptyArray(),
    val rawText: String? = null,
    val isError: Boolean = false,
) {
    fun text(context: Context): String =
        rawText ?: context.getString(resId, *formatArgs)

    companion object {
        fun res(@StringRes id: Int, vararg args: Any, isError: Boolean = false) =
            UiMessage(resId = id, formatArgs = arrayOf(*args), isError = isError)

        fun raw(text: String, isError: Boolean = false) =
            UiMessage(rawText = text, isError = isError)
    }
}
