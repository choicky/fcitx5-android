/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.speech.SpeechRecognizer
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceCategory
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.voice.AsrResolution
import org.fcitx.fcitx5.android.input.voice.AsrServiceId
import org.fcitx.fcitx5.android.input.voice.LocalAsrBackend
import org.fcitx.fcitx5.android.input.voice.LocalAsrEngines
import org.fcitx.fcitx5.android.input.voice.LocalAsrModel
import org.fcitx.fcitx5.android.input.voice.LocalStatus
import org.fcitx.fcitx5.android.input.voice.Recommendation
import org.fcitx.fcitx5.android.input.voice.SystemAsrAuthorization
import org.fcitx.fcitx5.android.input.voice.UnavailableReason
import org.fcitx.fcitx5.android.input.voice.VoiceSelectionStore
import org.fcitx.fcitx5.android.input.voice.applyRecommendation
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

    private fun modelInstalled(model: LocalAsrModel): Boolean =
        LocalAsrBackend.modelDir(requireContext().getExternalFilesDir(null), model)
            ?.let { model.missingFiles(it).isEmpty() } == true

    private fun label(service: AsrServiceId) = getString(
        when (service) {
            AsrServiceId.System -> R.string.asr_provider_system
            AsrServiceId.Local -> R.string.asr_provider_local
        }
    )

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
            resolveCurrentService(selection, localStatus(), authorization, ::systemAvailable)

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
            addSwitch(getString(R.string.voice_enable_system), null, selection.systemEnabled) {
                store.save(store.load().copy(systemEnabled = it))
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
            addSwitch(getString(R.string.voice_enable_local), null, selection.localEnabled) {
                store.save(store.load().copy(localEnabled = it))
            }
            val model = store.localModel
            val modelSummary = when {
                !LocalAsrEngines.AVAILABLE -> getString(R.string.voice_local_no_runtime)
                model == null -> getString(R.string.voice_local_model_none)
                else -> getString(
                    R.string.voice_local_model_summary,
                    modelLabel(model),
                    getString(
                        if (modelInstalled(model)) R.string.voice_model_installed
                        else R.string.voice_model_not_installed
                    ),
                    modelNote(model)
                )
            }
            addPreference(getString(R.string.voice_local_model), modelSummary) { chooseLocalModel() }
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
        val enabled = AsrServiceId.entries.filter { selection.isEnabled(it) }
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

    private fun chooseLocalModel() {
        if (!LocalAsrEngines.AVAILABLE) {
            requireContext().toast(R.string.voice_local_no_runtime)
            return
        }
        val models = LocalAsrModel.entries
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
