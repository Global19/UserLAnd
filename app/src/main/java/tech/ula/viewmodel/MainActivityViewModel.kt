package tech.ula.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import tech.ula.R
import tech.ula.model.entities.App
import tech.ula.model.entities.Filesystem
import tech.ula.model.entities.ServiceLocation
import tech.ula.model.entities.ServiceType
import tech.ula.model.entities.Session
import tech.ula.model.repositories.DownloadMetadata
import tech.ula.model.state.AppDatabaseEntriesSynced
import tech.ula.model.state.AppHasServiceLocationSet
import tech.ula.model.state.AppHasServiceTypeSet
import tech.ula.model.state.AppRequiresServiceLocation
import tech.ula.model.state.AppRequiresServiceType
import tech.ula.model.state.AppScriptCopyFailed
import tech.ula.model.state.AppScriptCopySucceeded
import tech.ula.model.state.AppSelected
import tech.ula.model.state.AppsFilesystemHasCredentials
import tech.ula.model.state.AppsFilesystemRequiresCredentials
import tech.ula.model.state.AppsStartupEvent
import tech.ula.model.state.AppsStartupFsm
import tech.ula.model.state.AppsStartupState
import tech.ula.model.state.AssetDownloadComplete
import tech.ula.model.state.AssetListsRetrievalFailed
import tech.ula.model.state.AssetListsRetrievalSucceeded
import tech.ula.model.state.AssetRetrievalState
import tech.ula.model.state.AssetVerificationState
import tech.ula.model.state.AssetsAreMissingFromSupportDirectories
import tech.ula.model.state.AttemptedCacheAccessWhileEmpty
import tech.ula.model.state.CheckAppSessionServiceType
import tech.ula.model.state.CheckAppsFilesystemCredentials
import tech.ula.model.state.CopyAppScriptToFilesystem
import tech.ula.model.state.CopyDownloadsToLocalStorage
import tech.ula.model.state.CopyingAppScript
import tech.ula.model.state.CopyingFilesLocallyState
import tech.ula.model.state.CopyingFilesToLocalDirectories
import tech.ula.model.state.DatabaseEntriesFetchFailed
import tech.ula.model.state.DatabaseEntriesFetched
import tech.ula.model.state.DownloadAssets
import tech.ula.model.state.DownloadRequirementsGenerationState
import tech.ula.model.state.DownloadingAssets
import tech.ula.model.state.DownloadingAssetsState
import tech.ula.model.state.DownloadsHaveFailed
import tech.ula.model.state.DownloadsHaveSucceeded
import tech.ula.model.state.DownloadsRequired
import tech.ula.model.state.ExtractFilesystem
import tech.ula.model.state.ExtractingFilesystem
import tech.ula.model.state.ExtractionFailed
import tech.ula.model.state.ExtractionHasCompletedSuccessfully
import tech.ula.model.state.ExtractionState
import tech.ula.model.state.FetchDatabaseEntries
import tech.ula.model.state.FetchingDatabaseEntries
import tech.ula.model.state.FilesystemAssetCopyFailed
import tech.ula.model.state.FilesystemAssetVerificationSucceeded
import tech.ula.model.state.GenerateDownloads
import tech.ula.model.state.GeneratingDownloadRequirements
import tech.ula.model.state.IncorrectAppTransition
import tech.ula.model.state.IncorrectSessionTransition
import tech.ula.model.state.LocalDirectoryCopyFailed
import tech.ula.model.state.LocalDirectoryCopySucceeded
import tech.ula.model.state.LowAvailableStorage
import tech.ula.model.state.NoDownloadsRequired
import tech.ula.model.state.RemoteUnreachableForGeneration
import tech.ula.model.state.ResetAppState
import tech.ula.model.state.ResetSessionState
import tech.ula.model.state.RetrieveAssetLists
import tech.ula.model.state.RetrievingAssetLists
import tech.ula.model.state.SessionIsReadyForPreparation
import tech.ula.model.state.SessionIsRestartable
import tech.ula.model.state.SessionSelected
import tech.ula.model.state.SessionStartupEvent
import tech.ula.model.state.SessionStartupFsm
import tech.ula.model.state.SessionStartupState
import tech.ula.model.state.SingleSessionSupported
import tech.ula.model.state.StorageVerificationCompletedSuccessfully
import tech.ula.model.state.StorageVerificationState
import tech.ula.model.state.SubmitAppSessionServiceLocation
import tech.ula.model.state.SubmitAppSessionServiceType
import tech.ula.model.state.SubmitAppsFilesystemCredentials
import tech.ula.model.state.SyncDatabaseEntries
import tech.ula.model.state.SyncDownloadState
import tech.ula.model.state.SyncingDatabaseEntries
import tech.ula.model.state.VerifyAvailableStorage
import tech.ula.model.state.VerifyAvailableStorageComplete
import tech.ula.model.state.VerifyFilesystemAssets
import tech.ula.model.state.VerifyingFilesystemAssets
import tech.ula.model.state.VerifyingSufficientStorage
import tech.ula.model.state.VerifyingSufficientStorageFailed
import tech.ula.model.state.WaitingForAppSelection
import tech.ula.model.state.WaitingForSessionSelection
import tech.ula.utils.AssetFileClearer
import tech.ula.utils.BreadcrumbType
import tech.ula.utils.DownloadFailureLocalizationData
import tech.ula.utils.Logger
import tech.ula.utils.SentryLogger
import tech.ula.utils.UlaBreadcrumb
import java.io.FileNotFoundException
import kotlin.coroutines.CoroutineContext

