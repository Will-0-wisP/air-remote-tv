package com.example.fireairmouse

import android.content.Context
import java.security.SecureRandom

object Pin {
    fun get(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("fam", Context.MODE_PRIVATE)
        var p = prefs.getString("pin", null)
        if (p == null) { p = (1000 + SecureRandom().nextInt(9000)).toString(); prefs.edit().putString("pin", p).apply() }
        return p
    }
    fun set(ctx: Context, p: String) { ctx.getSharedPreferences("fam", Context.MODE_PRIVATE).edit().putString("pin", p).apply() }
    fun regenerate(ctx: Context): String {
        ctx.getSharedPreferences("fam", Context.MODE_PRIVATE).edit().remove("pin").apply(); return get(ctx)
    }
}
