package com.protendai.wifidirect

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

/**
 * Headless fragment that runs one runtime-permission request and removes
 * itself. A plugin can't override the host activity's
 * onRequestPermissionsResult, so the Activity Result API lives here instead.
 *
 * The request survives the activity being recreated: the permissions travel
 * in the fragment arguments and "already launched" in the saved state, so a
 * recreated fragment waits for the pending result instead of dropping it.
 * Every way out answers with PermissionResult through WifiDirectManager.
 */
class PermissionFragment : Fragment() {

    private var launched = false
    private var rationaleBefore = false
    internal var answered = false
        private set

    private val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // The result map can be empty when another request was already showing;
        // WifiDirectManager reads the real grant state either way.
        answer(interrupted = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launched = savedInstanceState?.getBoolean(KEY_LAUNCHED) ?: false
        rationaleBefore = savedInstanceState?.getBoolean(KEY_RATIONALE) ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LAUNCHED, launched)
        outState.putBoolean(KEY_RATIONALE, rationaleBefore)
    }

    override fun onStart() {
        super.onStart()
        if (launched) return
        launched = true

        val permissions = arguments?.getStringArray(KEY_PERMISSIONS)
        if (permissions.isNullOrEmpty()) {
            answer(interrupted = true)
            return
        }

        rationaleBefore = permissions.any { shouldShowRequestPermissionRationale(it) }
        try {
            launcher.launch(permissions)
        } catch (e: Exception) {
            answer(interrupted = true)
        }
    }

    private fun answer(interrupted: Boolean) {
        if (answered) return
        answered = true
        activity?.let { WifiDirectManager.reportPermission(it, rationaleBefore, interrupted) }
        if (isAdded) parentFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
    }

    companion object {
        private const val TAG = "protendai-wifidirect-permission"
        private const val KEY_PERMISSIONS = "permissions"
        private const val KEY_LAUNCHED = "launched"
        private const val KEY_RATIONALE = "rationaleBefore"

        fun request(activity: FragmentActivity, permissions: Array<String>) {
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val fragments = activity.supportFragmentManager
                // One request at a time: a pending one answers for both calls.
                val existing = fragments.findFragmentByTag(TAG) as? PermissionFragment
                if (existing != null && !existing.answered) return@runOnUiThread

                val fragment = PermissionFragment().apply {
                    arguments = Bundle().apply { putStringArray(KEY_PERMISSIONS, permissions) }
                }
                val transaction = fragments.beginTransaction()
                existing?.let { transaction.remove(it) }
                transaction.add(fragment, TAG).commitAllowingStateLoss()
            }
        }
    }
}
