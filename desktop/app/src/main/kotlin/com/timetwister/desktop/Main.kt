package com.timetwister.desktop

import java.awt.GraphicsEnvironment
import kotlin.system.exitProcess

fun main() {
    // Bail before touching the EDT. Scheduling onto the AWT event queue in a headless
    // JVM only defers the HeadlessException onto a thread whose stack trace nobody
    // sees, and leaves the process alive with nothing on screen. A non-zero exit and a
    // sentence on stderr is the honest outcome.
    if (GraphicsEnvironment.isHeadless()) {
        System.err.println(TrayApp.HEADLESS_MESSAGE)
        exitProcess(1)
    }

    val config = UserConfig()
    TrayApp.runOnEdt {
        TrayApp(config).start()
    }
}
