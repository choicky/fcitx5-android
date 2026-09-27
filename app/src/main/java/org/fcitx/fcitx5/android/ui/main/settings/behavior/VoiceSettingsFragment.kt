/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.speech.SpeechRecognizer
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceCategory
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.voice.AsrResolution
import org.fcitx.fcitx5.android.input.voice.AsrServiceId
import org.fcitx.fcitx5.android.input.voice.DoubaoCredentials
import org.fcitx.fcitx5.android.input.voice.EndpointProblem
import org.fcitx.fcitx5.android.input.voice.KeystoreSecretCipher
import org.fcitx.fcitx5.android.input.voice.LocalAsrEngines
import org.fcitx.fcitx5.android.input.voice.InstallFailure
import org.fcitx.fcitx5.android.input.voice.LocalAsrModel
import org.fcitx.fcitx5.android.input.voice.LocalModels
import org.fcitx.fcitx5.android.input.voice.ModelCatalogEntry
import org.fcitx.fcitx5.android.input.voice.ModelJobs
import org.fcitx.fcitx5.android.input.voice.LocalStatus
import org.fcitx.fcitx5.android.input.voice.Recommendation
import org.fcitx.fcitx5.android.input.voice.SelfHostedInstance
import org.fcitx.fcitx5.android.input.voice.SelfHostedProtocol
import org.fcitx.fcitx5.android.input.voice.SystemAsrAuthorization
import org.fcitx.fcitx5.android.input.voice.UnavailableReason
import org.fcitx.fcitx5.android.input.voice.VoiceSelectionStore
import org.fcitx.fcitx5.android.input.voice.applyRecommendation
import org.fcitx.fcitx5.android.input.voice.endpointProblem
import org.fcitx.fcitx5.android.input.voice.recommend
import org.fcitx.fcitx5.android.input.voice.resolveCurrentService
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.toast

/**
 * Voice input settings (D034): services grouped by where audio is processed, their
 * enablement, and the one current service. The screen is rebuilt from the stored state
 * whenever it is shown or changed.
 */
class VoiceSettingsFragment : PaddingPreferenceFragment() {

    private val prefs = AppPrefs.getInstance()
    private val store = VoiceSelectionStore(prefs)
    private val credentials by lazy { KeystoreSecretCipher.store(requireContext()) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        render()
    }

    override fun onResume() {
        super.onResume()
        // the System ASR disclosure is answered in another activity
        render()
    }

    private fun systemAvailable() = SpeechRecognizer.isRecognitionAvailable(requireContext())

    private fun localStatus(): LocalStatus {
        val model = store.localModel
        return LocalStatus(LocalAsrEngines.AVAILABLE, model, model?.let(::modelInstalled) == true)
    }


    private fun label(service: AsrServiceId): String = when (service) {
        AsrServiceId.System -> getString(R.string.asr_provider_system)
        AsrServiceId.Local -> getString(R.string.asr_provider_local)
        AsrServiceId.Doubao -> getString(R.string.asr_service_doubao)
        is AsrServiceId.SelfHosted -> store.instances.firstOrNull { it.id == service.instanceId }
            ?.name?.ifEmpty { null } ?: getString(R.string.voice_selfhosted_unnamed)
    }

    private fun external() =
        store.externalServices(credentials, allowCleartext = BuildConfig.DEBUG)

    private fun stateText(resolution: AsrResolution): String = getString(
        when (resolution) {
            is AsrResolution.Ready -> R.string.voice_state_ready
            AsrResolution.NeedsSystemAuthorization -> R.string.voice_state_needs_authorization
            is AsrResolution.CurrentUnavailable -> when (resolution.reason) {
                UnavailableReason.Disabled -> R.string.voice_current_disabled
                UnavailableReason.NoSystemRecognizer -> R.string.voice_input_unavailable
                UnavailableReason.NoLocalRuntime -> R.string.voice_local_no_runtime
                UnavailableReason.NoLocalModel -> R.string.voice_local_no_model
                UnavailableReason.LocalModelFilesMissing -> R.string.voice_local_unavailable
                UnavailableReason.MissingCredentials -> R.string.voice_missing_credentials
                UnavailableReason.InstanceMissing -> R.string.voice_instance_missing
                UnavailableReason.InvalidEndpoint -> R.string.voice_endpoint_invalid
                UnavailableReason.CleartextEndpoint -> R.string.voice_endpoint_cleartext
            }
            is AsrResolution.NeedsRecommendation, AsrResolution.NoService -> R.string.voice_current_none
        }
    )

