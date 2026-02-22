package blbl.cat3399.core.ui

import android.content.Context
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.R as AppCompatR

object SingleChoiceDialog {
    private const val DESIRED_VISIBLE_ROWS = 7
    private const val MAX_DIALOG_HEIGHT_RATIO = 0.90f

    fun show(
        context: Context,
        title: String,
        items: List<String>,
        checkedIndex: Int,
        negativeText: String = "取消",
        neutralText: String? = null,
        onNeutral: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null,
        onPicked: (index: Int, label: String) -> Unit,
    ) {
        val dialogContext = context.userScaledContext()

        if (items.isEmpty()) {
            val builder =
                MaterialAlertDialogBuilder(dialogContext)
                .setTitle(title)
                .setMessage("暂无可选项")
            if (neutralText != null) {
                builder.setNeutralButton(neutralText) { _, _ -> onNeutral?.invoke() }
            }
            builder.setNegativeButton(negativeText) { _, _ -> onNegative?.invoke() }
            builder.show()
            return
        }

        val view = LayoutInflater.from(dialogContext).inflate(R.layout.dialog_single_choice_list, null, false)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(dialogContext)

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

        val dialog =
            MaterialAlertDialogBuilder(dialogContext)
                .setTitle(title)
                .setView(view)
                .apply {
                    if (neutralText != null) {
                        setNeutralButton(neutralText) { _, _ -> onNeutral?.invoke() }
                    }
                }
                .setNegativeButton(negativeText) { _, _ -> onNegative?.invoke() }
                .create()

        adapter.onDismiss = { dialog.dismiss() }

        dialog.setOnShowListener {
            // Reduce default custom-panel padding to avoid a "too empty" look on TV / D-pad UI.
            dialog.findViewById<View>(android.R.id.custom)?.setPadding(0, 0, 0, 0)
            dialog.findViewById<View>(AppCompatR.id.custom)?.setPadding(0, 0, 0, 0)
            dialog.findViewById<View>(AppCompatR.id.customPanel)?.setPadding(0, 0, 0, 0)

            // Avoid overly wide dialogs on large screens (e.g. TV), which makes the list feel sparse.
            val maxWidthPx = dp(dialogContext, 600f)
            val targetWidthPx = (dialogContext.resources.displayMetrics.widthPixels * 0.90f).toInt().coerceAtMost(maxWidthPx)
            dialog.window?.setLayout(targetWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)

            // Ensure the checked item is visible in the very first layout pass.
            recycler.scrollToPosition(safeChecked)

            // Apply list height + focus BEFORE the first visible draw to avoid a visible "flash" and focus jump.
            val decor = dialog.window?.decorView ?: recycler
            decor.viewTreeObserver.addOnPreDrawListener(
                object : ViewTreeObserver.OnPreDrawListener {
                    private var attempts: Int = 0

                    override fun onPreDraw(): Boolean {
                        if (!decor.viewTreeObserver.isAlive) return true
                        attempts++
                        val ok =
                            applyDesiredListHeight(
                                context = dialogContext,
                                dialog = dialog,
                                recycler = recycler,
                                itemCount = items.size,
                                targetWidthPx = targetWidthPx,
                                checkedIndex = safeChecked,
                            )
                        if (!ok) {
                            // Prevent a partially-laid-out dialog from being drawn (causes a height "flash").
                            // If something goes wrong, eventually allow drawing to avoid a hard lock.
                            if (attempts >= 3) {
                                decor.viewTreeObserver.removeOnPreDrawListener(this)
                                return true
                            }
                            return false
                        }
                        decor.viewTreeObserver.removeOnPreDrawListener(this)

                        // Cancel this draw, so the next traversal renders with the final height & focus.
                        return false
                    }
                },
            )
        }
        dialog.show()
    }

    private fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    private fun applyDesiredListHeight(
        context: Context,
        dialog: android.app.Dialog,
        recycler: RecyclerView,
        itemCount: Int,
        targetWidthPx: Int,
        checkedIndex: Int,
    ): Boolean {
        val count = itemCount.coerceAtLeast(0)
        if (count <= 0) return true
        val rows = DESIRED_VISIBLE_ROWS.coerceAtLeast(1).coerceAtMost(count)

        val dm = context.resources.displayMetrics
        val maxDialogHeightPx = (dm.heightPixels * MAX_DIALOG_HEIGHT_RATIO).toInt().coerceAtLeast(1)

        val child = recycler.getChildAt(0)
        val childLp = child?.layoutParams as? ViewGroup.MarginLayoutParams
        val measuredRowHeightPx =
            child
                ?.height
                ?.takeIf { it > 0 }
                ?.let { it + (childLp?.topMargin ?: 0) + (childLp?.bottomMargin ?: 0) }

        if (measuredRowHeightPx == null) return false

        val rowHeightPx = measuredRowHeightPx.coerceAtLeast(1)
        val desiredListHeightPx = (rowHeightPx * rows) + recycler.paddingTop + recycler.paddingBottom

        val lp =
            recycler.layoutParams
                ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (lp.height != desiredListHeightPx) {
            lp.height = desiredListHeightPx
            recycler.layoutParams = lp
        }

        // Dialog window size is decided at show-time. After we change list height, force the window
        // to re-layout so the dialog can actually grow (otherwise you may still see only ~3 rows).
        val currentWindowHeight = dialog.window?.decorView?.height ?: 0
        val currentListHeight = recycler.height.takeIf { it > 0 } ?: 0
        val nonListHeight = (currentWindowHeight - currentListHeight).coerceAtLeast(0)
        val desiredWindowHeight = (nonListHeight + desiredListHeightPx).coerceAtMost(maxDialogHeightPx).coerceAtLeast(1)
        dialog.window?.setLayout(targetWidthPx, desiredWindowHeight)

        recycler.scrollToPosition(checkedIndex)
        (recycler.findViewHolderForAdapterPosition(checkedIndex)?.itemView ?: recycler.getChildAt(0))?.requestFocus()
        return true
    }

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
