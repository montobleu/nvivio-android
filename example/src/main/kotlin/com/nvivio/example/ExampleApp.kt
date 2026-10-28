package com.nvivio.example

import android.app.Application
import com.nvivio.Nvivio

class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Nvivio.debug = true
        // The local ingest on the computer that runs the emulator; leave the endpoint out for nviv.io.
        Nvivio.start(this, "NV-9NR1XFQ", endpoint = "http://10.0.2.2:8797/a")
    }
}
