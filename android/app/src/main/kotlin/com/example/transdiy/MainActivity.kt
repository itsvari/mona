package com.deliacheminot.mona

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import com.deliacheminot.mona.wear.ForegroundWearBridge

class MainActivity : FlutterActivity() {
    private var wearBridge: ForegroundWearBridge? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        wearBridge = ForegroundWearBridge(this, flutterEngine.dartExecutor.binaryMessenger)
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        wearBridge?.close()
        wearBridge = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
