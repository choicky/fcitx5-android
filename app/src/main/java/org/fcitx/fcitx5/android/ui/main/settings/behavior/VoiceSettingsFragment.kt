/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.speech.SpeechRecognizer
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.CheckBox
import android.widget.EditText
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.annotation.VisibleForTesting
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.voice.AsrResolution
import org.fcitx.fcitx5.android.input.voice.AsrServiceId
import org.fcitx.fcitx5.android.input.voice.CloudStatus
import org.fcitx.fcitx5.android.input.voice.DoubaoCredentials
import org.fcitx.fcitx5.android.input.voice.EndpointProblem
import org.fcitx.fcitx5.android.input.voice.ExternalServices
import org.fcitx.fcitx5.android.input.voice.KeystoreSecretCipher
import org.fcitx.fcitx5.android.input.voice.LocalAsrEngines
import org.fcitx.fcitx5.android.input.voice.InstallFailure
import org.fcitx.fcitx5.android.input.voice.LocalAsrModel
import org.fcitx.fcitx5.android.input.voice.LocalModels
import org.fcitx.fcitx5.android.input.voice.ModelAction
import org.fcitx.fcitx5.android.input.voice.ModelCatalogEntry
import org.fcitx.fcitx5.android.input.voice.ModelJobs
import org.fcitx.fcitx5.android.input.voice.ModelRow
import org.fcitx.fcitx5.android.input.voice.ModelStatus
import org.fcitx.fcitx5.android.input.voice.ModelTasks
import org.fcitx.fcitx5.android.input.voice.QwenAsrConfig
import org.fcitx.fcitx5.android.input.voice.TencentAsrConfig
import org.fcitx.fcitx5.android.input.voice.LocalStatus
import org.fcitx.fcitx5.android.input.voice.Recommendation
import org.fcitx.fcitx5.android.input.voice.SelfHostedInstance
import org.fcitx.fcitx5.android.input.voice.SelfHostedProtocol
import org.fcitx.fcitx5.android.input.voice.SystemAction
import org.fcitx.fcitx5.android.input.voice.SystemAsrAuthorization
import org.fcitx.fcitx5.android.input.voice.SystemRow
import org.fcitx.fcitx5.android.input.voice.SystemStatus
import org.fcitx.fcitx5.android.input.voice.UnavailableReason
import org.fcitx.fcitx5.android.input.voice.VoiceSelection
import org.fcitx.fcitx5.android.input.voice.VoiceSelectionStore
import org.fcitx.fcitx5.android.input.voice.applyRecommendation
import org.fcitx.fcitx5.android.input.voice.cloudStatus
import org.fcitx.fcitx5.android.input.voice.endpointProblem
import org.fcitx.fcitx5.android.input.voice.modelRow
import org.fcitx.fcitx5.android.input.voice.modelSourceProblem
import org.fcitx.fcitx5.android.input.voice.recommend
import org.fcitx.fcitx5.android.input.voice.resolveCurrentService
import org.fcitx.fcitx5.android.input.voice.selectableServices
import org.fcitx.fcitx5.android.input.voice.systemRow
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.setup
import org.fcitx.fcitx5.android.utils.toast

/**
 * Voice input settings (D034): services grouped by where audio is processed, their
 * enablement, and the one current service. The screen is rebuilt from the stored state
 * whenever it is shown or changed.
 */
class VoiceSettingsFragment : PaddingPreferenceFragment() {

    private val prefs = AppPrefs.getInstance()
    private val store = VoiceSelectionStore(prefs) { VoiceSelectionStore.lastErrorFile(requireContext()) }
    private val credentials by lazy { KeystoreSecretCipher.store(requireContext()) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // the file picker may outlive this instance (e.g. after a configuration change)
        pendingImport = savedInstanceState?.getString(PENDING_IMPORT)
            ?.let { name -> ModelCatalogEntry.entries.firstOrNull { it.model.name == name } }
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        render()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // PreferenceGroupAdapter's stable ids are per Preference object: a rebuilt screen is all
        // new ids, which the default item animator cross-fades as removals and insertions (the
        // page went blank). Rebuilds happen only on real changes now, and without animation.
        listView.itemAnimator = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingImport?.let { outState.putString(PENDING_IMPORT, it.model.name) }
    }

    override fun onResume() {
        super.onResume()
        // the System ASR disclosure is answered in another activity
        render()
    }

    private fun systemAvailable() = SpeechRecognizer.isRecognitionAvailable(requireContext())

    private fun localStatus() =
        LocalStatus(LocalAsrEngines.AVAILABLE, LocalAsrModel.userVisibleEntries.filterTo(mutableSetOf(), ::modelInstalled))