    private fun modelLabel(model: LocalAsrModel) = getString(
        when (model) {
            LocalAsrModel.ZipformerZh -> R.string.voice_model_a
            LocalAsrModel.FunAsrNano -> R.string.voice_model_b
        }
    )

    private fun modelNote(model: LocalAsrModel) = getString(
        when (model) {
            LocalAsrModel.ZipformerZh -> R.string.voice_model_a_note
            LocalAsrModel.FunAsrNano -> R.string.voice_model_b_note
        }
    )

    private fun render() {
        val screen = preferenceScreen ?: return
        val ctx = requireContext()
        screen.removeAll()
        val selection = store.load()
        val authorization = store.systemAuthorization
        val resolution =
            resolveCurrentService(
                selection, localStatus(), authorization, ::systemAvailable, external()
            )

        screen.addCategory(R.string.voice_current_section) {
            val current = selection.current
            val summary = if (current == null) getString(R.string.voice_current_none)
            else getString(R.string.voice_current_summary, label(current), stateText(resolution))
            addPreference(getString(R.string.asr_provider), summary) { chooseCurrent() }
            val lastUsed = AsrServiceId.parse(store.lastUsedService)
            if (lastUsed != null && lastUsed != current) {
                addPreference(getString(R.string.voice_last_used, label(lastUsed)))
            }
            if (current == null && !selection.recommendationDone) {
                addPreference(
                    getString(R.string.voice_run_recommendation),
                    getString(R.string.voice_run_recommendation_summary)
                ) { runRecommendation() }
            }
        }

        screen.addCategory(R.string.voice_section_system) {
            addSwitch(getString(R.string.voice_enable_system), null, selection.isEnabled(AsrServiceId.System)) {
                store.save(store.load().withEnabled(AsrServiceId.System, it))
            }
            addPreference(MySwitchPreference(ctx).apply {
                key = prefs.voice.systemAsrAllowed.key
                setTitle(R.string.allow_system_asr)
                setSummary(R.string.allow_system_asr_summary)
                isIconSpaceReserved = false
                isSingleLineTitle = false
                setDefaultValue(false)
                setOnPreferenceChangeListener { _, _ ->
                    // toggling the switch answers the disclosure, so Auto-style prompts stop
                    prefs.internal.voiceSystemAsrAnswered.setValue(true)
                    true
                }
            })
        }

        screen.addCategory(R.string.voice_section_local) {
            addSwitch(getString(R.string.voice_enable_local), null, selection.isEnabled(AsrServiceId.Local)) {
                store.save(store.load().withEnabled(AsrServiceId.Local, it))
            }
            val current = store.localModel
            addPreference(
                getString(R.string.voice_local_model),
                if (!LocalAsrEngines.AVAILABLE) getString(R.string.voice_local_no_runtime)
                else current?.let(::modelLabel) ?: getString(R.string.voice_local_model_none)
            ) { chooseLocalModel() }
            ModelCatalogEntry.entries.forEach { entry ->
                addPreference(modelLabel(entry.model), modelSummary(entry)) { modelActions(entry) }
            }
        }

        screen.addCategory(R.string.voice_section_cloud) {
            addSwitch(
                getString(R.string.voice_enable_doubao),
                getString(R.string.voice_byok_note),
                selection.isEnabled(AsrServiceId.Doubao)
            ) {
                store.save(store.load().withEnabled(AsrServiceId.Doubao, it))
            }
            addPreference(
                getString(R.string.voice_doubao_credentials),
                getString(
                    if (credentials.has(DoubaoCredentials.PROVIDER)) R.string.voice_credentials_set
                    else R.string.voice_credentials_missing
                )
            ) { editDoubaoCredentials() }
        }

        screen.addCategory(R.string.voice_section_selfhosted) {
            store.instances.forEach { instance ->
                val problem = when (endpointProblem(instance.url, BuildConfig.DEBUG)) {
                    null -> null
                    EndpointProblem.Invalid -> getString(R.string.voice_endpoint_invalid)
                    EndpointProblem.Cleartext -> getString(R.string.voice_endpoint_cleartext)
                }
                val state = getString(
                    if (selection.isEnabled(instance.service)) R.string.voice_enabled
                    else R.string.voice_disabled
                )
                val summary = listOfNotNull(
                    getString(R.string.voice_selfhosted_sherpa), instance.url, state, problem
                ).joinToString(" · ")
                addPreference(label(instance.service), summary) { editInstance(instance) }
            }
            addPreference(
                getString(R.string.voice_selfhosted_add),
                getString(R.string.voice_selfhosted_note)
            ) { editInstance(null) }
        }

        screen.addCategory(R.string.voice_section_other) {
            addPreference(MySwitchPreference(ctx).apply {
                key = prefs.voice.showVoiceInputButton.key
                setTitle(R.string.show_voice_input_button)
                isIconSpaceReserved = false
                isSingleLineTitle = false
                setDefaultValue(prefs.voice.showVoiceInputButton.defaultValue)
            })
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

    /** Only enabled services can be current; the saved choice is never changed silently. */
    private fun chooseCurrent() {
        val selection = store.load()
        val enabled = (AsrServiceId.entries + store.instances.map { it.service })
            .filter { selection.isEnabled(it) }
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

    private fun modelSummary(entry: ModelCatalogEntry): String {
        val state = when (val job = ModelJobs.state(entry.model)) {
            is ModelJobs.State.Running -> getString(
                R.string.voice_model_progress,
                (job.done * 100 / job.total.coerceAtLeast(1)).toInt()
            )
            is ModelJobs.State.Failed -> getString(R.string.voice_model_failed, failureText(job))
            else -> getString(
                if (modelInstalled(entry.model)) R.string.voice_model_installed
                else R.string.voice_model_not_installed
            )
        }
        return getString(
            R.string.voice_model_summary, entry.version, megabytes(entry.totalBytes), state
        ) + "\n" + modelNote(entry.model)
    }

    private fun failureText(failed: ModelJobs.State.Failed): String = when (val r = failed.reason) {
        is InstallFailure.Cancelled -> getString(R.string.voice_model_cancelled)
        is InstallFailure.NotEnoughSpace -> getString(R.string.voice_model_no_space, megabytes(r.needed))
        is InstallFailure.Missing -> getString(R.string.voice_model_missing_file, r.path)
        is InstallFailure.Mismatch -> getString(R.string.voice_model_mismatch, r.path)
        else -> failed.detail
    }

    /** Only models that are installed can be the current Local model. */
    private fun chooseLocalModel() {
        if (!LocalAsrEngines.AVAILABLE) {
            requireContext().toast(R.string.voice_local_no_runtime)
            return
        }
        val models = LocalAsrModel.entries.filter(::modelInstalled)
        if (models.isEmpty()) {
            requireContext().toast(R.string.voice_model_none_installed)
            return
        }
        val labels = models.map { modelLabel(it) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.voice_local_model)
            .setSingleChoiceItems(labels, models.indexOf(store.localModel)) { dialog, which ->
                store.localModel = models[which]
                dialog.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private var pendingImport: ModelCatalogEntry? = null

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val entry = pendingImport ?: return@registerForActivityResult
            pendingImport = null
            if (uris.isNotEmpty()) ModelJobs.import(requireContext(), entry, uris)
            render()
        }

    /** Model Manager actions; only what the licence gate and the current state allow. */
    private fun modelActions(entry: ModelCatalogEntry) {
        val ctx = requireContext()
        val model = entry.model
        val installed = modelInstalled(model)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (ModelJobs.isRunning(model)) {
            actions += getString(R.string.voice_model_cancel) to { ModelJobs.cancel(model) }
        } else {
            if (entry.downloadBase != null) {
                actions += getString(R.string.voice_model_download, megabytes(entry.totalBytes)) to {
                    confirmDownload(entry)
                }
            }
            actions += getString(R.string.voice_model_import) to {
                pendingImport = entry
                importLauncher.launch(arrayOf("*/*"))
            }
            if (installed) {
                actions += getString(R.string.voice_model_use) to {
                    store.localModel = model
                    render()
                }
                actions += getString(R.string.voice_model_remove) to { confirmRemove(entry) }
            }
        }
        AlertDialog.Builder(ctx)
            .setTitle(modelLabel(model))
            .setMessage(modelNote(model))
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDownload(entry: ModelCatalogEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle(modelLabel(entry.model))
            .setMessage(getString(R.string.voice_model_download_confirm, megabytes(entry.totalBytes)))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ModelJobs.download(requireContext(), entry)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemove(entry: ModelCatalogEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle(modelLabel(entry.model))
            .setMessage(R.string.voice_model_remove_confirm)
            .setPositiveButton(R.string.voice_model_remove) { _, _ ->
                // a session that already loaded the model keeps its open files until it ends
                LocalModels.installer(requireContext()).remove(entry.model)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val jobListener: () -> Unit = { render() }

    override fun onStart() {
        super.onStart()
        ModelJobs.addListener(jobListener)
    }

    override fun onStop() {
        ModelJobs.removeListener(jobListener)
        super.onStop()
    }

    /** The one-time recommendation (D034); it never selects a network service. */
    private fun runRecommendation() {
        when (val recommendation = recommend(store.systemAuthorization, ::systemAvailable)) {
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
}
