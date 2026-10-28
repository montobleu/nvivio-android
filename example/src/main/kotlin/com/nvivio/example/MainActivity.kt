package com.nvivio.example

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.nvivio.Nvivio

/** Three screens in one activity, each a screen view of its own. */
class MainActivity : Activity() {
    private var screen = HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.home).setOnClickListener { show(HOME) }
        findViewById<Button>(R.id.products).setOnClickListener { show(PRODUCTS) }
        findViewById<Button>(R.id.settings).setOnClickListener { show(SETTINGS) }
        findViewById<Button>(R.id.buy).setOnClickListener {
            Nvivio.event("Purchase", mapOf("plan" to "pro"))
            Toast.makeText(this, R.string.bought, Toast.LENGTH_SHORT).show()
        }

        // nvivio-example://products?utm_source=newsletter opens Products, with the campaign on its screen view.
        val link = intent.data
        if (link != null) Nvivio.open(link)
        // After a rotation the same screen again: the SDK sends no second screen view for it.
        show(savedInstanceState?.getString(SCREEN) ?: LINKS[link?.host] ?: HOME)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SCREEN, screen)
    }

    private fun show(name: String) {
        screen = name
        findViewById<TextView>(R.id.title).text = name
        findViewById<TextView>(R.id.text).text = getString(R.string.screen_text, name)
        Nvivio.screen(name)
    }

    private companion object {
        const val SCREEN = "screen"
        const val HOME = "Home"
        const val PRODUCTS = "Products"
        const val SETTINGS = "Settings"
        val LINKS = mapOf("home" to HOME, "products" to PRODUCTS, "settings" to SETTINGS)
    }
}
