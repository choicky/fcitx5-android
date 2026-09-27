/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.speech.SpeechRecognizer
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceCategory
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.voice.AsrResolution
import org.fcitx.fcitx5.android.input.voice.AsrServiceId
import org.fcitx.fcitx5.android.input.voice.DoubaoCredentials
import org.fcitx.fcitx5.android.input.voice.KeystoreSecretCipher
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

    private fun modelInstalled(model: LocalAsrModel): Boolean =
        LocalAsrBackend.modelDir(requireContext().getExternalFilesDir(null), model)
            ?.let { model.missingFiles(it).isEmpty() } == true

    private fun label(service: AsrServiceId) = getString(
        when (service) {
            AsrServiceId.System -> R.string.asr_provider_system
            AsrServiceId.Local -> R.string.asr_provider_local
            AsrServiceId.Doubao -> R.string.asr_service_doubao
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
                UnavailableReason.MissingCredentials -> R.string.voice_missing_credentials
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
                selection, localStatus(), authorization, ::systemAvailable,
                configured = { it == AsrServiceId.Doubao && credentials.has(DoubaoCredentials.PROVIDER) }
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

    /**
     * Direct BYOK (D028): the user's own Volcengine credentials, stored encrypted on this device
     * only. Secret fields are never shown; leaving one empty keeps the stored value.
     */
    private fun editDoubaoCredentials() {
        val ctx = requireContext()
        val stored = credentials.read(DoubaoCredentials.PROVIDER).orEmpty()
        val pad = (16 * resources.displayMetrics.density).toInt()
        fun field(hint: Int, secret: Boolean, value: String = "") = EditText(ctx).apply {
            setHint(hint)
            setText(value)
            val variation = if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0
            inputType = InputType.TYPE_CLASS_TEXT or variation
        }
        val apiKey = field(R.string.voice_doubao_api_key, secret = true)
        val appKey = field(
            R.string.voice_doubao_app_key, secret = false,
            value = stored[DoubaoCredentials.APP_KEY].orEmpty()
        )
        val accessKey = field(R.string.voice_doubao_access_key, secret = true)
        val resourceId = field(
            R.string.voice_doubao_resource_id, secret = false,
            value = stored[DoubaoCredentials.RESOURCE_ID] ?: DoubaoCredentials.DEFAULT_RESOURCE_ID
        )
        if (!stored[DoubaoCredentials.API_KEY].isNullOrEmpty()) apiKey.setHint(R.string.voice_secret_kept)
        if (!stored[DoubaoCredentials.ACCESS_KEY].isNullOrEmpty()) accessKey.setHint(R.string.voice_secret_kept)
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            listOf(apiKey, appKey, accessKey, resourceId).forEach { addView(it) }
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.voice_doubao_credentials)
            .setMessage(R.string.voice_doubao_credentials_hint)
            .setView(form)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                fun keep(input: EditText, key: String) =
                    input.text.toString().trim().ifEmpty { stored[key].orEmpty() }
                val fields = mapOf(
                    DoubaoCredentials.API_KEY to keep(apiKey, DoubaoCredentials.API_KEY),
                    DoubaoCredentials.APP_KEY to appKey.text.toString().trim(),
                    DoubaoCredentials.ACCESS_KEY to keep(accessKey, DoubaoCredentials.ACCESS_KEY),
                    DoubaoCredentials.RESOURCE_ID to resourceId.text.toString().trim()
                ).filterValues { it.isNotEmpty() }
                if (DoubaoCredentials.fromStore(fields).isComplete) {
                    credentials.write(DoubaoCredentials.PROVIDER, fields)
                } else {
                    ctx.toast(R.string.voice_doubao_credentials_incomplete)
                }
                render()
            }
            .setNeutralButton(R.string.voice_credentials_clear) { _, _ ->
                credentials.clear(DoubaoCredentials.PROVIDER)
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
