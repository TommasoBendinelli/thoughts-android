// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityManageReviewColumnsBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemManageReviewColumnBinding

class ManageReviewColumnsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManageReviewColumnsBinding
    private lateinit var adapter: ColumnsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManageReviewColumnsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = getColor(R.color.review_columns_blue)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()

        val initial = savedInstanceState?.getStringArrayList(STATE_ACTIVE)
            ?: intent.getStringArrayListExtra(EXTRA_SELECTED_COLUMNS).orEmpty()
        val active = initial.mapNotNull { name -> ReviewCardBrowserColumn.entries.firstOrNull { it.name == name } }
            .distinct().ifEmpty { ReviewCardBrowserColumn.entries.filter { it in ReviewCardBrowserColumn.defaults } }
        val availableOrder = (savedInstanceState?.getStringArrayList(STATE_AVAILABLE)
            ?: intent.getStringArrayListExtra(EXTRA_COLUMN_ORDER))
            ?.mapNotNull { name -> ReviewCardBrowserColumn.entries.firstOrNull { it.name == name } }
            ?.distinct().orEmpty()
        val available = availableOrder.filter { it !in active } +
            ReviewCardBrowserColumn.entries.filter { it !in active && it !in availableOrder }
        val previews = intent.getStringArrayListExtra(EXTRA_PREVIEWS).orEmpty()
        adapter = ColumnsAdapter(active, available) { column ->
            previews.getOrNull(ReviewCardBrowserColumn.entries.indexOf(column)).orEmpty()
        }
        val dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0,
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean = adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
                if (adapter.isColumnPosition(viewHolder.bindingAdapterPosition))
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                else makeMovementFlags(0, 0)

            override fun isLongPressDragEnabled(): Boolean = false
        })
        adapter.dragHelper = dragHelper
        binding.manageColumnsList.layoutManager = LinearLayoutManager(this)
        binding.manageColumnsList.adapter = adapter
        dragHelper.attachToRecyclerView(binding.manageColumnsList)
        binding.manageColumnsBack.setOnClickListener { finish() }
        binding.manageColumnsDone.setOnClickListener {
            setResult(Activity.RESULT_OK, Intent()
                .putStringArrayListExtra(EXTRA_SELECTED_COLUMNS, ArrayList(adapter.active.map { it.name }))
                .putStringArrayListExtra(EXTRA_COLUMN_ORDER,
                    ArrayList((adapter.active + adapter.available).map { it.name })))
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(STATE_ACTIVE, ArrayList(adapter.active.map { it.name }))
        outState.putStringArrayList(STATE_AVAILABLE, ArrayList(adapter.available.map { it.name }))
        super.onSaveInstanceState(outState)
    }

    private class ColumnsAdapter(
        initialActive: List<ReviewCardBrowserColumn>,
        initialAvailable: List<ReviewCardBrowserColumn>,
        private val preview: (ReviewCardBrowserColumn) -> String,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        val active = initialActive.toMutableList()
        val available = initialAvailable.toMutableList()
        lateinit var dragHelper: ItemTouchHelper

        override fun getItemCount(): Int = active.size + available.size + 2

        override fun getItemViewType(position: Int): Int =
            if (position == 0 || position == active.size + 1) HEADER else COLUMN

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == HEADER) {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_manage_review_column_section, parent, false)
                HeaderHolder(view)
            } else {
                ColumnHolder(ItemManageReviewColumnBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false,
                ))
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is HeaderHolder) {
                holder.label.setText(if (position == 0) R.string.review_columns_active else R.string.review_columns_available)
                return
            }
            holder as ColumnHolder
            val isActive = position in 1..active.size
            val column = if (isActive) active[position - 1] else available[position - active.size - 2]
            val context = holder.itemView.context
            val label = context.getString(column.labelRes)
            holder.binding.manageColumnName.text = label
            holder.binding.manageColumnPreview.text = preview(column)
            holder.binding.manageColumnPreview.visibility = if (holder.binding.manageColumnPreview.text.isBlank()) View.GONE else View.VISIBLE
            holder.binding.manageColumnToggle.text = if (isActive) "−" else "+"
            holder.binding.manageColumnToggle.contentDescription = context.getString(
                if (isActive) R.string.review_columns_remove else R.string.review_columns_add, label,
            )
            holder.binding.manageColumnToggle.isEnabled = !isActive || active.size > 1
            holder.binding.manageColumnToggle.setOnClickListener {
                if (isActive && active.size > 1) {
                    active.remove(column)
                    available.add(0, column)
                    notifyDataSetChanged()
                } else if (!isActive) {
                    available.remove(column)
                    active.add(column)
                    notifyDataSetChanged()
                }
            }
            holder.binding.manageColumnDrag.contentDescription = context.getString(R.string.review_columns_drag, label)
            @Suppress("ClickableViewAccessibility")
            holder.binding.manageColumnDrag.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) dragHelper.startDrag(holder)
                false
            }
        }

        fun isColumnPosition(position: Int): Boolean =
            position in 1..active.size || position in (active.size + 2) until itemCount

        fun move(from: Int, to: Int): Boolean {
            val inActive = from in 1..active.size && to in 1..active.size
            val inAvailable = from in (active.size + 2) until itemCount &&
                to in (active.size + 2) until itemCount
            if (!inActive && !inAvailable) return false
            val list = if (inActive) active else available
            val offset = if (inActive) 1 else active.size + 2
            list.add(to - offset, list.removeAt(from - offset))
            notifyItemMoved(from, to)
            return true
        }

        private class HeaderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val label: TextView = itemView.findViewById(R.id.manageColumnSection)
        }

        private class ColumnHolder(val binding: ItemManageReviewColumnBinding) : RecyclerView.ViewHolder(binding.root)

        private companion object {
            const val HEADER = 0
            const val COLUMN = 1
        }
    }

    companion object {
        const val EXTRA_SELECTED_COLUMNS = "selected_columns"
        const val EXTRA_COLUMN_ORDER = "column_order"
        private const val EXTRA_PREVIEWS = "column_previews"
        private const val STATE_ACTIVE = "active_columns"
        private const val STATE_AVAILABLE = "available_columns"

        fun intent(
            context: Context,
            selected: List<ReviewCardBrowserColumn>,
            order: List<ReviewCardBrowserColumn>,
            previews: List<String>,
        ): Intent = Intent(context, ManageReviewColumnsActivity::class.java)
            .putStringArrayListExtra(EXTRA_SELECTED_COLUMNS, ArrayList(selected.map { it.name }))
            .putStringArrayListExtra(EXTRA_COLUMN_ORDER, ArrayList(order.map { it.name }))
            .putStringArrayListExtra(EXTRA_PREVIEWS, ArrayList(previews))
    }
}
