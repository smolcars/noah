package com.noahwallet.services.wallet

import android.content.Context
import com.noahwallet.BuildConfig
import com.noahwallet.history.HistoryMapping
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.onchain.*
import com.noahwallet.send.*
import com.noahwallet.services.storage.WalletDataLocation
import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** One owner per process: bark locks the datadir. All wallet operations go through this session. */
class WalletSession internal constructor(
    private val variant: String,
    private val location: WalletDataLocation,
    private val inspect: (File) -> WalletDatabase.Properties = WalletDatabase::properties,
    private val isSuspended: () -> Boolean = { false },
    private val sendWarn: (String) -> Unit = {},
    private val opener: suspend (String, String, Boolean, String, Boolean) -> Handles = sdkOpener(variant, sendWarn),
) {
    enum class OpenFailure {
        walletDatabaseMissing, notABarkWallet, networkMismatch, busy, walletDatabaseExists, restorePending, teardownFailed, suspended, seedConflict,
    }
    class OpenError(val reason: OpenFailure, val found: String? = null) : Exception(reason.name)
    class SessionError : Exception("Wallet not open")

    data class Summary(val network: Network, val fingerprint: String, val balance: WalletBalance, val movementCount: Int) {
        val spendableSats: ULong get() = balance.ark.spendable
    }

    enum class DebugAction(val title: String, val needsInput: Boolean = false, val destructive: Boolean = false) {
        ark("Get Ark Info"), debugInfo("Get Debug Info"), refresh("Refresh Server"), maintenance("Maintenance"),
        maintenanceRefresh("Maintenance Refresh"), delegated("Maintenance Delegated"),
        offboard("Offboard All", true, true), decode("Decode VTXO Hex", true),
        importVtxo("Import VTXO", true), drop("Drop VTXO", true, true), unlock("Unlock VTXO", true),
    }

    internal interface Handles {
        val synchronization: WalletSync? get() = null
        val send: SendWallet get() = throw UnsupportedOperationException()
        suspend fun retrySendDiscards() {}
        suspend fun discardSendReviews() {}
        val m6: OnchainWalletAccess get() = throw UnsupportedOperationException()
        suspend fun arkInfo(): io.github.niteshbalusu11.bark.ArkInfo? = throw UnsupportedOperationException()
        fun mailboxAuthorization(expiry: Long): io.github.niteshbalusu11.bark.MailboxAuthorization = throw UnsupportedOperationException()
        suspend fun debug(action: DebugAction, input: String): String = throw UnsupportedOperationException()
        fun stateChanges(): Changes = throw UnsupportedOperationException()
        fun notifications(): Notifications
        suspend fun pendingRounds(): List<PendingRoundStatus> = throw UnsupportedOperationException()
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
                    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { // the collector rethrows what ended it
                        events.close(failure)
                    }
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
    }.flowOn(Dispatchers.IO)
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
    private val syncFlight = WalletSync()
    private val changed = MutableStateFlow(0L)
    val walletChanges = changed.asStateFlow()
    private fun walletChanged() { changed.value += 1 }
    internal suspend fun <T> withSendReservation(block: suspend (SendWallet) -> T): T {
        val caller = currentCoroutineContext()
        return withOpen { handles -> sendMutation.withLock {
            caller.checkWalletAdmission()
            block(handles.send)
        } }
    }
    // Block unrelated counted calls and close while unattended submission owns this wallet.
    private var exclusiveSend = false
    internal suspend fun <T> withRecurringReservation(block: suspend (SendWallet) -> T): T {
        val caller = currentCoroutineContext()
        caller.checkWalletAdmission()
        return withContext(NonCancellable + Dispatchers.IO) {
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
                caller.checkWalletAdmission()
                handles.retrySendDiscards()
                caller.checkWalletAdmission()
                block(handles.send)
            } finally {
                synchronized(lock) {
                    if (counted && --runningCalls == 0) drained.complete(Unit)
                    exclusiveSend = false
                }
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
    /** The amount a BOLT12 offer fixes, for Send's keypad; the quote checks it again. */
    suspend fun sendOfferAmount(offer: String): Long? = withOpen { (it.send as SdkSendWallet).offerAmount(offer) }
    /** Display only, for Send's method cards; the review's quote stays the authority. */
    suspend fun estimateSendFee(rail: com.noahwallet.send.SendRail, amount: Long, destination: String?): Long =
        withOpen { (it.send as SdkSendWallet).estimateFee(rail, amount, destination) }
    /** bark's own records, for Send to read a failed Lightning send's recorded refund. */
    suspend fun movements(): List<io.github.niteshbalusu11.bark.Movement> = withOpen { it.history() }
    /** The tip of the chain source the wallet already uses, for Send and Receive's block row. */
    suspend fun chainTip(): Long = withOpen { it.m6.tip() }
    /** A transaction's confirmation from that same chain source, for Activity's block row. */
    suspend fun chainStatus(txid: String): ChainStatus = withOpen { ChainStatus.from(it.m6.transactionStatus(txid)) }

    class Reservation internal constructor()
    private var runningCalls = 0
    private var drained = CompletableDeferred<Unit>().apply { complete(Unit) }
    // A UI caller's cancellation must not abandon a native open and leak its datadir lock.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var state: State = State.Closed
    // Bind a reused session to the exact credential that passed SDK fingerprint verification.
    private var openingSeedDigest: ByteArray? = null
    private fun seedDigest(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val isOpen: Boolean get() = state is State.Open
    @Volatile private var daemon = false
    /** This open while bark's daemon runs in it, progressing exits by itself (each open has its own value), or null. */
    val daemonRun: Long? get() = walletChanges.value.takeIf { isOpen && daemon }
    /** Open without bark's daemon: nothing progresses exits but Check the chain. */
    val openWithoutDaemon: Boolean get() = isOpen && !daemon

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

    suspend fun verify(reservation: Reservation, mnemonic: String, appVersion: String) {
        val caller = currentCoroutineContext()
        caller.checkWalletAdmission()
        return withContext(NonCancellable + Dispatchers.IO) {
            synchronized(lock) { checkHeld(reservation); checkExistingDatabase() }
            caller.checkWalletAdmission()
            val handles = opener(mnemonic, location.directory.path, false, appVersion, false)
            try { handles.stopDaemonWait(); handles.close() }
            catch (_: Throwable) {
                // Never allow rollback underneath native work when teardown failed.
                synchronized(lock) { state = State.Quarantined(handles) }
                throw OpenError(OpenFailure.teardownFailed)
            }
        }
    }

    suspend fun createNew(mnemonic: String, runDaemon: Boolean, appVersion: String) {
        currentCoroutineContext().checkWalletAdmission()
        val reservation = reserve()
        try { createNew(reservation, mnemonic, runDaemon, appVersion) }
        finally { release(reservation) }
    }

    suspend fun createNew(reservation: Reservation, mnemonic: String, runDaemon: Boolean, appVersion: String) {
        val caller = currentCoroutineContext()
        caller.checkWalletAdmission()
        return withContext(NonCancellable + Dispatchers.IO) {
            synchronized(lock) { prepareCreate(reservation, runDaemon) }
            caller.checkWalletAdmission()
            val handles = opener(mnemonic, location.directory.path, runDaemon, appVersion, true)
            synchronized(lock) { openingSeedDigest = seedDigest(mnemonic) }
            var initialized = false
            try {
                handles.initializeAddress()
                initialized = true
            } finally {
                if (!initialized) tearDownCreated(handles)
            }
            synchronized(lock) { state = State.Open(handles); walletChanged() }
        }
    }

    /** Called holding [lock]. */
    private fun prepareCreate(reservation: Reservation, runDaemon: Boolean) {
        checkHeld(reservation)
        if (location.restorePending) throw OpenError(OpenFailure.restorePending)
        if (location.databaseExists) throw OpenError(OpenFailure.walletDatabaseExists)
        check(location.directory.isDirectory || location.directory.mkdirs()) { """Cannot create wallet directory""" }
        daemon = runDaemon
    }

    /** A creation that failed after opening: handles that can't be torn down stay published. */
    private suspend fun tearDownCreated(handles: Handles) {
        try { handles.stopDaemonWait(); handles.close() }
        catch (_: Throwable) {
            synchronized(lock) { state = State.Open(handles); walletChanged() }
            throw OpenError(OpenFailure.teardownFailed)
        }
    }

    private suspend fun beginOpen(
        mnemonic: String, runDaemon: Boolean, appVersion: String, create: Boolean, join: Boolean,
    ): Deferred<Unit>? {
        val caller = currentCoroutineContext()
        caller.checkWalletAdmission()
        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                requireNotSuspended()
                when (val current = state) {
                    is State.Open -> return@synchronized joined(join, mnemonic, null)
                    is State.Opening -> return@synchronized joined(join, mnemonic, current.attempt)
                    State.Closed -> Unit
                    else -> throw OpenError(OpenFailure.busy)
                }
                checkOpenable()
                caller.checkWalletAdmission()
                val attempt = scope.async(start = CoroutineStart.LAZY) {
                    var opened = false
                    try {
                        caller.checkWalletAdmission()
                        val handles = opener(mnemonic, location.directory.path, runDaemon, appVersion, create)
                        synchronized(lock) { state = State.Open(handles); walletChanged() }
                        opened = true
                    } finally {
                        if (!opened) synchronized(lock) { state = State.Closed }
                    }
                }
                openingSeedDigest = seedDigest(mnemonic)
                daemon = runDaemon
                state = State.Opening(attempt)
                attempt.start()
                attempt
            }
        }
    }

    /** Called holding [lock]: joining needs the same seed as the session already opening or open. */
    private fun <T> joined(join: Boolean, mnemonic: String, attempt: T): T {
        if (!join) throw OpenError(OpenFailure.busy)
        if (openingSeedDigest?.contentEquals(seedDigest(mnemonic)) != true) throw OpenError(OpenFailure.seedConflict)
        return attempt
    }

    private fun requireNotSuspended() {
        if (isSuspended()) throw OpenError(OpenFailure.suspended)
    }

    private fun checkOpenable() {
        if (location.restorePending) throw OpenError(OpenFailure.restorePending)
        checkExistingDatabase()
    }

    private fun checkExistingDatabase() {
        if (!location.databaseExists) throw OpenError(OpenFailure.walletDatabaseMissing)
        val properties = inspectDatabase()
        if (properties.network != WalletConfiguration.base(variant).networkName) {
            throw OpenError(OpenFailure.networkMismatch, properties.network)
        }
    }

    private fun inspectDatabase() = try { inspect(location.database) } catch (_: Exception) {
        throw OpenError(OpenFailure.notABarkWallet)
    }

    suspend fun summary(): Summary? = if (isOpen) withOpen { it.summary() } else null
    suspend fun sync(includingLightningClaims: Boolean = false) {
        if (isOpen) withOpen { handles ->
            val flight = handles.synchronization ?: syncFlight
            if (includingLightningClaims) flight.run(handles::sync, handles::claimLightningReceives)
            else flight.run(handles::sync)
        }
    }

    suspend fun transactionHistory(): List<HistoryTransaction> = withOpen { handles ->
        // Without bark's exit records, fee children list as plain on-chain rows.
        val exitFees = runCatching { HistoryMapping.exitFees(handles.m6.allExits()) }.getOrDefault(emptyMap())
        handles.history().filter(HistoryMapping::include).map { HistoryMapping.transaction(it) } +
            handles.transactions().filter { it.balanceChangeSats != 0L }.map { HistoryMapping.transaction(it, exitFeeFor = exitFees[it.txid]) }
    }

    suspend fun peekAddress(index: UInt): String = withOpen { it.peekAddress(index) }

    suspend fun peekPublicKey(index: UInt): String = withOpen { it.peekPublicKey(index) }

    suspend fun maintenanceDelegated() = withOpen { it.maintenanceDelegated() }
    suspend fun bolt11Invoice(amountSats: ULong): String = withOpen { it.bolt11Invoice(amountSats) }
    suspend fun claimLightningReceives() = withOpen { it.claimLightningReceives() }
    suspend fun closestExpiryBlocks(): Long? = withOpen { it.closestExpiryBlocks() }

    internal suspend fun <T> withOpen(block: suspend (Handles) -> T): T {
        val caller = currentCoroutineContext()
        caller.checkWalletAdmission()
        return withContext(NonCancellable + Dispatchers.IO) {
            val handles = synchronized(lock) {
                requireNotSuspended()
                if (exclusiveSend) throw OpenError(OpenFailure.busy)
                val handles = (state as? State.Open)?.handles ?: throw SessionError()
                if (runningCalls++ == 0) drained = CompletableDeferred()
                handles
            }
            try {
                caller.checkWalletAdmission()
                handles.retrySendDiscards()
                caller.checkWalletAdmission()
                block(handles)
            } finally { synchronized(lock) { if (--runningCalls == 0) drained.complete(Unit) } }
        }
    }

    private suspend fun <T> withM6(block: suspend (Handles) -> T): T = withOpen {
        if (WalletBackgroundActivity.isRunning) throw OnchainException(OnchainFailure.unavailable)
        block(it)
    }
    private val onchainMutation = Mutex()
    val onchainJournal = OnchainOperationJournal(File(location.directory, "noah-onchain-attempt.json"))
    private fun onchainOperations(handles: Handles) = OnchainOperations(handles.m6, onchainJournal,
        ready = { !isSuspended() && !WalletBackgroundActivity.isRunning }, refused = com.noahwallet.onchain.ExitErrors.refused)
    suspend fun onchainOverview(): OnchainOverview = withM6 {
        onchainOperations(it).overview(WalletConfiguration.base(variant).vtxoRefreshExpiryThreshold.toUInt())
    }
    suspend fun exitDetail(id: String): ExitModel? = withM6 { onchainOperations(it).detail(id) }
    suspend fun vtxoDetail(id: String): com.noahwallet.onchain.VtxoModel = withM6 {
        onchainOperations(it).vtxo(id, WalletConfiguration.base(variant).vtxoRefreshExpiryThreshold.toUInt())
    }
    suspend fun exitDepositAddress(): String = withM6 { it.m6.depositAddress() }
    suspend fun exitClaimDestinationIsMine(address: String): Boolean = withM6 { it.m6.destinationIsMine(address) }
    suspend fun boardingMinimum(): Long? = withM6 { it.m6.minimum() }
    suspend fun quoteOnchain(action: OnchainAction, ids: List<String> = emptyList(), amount: Long = 0,
        destination: String? = null, automatic: Boolean = false): OnchainQuote = withM6 {
        onchainOperations(it).quote(action, ids, amount, destination, automatic)
    }
    /** [kept]: each wallet's coins claimed to this phone, which auto-board never moves. */
    suspend fun autoBoardFunding(kept: (String) -> Set<String> = { emptySet() }): AutoBoardFunding.Snapshot? =
        withM6 { onchainOperations(it).autoBoardFunding(kept) }
    suspend fun autoBoardQuote(kept: (String) -> Set<String> = { emptySet() }): OnchainQuote? = withM6 { onchainOperations(it).autoBoardQuote(kept) }
    /**
     * [renewal]: Home's Renew, whose VTXOs must all still be due when it's submitted, checked under these locks.
     * [keepOnchain]: where a claim to this wallet records its outputs, so auto-board leaves them on-chain.
     */
    @Suppress("LongParameterList") // optional hooks; each caller passes only the ones it records
    suspend fun executeOnchain(quote: OnchainQuote, funding: AutoBoardFunding.Snapshot? = null, renewal: Boolean = false,
        keepOnchain: (Set<String>) -> Unit = {}, kept: (String) -> Set<String> = { emptySet() },
        recordClaim: (com.noahwallet.onchain.ExitClaimRecords.Record) -> Unit = {},
        newRun: (List<String>) -> Unit = {}, shortOfFunds: (Map<String, Long>) -> Unit = {}): String = withM6 { handles ->
        val threshold = if (renewal) WalletConfiguration.base(variant).vtxoRefreshExpiryThreshold.toUInt() else null
        onchainMutation.withLock {
            onchainOperations(handles).execute(quote, funding, renewalThreshold = threshold, keepOnchain = keepOnchain, kept = kept,
                recordClaim = recordClaim, newRun = newRun, shortOfFunds = shortOfFunds)
        }
    }
    internal suspend fun exitCosts(ids: List<String>): Map<String, com.noahwallet.onchain.ExitCosts.Cost?> = withM6 { onchainOperations(it).exitCosts(ids) }
    internal suspend fun exitExcluded(): Set<String> = withM6 { onchainOperations(it).exitExcluded() }
    suspend fun abandonUnsubmittedAutoBoard(quote: OnchainQuote): Boolean = withM6 { handles ->
        onchainMutation.withLock { onchainOperations(handles).abandonUnsubmittedAutoBoard(quote) }
    }
    suspend fun checkOnchainAttempt(id: String): Boolean = withM6 { onchainOperations(it).check(id) }
    /**
     * Background jobs' exit progress ([OnchainOperations.progressPending]); not [withM6], which refuses while a background
     * job holds the wallet. Never throws but for cancellation: a failure's reason, safe to log, goes to [failed].
     */
    suspend fun progressPendingExits(failed: (String) -> Unit) {
        val caller = currentCoroutineContext()
        try {
            withOpen { handles ->
                onchainMutation.withLock { onchainOperations(handles).progressPending(ExitFunding.shared) { caller.checkWalletAdmission() } }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) { // documented: never throws but for cancellation
            failed(com.noahwallet.services.logging.SafeErrorDescription.of(error))
        }
    }
    suspend fun refreshOnchainReceiptFees() = withM6 { onchainOperations(it).refreshReceiptFees() }

    suspend fun pendingRounds() = withOpen { it.pendingRounds() }
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
    suspend fun receiveCompletion(request: ReceiveRequest): ReceiveCompletion? = withContext(Dispatchers.IO) {
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
        try { observer.job.await() }
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

    suspend fun closeSettled() = withContext(NonCancellable + Dispatchers.IO) {
        val opening = synchronized(lock) { (state as? State.Opening)?.attempt }
        try { opening?.await() } catch (_: Exception) { /* A failed open is already closed. */ }
        close()
    }

    suspend fun close() = close(false, null)

    internal suspend fun reserveForConfigurationChange(): Reservation {
        val reservation = Reservation()
        close(refuseRunning = true, reservation)
        return reservation
    }

    private suspend fun close(refuseRunning: Boolean, reservation: Reservation?) = withContext(NonCancellable + Dispatchers.IO) {
        val (handles, quarantined) = synchronized(lock) {
            checkClosable(refuseRunning)
            beginClose(reservation)
        } ?: return@withContext
        withContext(NonCancellable + Dispatchers.IO) {
            stopObservers()
            synchronized(lock) { drained }.await()
            finishClose(handles, quarantined, reservation)
        }
    }

    /** Called holding [lock]. */
    private fun checkClosable(refuseRunning: Boolean) {
        if (refuseRunning && (runningCalls != 0 || exclusiveSend)) {
            throw EsploraEndpoint.Failure("Wait for the current wallet operation to finish before changing the endpoint.")
        }
        if (exclusiveSend) throw OpenError(OpenFailure.busy)
    }

    /** Called holding [lock]: the handles to close and whether they were quarantined; null when already closed. */
    private fun beginClose(reservation: Reservation?): Pair<Handles, Boolean>? = when (val current = state) {
        State.Closed -> {
            if (reservation != null) state = State.Reserved(reservation)
            null
        }
        is State.Open -> current.handles.also { state = State.Closing } to false
        is State.Quarantined -> current.handles.also { state = State.Closing } to true
        else -> throw OpenError(OpenFailure.busy)
    }

    private suspend fun stopObservers() {
        val changes = synchronized(lock) { stateObservers.toList() }
        changes.forEach { stopStateObserver(it) }
        synchronized(lock) { stateObservers.removeAll(changes.toSet()) }
        val running = synchronized(lock) { observers.toList() }
        running.forEach { stopObserver(it) }
        synchronized(lock) { observers.removeAll(running.toSet()) }
    }

    /** A handle that fails to stop stays usable (or quarantined); one that fails to close is quarantined. */
    private suspend fun finishClose(handles: Handles, quarantined: Boolean, reservation: Reservation?) {
        var stopped = false
        try {
            handles.discardSendReviews()
            handles.stopDaemonWait()
            stopped = true
        } finally {
            if (!stopped) synchronized(lock) { state = if (quarantined || location.restorePending) State.Quarantined(handles) else State.Open(handles) }
        }
        var closed = false
        try {
            handles.close()
            closed = true
        } finally {
            if (!closed) synchronized(lock) { state = State.Quarantined(handles) }
        }
        synchronized(lock) { state = reservation?.let { State.Reserved(it) } ?: State.Closed; walletChanged() }
    }

    /** Reservation remains exclusive until the settings commit has completed. */
    internal suspend fun reopen(reservation: Reservation, mnemonic: String, appVersion: String, commit: () -> Unit) {
        synchronized(lock) { checkHeld(reservation); checkExistingDatabase() }
        val handles = opener(mnemonic, location.directory.path, true, appVersion, false)
        var committed = false
        try {
            commit()
            committed = true
        } finally {
            if (!committed) tearDownReopened(handles)
        }
        synchronized(lock) { openingSeedDigest = seedDigest(mnemonic); daemon = true; state = State.Open(handles); walletChanged() }
    }

    /** Handles that can't be torn down after a failed commit are quarantined. */
    private suspend fun tearDownReopened(handles: Handles) {
        try { handles.stopDaemonWait(); handles.close() }
        catch (_: Exception) { synchronized(lock) { state = State.Quarantined(handles) }; throw OpenError(OpenFailure.teardownFailed) }
    }

    companion object {
        @Volatile private var instance: WalletSession? = null

        fun shared(context: Context): WalletSession = instance ?: synchronized(this) {
            instance ?: WalletSession(BuildConfig.APP_VARIANT,
                WalletDataLocation(context.applicationContext.filesDir, BuildConfig.APP_VARIANT),
                sendWarn = com.noahwallet.services.logging.AppLog(context, "send")::w,
                opener = sdkOpener(BuildConfig.APP_VARIANT, com.noahwallet.services.logging.AppLog(context, "send")::w,
                    EsploraSettings(com.noahwallet.services.storage.SettingsStorage(context))),
                isSuspended = {
                    com.noahwallet.services.storage.SettingsStorage(context)
                        .persistedState<com.noahwallet.services.storage.PersistedWalletState>(com.noahwallet.services.storage.PersistedWalletState.key)
                        ?.isWalletSuspended == true
                })
                .also { instance = it }
        }

        internal fun sdkOpener(variant: String, warn: (String) -> Unit, settings: EsploraSettings? = null,
            openOnchain: suspend (Network, String, Config, String, Boolean) -> OnchainWallet = { network, mnemonic, config, datadir, create ->
                if (create) OnchainWallet.default(network, mnemonic, config, datadir)
                else OnchainWallet.openExisting(network, mnemonic, config, datadir)
            }): suspend (String, String, Boolean, String, Boolean) -> Handles =
            { inputMnemonic, datadir, runDaemon, appVersion, create ->
                val mnemonic = com.noahwallet.services.storage.StoredMnemonic.normalize(inputMnemonic)
                    ?: throw com.noahwallet.services.storage.SeedItem.Unavailable()
                val opening = currentCoroutineContext()[EsploraOpening]
                val endpoint = if (opening != null) opening.selection.endpoint else settings?.resolve(variant)
                settings?.protectLogPrivacy(endpoint)
                com.noahwallet.services.logging.EndpointLogPrivacy.register(endpoint)
                val configuration = WalletConfiguration.effective(variant, endpoint)
                val config = configuration.sdkConfig(WalletConfiguration.userAgent(variant, appVersion))
                // Existing wallets go through the SDK's read-only identity check (properties,
                // network, seed fingerprint) before anything writes; only creation may use default.
                val onchain = openOnchain(configuration.network, mnemonic, config, datadir, create)
                var opened = false
                val wallet = try {
                    Wallet.openWithOnchain(configuration.network, mnemonic, config,
                        WalletOpenArgs(runDaemon = runDaemon, datadir = datadir, createIfNotExists = create), onchain)
                        .also { opened = true }
                } finally {
                    if (!opened) onchain.close()
                }
                SdkHandles(wallet, onchain, variant, warn)
            }
    }

    private class SdkHandles(val wallet: Wallet, val onchain: OnchainWallet, variant: String, warn: (String) -> Unit) : Handles {
        override val synchronization = WalletSync()
        override val send = SdkSendWallet(wallet, onchain, variant, warn, synchronization)
        override suspend fun retrySendDiscards() { send.retryDiscards(); m6.retryDiscards() }
        override suspend fun discardSendReviews() { send.discardAll(); m6.discardAll() }
        override val m6 = BarkOnchainAccess(wallet, onchain, warn)
        override suspend fun arkInfo() = wallet.arkInfo()
        override fun mailboxAuthorization(expiry: Long) = wallet.mailboxAuthorizationAt(expiry)
        override suspend fun debug(action: DebugAction, input: String): String = when (action) {
            DebugAction.ark -> DebugJSON.ark(wallet.arkInfo())
            // Shown only here for support; the xpub must never reach logs or Sentry.
            DebugAction.debugInfo -> DebugJSON.debugInfo(wallet.debugInfo())
            DebugAction.refresh -> { wallet.refreshServer(); "Server refreshed" }
            DebugAction.maintenance -> { wallet.maintenance(); "Maintenance completed" }
            DebugAction.maintenanceRefresh -> wallet.maintenanceRefresh() ?: "Maintenance refresh completed"
            DebugAction.delegated -> { wallet.maintenanceDelegated(); "Delegated maintenance completed" }
            DebugAction.offboard -> wallet.offboardAll(input).toString()
            DebugAction.decode -> DebugJSON.decoded(io.github.niteshbalusu11.bark.decodeVtxo(input))
            DebugAction.importVtxo -> DebugJSON.imported(wallet.importVtxos(listOf(input)))
            DebugAction.drop -> { wallet.dangerousDropVtxo(input); "VTXO removed" }
            DebugAction.unlock -> { wallet.unlockVtxos(listOf(input), null); "VTXO unlocked" }
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
        override suspend fun pendingRounds() = wallet.syncPendingRounds()
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
        override suspend fun sync() { WalletSync.attemptAll({ onchain.sync() }, { wallet.sync() }) }
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
