package com.pinekone.app

import android.app.Application
import com.pinekone.app.data.ContactRepository
import com.pinekone.app.data.GovernanceRepository
import com.pinekone.app.data.MessageRepository
import com.pinekone.app.data.PublicChatRepository
import com.pinekone.app.data.RoutingTelemetryRepository
import com.pinekone.app.data.SettingsRepository
import com.pinekone.app.data.AttachmentRepository
import com.pinekone.app.data.db.PkDatabase
import com.pinekone.app.engine.CapGovernor
import com.pinekone.app.engine.DeviceCaps
import com.pinekone.app.engine.PkEngine
import com.pinekone.app.engine.RelationalPathScorer
import com.pinekone.app.engine.SystemDeviceStatusProvider
import com.pinekone.app.identity.IdentityRepository
import com.pinekone.app.store.PkMessageStore
import com.pinekone.app.auth.AuthRepository
import com.pinekone.app.transport.BleGattTransport
import com.pinekone.app.transport.HttpWebMailbox
import com.pinekone.app.transport.LanMulticastTransport
import com.pinekone.app.transport.NoopWebMailbox
import com.pinekone.app.transport.NearbyTransport
import com.pinekone.app.transport.TransportMux
import com.pinekone.app.transport.WifiAwareTransport
import com.pinekone.app.transport.WifiP2pTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PineKoneApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var engine: PkEngine
        private set
    lateinit var contactRepository: ContactRepository
        private set
    lateinit var messageRepository: MessageRepository
        private set
    lateinit var publicChatRepository: PublicChatRepository
        private set
    lateinit var routingTelemetryRepository: RoutingTelemetryRepository
        private set
    lateinit var governanceRepository: GovernanceRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var attachmentRepository: AttachmentRepository
        private set
    lateinit var authRepository: AuthRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val identityRepository = IdentityRepository(this, appScope)
        authRepository = AuthRepository(this, appScope)
        val radioTransports = listOf(
            BleGattTransport(this, appScope, identityRepository),
            WifiP2pTransport(this, appScope, identityRepository),
            WifiAwareTransport(this, appScope, identityRepository),
            LanMulticastTransport(this, appScope, identityRepository),
            NearbyTransport(this, appScope, identityRepository)
        )
        val meshTransport = TransportMux(appScope, radioTransports)
        val mailboxUrl = getString(R.string.web_mailbox_base_url)
        val webTransport = if (mailboxUrl.isNullOrBlank()) {
            NoopWebMailbox()
        } else {
            HttpWebMailbox(mailboxUrl)
        }
        val messageStore = PkMessageStore(appScope)
        val database = PkDatabase.build(this)
        contactRepository = ContactRepository(database.contactDao())
        messageRepository = MessageRepository(database.messageDao(), database.contactDao())
        publicChatRepository = PublicChatRepository(database.publicMessageDao())
        routingTelemetryRepository = RoutingTelemetryRepository(database.routingTelemetryDao())
        governanceRepository = GovernanceRepository(database.governanceDao())
        settingsRepository = SettingsRepository(this, appScope)
        attachmentRepository = AttachmentRepository(this)
        val capGovernor = CapGovernor(
            DeviceCaps(
                maxFanoutDevice = 2,
                transmitBudget = 1.0,
                minBatteryPct = 15
            )
        )
        val statusProvider = SystemDeviceStatusProvider(this)
        val pathScorer = RelationalPathScorer(governanceRepository)

        engine = PkEngine(
            scope = appScope,
            identityRepository = identityRepository,
            meshTransport = meshTransport,
            webTransport = webTransport,
            messageStore = messageStore,
            contactRepository = contactRepository,
            messageRepository = messageRepository,
            publicChatRepository = publicChatRepository,
            routingTelemetryRepository = routingTelemetryRepository,
            governanceRepository = governanceRepository,
            attachmentRepository = attachmentRepository,
            pathScorer = pathScorer,
            capGovernor = capGovernor,
            statusProvider = statusProvider
        )

        engine.start()
    }
}