    private fun label(service: AsrServiceId): String = when (service) {
        AsrServiceId.System -> getString(R.string.asr_provider_system)
        is AsrServiceId.Local -> getString(R.string.asr_service_local_model, modelLabel(service.model))
        AsrServiceId.Doubao -> getString(R.string.asr_service_doubao)
        AsrServiceId.Qwen -> getString(R.string.asr_service_qwen)
        AsrServiceId.Tencent -> getString(R.string.asr_service_tencent)
        is AsrServiceId.SelfHosted -> store.instances.firstOrNull { it.id == service.instanceId }
            ?.name?.ifEmpty { null } ?: getString(R.string.voice_selfhosted_unnamed)
    }

    private fun external() =
        store.externalServices(credentials, allowCleartext = BuildConfig.DEBUG)

    /** A short reason why the selected service cannot run now; nothing when it is ready. */
    private fun reasonText(resolution: AsrResolution): String? = when (resolution) {
        is AsrResolution.Ready -> null
        AsrResolution.NeedsSystemAuthorization -> getString(R.string.voice_reason_needs_permission)
        is AsrResolution.CurrentUnavailable -> getString(
            when (resolution.reason) {
                UnavailableReason.Disabled -> R.string.voice_reason_disabled
                UnavailableReason.NoSystemRecognizer -> R.string.voice_reason_no_system
                UnavailableReason.NoLocalRuntime -> R.string.voice_reason_no_runtime
                UnavailableReason.LocalModelFilesMissing -> R.string.voice_reason_model_missing
                UnavailableReason.MissingCredentials -> R.string.voice_reason_no_credentials
                UnavailableReason.InstanceMissing -> R.string.voice_reason_instance_missing
                UnavailableReason.InvalidEndpoint -> R.string.voice_reason_invalid_endpoint
                UnavailableReason.CleartextEndpoint -> R.string.voice_reason_cleartext
            }
        )
        is AsrResolution.NeedsRecommendation, AsrResolution.NoService -> null
    }

    private fun modelLabel(model: LocalAsrModel) = getString(
        when (model) {
            LocalAsrModel.ZipformerBilingual -> R.string.voice_model_c
            LocalAsrModel.FunAsrNano -> R.string.voice_model_b
        }
    )

    /** Full rebuilds of the screen; progress must not cause them (see [onModelChanged]). */
    @VisibleForTesting
    internal var renderCount = 0
        private set

    /** The row status each model row was built with; a change of status needs a rebuild. */
    private val renderedStatus = mutableMapOf<LocalAsrModel, ModelStatus>()

    private fun render() {
        // dialog callbacks and posted updates can arrive after the screen is gone
        if (!isAdded) return
        val screen = preferenceScreen ?: return
        val ctx = requireContext()
        renderCount++
        screen.removeAll()
        val selection = store.load()
        val authorization = store.systemAuthorization
        val external = external()
        val resolution =
            resolveCurrentService(selection, localStatus(), authorization, ::systemAvailable, external)

        screen.addCategory(R.string.voice_current_section) {
            // the selected service itself is the row; it stays shown (with why) when unusable
            val current = selection.current
            val lastError = store.lastError?.takeIf { it.first == current?.key }?.second
            val title = current?.let(::label) ?: getString(R.string.voice_current_none)
            val state = listOfNotNull(
                reasonText(resolution),
                getString(if (current == null) R.string.voice_current_choose else R.string.voice_current_change)
            ).joinToString(" · ")
            val summary = state + (lastError?.let { "\n" + getString(R.string.voice_last_error, it) } ?: "")
            addPreference(title, summary) { chooseCurrent() }
            val lastUsed = AsrServiceId.parse(store.lastUsedService)
            if (lastUsed != null && lastUsed != current) {
                addPreference(getString(R.string.voice_last_used, label(lastUsed)))
            }
            if (current == null) {
                addPreference(
                    getString(R.string.voice_run_recommendation),
                    getString(R.string.voice_run_recommendation_summary)
                ) { runRecommendation() }
            }
        }

        screen.addCategory(R.string.voice_section_system) {
            // one row for enablement and the disclosure answer; they stay separate states
            val row = systemRow(
                selection.isEnabled(AsrServiceId.System), authorization, systemAvailable(),
                selection.current == AsrServiceId.System
            )
            addPreference(Preference(ctx).apply {
                key = SYSTEM_ROW_KEY
                isPersistent = false
                setup(getString(R.string.asr_provider_system), systemSummary(row.status)) { systemActions(row) }
            })
        }

        screen.addCategory(R.string.voice_section_local) {
            if (!LocalAsrEngines.AVAILABLE) addPreference(getString(R.string.voice_local_no_runtime))
            // one row per model: status and next step, actions on tap, and once installed its
            // own enable switch in the row; installing enables and selects nothing
            ModelCatalogEntry.entries.forEach { entry ->
                val model = entry.model
                val row = modelRowOf(entry, selection)
                renderedStatus[model] = row.status
                addPreference(ModelRowPreference(ctx).apply {
                    key = modelRowKey(model)
                    setup(modelLabel(model), modelSummary(entry, row)) { modelActions(entry) }
                    if (row.status in INSTALLED) {
                        enable = ModelRowPreference.Enable(
                            selection.isEnabled(AsrServiceId.Local(model)),
                            getString(R.string.voice_model_enable, modelLabel(model))
                        ) { on -> view?.post { setModelEnabled(model, on) } }
                    }
                })
            }
        }

        screen.addCategory(R.string.voice_section_cloud) {
            addCloud(AsrServiceId.Doubao, R.string.voice_enable_doubao, R.string.voice_doubao_credentials, selection, external) {
                editDoubaoCredentials()
            }
            addCloud(AsrServiceId.Qwen, R.string.voice_enable_qwen, R.string.voice_qwen_credentials, selection, external) {
                editQwenCredentials()
            }
            addCloud(AsrServiceId.Tencent, R.string.voice_enable_tencent, R.string.voice_tencent_credentials, selection, external) {
                editTencentCredentials()
            }
        }

        screen.addCategory(R.string.voice_section_selfhosted) {
            store.instances.forEach { instance ->
                val problem = when (endpointProblem(instance.url, BuildConfig.DEBUG, instance.protocol)) {
                    null -> null
                    EndpointProblem.Invalid -> getString(R.string.voice_endpoint_invalid)
                    EndpointProblem.Cleartext -> getString(R.string.voice_endpoint_cleartext)
                }
                val state = getString(
                    if (selection.isEnabled(instance.service)) R.string.voice_enabled
                    else R.string.voice_disabled
                )
                val summary = listOfNotNull(
                    protocolLabel(instance.protocol), instance.url, state, problem
                ).joinToString(" · ")
                addPreference(label(instance.service), summary) { editInstance(instance) }
            }
            addPreference(
                getString(R.string.voice_selfhosted_add),
                getString(R.string.voice_selfhosted_note)
            ) { editInstance(null) }
        }

    }

