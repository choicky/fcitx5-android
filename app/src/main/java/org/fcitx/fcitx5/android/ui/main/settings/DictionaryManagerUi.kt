/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.ui.common.OnItemChangedListener
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import splitties.resources.styledColor

/** Sectioned dictionary list. Header positions never address the source list. */
@SuppressLint("NotifyDataSetChanged")
internal class DictionaryManagerUi(
    private val ctx: Context,
    initialEntries: List<PinyinDictionary>,
    private val model: MainViewModel,
    private val toggle: (PinyinDictionary, Boolean) -> Unit,
    private val detail: (PinyinDictionary) -> Unit,
    add: () -> Unit,
) : RecyclerView.Adapter<DictionaryManagerUi.Holder>() {
    private val source = initialEntries.toMutableList()
    val entries: List<PinyinDictionary> get() = source
    private val selected = mutableSetOf<PinyinDictionary>()
    private var listener: OnItemChangedListener<PinyinDictionary>? = null
    private var back: OnBackPressedCallback? = null
    private var multiselect = false
    private var rows = emptyList<DictionaryPresentation.Row?>()
    private var sections = emptyList<DictionaryPresentation.Section>()
    val root = FrameLayout(ctx)
    private val list = RecyclerView(ctx).apply {
        layoutManager = LinearLayoutManager(ctx)
        adapter = this@DictionaryManagerUi
        clipToPadding = false
    }
    private val fab = FloatingActionButton(ctx).apply {
        setImageResource(R.drawable.ic_baseline_plus_24)
        contentDescription = ctx.getString(R.string.add)
        setOnClickListener { add() }
    }

    init {
        root.setBackgroundColor(ctx.styledColor(android.R.attr.colorBackground))
        root.addView(list, FrameLayout.LayoutParams(-1, -1))
        root.addView(fab, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(16), dp(16), dp(16), dp(16))
        })
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            list.setPadding(0, 0, 0, bottom + dp(88))
            (fab.layoutParams as FrameLayout.LayoutParams).also {
                it.bottomMargin = bottom + dp(16)
                fab.layoutParams = it
            }
            insets
        }
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun getSwipeDirs(rv: RecyclerView, holder: RecyclerView.ViewHolder): Int {
                val row = rows.getOrNull(holder.bindingAdapterPosition)
                return if (!multiselect && row?.removable == true) ItemTouchHelper.LEFT else 0
            }
            override fun onMove(rv: RecyclerView, h: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                rows.getOrNull(holder.bindingAdapterPosition)?.let { removeItem(indexItem(it.dictionary)) }
            }
            override fun onChildDraw(c: Canvas, r: RecyclerView, h: RecyclerView.ViewHolder,
                dx: Float, dy: Float, state: Int, active: Boolean) {
                if (dx < 0) {
                    val v = h.itemView
                    ColorDrawable(ctx.getColor(R.color.red_400)).apply {
                        setBounds((v.right + dx).toInt(), v.top, v.right, v.bottom); draw(c)
                    }
                    ctx.getDrawable(R.drawable.ic_baseline_delete_24)?.apply {
                        setTint(ctx.styledColor(android.R.attr.colorBackground))
                        val top = v.top + (v.height - dp(24)) / 2
                        setBounds(v.right - dp(40), top, v.right - dp(16), top + dp(24)); draw(c)
                    }
                }
                super.onChildDraw(c, r, h, dx, dy, state, active)
            }
        }).attachToRecyclerView(list)
        refresh()
    }

    private fun dp(value: Int) = (value * ctx.resources.displayMetrics.density).toInt()
    private fun refresh() {
        val mapped = DictionaryPresentation.rows(entries)
        val flattened = mutableListOf<DictionaryPresentation.Row?>()
        val groups = mutableListOf<DictionaryPresentation.Section>()
        DictionaryPresentation.Section.entries.forEach { section ->
            mapped.filter { it.section == section }.takeIf { it.isNotEmpty() }?.let { children ->
                flattened.add(null); groups.add(section)
                children.forEach { flattened.add(it); groups.add(section) }
            }
        }
        rows = flattened; sections = groups; notifyDataSetChanged()
    }
    fun refreshState() = refresh()
    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = if (rows[position] == null) 0 else 1
    class Holder(val layout: LinearLayout) : RecyclerView.ViewHolder(layout)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = RecyclerView.LayoutParams(-1, -2)
    })
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val layout = holder.layout; layout.removeAllViews(); layout.setOnClickListener(null)
        layout.isClickable = false; layout.background = null; layout.minimumHeight = 0
        layout.setPadding(dp(16), dp(12), dp(12), dp(12))
        val row = rows[position]
        if (row == null) {
            layout.addView(TextView(ctx).apply {
                text = ctx.getString(if (sections[position] == DictionaryPresentation.Section.Builtin)
                    R.string.dictionary_section_builtin else R.string.dictionary_section_imported)
                setTextAppearance(android.R.style.TextAppearance_Material_Body2)
                ViewCompat.setAccessibilityHeading(this, true)
            }); return
        }
        layout.minimumHeight = dp(72)
        layout.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_baseline_library_books_24)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ctx.styledColor(android.R.attr.colorControlNormal))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            text = row.name; setTextAppearance(android.R.style.TextAppearance_Material_Body1)
        })
        texts.addView(TextView(ctx).apply {
            text = row.dictionary.file.name; setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        })
        layout.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val action = row.tap(multiselect)
        if (multiselect && row.removable) {
            layout.addView(CheckBox(ctx).apply {
                contentDescription = row.name; isChecked = row.dictionary in selected
                minimumWidth = dp(48); minimumHeight = dp(48)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(row.dictionary) else selected.remove(row.dictionary)
                }
            })
        } else if (!multiselect && row.enabled != null) {
            layout.addView(SwitchCompat(ctx).apply {
                contentDescription = ctx.getString(R.string.dictionary_use_named, row.name)
                isChecked = row.enabled; minimumHeight = dp(48); minimumWidth = dp(48)
                setOnCheckedChangeListener { _, checked -> toggle(row.dictionary, checked) }
            })
        } else if (!multiselect && row.required) {
            layout.addView(TextView(ctx).apply { setText(R.string.dictionary_required) })
        } else if (!multiselect && row.manageable) {
            layout.addView(TextView(ctx).apply { text = "›"; textSize = 24f })
        }
        if (action != DictionaryPresentation.Tap.None) {
            layout.setBackgroundResource(android.R.drawable.list_selector_background); layout.isClickable = true
            layout.setOnClickListener {
                when (action) {
                    DictionaryPresentation.Tap.Detail -> detail(row.dictionary)
                    DictionaryPresentation.Tap.Select -> {
                        if (!selected.add(row.dictionary)) selected.remove(row.dictionary)
                        holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { notifyItemChanged(it) }
                    }
                    DictionaryPresentation.Tap.None -> Unit
                }
            }
        }
    }
    fun addOnItemChangedListener(value: OnItemChangedListener<PinyinDictionary>) { listener = value }
    fun removeItemChangedListener() { listener = null; back?.remove() }
    fun indexItem(item: PinyinDictionary) = source.indexOf(item)
    fun addItem(idx: Int = source.size, item: PinyinDictionary) {
        source.add(idx, item); listener?.onItemAdded(idx, item); refresh()
        Snackbar.make(root, ctx.getString(R.string.added_x, item.name), Snackbar.LENGTH_SHORT).show()
    }
    fun updateItem(idx: Int, item: PinyinDictionary) {
        val old = source.set(idx, item); listener?.onItemUpdated(idx, old, item); refresh()
    }
    fun removeItem(idx: Int) {
        if (idx !in source.indices) return
        val item = source.removeAt(idx); selected.remove(item); listener?.onItemRemoved(idx, item); refresh()
        Snackbar.make(root, ctx.getString(R.string.removed_x, item.name), Snackbar.LENGTH_SHORT).show()
    }
    fun enterMultiSelect(dispatcher: OnBackPressedDispatcher) {
        if (multiselect) return; multiselect = true; fab.hide(); model.toolbarButton.value = MainViewModel.ButtonMode.DELETE
        back = object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = exitMultiSelect() }
            .also { dispatcher.addCallback(it) }; refresh()
    }
    fun exitMultiSelect() {
        if (!multiselect) return; back?.remove(); multiselect = false; selected.clear(); fab.show()
        model.toolbarButton.value = MainViewModel.ButtonMode.EDIT; refresh()
    }
    fun deleteSelected() {
        if (!multiselect) return
        val indexed = selected.mapNotNull { entry -> indexItem(entry).takeIf { it >= 0 }?.let { it to entry } }
            .sortedByDescending { it.first }
        indexed.forEach { source.removeAt(it.first) }; listener?.onItemRemovedBatch(indexed); refresh()
        if (indexed.isNotEmpty()) Snackbar.make(root, ctx.getString(R.string.removed_n_items, indexed.size), Snackbar.LENGTH_SHORT).show()
    }
}
