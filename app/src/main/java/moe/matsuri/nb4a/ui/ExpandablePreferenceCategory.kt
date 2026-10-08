package moe.matsuri.nb4a.ui

import android.content.Context
import android.content.res.ColorStateList
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme
import com.google.android.material.card.MaterialCardView

class ExpandablePreferenceCategory @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceCategoryStyle,
    defStyleRes: Int = 0,
) : PreferenceCategory(context, attrs, defStyleAttr, defStyleRes) {

    companion object {
        private val expandedStateMap = HashMap<String, Boolean>()

        /** 卡片样式在每次绑定都要用，缓存起来避免每次展开/收起都在主线程查数据库；样式变更时清掉。 */
        @Volatile
        var cachedCardStyle: Int? = null

        fun isCategoryExpanded(key: String?): Boolean {
            if (key == null) return false
            return expandedStateMap[key] ?: false
        }

        fun setCategoryExpanded(key: String?, expanded: Boolean) {
            if (key == null) return
            expandedStateMap[key] = expanded
        }
    }

    private val childVisibilityRules = mutableMapOf<String, () -> Boolean>()

    var isExpanded: Boolean = false
        private set

    private var arrowAnimating = false

    init {
        isSelectable = true
        layoutResource = R.layout.layout_expandable_category
    }

    override fun onAttached() {
        super.onAttached()
        if (key != null && expandedStateMap.containsKey(key)) {
            isExpanded = expandedStateMap[key] == true
            applyChildrenVisibility()
        }
    }

    override fun setKey(key: String?) {
        super.setKey(key)
        if (key != null && expandedStateMap.containsKey(key)) {
            isExpanded = expandedStateMap[key] == true
            applyChildrenVisibility()
        }
    }

    override fun isSelectable(): Boolean = true
    override fun isEnabled(): Boolean = true

    override fun addPreference(preference: Preference): Boolean {
        val result = super.addPreference(preference)
        val currentExpanded = if (key != null && expandedStateMap.containsKey(key)) {
            expandedStateMap[key] == true
        } else {
            isExpanded
        }
        if (!currentExpanded) {
            preference.isVisible = false
        }
        return result
    }

    fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        if (key != null) {
            expandedStateMap[key] = expanded
        }
        applyChildrenVisibility()
        notifyChanged()
    }

    fun toggle() {
        setExpanded(!isExpanded)
    }

    fun setChildVisibilityRule(key: String, rule: () -> Boolean) {
        childVisibilityRules[key] = rule
        if (isExpanded) {
            findPreference<Preference>(key)?.isVisible = rule()
        }
    }

    fun updateChildVisibility(key: String) {
        if (isExpanded) {
            val rule = childVisibilityRules[key]
            findPreference<Preference>(key)?.isVisible = rule?.invoke() ?: true
        }
    }

    fun applyChildrenVisibility() {
        for (i in 0 until preferenceCount) {
            val child = getPreference(i)
            val shouldShow = if (isExpanded) {
                childVisibilityRules[child.key]?.invoke() ?: true
            } else {
                false
            }
            child.isVisible = shouldShow
        }
    }

    override fun onClick() {
        super.onClick()
        toggle()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val primaryColor = Theme.getPrimaryColor(context)

        val card = holder.itemView as? MaterialCardView
        if (card != null) {
            val ctx = card.context
            val surface = ctx.getColorAttr(R.attr.colorSurface)
            card.setCardBackgroundColor(surface)
            val style = cachedCardStyle ?: DataStore.profileCardStyle.also { cachedCardStyle = it }
            if (style == 1) {
                card.cardElevation = 0f
                card.strokeWidth = ctx.resources.getDimensionPixelSize(R.dimen.card_stroke_width)
                card.strokeColor = ctx.getColor(R.color.card_stroke)
            } else {
                card.cardElevation = ctx.resources.getDimension(R.dimen.profile_card_elevation_classic)
                card.strokeWidth = 0
            }
        }

        val textPrimary = context.getColorAttr(android.R.attr.textColorPrimary)
        val textSecondary = context.getColorAttr(android.R.attr.textColorSecondary)

        val titleView = holder.findViewById(android.R.id.title) as? TextView
        titleView?.setTextColor(if (isExpanded) primaryColor else textPrimary)

        val arrow = holder.findViewById(R.id.category_arrow) as? ImageView
        if (arrow != null) {
            val arrowColor = if (isExpanded) primaryColor else textSecondary
            arrow.imageTintList = ColorStateList.valueOf(arrowColor)
            // 一个向下箭头靠旋转表示展开/收起：点击时立即转动，不再等列表重新绑定后换图
            arrow.setImageResource(R.drawable.ic_baseline_keyboard_arrow_down_24)
            if (!arrowAnimating) arrow.rotation = if (isExpanded) 180f else 0f
        }

        holder.itemView.isClickable = true
        holder.itemView.isFocusable = true
        holder.itemView.setOnClickListener {
            val target = !isExpanded
            // 先给即时反馈（箭头、标题颜色），再改子项可见性
            titleView?.setTextColor(if (target) primaryColor else textPrimary)
            if (arrow != null) {
                arrow.imageTintList = ColorStateList.valueOf(if (target) primaryColor else textSecondary)
                arrowAnimating = true
                arrow.animate().cancel()
                arrow.animate()
                    .rotation(if (target) 180f else 0f)
                    .setDuration(180L)
                    .withEndAction { arrowAnimating = false }
                    .start()
            }
            setExpanded(target)
        }
    }

    override fun onSaveInstanceState(): Parcelable {
        val myState = SavedState(super.onSaveInstanceState())
        myState.isExpanded = isExpanded
        return myState
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.superState)
        setExpanded(state.isExpanded)
    }

    /**
     * Self-contained saved state. The previous version extended
     * Preference.BaseSavedState, whose Parcel constructor reads the nested
     * super state (androidx.preference.PreferenceGroup$SavedState) with the
     * boot class loader. When the system restores the activity (freeform
     * window, process death, MIUI) that lookup fails with
     * BadParcelableException and the app crashes on launch.
     * Here the nested state is always read with the app's class loader, and a
     * state that still cannot be read is dropped instead of crashing.
     */
    private class SavedState : Parcelable {
        val superState: Parcelable?
        var isExpanded: Boolean = false

        constructor(superState: Parcelable?) {
            this.superState = superState
        }

        constructor(source: Parcel, loader: ClassLoader?) {
            val cl = loader ?: SavedState::class.java.classLoader
            superState = try {
                @Suppress("DEPRECATION")
                source.readParcelable(cl)
            } catch (e: Exception) {
                null
            }
            isExpanded = try {
                source.readInt() == 1
            } catch (e: Exception) {
                false
            }
        }

        override fun describeContents(): Int = 0

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeParcelable(superState, flags)
            dest.writeInt(if (isExpanded) 1 else 0)
        }

        companion object CREATOR : Parcelable.ClassLoaderCreator<SavedState> {
            override fun createFromParcel(source: Parcel, loader: ClassLoader?): SavedState =
                SavedState(source, loader)

            override fun createFromParcel(source: Parcel): SavedState =
                SavedState(source, null)

            override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
        }
    }
}