    /** A switch that is not bound to a preference key; [onChange] stores the value. */
    private fun PreferenceCategory.addSwitch(
        title: String,
        summary: String?,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        addPreference(MySwitchPreference(context).apply {
            isPersistent = false
            isChecked = checked
            setTitle(title)
            setSummary(summary)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setOnPreferenceChangeListener { _, value ->
                onChange(value as Boolean)
                view?.post { render() }
                true
            }
        })
    }

    /**
     * A cloud provider: its enable switch with a short status, then its credential row. What is
     * stored where and who receives the audio is explained in the credential form, before
     * anything is entered; enabling a provider without credentials opens that form.
     */
    private fun PreferenceCategory.addCloud(
        service: AsrServiceId,
        enableTitle: Int,
        credentialsTitle: Int,
        selection: VoiceSelection,
        external: ExternalServices,
        edit: () -> Unit
    ) {
        val configured = external.configured(service)
        val status = cloudStatus(selection.isEnabled(service), configured, selection.current == service)
        val summary = getString(
            when (status) {
                CloudStatus.NeedsCredentials -> R.string.voice_cloud_needs_credentials
                CloudStatus.EnableToSelect -> R.string.voice_cloud_enable_to_select
                CloudStatus.Selectable -> R.string.voice_cloud_selectable
                CloudStatus.InUse -> R.string.voice_status_in_use
            }
        )
        addSwitch(getString(enableTitle), summary, selection.isEnabled(service)) { on ->
            store.save(store.load().withEnabled(service, on))
            if (on && !configured) view?.post { edit() }
        }
        addPreference(
            getString(credentialsTitle),
            getString(if (configured) R.string.voice_credentials_set else R.string.voice_credentials_missing)
        ) { edit() }
    }

    /** Status and next step, then the disclosure: the device's service decides, it may go online. */
    private fun systemSummary(status: SystemStatus): String = getString(
        when (status) {
            SystemStatus.Disabled -> R.string.voice_system_disabled
            SystemStatus.Unavailable -> R.string.voice_system_unavailable
            SystemStatus.NeedsPermission -> R.string.voice_system_needs_permission
            SystemStatus.Ready -> R.string.voice_system_ready
            SystemStatus.InUse -> R.string.voice_status_in_use
        }
    ) + "\n" + getString(R.string.voice_system_note)