class MainActivityViewModel(
    private val appsStartupFsm: AppsStartupFsm,
    private val sessionStartupFsm: SessionStartupFsm,
    private val logger: Logger = SentryLogger()
) : ViewModel(), CoroutineScope {

    private val className = "MainVM"

    private var appsAreWaitingForSelection = false
    private var sessionsAreWaitingForSelection = false

    private val unselectedApp = App(name = "UNSELECTED")
    var lastSelectedApp = unselectedApp

    private val unselectedSession = Session(id = -1, name = "UNSELECTED", filesystemId = -1)
    var lastSelectedSession = unselectedSession

    private val unselectedFilesystem = Filesystem(id = -1, name = "UNSELECTED")
    var lastSelectedFilesystem = unselectedFilesystem

    private val appsState = appsStartupFsm.getState()

    private val sessionState = sessionStartupFsm.getState()

    private val state = MediatorLiveData<State>().apply {
        postValue(WaitingForInput)
    }

    private fun postIllegalStateWithLog(newState: IllegalState) {
        logger.sendIllegalStateLog(newState)
        state.postValue(newState)
    }

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    override fun onCleared() {
        super.onCleared()
        job.cancel()
    }

    init {
        state.addSource(appsState) { it?.let { update ->
            val breadcrumb = UlaBreadcrumb(className, BreadcrumbType.ObservedState, "$update")
            logger.addBreadcrumb(breadcrumb)
            // Update stateful variables before handling the update so they can be used during it
            if (update !is WaitingForAppSelection) {
                appsAreWaitingForSelection = false
            }
            when (update) {
                is WaitingForAppSelection -> {
                    appsAreWaitingForSelection = true
                }
                is DatabaseEntriesFetched -> {
                    lastSelectedSession = update.appSession
                    lastSelectedFilesystem = update.appsFilesystem
                }
                is AppDatabaseEntriesSynced -> {
                    lastSelectedApp = update.app
                    lastSelectedSession = update.session
                    lastSelectedFilesystem = update.filesystem
                }
            }
            handleAppsPreparationState(update)
        } }
        state.addSource(sessionState) { it?.let { update ->
            val breadcrumb = UlaBreadcrumb(className, BreadcrumbType.ObservedState, "$update")
            logger.addBreadcrumb(breadcrumb)
            handleSessionPreparationState(update)
        } }
    }

    fun getState(): LiveData<State> {
        return state
    }

    fun handleOnResume() {
        submitSessionStartupEvent(SyncDownloadState)
    }

    fun waitForPermissions(appToContinue: App = unselectedApp, sessionToContinue: Session = unselectedSession) {
        resetStartupState()
        lastSelectedApp = appToContinue
        lastSelectedSession = sessionToContinue
    }

    fun permissionsHaveBeenGranted() {
        when {
            lastSelectedApp != unselectedApp && lastSelectedSession != unselectedSession -> {
                postIllegalStateWithLog(TooManySelectionsMadeWhenPermissionsGranted)
            }
            lastSelectedApp == unselectedApp && lastSelectedSession == unselectedSession -> {
                postIllegalStateWithLog(NoSelectionsMadeWhenPermissionsGranted)
            }
            lastSelectedApp != unselectedApp -> {
                submitAppsStartupEvent(AppSelected(lastSelectedApp))
            }
            lastSelectedSession != unselectedSession -> {
                submitSessionStartupEvent(SessionSelected(lastSelectedSession))
            }
        }
    }

    fun submitAppSelection(app: App) {
        if (!selectionsCanBeMade()) return
        lastSelectedApp = app
        submitAppsStartupEvent(AppSelected(app))
    }

    fun submitSessionSelection(session: Session) {
        if (!selectionsCanBeMade()) return
        lastSelectedSession = session
        submitSessionStartupEvent(SessionSelected(session))
    }

    fun submitCompletedDownloadId(id: Long) {
        submitSessionStartupEvent(AssetDownloadComplete(id))
    }

    fun submitFilesystemCredentials(username: String, password: String, vncPassword: String) {
        if (lastSelectedFilesystem == unselectedFilesystem) {
            postIllegalStateWithLog(NoFilesystemSelectedWhenCredentialsSubmitted)
            return
        }
        submitAppsStartupEvent(SubmitAppsFilesystemCredentials(lastSelectedFilesystem, username, password, vncPassword))
    }

    fun lowAvailableStorageAcknowledged() {
        submitSessionStartupEvent(VerifyAvailableStorageComplete)
    }

    fun submitAppServiceLocation(serviceLocation: ServiceLocation) {
        if (lastSelectedApp == unselectedApp) {
            postIllegalStateWithLog(NoAppSelectedWhenPreferenceSubmitted)
            return
        }
        submitAppsStartupEvent(SubmitAppSessionServiceLocation(lastSelectedApp, serviceLocation))
    }

    fun submitAppServiceType(serviceType: ServiceType) {
        if (lastSelectedApp == unselectedApp) {
            postIllegalStateWithLog(NoAppSelectedWhenPreferenceSubmitted)
            return
        }
        submitAppsStartupEvent(SubmitAppSessionServiceType(lastSelectedApp, serviceType))
    }

    fun handleUserInputCancelled() {
        resetStartupState()
    }

    fun handleError() {
        resetStartupState()
    }

    // Exposed so that downloads can be continued from activity
    fun startAssetDownloads(downloadRequirements: List<DownloadMetadata>) {
        submitSessionStartupEvent(DownloadAssets(downloadRequirements))
    }

    fun handleSessionHasBeenActivated() {
        resetStartupState()
    }

    suspend fun handleClearSupportFiles(assetFileClearer: AssetFileClearer) {
        if (sessionStartupFsm.sessionsAreActive()) {
            state.postValue(ActiveSessionsMustBeDeactivated)
            return
        }
        state.postValue(ClearingSupportFiles)
        try {
            assetFileClearer.clearAllSupportAssets()
            state.postValue(ProgressBarOperationComplete)
        } catch (err: FileNotFoundException) {
            postIllegalStateWithLog(FailedToClearSupportFiles)
        } catch (err: IllegalStateException) {
            postIllegalStateWithLog(BusyboxMissing)
        }
    }

    private fun handleAppsPreparationState(newState: AppsStartupState) {
        // Exit early if we aren't expecting preparation requirements to have been met
        if (newState is WaitingForAppSelection || newState is FetchingDatabaseEntries) {
            return
        }
        if (!appsPreparationRequirementsHaveBeenSelected() && !((newState == AppHasServiceLocationSet) || (newState == AppRequiresServiceLocation))) {
            postIllegalStateWithLog(NoAppSelectedWhenTransitionNecessary)
            return
        }
        // Return when statement for compile-time exhaustiveness check
        return when (newState) {
            is IncorrectAppTransition -> {
                postIllegalStateWithLog(IllegalStateTransition("$newState"))
            }
            is WaitingForAppSelection -> {}
            is AppHasServiceLocationSet -> {
                submitAppsStartupEvent(FetchDatabaseEntries(lastSelectedApp))
            }
            is AppRequiresServiceLocation -> {
                state.postValue(AppServiceLocationPreferenceRequired)
            }
            is FetchingDatabaseEntries -> {}
            is DatabaseEntriesFetched -> {
                submitAppsStartupEvent(CheckAppsFilesystemCredentials(lastSelectedFilesystem))
            }
            is DatabaseEntriesFetchFailed -> {
                postIllegalStateWithLog(ErrorFetchingAppDatabaseEntries)
            }
            is AppsFilesystemHasCredentials -> {
                submitAppsStartupEvent(CheckAppSessionServiceType(lastSelectedApp))
            }
            is AppsFilesystemRequiresCredentials -> {
                state.postValue(FilesystemCredentialsRequired)
            }
            is AppHasServiceTypeSet -> {
                submitAppsStartupEvent(CopyAppScriptToFilesystem(lastSelectedApp, lastSelectedFilesystem))
            }
            is AppRequiresServiceType -> {
                state.postValue(AppServiceTypePreferenceRequired)
            }
            is CopyingAppScript -> {}
            is AppScriptCopySucceeded -> {
                submitAppsStartupEvent(SyncDatabaseEntries(lastSelectedApp, lastSelectedSession, lastSelectedFilesystem))
            }
            is AppScriptCopyFailed -> {
                postIllegalStateWithLog(ErrorCopyingAppScript)
            }
            is SyncingDatabaseEntries -> {}
            is AppDatabaseEntriesSynced -> {
                submitSessionStartupEvent(SessionSelected(lastSelectedSession))
            }
        }
    }

    // Post state values and delegate responsibility appropriately
    private fun handleSessionPreparationState(newState: SessionStartupState) {
        // Update stateful variables before handling the update so they can be used during it
        if (newState !is WaitingForSessionSelection) {
            sessionsAreWaitingForSelection = false
        }
        // Return for compile-time exhaustiveness check
        return when (newState) {
            is IncorrectSessionTransition -> {
                postIllegalStateWithLog(IllegalStateTransition("$newState"))
            }
            is WaitingForSessionSelection -> {
                sessionsAreWaitingForSelection = true
            }
            is SingleSessionSupported -> {
                state.postValue(CanOnlyStartSingleSession)
            }
            is SessionIsRestartable -> {
                state.postValue(SessionCanBeRestarted(newState.session))
            }
            is SessionIsReadyForPreparation -> {
                lastSelectedSession = newState.session
                lastSelectedFilesystem = newState.filesystem
                state.postValue(StartingSetup)
                doTransitionIfRequirementsAreSelected {
                    submitSessionStartupEvent(RetrieveAssetLists(lastSelectedFilesystem))
                }
            }
            is AssetRetrievalState -> {
                handleAssetRetrievalState(newState)
            }
            is DownloadRequirementsGenerationState -> {
                handleDownloadRequirementsGenerationState(newState)
            }
            is DownloadingAssetsState -> {
                handleDownloadingAssetsState(newState)
            }
            is CopyingFilesLocallyState -> {
                handleCopyingFilesLocallyState(newState)
            }
            is AssetVerificationState -> {
                handleAssetVerificationState(newState)
            }
            is ExtractionState -> {
                handleExtractionState(newState)
            }
            is StorageVerificationState -> {
                handleStorageVerificationState(newState)
            }
        }
    }

    private fun handleAssetRetrievalState(newState: AssetRetrievalState) {
        return when (newState) {
            is RetrievingAssetLists -> state.postValue(FetchingAssetLists)
            is AssetListsRetrievalSucceeded -> { doTransitionIfRequirementsAreSelected {
                    submitSessionStartupEvent(GenerateDownloads(lastSelectedFilesystem, newState.assetList))
            } }
            is AssetListsRetrievalFailed -> postIllegalStateWithLog(ErrorFetchingAssetLists)
        }
    }

    private fun handleDownloadRequirementsGenerationState(newState: DownloadRequirementsGenerationState) {
        return when (newState) {
            is GeneratingDownloadRequirements -> state.postValue(CheckingForAssetsUpdates)
            is RemoteUnreachableForGeneration -> {
                postIllegalStateWithLog(ErrorGeneratingDownloads(R.string.illegal_state_remote_unreachable_during_generation))
            }
            is DownloadsRequired -> {
                if (newState.largeDownloadRequired) {
                    state.postValue(LargeDownloadRequired(newState.downloadsRequired))
                } else {
                    startAssetDownloads(newState.downloadsRequired)
                }
            }
            is NoDownloadsRequired -> { doTransitionIfRequirementsAreSelected {
                    submitSessionStartupEvent(VerifyFilesystemAssets(lastSelectedFilesystem))
            } }
        }
    }

    private fun handleDownloadingAssetsState(newState: DownloadingAssetsState) {
        return when (newState) {
            is DownloadingAssets -> state.postValue(DownloadProgress(newState.numCompleted, newState.numTotal))
            is DownloadsHaveSucceeded -> submitSessionStartupEvent(CopyDownloadsToLocalStorage)
            is DownloadsHaveFailed -> postIllegalStateWithLog(DownloadsDidNotCompleteSuccessfully(newState.reason))
            is AttemptedCacheAccessWhileEmpty -> {
                postIllegalStateWithLog(DownloadCacheAccessedWhileEmpty)
            }
        }
    }

    private fun handleCopyingFilesLocallyState(newState: CopyingFilesLocallyState) {
        return when (newState) {
            is CopyingFilesToLocalDirectories -> state.postValue(CopyingDownloads)
            is LocalDirectoryCopySucceeded -> {
                if (sessionPreparationRequirementsHaveBeenSelected()) {
                    submitSessionStartupEvent(VerifyFilesystemAssets(lastSelectedFilesystem))
                } else {
                    state.postValue(ProgressBarOperationComplete)
                    resetStartupState()
                }
            }
            is LocalDirectoryCopyFailed -> postIllegalStateWithLog(FailedToCopyAssetsToLocalStorage)
        }
    }

    private fun handleAssetVerificationState(newState: AssetVerificationState) {
        return when (newState) {
            is VerifyingFilesystemAssets -> state.postValue(VerifyingFilesystem)
            is FilesystemAssetVerificationSucceeded -> { doTransitionIfRequirementsAreSelected {
                    submitSessionStartupEvent(VerifyAvailableStorage(lastSelectedFilesystem))
            } }
            is AssetsAreMissingFromSupportDirectories -> postIllegalStateWithLog(AssetsHaveNotBeenDownloaded)
            is FilesystemAssetCopyFailed -> postIllegalStateWithLog(FailedToCopyAssetsToFilesystem)
        }
    }

    private fun handleStorageVerificationState(newState: StorageVerificationState) {
        return when (newState) {
            is VerifyingSufficientStorage -> state.postValue(VerifyingAvailableStorage)
            is VerifyingSufficientStorageFailed -> postIllegalStateWithLog(InsufficientAvailableStorage)
            is LowAvailableStorage -> state.postValue(LowStorageAcknowledgementRequired)
            is StorageVerificationCompletedSuccessfully -> { doTransitionIfRequirementsAreSelected {
                submitSessionStartupEvent(ExtractFilesystem(lastSelectedFilesystem))
            } }
        }
    }

    private fun handleExtractionState(newState: ExtractionState) {
        return when (newState) {
            is ExtractingFilesystem -> state.postValue(FilesystemExtractionStep(newState.extractionTarget))
            is ExtractionHasCompletedSuccessfully -> { doTransitionIfRequirementsAreSelected {
                state.postValue(SessionCanBeStarted(lastSelectedSession))
            } }
            is ExtractionFailed -> postIllegalStateWithLog(FailedToExtractFilesystem(newState.reason))
        }
    }

    private fun resetStartupState() {
        lastSelectedApp = unselectedApp
        lastSelectedSession = unselectedSession
        lastSelectedFilesystem = unselectedFilesystem
        state.postValue(WaitingForInput)
        submitAppsStartupEvent(ResetAppState)
        submitSessionStartupEvent(ResetSessionState)
    }

    private fun selectionsCanBeMade(): Boolean {
        return appsAreWaitingForSelection && sessionsAreWaitingForSelection
    }

    private fun appsPreparationRequirementsHaveBeenSelected(): Boolean {
        return lastSelectedApp != unselectedApp && sessionPreparationRequirementsHaveBeenSelected()
    }

    private fun doTransitionIfRequirementsAreSelected(transition: () -> Unit) {
        if (!sessionPreparationRequirementsHaveBeenSelected()) {
            postIllegalStateWithLog(NoSessionSelectedWhenTransitionNecessary)
            return
        }
        transition()
    }

    private fun sessionPreparationRequirementsHaveBeenSelected(): Boolean {
        return lastSelectedSession != unselectedSession && lastSelectedFilesystem != unselectedFilesystem
    }

    private fun submitAppsStartupEvent(event: AppsStartupEvent) {
        val breadcrumb = UlaBreadcrumb(className, BreadcrumbType.SubmittedEvent, "$event")
        logger.addBreadcrumb(breadcrumb)
        appsStartupFsm.submitEvent(event, this)
    }

    private fun submitSessionStartupEvent(event: SessionStartupEvent) {
        val breadcrumb = UlaBreadcrumb(className, BreadcrumbType.SubmittedEvent, "$event")
        logger.addBreadcrumb(breadcrumb)
        sessionStartupFsm.submitEvent(event, this)
    }
}

