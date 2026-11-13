package org.mcfso.irix

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import org.mcfso.irix.data.FileTransfer
import org.mcfso.irix.data.NodeRepository
import org.mcfso.irix.data.NodeStore
import org.mcfso.irix.gnss.GnssCalibrationController

/** 应用级单例容器（轻量手写 DI，避免引入 Hilt）。 */
object AppContainer {
    lateinit var nodeStore: NodeStore
        private set
    lateinit var gnss: GnssCalibrationController
        private set
    val repository: NodeRepository = NodeRepository()
    val fileTransfer: FileTransfer = FileTransfer()

    fun init(store: NodeStore) { nodeStore = store }
    fun initGnss(context: android.content.Context) { gnss = GnssCalibrationController(context) }
}

private val android.content.Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "irix_prefs")

class IrixApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(NodeStore(dataStore))
        AppContainer.initGnss(this)
    }
}
