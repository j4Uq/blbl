package blbl.cat3399.core.ui

import android.content.Context
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.R as AppCompatR

object SingleChoiceDialog {
    fun show(
        context: Context,
        title: String,
        items: List<String>,
        checkedIndex: Int,
        negativeText: String = "取消",
        onPicked: (index: Int, label: String) -> Unit,
    ) {
        if (items.isEmpty()) {
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage("暂无可选项")
                .setNegativeButton(negativeText, null)
                .show()
            return
        }

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_single_choice_list, null, false)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(context)

        val safeChecked = checkedIndex.takeIf { it in items.indices } ?: 0
        val adapter =
            Adapter(
                items = items,
                checkedIndex = safeChecked,
                onPick = { index ->
                    val label = items.getOrNull(index) ?: return@Adapter
                    onPicked(index, label)
                },
            )
        recycler.adapter = adapter
        if (items.size > 8) {
            val maxHeight = (context.resources.displayMetrics.heightPixels * 0.62f).toInt()
            val lp =
                recycler.layoutParams
                    ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.height = maxHeight
            recycler.layoutParams = lp
        }

        val dialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(view)
                .setNegativeButton(negativeText, null)
                .create()

        adapter.onDismiss = { dialog.dismiss() }

        dialog.setOnShowListener {
            // Reduce default custom-panel padding to avoid a "too empty" look on TV / D-pad UI.
            dialog.findViewById<View>(android.R.id.custom)?.setPadding(0, 0, 0, 0)
            dialog.findViewById<View>(AppCompatR.id.custom)?.setPadding(0, 0, 0, 0)
            dialog.findViewById<View>(AppCompatR.id.customPanel)?.setPadding(0, 0, 0, 0)

            // Avoid overly wide dialogs on large screens (e.g. TV), which makes the list feel sparse.
            val maxWidthPx = dp(context, 520f)
            val targetWidthPx = (context.resources.displayMetrics.widthPixels * 0.90f).toInt().coerceAtMost(maxWidthPx)
            dialog.window?.setLayout(targetWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)

            recycler.post {
                recycler.scrollToPosition(safeChecked)
                (recycler.findViewHolderForAdapterPosition(safeChecked)?.itemView ?: recycler.getChildAt(0))?.requestFocus()
            }
        }
        dialog.show()
    }

    private fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    private class Adapter(
        private val items: List<String>,
        private var checkedIndex: Int,
        private val onPick: (Int) -> Unit,
    ) : RecyclerView.Adapter<Adapter.Vh>() {
        var onDismiss: (() -> Unit)? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_dialog_single_choice, parent, false)
            return Vh(view)
        }

        override fun onBindViewHolder(holder: Vh, position: Int) {
            val label = items.getOrNull(position).orEmpty()
            holder.bind(
                label = label,
                checked = position == checkedIndex,
                onClick = {
                    checkedIndex = position
                    notifyDataSetChanged()
                    onPick(position)
                    onDismiss?.invoke()
                },
            )
        }

        override fun getItemCount(): Int = items.size

        class Vh(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvLabel: TextView = itemView.findViewById(R.id.tv_label)
            private val tvCheck: TextView = itemView.findViewById(R.id.tv_check)

            fun bind(label: String, checked: Boolean, onClick: () -> Unit) {
                tvLabel.text = label
                tvCheck.visibility = if (checked) View.VISIBLE else View.GONE
                itemView.setOnClickListener { onClick() }
                itemView.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                        onClick()
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }
}