sealed class State
object WaitingForInput : State()
object CanOnlyStartSingleSession : State()
data class SessionCanBeStarted(val session: Session) : State()
data class SessionCanBeRestarted(val session: Session) : State()

sealed class IllegalState : State()
data class IllegalStateTransition(val transition: String) : IllegalState()
object TooManySelectionsMadeWhenPermissionsGranted : IllegalState()
object NoSelectionsMadeWhenPermissionsGranted : IllegalState()

object NoFilesystemSelectedWhenCredentialsSubmitted : IllegalState()
object NoAppSelectedWhenPreferenceSubmitted : IllegalState()
object NoAppSelectedWhenTransitionNecessary : IllegalState()
object ErrorFetchingAppDatabaseEntries : IllegalState()
object ErrorCopyingAppScript : IllegalState()

object NoSessionSelectedWhenTransitionNecessary : IllegalState()
object ErrorFetchingAssetLists : IllegalState()
data class ErrorGeneratingDownloads(val errorId: Int) : IllegalState()
data class DownloadsDidNotCompleteSuccessfully(val reason: DownloadFailureLocalizationData) : IllegalState()
object DownloadCacheAccessedWhileEmpty : IllegalState()
object FailedToCopyAssetsToLocalStorage : IllegalState()
object AssetsHaveNotBeenDownloaded : IllegalState()
object FailedToCopyAssetsToFilesystem : IllegalState()
data class FailedToExtractFilesystem(val reason: String) : IllegalState()
object FailedToClearSupportFiles : IllegalState()
object InsufficientAvailableStorage : IllegalState()
object BusyboxMissing : IllegalState()

