// SPDX-License-Identifier: MIT
package dev.morpbox.app

import android.app.Application
import android.os.Build
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.config.TinkConfig
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class MorpApplication : Application() {
    lateinit var graph: AppGraph
        private set

    val lowRam: Boolean by lazy {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        am.isLowRamDevice || Build.VERSION.SDK_INT < 28
    }

    override fun onCreate() {
        super.onCreate()
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        runCatching { TinkConfig.register() }
        runCatching { AeadConfig.register() }
        graph = AppGraph(this)
        graph.session.start()
    }
}

class AppGraph(app: MorpApplication) {
    val identity = dev.morpbox.app.crypto.IdentityStore(app)
    val store = dev.morpbox.app.data.LocalStore(app)
    val session = MorpSession(app, identity, store)
}
