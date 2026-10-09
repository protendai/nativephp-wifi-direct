package com.protendai.wifidirect

import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

/**
 * Headless fragment that runs one runtime-permission request and removes
 * itself. A plugin can't override the host activity's
 * onRequestPermissionsResult, so the Activity Result API lives here instead.
 */
class PermissionFragment : Fragment() {

    private var permissions: Array<String> = emptyArray()
    private var onResult: ((Boolean) -> Unit)? = null
    private var launched = false

    private val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        onResult?.invoke(result.isNotEmpty() && result.values.all { it })
        parentFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
    }

    override fun onStart() {
        super.onStart()
        if (launched) return
        launched = true

        if (permissions.isEmpty()) {
            // Recreated after a configuration change: the request was lost with the old instance.
            parentFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
            return
        }
        launcher.launch(permissions)
    }

    companion object {
        private const val TAG = "protendai-wifidirect-permission"

        fun request(activity: FragmentActivity, permissions: Array<String>, onResult: (Boolean) -> Unit) {
            activity.runOnUiThread {
                val fragment = PermissionFragment().apply {
                    this.permissions = permissions
                    this.onResult = onResult
                }
                activity.supportFragmentManager.beginTransaction().add(fragment, TAG).commitAllowingStateLoss()
            }
        }
    }
}
