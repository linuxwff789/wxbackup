package com.nous.wxhook.ui.module

import android.service.quicksettings.TileService
import android.content.Intent

class ModuleTileService : TileService() {
    override fun onClick() {
        val intent = Intent(this, ModuleActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
}