package com.timetwister.desktop

fun main() {
    val config = UserConfig()
    TrayApp.runOnEdt {
        TrayApp(config).start()
    }
}