sealed class UserInputRequiredState : State()
object FilesystemCredentialsRequired : UserInputRequiredState()
object LowStorageAcknowledgementRequired : UserInputRequiredState()
object AppServiceLocationPreferenceRequired : UserInputRequiredState()
object AppServiceTypePreferenceRequired : UserInputRequiredState()
data class LargeDownloadRequired(val downloadRequirements: List<DownloadMetadata>) : UserInputRequiredState()
object ActiveSessionsMustBeDeactivated : UserInputRequiredState()

sealed class ProgressBarUpdateState : State()
object StartingSetup : ProgressBarUpdateState()
object FetchingAssetLists : ProgressBarUpdateState()
object CheckingForAssetsUpdates : ProgressBarUpdateState()
data class DownloadProgress(val numComplete: Int, val numTotal: Int) : ProgressBarUpdateState()
object CopyingDownloads : ProgressBarUpdateState()
object VerifyingFilesystem : ProgressBarUpdateState()
object VerifyingAvailableStorage : ProgressBarUpdateState()
data class FilesystemExtractionStep(val extractionTarget: String) : ProgressBarUpdateState()
object ClearingSupportFiles : ProgressBarUpdateState()
object ProgressBarOperationComplete : ProgressBarUpdateState()

class MainActivityViewModelFactory(private val appsStartupFsm: AppsStartupFsm, private val sessionStartupFsm: SessionStartupFsm) : ViewModelProvider.NewInstanceFactory() {
    override fun <T : ViewModel?> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return MainActivityViewModel(appsStartupFsm, sessionStartupFsm) as T
    }
}