    private fun systemActions(row: SystemRow) {
        val labels = row.actions.map { action ->
            getString(
                when (action) {
                    SystemAction.Enable -> R.string.voice_system_enable
                    SystemAction.Allow -> R.string.voice_system_allow
                    SystemAction.Disable -> R.string.voice_system_disable
                    SystemAction.Revoke -> R.string.voice_system_revoke
                }
            )
        }
        ActionListDialog.create(requireContext(), getString(R.string.asr_provider_system), labels) { which ->
            when (row.actions[which]) {
                SystemAction.Enable -> {
                    store.save(store.load().withEnabled(AsrServiceId.System, true))
                    // enabling asks for the missing permission right away; a decline keeps it
                    // enabled but not allowed, which the row says
                    if (store.systemAuthorization != SystemAsrAuthorization.Allowed) askSystemPermission()
                    else render()
                }
                SystemAction.Allow -> askSystemPermission()
                SystemAction.Disable -> {
                    // a disabled current service stays selected; the current row says so
                    store.save(store.load().withEnabled(AsrServiceId.System, false))
                    render()
                }
                SystemAction.Revoke -> {
                    store.setSystemAllowed(false)
                    render()
                }
            }
        }.show()
    }

    /** The System ASR disclosure asked from its row: it answers permission, it selects nothing. */
    private fun askSystemPermission() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.system_asr_disclosure_title)
            .setMessage(R.string.system_asr_disclosure_message)
            .setNegativeButton(R.string.system_asr_decline) { _, _ -> store.setSystemAllowed(false) }
            .setPositiveButton(R.string.system_asr_allow) { _, _ -> store.setSystemAllowed(true) }
            .setOnDismissListener { render() }
            .show()
    }

    /**
     * Only enabled services that can run now are listed (a cloud provider needs credentials, a
     * model must be installed); the saved choice is never changed silently, and stays shown in
     * the current row with its reason when it is no longer listed.
     */
    private fun chooseCurrent() {
        val selection = store.load()
        val enabled = selectableServices(
            AsrServiceId.entries + store.instances.map { it.service },
            selection, localStatus(), store.systemAuthorization, ::systemAvailable, external()
        )
        if (enabled.isEmpty()) {
            requireContext().toast(R.string.voice_no_enabled_services)
            return
        }
        val labels = enabled.map { label(it) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.voice_choose_current)
            .setSingleChoiceItems(labels, enabled.indexOf(selection.current)) { dialog, which ->
                val chosen = enabled[which]
                store.save(store.load().copy(current = chosen, recommendationDone = true))
                dialog.dismiss()
                if (chosen == AsrServiceId.System &&
                    store.systemAuthorization != SystemAsrAuthorization.Allowed
                ) {
                    showSystemDisclosure()
                } else {
                    render()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun modelInstalled(model: LocalAsrModel) =
        LocalModels.isInstalled(requireContext(), model)

    private fun megabytes(bytes: Long) = (bytes + 999_999) / 1_000_000

    private fun modelRowOf(entry: ModelCatalogEntry, selection: VoiceSelection = store.load()): ModelRow {
        val model = entry.model
        val busy = ModelJobs.isRunning(model)
        return modelRow(
            // no file checks while a task runs: its model is not installed until it finishes
            installed = !busy && modelInstalled(model),
            task = ModelJobs.state(model),
            busy = busy,
            stagedBytes = { stagedBytes(model) },
            downloadOffered = entry.downloadOffered(BuildConfig.DEBUG),
            enabled = selection.isEnabled(AsrServiceId.Local(model)),
            current = selection.current == AsrServiceId.Local(model)
        )
    }

    /** One short line: status and next step. Source, licence, size and limits are in the details. */
    private fun modelSummary(entry: ModelCatalogEntry, row: ModelRow = modelRowOf(entry)): String {
        val size = megabytes(entry.totalBytes)
        return when (row.status) {
            ModelStatus.NotInstalled -> getString(
                if (ModelAction.Download in row.actions) R.string.voice_model_status_not_installed
                else R.string.voice_model_status_not_installed_import,
                size
            )
            ModelStatus.Partial -> getString(
                R.string.voice_model_status_partial, stagedBytes(entry.model) / 1_000_000, size
            )
            ModelStatus.Running -> (ModelJobs.state(entry.model) as? ModelTasks.State.Running)?.let {
                getString(R.string.voice_model_progress, (it.done * 100 / it.total.coerceAtLeast(1)).toInt())
            } ?: getString(R.string.voice_model_progress, 0)
            ModelStatus.Stopping -> getString(R.string.voice_model_cancelling)
            ModelStatus.Failed -> getString(
                R.string.voice_model_status_failed,
                (ModelJobs.state(entry.model) as? ModelTasks.State.Failed)?.let(::failureText).orEmpty()
            )
            ModelStatus.Installed -> getString(R.string.voice_model_status_installed)
            ModelStatus.Enabled -> getString(R.string.voice_model_status_enabled)
            ModelStatus.InUse -> getString(R.string.voice_status_in_use)
        }
    }

    private fun modelRowKey(model: LocalAsrModel) = "voice_model_row_${model.name}"

    /**
     * A task's progress changes only that model's row summary, on the same Preference, so the
     * list keeps its items, ids and scroll position. A change of the row's status (started,
     * finished, failed, cancelled) changes its actions and switch, so the screen is rebuilt once.
     */
    private fun onModelChanged(model: LocalAsrModel) {
        if (!isAdded) return
        val entry = ModelCatalogEntry.of(model)
        val row = modelRowOf(entry)
        val preference = findPreference<Preference>(modelRowKey(model))
        if (preference == null || renderedStatus[model] != row.status) {
            render()
            return
        }
        preference.summary = modelSummary(entry, row)
    }

    private fun setModelEnabled(model: LocalAsrModel, on: Boolean) {
        // disabling the current model keeps it selected; the current service says it is disabled
        store.save(store.load().withEnabled(AsrServiceId.Local(model), on))
        render()
    }

    /** Version, size, source, licence and limits; the full disclosure stays in the confirmation. */
    private fun showModelDetails(entry: ModelCatalogEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle(modelLabel(entry.model))
            .setView(metadataView(modelMetadataText(entry)))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun modelMetadataText(entry: ModelCatalogEntry): String = buildString {
        append(getString(R.string.voice_model_details_message, entry.version, megabytes(entry.totalBytes)))
        append("\n\n")
        append(getString(R.string.voice_model_source, entry.sourceName ?: entry.sourceLabel ?: "unknown"))
        entry.sourceUrl?.let { append("\n").append(it) }
        append("\n").append(getString(R.string.voice_model_license, entry.license ?: getString(R.string.voice_license_unconfirmed)))
        entry.licenseUrl?.let { append("\n").append(it) }
        entry.attribution?.let { append("\n").append(getString(R.string.voice_model_attribution, it)) }
        append("\n\n").append(getString(R.string.voice_model_offline_note))
        entry.limitation?.let { append("\n\n").append(it) }
        if (!entry.distributionApproved) {
            append("\n\n").append(getString(R.string.voice_model_research_status))
        }
    }

    private fun metadataView(text: String) = TextView(requireContext()).apply {
        setPadding(48, 0, 48, 0)
        this.text = text
        autoLinkMask = Linkify.WEB_URLS
        movementMethod = LinkMovementMethod.getInstance()
    }

    /** A model whose previous download is still stopping cannot start another one yet. */
    private fun startedOrBusy(started: Boolean) {
        if (!started) context?.toast(R.string.voice_model_busy)
    }

    private fun stagedBytes(model: LocalAsrModel) = LocalModels.stagedBytes(requireContext(), model)

    private fun failureText(failed: ModelTasks.State.Failed): String = when (val r = failed.reason) {
        is InstallFailure.Cancelled -> getString(R.string.voice_model_cancelled)
        is InstallFailure.NotEnoughSpace -> getString(R.string.voice_model_no_space, megabytes(r.needed))
        is InstallFailure.Missing -> getString(R.string.voice_model_missing_file, r.path)
        is InstallFailure.Mismatch -> getString(R.string.voice_model_mismatch, r.path)
        else -> failed.detail
    }

    private var pendingImport: ModelCatalogEntry? = null

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val entry = pendingImport ?: return@registerForActivityResult
            pendingImport = null
            if (uris.isNotEmpty()) startedOrBusy(ModelJobs.import(requireContext(), entry, uris))
            render()
        }

    /**
     * Model Manager actions: exactly those that apply to the model's state (see [modelRow]), as a
     * list under the model name. Details are one of the actions, never a dialog message, which
     * would hide the list.
     */
    private fun modelActions(entry: ModelCatalogEntry) {
        val ctx = requireContext()
        val model = entry.model
        val actions = modelRowOf(entry).actions
        val labels = actions.map { action ->
            when (action) {
                ModelAction.Download -> stagedBytes(model).takeIf { it > 0 }?.let {
                    getString(R.string.voice_model_resume, megabytes(entry.totalBytes - it))
                } ?: getString(R.string.voice_model_download, megabytes(entry.totalBytes))
                ModelAction.DownloadFrom -> getString(R.string.voice_model_download_other)
                ModelAction.Import -> getString(R.string.voice_model_import)
                ModelAction.Discard -> getString(R.string.voice_model_discard)
                ModelAction.Cancel -> getString(R.string.voice_model_cancel)
                ModelAction.Use -> getString(
                    if (store.load().isEnabled(AsrServiceId.Local(model))) R.string.voice_model_use
                    else R.string.voice_model_enable_and_use
                )
                ModelAction.Remove -> getString(R.string.voice_model_remove)
                ModelAction.Details -> getString(R.string.voice_model_details)
            }
        }
        ActionListDialog.create(ctx, modelLabel(model), labels) { which ->
            when (actions[which]) {
                ModelAction.Download -> confirmDownload(entry)
                ModelAction.DownloadFrom -> chooseSource(entry)
                ModelAction.Import -> {
                    pendingImport = entry
                    importLauncher.launch(arrayOf("*/*"))
                }
                ModelAction.Discard -> {
                    if (ModelJobs.isRunning(model)) startedOrBusy(false) else LocalModels.remove(ctx, model)
                    render()
                }
                ModelAction.Cancel -> ModelJobs.cancel(model)
                ModelAction.Use -> useModel(model)
                ModelAction.Remove -> confirmRemove(entry)
                ModelAction.Details -> showModelDetails(entry)
            }
        }.show()
    }

    /**
     * The explicit "Use": enable this model if needed and make it the current service. Only an
     * installed model offers it; another model or service is never selected silently.
     */
    private fun useModel(model: LocalAsrModel) {
        val service = AsrServiceId.Local(model)
        store.save(store.load().withEnabled(service, true).copy(current = service, recommendationDone = true))
        render()
        requireContext().toast(getString(R.string.voice_model_now_used, modelLabel(model)))
    }

    private fun confirmDownload(entry: ModelCatalogEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle(modelLabel(entry.model))
            .setView(metadataView(modelMetadataText(entry)))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                startedOrBusy(ModelJobs.download(requireContext(), entry))
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The same files from an address the user enters (for example a mirror of the pinned
     * revision when the upstream host is unreachable, or a computer on the local network for
     * testing); the pinned SHA-256 still decides what is installed.
     */
    private fun chooseSource(entry: ModelCatalogEntry) {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val address = EditText(ctx).apply {
            setText(entry.downloadBase.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(address)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.voice_model_source_title)
            .setMessage(R.string.voice_model_source_hint)
            .setView(form)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val base = address.text.toString().trim().trimEnd('/')
                when {
                    modelSourceProblem(base, BuildConfig.DEBUG) != null ->
                        ctx.toast(R.string.voice_model_source_invalid)
                    base == entry.downloadBase -> confirmDownload(entry)
                    else -> AlertDialog.Builder(ctx)
                        .setTitle(modelLabel(entry.model))
                        .setMessage(
                            getString(R.string.voice_model_download_confirm_custom, megabytes(entry.totalBytes), base)
                        )
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            startedOrBusy(ModelJobs.download(ctx, entry, base))
                            render()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemove(entry: ModelCatalogEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle(modelLabel(entry.model))
            .setMessage(R.string.voice_model_remove_confirm)
            .setPositiveButton(R.string.voice_model_remove) { _, _ ->
                // a download started meanwhile owns the staging directory: not removed under it
                if (ModelJobs.isRunning(entry.model)) {
                    startedOrBusy(false)
                    return@setPositiveButton
                }
                // a session that already loaded the model keeps its open files until it ends;
                // an adb-pushed copy is removed too, or recognition would keep using it
                LocalModels.remove(requireContext(), entry.model)
                // a removed model is no longer enabled; if it was current it stays selected and
                // the current service says it is not installed, so nothing else runs silently
                store.save(store.load().withEnabled(AsrServiceId.Local(entry.model), false))
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val jobListener: (LocalAsrModel) -> Unit = { onModelChanged(it) }

    override fun onStart() {
        super.onStart()
        ModelJobs.addListener(jobListener)
    }

    override fun onStop() {
        ModelJobs.removeListener(jobListener)
        super.onStop()
    }

    /** One field of a provider's credential form; [choices] shows radio buttons instead of text. */
    private data class CredentialField(
        val key: String,
        val label: Int,
        val secret: Boolean = false,
        val default: String = "",
        val choices: List<Pair<String, String>> = emptyList()
    )

    /**
     * Direct BYOK (D028): a provider's own credentials, stored encrypted on this device only and
     * never shown back; leaving a secret field empty keeps the stored value. [note] says where
     * the key is kept and who receives the audio, above the fields.
     */
    private fun editCredentials(
        provider: String,
        title: Int,
        note: Int,
        hint: Int,
        fields: List<CredentialField>,
        isComplete: (Map<String, String>) -> Boolean
    ) {
        val ctx = requireContext()
        val stored = credentials.read(provider).orEmpty()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val readers: List<Pair<String, () -> String>> = fields.map { field ->
            if (field.choices.isNotEmpty()) {
                form.addView(TextView(ctx).apply { setText(field.label) })
                val group = RadioGroup(ctx)
                val current = stored[field.key] ?: field.default
                val ids = field.choices.map { (value, text) ->
                    val button = RadioButton(ctx).apply {
                        id = View.generateViewId()
                        this.text = text
                    }
                    group.addView(button)
                    if (value == current) group.check(button.id)
                    button.id to value
                }
                form.addView(group)
                field.key to {
                    ids.firstOrNull { it.first == group.checkedRadioButtonId }?.second ?: field.default
                }
            } else {
                val keep = field.secret && !stored[field.key].isNullOrEmpty()
                val input = EditText(ctx).apply {
                    setHint(if (keep) R.string.voice_secret_kept else field.label)
                    if (!field.secret) setText(stored[field.key] ?: field.default)
                    val variation = if (field.secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0
                    inputType = InputType.TYPE_CLASS_TEXT or variation
                }
                form.addView(input)
                field.key to {
                    val typed = input.text.toString().trim()
                    if (typed.isEmpty() && field.secret) stored[field.key].orEmpty() else typed
                }
            }
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(getString(note) + "\n\n" + getString(hint))
            .setView(ScrollView(ctx).apply { addView(form) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val values = readers.associate { (key, read) -> key to read() }
                    .filterValues { it.isNotEmpty() }
                if (isComplete(values)) {
                    saveCredentials(provider, values)
                } else {
                    ctx.toast(R.string.voice_credentials_incomplete)
                }
                render()
            }
            .setNeutralButton(R.string.voice_credentials_clear) { _, _ ->
                credentials.clear(provider)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The Keystore can fail on some devices; that must not crash the settings screen. */
    private fun saveCredentials(provider: String, values: Map<String, String>) {
        runCatching { credentials.write(provider, values) }.onFailure {
            requireContext().toast(getString(R.string.voice_credentials_save_failed, it.javaClass.simpleName))
        }
    }

    private fun editDoubaoCredentials() = editCredentials(
        DoubaoCredentials.PROVIDER,
        R.string.voice_doubao_credentials,
        R.string.voice_byok_note,
        R.string.voice_doubao_credentials_hint,
        listOf(
            CredentialField(DoubaoCredentials.API_KEY, R.string.voice_doubao_api_key, secret = true),
            CredentialField(DoubaoCredentials.APP_KEY, R.string.voice_doubao_app_key),
            CredentialField(DoubaoCredentials.ACCESS_KEY, R.string.voice_doubao_access_key, secret = true),
            CredentialField(
                DoubaoCredentials.RESOURCE_ID, R.string.voice_doubao_resource_id,
                default = DoubaoCredentials.DEFAULT_RESOURCE_ID
            )
        )
    ) { DoubaoCredentials.fromStore(it).isComplete }

    private fun editQwenCredentials() = editCredentials(
        QwenAsrConfig.PROVIDER,
        R.string.voice_qwen_credentials,
        R.string.voice_byok_note_qwen,
        R.string.voice_qwen_credentials_hint,
        listOf(
            CredentialField(QwenAsrConfig.API_KEY, R.string.voice_qwen_api_key, secret = true),
            CredentialField(QwenAsrConfig.WORKSPACE_ID, R.string.voice_qwen_workspace),
            CredentialField(
                QwenAsrConfig.REGION, R.string.voice_qwen_region,
                default = QwenAsrConfig.Region.Beijing.name,
                choices = listOf(
                    QwenAsrConfig.Region.Beijing.name to getString(R.string.voice_region_beijing),
                    QwenAsrConfig.Region.Singapore.name to getString(R.string.voice_region_singapore)
                )
            ),
            CredentialField(
                QwenAsrConfig.MODEL, R.string.voice_qwen_model,
                default = QwenAsrConfig.DEFAULT_MODEL,
                choices = QwenAsrConfig.MODELS.map { it to it }
            )
        )
    ) { QwenAsrConfig.fromStore(it).isComplete }

    private fun editTencentCredentials() = editCredentials(
        TencentAsrConfig.PROVIDER,
        R.string.voice_tencent_credentials,
        R.string.voice_byok_note_tencent,
        R.string.voice_tencent_credentials_hint,
        listOf(
            CredentialField(TencentAsrConfig.APP_ID, R.string.voice_tencent_app_id),
            CredentialField(TencentAsrConfig.SECRET_ID, R.string.voice_tencent_secret_id),
            CredentialField(TencentAsrConfig.SECRET_KEY, R.string.voice_tencent_secret_key, secret = true),
            CredentialField(
                TencentAsrConfig.ENGINE, R.string.voice_tencent_engine,
                default = TencentAsrConfig.DEFAULT_ENGINE,
                choices = listOf(
                    "16k_zh_en" to getString(R.string.voice_tencent_engine_zh_en),
                    "16k_zh" to getString(R.string.voice_tencent_engine_zh),
                    "Hy-ASR-3.0-preview" to getString(R.string.voice_tencent_engine_hy)
                )
            )
        )
    ) { TencentAsrConfig.fromStore(it).isComplete }

    /**
     * Add or edit a self-hosted server. Only `wss://` is accepted in release builds; the token
     * is optional (for a reverse proxy), stored encrypted, and never shown.
     */
    private fun editInstance(existing: SelfHostedInstance?) {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val storedToken = existing?.let { credentials.read(it.credentialProvider)?.get(SelfHostedInstance.TOKEN) }
        val name = EditText(ctx).apply {
            setHint(R.string.voice_selfhosted_name)
            setText(existing?.name.orEmpty())
        }
        val url = EditText(ctx).apply {
            setHint(R.string.voice_selfhosted_url)
            setText(existing?.url.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val model = EditText(ctx).apply {
            setHint(R.string.voice_selfhosted_model)
            setText(existing?.model.orEmpty())
        }
        val token = EditText(ctx).apply {
            setHint(
                if (storedToken.isNullOrEmpty()) R.string.voice_selfhosted_token
                else R.string.voice_secret_kept
            )
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val protocols = RadioGroup(ctx)
        val protocolIds = SelfHostedProtocol.entries.map { protocol ->
            val button = RadioButton(ctx).apply {
                id = View.generateViewId()
                text = protocolLabel(protocol)
            }
            protocols.addView(button)
            if (protocol == (existing?.protocol ?: SelfHostedProtocol.SherpaOnnx)) protocols.check(button.id)
            button.id to protocol
        }
        val enabled = CheckBox(ctx).apply {
            setText(R.string.voice_selfhosted_enable)
            isChecked = existing?.let { store.load().isEnabled(it.service) } ?: true
        }
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            listOf(name, protocols, url, model, token, enabled).forEach { addView(it) }
        }
        val builder = AlertDialog.Builder(ctx)
            .setTitle(R.string.voice_selfhosted_server)
            .setMessage(R.string.voice_selfhosted_protocol_hint)
            .setView(form)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val endpoint = url.text.toString().trim()
                val protocol = protocolIds.firstOrNull { it.first == protocols.checkedRadioButtonId }
                    ?.second ?: SelfHostedProtocol.SherpaOnnx
                when (endpointProblem(endpoint, BuildConfig.DEBUG, protocol)) {
                    EndpointProblem.Invalid -> ctx.toast(R.string.voice_endpoint_invalid)
                    EndpointProblem.Cleartext -> ctx.toast(R.string.voice_endpoint_cleartext)
                    null -> {
                        val id = existing?.id ?: newInstanceId()
                        val instance = SelfHostedInstance(
                            id, name.text.toString().trim(), protocol, endpoint,
                            model.text.toString().trim()
                        )
                        store.instances = store.instances.filter { it.id != id } + instance
                        val newToken = token.text.toString().trim()
                        if (newToken.isNotEmpty()) {
                            saveCredentials(
                                instance.credentialProvider,
                                mapOf(SelfHostedInstance.TOKEN to newToken)
                            )
                        }
                        store.save(store.load().withEnabled(instance.service, enabled.isChecked))
                    }
                }
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (existing != null) {
            builder.setNeutralButton(R.string.voice_credentials_clear) { _, _ ->
                store.removeInstance(existing.id, credentials)
                render()
            }
        }
        builder.show()
    }

    private fun protocolLabel(protocol: SelfHostedProtocol) = getString(
        when (protocol) {
            SelfHostedProtocol.SherpaOnnx -> R.string.voice_selfhosted_sherpa
            SelfHostedProtocol.FunAsr2Pass -> R.string.voice_selfhosted_funasr
            SelfHostedProtocol.FunAsrNano -> R.string.voice_selfhosted_nano
            SelfHostedProtocol.OpenAiCompatible -> R.string.voice_selfhosted_openai
        }
    )

    private fun newInstanceId(): String {
        val used = store.instances.map { it.id }.toSet()
        while (true) {
            val id = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
            if (id !in used) return id
        }
    }

    /** The user-initiated recommendation (D034); it never selects a network service. */
    private fun runRecommendation() {
        when (val recommendation = recommend(
            localStatus(), store.load(), store.systemAuthorization, ::systemAvailable
        )) {
            Recommendation.AskSystemAuthorization -> showSystemDisclosure()
            else -> {
                store.save(store.load().applyRecommendation(recommendation))
                if (recommendation == Recommendation.Nothing) {
                    requireContext().toast(R.string.voice_no_provider)
                }
                render()
            }
        }
    }

    private fun showSystemDisclosure() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.system_asr_disclosure_title)
            .setMessage(R.string.system_asr_disclosure_message)
            .setNegativeButton(R.string.system_asr_decline) { _, _ ->
                store.answerSystemDisclosure(false)
            }
            .setPositiveButton(R.string.system_asr_allow) { _, _ ->
                store.answerSystemDisclosure(true)
            }
            .setOnDismissListener { render() }
            .show()
    }

    private companion object {
        private val INSTALLED = setOf(ModelStatus.Installed, ModelStatus.Enabled, ModelStatus.InUse)
        const val PENDING_IMPORT = "pending_model_import"
        const val SYSTEM_ROW_KEY = "voice_system_row"
    }
}
