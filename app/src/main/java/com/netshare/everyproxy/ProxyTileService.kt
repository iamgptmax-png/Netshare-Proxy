package com.netshare.everyproxy

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class ProxyTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ProxyService::class.java)
        if (ProxyService.isRunning) {
            intent.action = ProxyService.ACTION_STOP
            startService(intent)
        } else {
            intent.action = ProxyService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        qsTile?.let {
            it.state = if (ProxyService.isRunning) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            it.updateTile()
        }
    }

    private fun updateTileState() {
        qsTile?.let {
            it.state = if (ProxyService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.label = if (ProxyService.isRunning) "Proxy ACTIF" else "NetShare Proxy"
            it.updateTile()
        }
    }
}
