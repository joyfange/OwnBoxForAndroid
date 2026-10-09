package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.google.android.material.button.MaterialButton
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.dp2px

class ProfileSelectActivity : ThemedActivity(R.layout.layout_profile_select),
    ConfigurationFragment.SelectCallback {

    companion object {
        const val EXTRA_SELECTED = "selected"
        const val EXTRA_PROFILE_ID = "id"
        const val EXTRA_MULTI_SELECT = "multi_select"
        const val EXTRA_SELECTED_IDS = "selected_ids"
        const val EXTRA_EXCLUDE_IDS = "exclude_ids"
        const val EXTRA_PROFILE_IDS = "profile_ids"
    }

    private var isMultiSelect = false
    private val selectedIds = LinkedHashSet<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selected = intent.getParcelableExtra<ProxyEntity>(EXTRA_SELECTED)
        isMultiSelect = intent.getBooleanExtra(EXTRA_MULTI_SELECT, false)
        val initialSelected = intent.getLongArrayExtra(EXTRA_SELECTED_IDS)
        val excludeIds = intent.getLongArrayExtra(EXTRA_EXCLUDE_IDS)

        if (initialSelected != null) {
            selectedIds.addAll(initialSelected.toList())
        }

        val bottomBar = findViewById<View>(R.id.bottom_bar)
        if (isMultiSelect) {
            bottomBar?.isVisible = true
            findViewById<View>(R.id.fragment_holder)?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = dp2px(108)
            }
            bottomBar?.post {
                findViewById<View>(R.id.fragment_holder)?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = bottomBar.height
                }
            }
            updateBottomBar()
            fun bulk(action: ConfigurationFragment.BulkSelect) {
                (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment)
                    ?.bulkSelect(action)
            }
            findViewById<MaterialButton>(R.id.btn_select_all)?.setOnClickListener { bulk(ConfigurationFragment.BulkSelect.ALL) }
            findViewById<MaterialButton>(R.id.btn_select_invert)?.setOnClickListener { bulk(ConfigurationFragment.BulkSelect.INVERT) }
            findViewById<MaterialButton>(R.id.btn_select_clear)?.setOnClickListener { bulk(ConfigurationFragment.BulkSelect.CLEAR) }
            findViewById<MaterialButton>(R.id.btn_confirm)?.setOnClickListener {
                setResult(RESULT_OK, Intent().apply {
                    putExtra(EXTRA_PROFILE_IDS, selectedIds.toLongArray())
                })
                finish()
            }
        } else {
            bottomBar?.isVisible = false
        }

        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_holder,
                ConfigurationFragment(
                    select = true,
                    selectedItem = selected,
                    titleRes = if (isMultiSelect) R.string.select_multi_profile else R.string.select_profile,
                    multiSelect = isMultiSelect,
                    initialSelectedIds = initialSelected,
                    excludedIds = excludeIds
                )
            )
            .commitAllowingStateLoss()
    }

    private fun updateBottomBar() {
        val countText = findViewById<TextView>(R.id.selected_count_text)
        val btnConfirm = findViewById<MaterialButton>(R.id.btn_confirm)
        val count = selectedIds.size
        countText?.text = getString(R.string.selected_count, count)
        btnConfirm?.isEnabled = true
    }

    override fun returnProfile(profileId: Long) {
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_PROFILE_ID, profileId)
        })
        finish()
    }

    override fun onSelectionReplaced(selected: Set<Long>) {
        selectedIds.clear()
        selectedIds.addAll(selected)
        updateBottomBar()
    }

    override fun onProfileToggled(profileId: Long, isSelected: Boolean, totalSelected: Int) {
        if (isSelected) {
            selectedIds.add(profileId)
        } else {
            selectedIds.remove(profileId)
        }
        updateBottomBar()
    }
}