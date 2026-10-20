package com.noahwallet.services.wallet

import com.noahwallet.onchain.*
import android.content.Context
import com.noahwallet.history.HistoryMapping
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.BuildConfig
import com.noahwallet.services.storage.WalletDataLocation
import io.github.niteshbalusu11.bark.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.noahwallet.send.*

/** One owner per process: bark locks the datadir. All wallet operations go through this session. */
class WalletSession internal constructor(
    private val variant: String,
    private val location: WalletDataLocation,
    private val inspect: (File) -> WalletDatabase.Properties = WalletDatabase::properties,
    private val isSuspended: () -> Boolean = { false },
    private val sendWarn: (String) -> Unit = {},
    private val opener: suspend (String, String, Boolean, String, Boolean) -> Handles = sdkOpener(variant, sendWarn),
) {
    enum class OpenFailure { walletDatabaseMissing, notABarkWallet, networkMismatch, busy, walletDatabaseExists, restorePending, teardownFailed, suspended }
    class OpenError(val reason: OpenFailure, val found: String? = null) : Exception(reason.name)
    class SessionError : Exception("Wallet not open")

    data class Summary(val network: Network, val fingerprint: String, val balance: WalletBalance, val movementCount: Int) {
        val spendableSats: ULong get() = balance.ark.spendable
    }

    enum class DebugAction(val title: String, val needsInput: Boolean = false, val destructive: Boolean = false) {
        ark("Get Ark Info"), refresh("Refresh Server"), maintenance("Maintenance"),
        maintenanceRefresh("Maintenance Refresh"), delegated("Maintenance Delegated"),
        offboard("Offboard All", true, true), decode("Decode VTXO Hex", true),
        importVtxo("Import VTXO", true), drop("Drop VTXO", true, true), unlock("Unlock VTXO", true),
    }

    internal interface Handles {
        val send: SendWallet get() = throw UnsupportedOperationException()
        suspend fun retrySendDiscards() {}
        suspend fun discardSendReviews() {}
        val m6: OnchainWalletAccess get() = throw UnsupportedOperationException()
        suspend fun arkInfo(): io.github.niteshbalusu11.bark.ArkInfo? = throw UnsupportedOperationException()
        fun mailboxAuthorization(expiry: Long): io.github.niteshbalusu11.bark.MailboxAuthorization = throw UnsupportedOperationException()
        suspend fun debug(action: DebugAction, input: String): String = throw UnsupportedOperationException()
        fun stateChanges(): Changes = throw UnsupportedOperationException()
        fun notifications(): Notifications
        suspend fun history(): List<Movement>
        suspend fun transactions(): List<WalletTransaction> = throw UnsupportedOperationException()
        suspend fun receiveRequest(): ReceiveRequest
        suspend fun receiveInvoice(request: ReceiveRequest, amountSat: Long, description: String): ReceiveRequest
        suspend fun initializeAddress()
        suspend fun peekPublicKey(index: UInt): String
        suspend fun peekAddress(index: UInt): String
        suspend fun summary(): Summary
        suspend fun sync()
        suspend fun maintenanceDelegated()
        suspend fun bolt11Invoice(amountSats: ULong): String
        suspend fun claimLightningReceives()
        suspend fun closestExpiryBlocks(): Long?
        suspend fun createSnapshot(destination: File): WalletSnapshotInfo
        suspend fun stopDaemonWait()
        fun close()
    }

    internal interface Notifications : AutoCloseable {
        suspend fun next(): WalletNotification?
        fun cancelWait()
    }
    internal interface Changes : AutoCloseable {
        suspend fun next(): StateChangeEvent?
        fun stop()
    }
    private class StateObserver(val holder: Changes) {
        lateinit var job: Job
        var closed = false
    }
    private val stateObservers = mutableSetOf<StateObserver>()

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    fun stateChanges(): Flow<StateChangeEvent> = flow {
        val events = Channel<StateChangeEvent>(Channel.UNLIMITED)
        val observer = synchronized(lock) {
            val holder = (state as? State.Open)?.handles?.stateChanges() ?: throw SessionError()
            StateObserver(holder).also { observer ->
                observer.job = scope.launch(start = CoroutineStart.ATOMIC) {
                    try {
                        while (isActive) { events.send(holder.next() ?: break) }
                    } catch (failure: Exception) { events.close(failure) }
                    finally {
                        synchronized(holder) { holder.stop(); holder.close(); observer.closed = true }
                        events.close()
                    }
                }
                stateObservers.add(observer)
            }
        }
        try { for (event in events) emit(event) }
        finally {
            withContext(NonCancellable) { stopStateObserver(observer) }
            synchronized(lock) { stateObservers.remove(observer) }
            events.cancel()
        }
    }
    private suspend fun stopStateObserver(observer: StateObserver) {
        synchronized(observer.holder) {
            if (!observer.closed) { observer.holder.stop(); observer.job.cancel() }
        }
        observer.job.join()
    }

    private class Observer(val holder: Notifications) {
        lateinit var job: Deferred<ReceiveCompletion?>
        var holderClosed = false
    }
    private val observers = mutableSetOf<Observer>()

    private sealed interface State {
        data object Closed : State
        class Opening(val attempt: Deferred<Unit>) : State
        class Open(val handles: Handles) : State
        class Quarantined(val handles: Handles) : State
        class Reserved(val reservation: Reservation) : State
        data object Closing : State
    }

    private val lock = Any()
    private val sendMutation = Mutex()
    private val changed = MutableStateFlow(0L)
    val walletChanges = changed.asStateFlow()
    private fun walletChanged() { changed.value += 1 }
    internal suspend fun <T> withSendReservation(block: suspend (SendWallet) -> T): T =
        withOpen { handles -> sendMutation.withLock { block(handles.send) } }
    // Block unrelated counted calls and close while unattended submission owns this wallet.
    private var exclusiveSend = false
    internal suspend fun <T> withRecurringReservation(block: suspend (SendWallet) -> T): T = withContext(NonCancellable + Dispatchers.IO) {
        val handles = synchronized(lock) {
            if (exclusiveSend || isSuspended()) throw OpenError(OpenFailure.busy)
            val open = (state as? State.Open)?.handles ?: throw SessionError()
            exclusiveSend = true
            open
        }
        var counted = false
        try {
            synchronized(lock) { drained }.await()
            synchronized(lock) {
                check((state as? State.Open)?.handles === handles)
                if (runningCalls++ == 0) drained = CompletableDeferred()
                counted = true
            }
            handles.retrySendDiscards()
            block(handles.send)
        } finally {
            synchronized(lock) {
                if (counted && --runningCalls == 0) drained.complete(Unit)
                exclusiveSend = false
            }
        }
    }
    suspend fun quoteSend(payment: SendPayment): SendQuote {
        var result: SendQuote? = null
        try { return withSendReservation { it.quote(payment).also { quote -> result = quote } } }
        catch (failure: CancellationException) {
            // withContext can drop a successful result while returning to a canceled UI caller.
            withContext(NonCancellable) { result?.let { quote -> runCatching { discardSend(quote) } } }
            throw failure
        }
    }
    suspend fun discardSend(quote: SendQuote) = withSendReservation { it.discard(quote) }
    suspend fun resolveSendRecipient(destination: String): SendRecipientRoute = withOpen { handles ->
        val sdk = handles.send as? SdkSendWallet ?: throw SessionError()
        sdk.recipientService().resolve(destination)
    }
    suspend fun pendingSendHashes(): Set<String> = withOpen { (it.send as SdkSendWallet).pendingHashes() }

    class Reservation internal constructor()
    private var runningCalls = 0
    private var drained = CompletableDeferred<Unit>().apply { complete(Unit) }
    // A UI caller's cancellation must not abandon a native open and leak its datadir lock.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var state: State = State.Closed
    val isOpen: Boolean get() = synchronized(lock) { state is State.Open }

    suspend fun openExisting(mnemonic: String, runDaemon: Boolean, appVersion: String) {
        beginOpen(mnemonic, runDaemon, appVersion, create = false, join = false)?.await()
    }

    suspend fun ensureOpen(mnemonic: String, runDaemon: Boolean, appVersion: String) {
        beginOpen(mnemonic, runDaemon, appVersion, create = false, join = true)?.await()
    }

    fun reserve(): Reservation = synchronized(lock) {
        if (state != State.Closed) throw OpenError(OpenFailure.busy)
        Reservation().also { state = State.Reserved(it) }
    }

    fun release(reservation: Reservation) = synchronized(lock) {
        if ((state as? State.Reserved)?.reservation === reservation) state = State.Closed
    }

    fun unlessReserved(body: () -> Unit): Boolean = synchronized(lock) {
        if (state is State.Reserved) false else { body(); true }
    }

    private fun checkHeld(reservation: Reservation) {
        if ((state as? State.Reserved)?.reservation !== reservation) throw OpenError(OpenFailure.busy)
    }

    suspend fun verify(reservation: Reservation, mnemonic: String, appVersion: String) =
        withContext(NonCancellable + Dispatchers.IO) {
            synchronized(lock) { checkHeld(reservation); checkExistingDatabase() }
            val handles = opener(mnemonic, location.directory.path, false, appVersion, false)
            try { handles.stopDaemonWait(); handles.close() }
            catch (_: Throwable) {
                // Never allow rollback underneath native work when teardown failed.
                synchronized(lock) { state = State.Quarantined(handles) }
                throw OpenError(OpenFailure.teardownFailed)
            }
        }

    suspend fun createNew(mnemonic: String, runDaemon: Boolean, appVersion: String) {
        val reservation = reserve()
        try { createNew(reservation, mnemonic, runDaemon, appVersion) }
        finally { release(reservation) }
    }

    suspend fun createNew(reservation: Reservation, mnemonic: String, runDaemon: Boolean, appVersion: String) =
        withContext(NonCancellable + Dispatchers.IO) {
            synchronized(lock) {
                checkHeld(reservation)
                if (location.restorePending) throw OpenError(OpenFailure.restorePending)
                if (location.databaseExists) throw OpenError(OpenFailure.walletDatabaseExists)
                check(location.directory.isDirectory || location.directory.mkdirs()) { "Cannot create wallet directory" }
            }
            val handles = opener(mnemonic, location.directory.path, runDaemon, appVersion, true)
            try { handles.initializeAddress() }
            catch (failure: Throwable) {
                try { handles.stopDaemonWait(); handles.close() }
                catch (_: Throwable) {
                    synchronized(lock) { state = State.Open(handles); walletChanged() }
                    throw OpenError(OpenFailure.teardownFailed)
                }
                throw failure
            }
            synchronized(lock) { state = State.Open(handles); walletChanged() }
        }

    private suspend fun beginOpen(
        mnemonic: String, runDaemon: Boolean, appVersion: String, create: Boolean, join: Boolean,
    ): Deferred<Unit>? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (isSuspended()) throw OpenError(OpenFailure.suspended)
            when (val current = state) {
                is State.Open -> if (join) return@synchronized null else throw OpenError(OpenFailure.busy)
                is State.Opening -> if (join) return@synchronized current.attempt else throw OpenError(OpenFailure.busy)
                State.Closed -> Unit
                else -> throw OpenError(OpenFailure.busy)
            }
            if (location.restorePending) throw OpenError(OpenFailure.restorePending)
            checkExistingDatabase()
            val attempt = scope.async(start = CoroutineStart.LAZY) {
                try {
                    val handles = opener(mnemonic, location.directory.path, runDaemon, appVersion, create)
                    synchronized(lock) { state = State.Open(handles); walletChanged() }
                } catch (failure: Throwable) {
                    synchronized(lock) { state = State.Closed }
                    throw failure
                }
            }
            state = State.Opening(attempt)
            attempt.start()
            attempt
        }
    }

    private fun checkExistingDatabase() {
        if (!location.databaseExists) throw OpenError(OpenFailure.walletDatabaseMissing)
        val properties = try { inspect(location.database) } catch (_: Exception) {
            throw OpenError(OpenFailure.notABarkWallet)
        }
        if (properties.network != WalletConfiguration.base(variant).networkName) {
            throw OpenError(OpenFailure.networkMismatch, properties.network)
        }
    }

    suspend fun summary(): Summary? = if (isOpen) withOpen { it.summary() } else null
    suspend fun sync() { if (isOpen) withOpen { it.sync() } }

    suspend fun transactionHistory(): List<HistoryTransaction> = withOpen { handles ->
        handles.history().filter(HistoryMapping::include).map { HistoryMapping.transaction(it) } +
            handles.transactions().filter { it.balanceChangeSats != 0L }.map { HistoryMapping.transaction(it) }
    }

    suspend fun peekAddress(index: UInt): String = withOpen { it.peekAddress(index) }

    suspend fun peekPublicKey(index: UInt): String = withOpen { it.peekPublicKey(index) }

    suspend fun maintenanceDelegated() = withOpen { it.maintenanceDelegated() }
    suspend fun bolt11Invoice(amountSats: ULong): String = withOpen { it.bolt11Invoice(amountSats) }
    suspend fun claimLightningReceives() = withOpen { it.claimLightningReceives() }
    suspend fun closestExpiryBlocks(): Long? = withOpen { it.closestExpiryBlocks() }

    internal suspend fun <T> withOpen(block: suspend (Handles) -> T): T {
        val handles = synchronized(lock) {
            if (isSuspended()) throw OpenError(OpenFailure.suspended)
            if (exclusiveSend) throw OpenError(OpenFailure.busy)
            val handles = (state as? State.Open)?.handles ?: throw SessionError()
            if (runningCalls++ == 0) drained = CompletableDeferred()
            handles
        }
        try { return withContext(NonCancellable + Dispatchers.IO) { handles.retrySendDiscards(); block(handles) } }
        finally { synchronized(lock) { if (--runningCalls == 0) drained.complete(Unit) } }
    }

    private suspend fun <T> withM6(block: suspend (Handles) -> T): T = withOpen {
        if (WalletBackgroundActivity.isRunning) throw OnchainException(OnchainFailure.unavailable)
        block(it)
    }
    private val onchainMutation = Mutex()
    val onchainJournal = OnchainOperationJournal(File(location.directory, "noah-onchain-attempt.json"))
    private val exitHistory = ExitHistoryStore(File(location.directory, "noah-exit-history.json"))
    private fun onchainOperations(handles: Handles) = OnchainOperations(handles.m6, onchainJournal, exitHistory,
        ready = { !isSuspended() && !WalletBackgroundActivity.isRunning })
    suspend fun onchainOverview(): OnchainOverview = withM6 {
        onchainOperations(it).overview(WalletConfiguration.base(variant).vtxoRefreshExpiryThreshold.toUInt())
    }
    suspend fun exitDetail(id: String): ExitModel? = withM6 { onchainOperations(it).detail(id) }
    suspend fun exitDepositAddress(): String = withM6 { it.m6.depositAddress() }
    suspend fun exitClaimDestinationIsMine(address: String): Boolean = withM6 { it.m6.destinationIsMine(address) }
    suspend fun boardingMinimum(): Long? = withM6 { it.m6.minimum() }
    suspend fun quoteOnchain(action: OnchainAction, ids: List<String> = emptyList(), amount: Long = 0,
        destination: String? = null, automatic: Boolean = false): OnchainQuote = withM6 {
        onchainOperations(it).quote(action, ids, amount, destination, automatic)
    }
    suspend fun autoBoardQuote(): OnchainQuote? = withM6 { onchainOperations(it).autoBoardQuote() }
    suspend fun executeOnchain(quote: OnchainQuote): String = withM6 { handles ->
        onchainMutation.withLock { onchainOperations(handles).execute(quote) }
    }
    suspend fun checkOnchainAttempt(id: String): Boolean = withM6 { onchainOperations(it).check(id) }
    suspend fun refreshOnchainReceiptFees() = withM6 { onchainOperations(it).refreshReceiptFees() }

    suspend fun arkInfo() = withOpen { it.arkInfo() }
    suspend fun mailboxAuthorization(expiry: Long) = withOpen { it.mailboxAuthorization(expiry) }
    suspend fun debug(action: DebugAction, input: String) = withOpen { it.debug(action, input.trim()) }

    suspend fun createSnapshot(destination: File): WalletSnapshotInfo = withOpen { it.createSnapshot(destination) }

    suspend fun receiveRequest(): ReceiveRequest = withOpen { it.receiveRequest() }
    suspend fun receiveInvoice(request: ReceiveRequest, amountSat: Long, description: String): ReceiveRequest {
        val note = description.trim()
        ReceiveRequest.validate(amountSat, note)
        return withOpen { it.receiveInvoice(request, amountSat, note) }
    }

    /** Subscribe before history. This wait never owns the operations mutex. */
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    suspend fun receiveCompletion(request: ReceiveRequest): ReceiveCompletion? {
        val observer = synchronized(lock) {
            val handles = (state as? State.Open)?.handles ?: throw SessionError()
            val holder = handles.notifications()
            val observer = Observer(holder)
            // ATOMIC guarantees finally runs even if close wins before the IO dispatcher starts.
            observer.job = scope.async(start = CoroutineStart.ATOMIC) {
                try {
                    suspend fun reconcile() = withOpen { it.history() }.firstNotNullOfOrNull {
                        ReceiveCompletion.matching(it, request)
                    }
                    var completion = reconcile()
                    while (completion == null && isActive) {
                        // Unlike submitted wallet work, this read-only FFI future must remain cancellable:
                        // cancelWait is per-wait and can run before Rust installs its cancellation slot.
                        val event = holder.next() ?: break
                        completion = when (event) {
                            is WalletNotification.MovementCreated -> ReceiveCompletion.matching(event.movement, request)
                            is WalletNotification.MovementUpdated -> ReceiveCompletion.matching(event.movement, request)
                            WalletNotification.ChannelLagging -> reconcile()
                        }
                    }
                    completion
                } finally { synchronized(holder) { observer.holderClosed = true; holder.close() } }
            }
            observer.also { observers.add(it) }
        }
        try { return observer.job.await() }
        finally {
            withContext(NonCancellable) {
                stopObserver(observer)
                synchronized(lock) { observers.remove(observer) }
            }
        }
    }

    private suspend fun stopObserver(observer: Observer) {
        // Cancel the coroutine and release Rust's pending wait before disposing its wrapper.
        // The job closes the holder; synchronized prevents cancelWait racing that close.
        synchronized(observer.holder) {
            if (!observer.holderClosed) {
                observer.holder.cancelWait()
                observer.job.cancel()
            }
        }
        observer.job.join()
    }

    suspend fun closeSettled() = withContext(NonCancellable) {
        val opening = synchronized(lock) { (state as? State.Opening)?.attempt }
        try { opening?.await() } catch (_: Exception) { /* A failed open is already closed. */ }
        close()
    }

    suspend fun close() {
        val handles = synchronized(lock) {
            if (exclusiveSend) throw OpenError(OpenFailure.busy)
            when (val current = state) {
                State.Closed -> return
                is State.Open -> current.handles.also { state = State.Closing }
                is State.Quarantined -> current.handles.also { state = State.Closing }
                else -> throw OpenError(OpenFailure.busy)
            }
        }
        withContext(NonCancellable + Dispatchers.IO) {
            val changes = synchronized(lock) { stateObservers.toList() }
            changes.forEach { stopStateObserver(it) }
            synchronized(lock) { stateObservers.removeAll(changes.toSet()) }
            val running = synchronized(lock) { observers.toList() }
            running.forEach { stopObserver(it) }
            synchronized(lock) { observers.removeAll(running.toSet()) }
            synchronized(lock) { drained }.await()
            run {
                try {
                    handles.discardSendReviews()
                    handles.stopDaemonWait()
                } catch (failure: Throwable) {
                    synchronized(lock) { state = if (location.restorePending) State.Quarantined(handles) else State.Open(handles) }
                    throw failure
                }
                try { handles.close() }
                catch (failure: Throwable) {
                    synchronized(lock) { state = State.Quarantined(handles) }
                    throw failure
                }
                synchronized(lock) { state = State.Closed; walletChanged() }
            }
        }
    }

    companion object {
        @Volatile private var instance: WalletSession? = null

        fun shared(context: Context): WalletSession = instance ?: synchronized(this) {
            instance ?: WalletSession(BuildConfig.APP_VARIANT,
                WalletDataLocation(context.applicationContext.filesDir, BuildConfig.APP_VARIANT),
                sendWarn = com.noahwallet.services.logging.AppLog(context, "send")::w,
                isSuspended = {
                    com.noahwallet.services.storage.SettingsStorage(context).persistedState<com.noahwallet.services.storage.PersistedWalletState>(
                        com.noahwallet.services.storage.PersistedWalletState.key)?.isWalletSuspended == true
                })
                .also { instance = it }
        }

        private fun sdkOpener(variant: String, warn: (String) -> Unit): suspend (String, String, Boolean, String, Boolean) -> Handles =
            { mnemonic, datadir, runDaemon, appVersion, create ->
                val configuration = WalletConfiguration.base(variant)
                val config = configuration.sdkConfig(WalletConfiguration.userAgent(variant, appVersion))
                // Existing wallets go through the SDK's read-only identity check (properties,
                // network, seed fingerprint) before anything writes; only creation may use default.
                val onchain = if (create) {
                    OnchainWallet.default(configuration.network, mnemonic, config, datadir)
                } else {
                    OnchainWallet.openExisting(configuration.network, mnemonic, config, datadir)
                }
                val wallet = try {
                    Wallet.openWithOnchain(configuration.network, mnemonic, config,
                        WalletOpenArgs(runDaemon = runDaemon, datadir = datadir, createIfNotExists = create), onchain)
                } catch (failure: Throwable) {
                    onchain.close()
                    throw failure
                }
                SdkHandles(wallet, onchain, variant, warn)
            }
    }

    private class SdkHandles(val wallet: Wallet, val onchain: OnchainWallet, variant: String, warn: (String) -> Unit) : Handles {
        override val send = SdkSendWallet(wallet, onchain, variant, warn)
        override suspend fun retrySendDiscards() { send.retryDiscards() }
        override suspend fun discardSendReviews() { send.discardAll() }
        override val m6 = BarkOnchainAccess(wallet, onchain)
        override suspend fun arkInfo() = wallet.arkInfo()
        override fun mailboxAuthorization(expiry: Long) = wallet.mailboxAuthorizationAt(expiry)
        override suspend fun debug(action: DebugAction, input: String): String {
            return when (action) {
                DebugAction.ark -> wallet.arkInfo().toString()
                DebugAction.refresh -> { wallet.refreshServer(); "Server refreshed" }
                DebugAction.maintenance -> { wallet.maintenance(); "Maintenance completed" }
                DebugAction.maintenanceRefresh -> wallet.maintenanceRefresh() ?: "Maintenance refresh completed"
                DebugAction.delegated -> { wallet.maintenanceDelegated(); "Delegated maintenance completed" }
                DebugAction.offboard -> wallet.offboardAll(input).toString()
                DebugAction.decode -> io.github.niteshbalusu11.bark.decodeVtxo(input).toString()
                DebugAction.importVtxo -> wallet.importVtxos(listOf(input)).toString()
                DebugAction.drop -> { wallet.dangerousDropVtxo(input); "VTXO removed" }
                DebugAction.unlock -> { wallet.unlockVtxos(listOf(input), null); "VTXO unlocked" }
            }
        }
        override fun stateChanges(): Changes {
            val subscription = wallet.stateChanges()
            return object : Changes {
                override suspend fun next() = subscription.nextChange()
                override fun stop() = subscription.stop()
                override fun close() = subscription.close()
            }
        }
        override fun notifications(): Notifications {
            val holder = wallet.notifications()
            return object : Notifications {
                override suspend fun next() = holder.nextNotification()
                override fun cancelWait() = holder.cancelNextNotificationWait()
                override fun close() = holder.close()
            }
        }
        override suspend fun history() = wallet.history()
        override suspend fun transactions() = onchain.transactions()
        override suspend fun receiveRequest() = ReceiveRequest(wallet.newAddress(), onchain.newAddress())
        override suspend fun receiveInvoice(request: ReceiveRequest, amountSat: Long, description: String): ReceiveRequest {
            val invoice = wallet.bolt11Invoice(amountSat.toULong(), description.ifEmpty { null })
            return request.copy(amountSat = amountSat, description = description,
                lightningInvoice = invoice.invoice, paymentHash = invoice.paymentHash)
        }
        override suspend fun initializeAddress() { wallet.deriveStoreNextKeypair() }
        override suspend fun peekPublicKey(index: UInt) = wallet.peekPublicKey(index)
        override suspend fun peekAddress(index: UInt): String = wallet.peekAddress(index)
        override suspend fun summary(): Summary {
            val ark = wallet.balance()
            val chain = onchain.balance()
            return Summary(wallet.network(), wallet.fingerprint(), WalletBalance(
                WalletBalance.Ark(ark.spendableSats, ark.needsRefreshSats, ark.pendingLightningSendSats,
                    ark.claimableLightningReceiveSats, ark.pendingInRoundSats, ark.pendingExitSats,
                    ark.pendingBoardSats, ark.pendingArkoorSendSats, ark.pendingOffboardSats,
                    pending = ark.pendingSats, total = ark.totalSats),
                WalletBalance.Onchain(chain.confirmedSats, chain.pendingSats, chain.totalSats),
            ), wallet.history().size)
        }
        override suspend fun sync() { onchain.sync(); wallet.sync() }
        override suspend fun maintenanceDelegated() { wallet.maintenanceDelegated() }
        override suspend fun bolt11Invoice(amountSats: ULong) = wallet.bolt11Invoice(amountSats, null).invoice
        override suspend fun claimLightningReceives() { wallet.tryClaimAllLightningReceives(false) }
        override suspend fun closestExpiryBlocks(): Long? {
            val vtxos = wallet.vtxos()
            if (vtxos.isEmpty()) return null
            val tip = onchain.tipHeight().toLong()
            return vtxos.minOf { it.expiryHeight.toLong() - tip }
        }
        override suspend fun createSnapshot(destination: File) = wallet.createSnapshot(destination.path)
        override suspend fun stopDaemonWait() { wallet.stopDaemonWait() }
        override fun close() { try { wallet.close() } finally { onchain.close() } }
    }
}
