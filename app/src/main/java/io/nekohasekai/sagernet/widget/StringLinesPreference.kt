package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference

/**
 * A preference whose value is a newline-separated list kept as one string in the preference data store, edited
 * elsewhere (a picker activity) rather than in a dialog. Minimal OwnBox counterpart of ThroneForAndroid's
 * StringLinesPreference, used by the route rule editor for rule-sets and apps.
 */
class StringLinesPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle,
    defStyleRes: Int = 0,
) : Preference(context, attrs, defStyleAttr, defStyleRes) {

    /** The non-blank, trimmed lines currently stored under [getKey]. */
    val values: List<String>
        get() = (preferenceDataStore?.getString(key, null) ?: getPersistedString(null) ?: "")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** Re-renders the summary after the stored value changed behind the preference's back. */
    fun refresh() = notifyChanged()
}
