package blbl.cat3399.feature.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.databinding.ItemSettingsLeftBinding

class SettingsLeftAdapter(
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<SettingsLeftAdapter.Vh>() {
    private val items = ArrayList<String>()
    private var selected = 0

    fun submit(list: List<String>, selected: Int) {
        items.clear()
        items.addAll(list)
        this.selected = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemSettingsLeftBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) = holder.bind(items[position], position == selected) {
        selected = position
        notifyDataSetChanged()
        onClick(position)
    }

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemSettingsLeftBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(text: String, selected: Boolean, onClick: () -> Unit) {
            binding.tvTitle.text = text
            binding.root.isSelected = selected
            binding.root.setOnClickListener { onClick() }
        }
    }
}
