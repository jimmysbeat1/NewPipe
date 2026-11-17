/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.DialogTmdbKeyBinding
import org.schabi.newpipe.util.external_communication.ShareUtils

/**
 * Asks for the TMDB key of the user, checks it with TMDB and stores it encrypted. The key is
 * never shown again after it was saved: the dialog only says that one is stored.
 */
class TmdbKeyDialog : DialogFragment() {
    private var binding: DialogTmdbKeyBinding? = null
    private val scope: CoroutineScope = MainScope()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val binding = DialogTmdbKeyBinding.inflate(requireActivity().layoutInflater).also { this.binding = it }
        val credentials = TmdbCredentials.get(context)

        val saved = credentials.has()
        binding.keyStatus.isVisible = saved
        binding.keyStatus.setText(R.string.discover_key_saved)
        binding.keyRemove.isVisible = saved
        binding.keyRemove.setOnClickListener {
            credentials.clear()
            Toast.makeText(context, R.string.discover_key_removed, Toast.LENGTH_SHORT).show()
            finish()
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.discover_key_title)
            .setView(binding.root)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.discover_setup_get_key, null)
            .setPositiveButton(R.string.discover_key_check, null)
            .create()
        // set here instead of in the builder, so that these buttons do not close the dialog
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { check() }
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                ShareUtils.openUrlInBrowser(context, ProviderLinks.TMDB_KEY_PAGE)
            }
        }
        return dialog
    }

    private fun status(text: Int) {
        val binding = binding ?: return
        binding.keyStatus.setText(text)
        binding.keyStatus.isVisible = true
    }

    private fun setBusy(busy: Boolean) {
        (dialog as? AlertDialog)?.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = !busy
        binding?.keyInput?.isEnabled = !busy
    }

    private fun check() {
        val binding = binding ?: return
        val context = requireContext().applicationContext
        val credential = TmdbCredential.parse(binding.keyInput.text?.toString())
        if (credential == null) {
            status(R.string.discover_key_format)
            return
        }
        status(R.string.discover_key_checking)
        setBusy(true)
        scope.launch {
            val problem = withContext(Dispatchers.IO) {
                try {
                    Discover.client(context).validate(credential)
                    null
                } catch (e: TmdbException) {
                    e.problem
                }
            }
            when (problem) {
                null -> {
                    val credentials = TmdbCredentials.get(context)
                    withContext(Dispatchers.IO) { credentials.set(credential) }
                    val message = if (credentials.persistent) {
                        R.string.discover_key_accepted
                    } else {
                        R.string.discover_key_not_persistent
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    finish()
                }

                TmdbProblem.INVALID_KEY -> {
                    setBusy(false)
                    status(R.string.discover_key_rejected)
                }

                else -> {
                    setBusy(false)
                    status(R.string.discover_key_unreachable)
                }
            }
        }
    }

    /** Tells the Movies tab that the key changed and closes the dialog. */
    private fun finish() {
        parentFragmentManager.setFragmentResult(RESULT, Bundle.EMPTY)
        dismissAllowingStateLoss()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val RESULT = "tmdb_key_changed"
    }
}
