package com.mibandhub.app.relay

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mibandhub.app.relay.model.RelayConfig
import com.mibandhub.app.relay.model.RelayDevice
import com.mibandhub.app.relay.model.RelayLogLevel
import com.mibandhub.app.relay.model.RelayLogEntry
import com.mibandhub.app.relay.model.RelayMode
import com.mibandhub.app.relay.model.RelaySyncState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 数据中转页面的 ViewModel：只是 [RelayRepository] 的一层薄封装，
 * 把仓库的状态流和动作透出来给 Compose 用。配置改动一律落盘（经仓库 → DataStore）。
 */
class RelayViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = RelayRepository.get(app)

    val state: StateFlow<RelaySyncState> = repo.state
    val devices: StateFlow<List<RelayDevice>> = repo.devices
    val config: StateFlow<RelayConfig> = repo.config
    val logs: StateFlow<List<RelayLogEntry>> = repo.logs
    val preview: StateFlow<com.mibandhub.app.relay.model.RelayBatch?> = repo.preview

    // 自动发现由 RelayRepository 在配置首次生效时统一触发：放在 init 抢跑会
    // 用默认 MOCK 配置发现出假节点（DataStore Flow 是异步到达的）。
    fun refresh() = viewModelScope.launch { repo.discover() }
    fun syncNow() = repo.syncNow()

    fun setEnabled(on: Boolean) = viewModelScope.launch { repo.updateConfig { it.copy(enabled = on) } }
    fun setAutoSync(on: Boolean) = viewModelScope.launch { repo.updateConfig { it.copy(autoSync = on) } }
    fun setMode(mode: RelayMode) = viewModelScope.launch { repo.updateConfig { it.copy(mode = mode) } }
    fun setInterval(minutes: Int) = viewModelScope.launch { repo.updateConfig { it.copy(intervalMinutes = minutes) } }
    fun setServerUrl(url: String) = viewModelScope.launch { repo.updateConfig { it.copy(serverUrl = url) } }
    fun setAlias(alias: String) = viewModelScope.launch { repo.updateConfig { it.copy(sourceAlias = alias) } }
    fun selectDevice(id: String?) = viewModelScope.launch { repo.updateConfig { it.copy(selectedDeviceId = id) } }
    fun clearLogs() = repo.clearLogs()
}
