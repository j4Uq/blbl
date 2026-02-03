package blbl.cat3399.core.ui

import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView

internal object RecyclerViewDpadEdgeGuard {
    fun install(recyclerView: RecyclerView) {
        recyclerView.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { v, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        val holder = recyclerView.findContainingViewHolder(v) ?: return@setOnKeyListener false
                        val pos =
                            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                                ?: return@setOnKeyListener false

                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (pos == 0 && !recyclerView.canScrollVertically(-1)) return@setOnKeyListener true
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val last = (recyclerView.adapter?.itemCount ?: 0) - 1
                                if (pos == last && !recyclerView.canScrollVertically(1)) return@setOnKeyListener true
                                false
                            }

                            else -> false
                        }
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    view.setOnKeyListener(null)
                }
            },
        )
    }
}

