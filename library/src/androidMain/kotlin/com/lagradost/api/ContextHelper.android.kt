package com.lagradost.api

import android.content.Context
import java.lang.ref.WeakReference

var ctx: WeakReference<Context>? = null

/**
 * Helper function for Android specific context. Not usable in JVM.
 * Do not use this unless absolutely necessary.
 */
actual fun getContext(): Any? {
    return ctx?.get()
}

actual fun setContext(context: WeakReference<Any>) {
    val contextRef = context.get()
    if (contextRef is Context) {
        ctx = WeakReference(contextRef)
    }
}