package blbl.cat3399.feature.dynamic

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.databinding.ItemFollowingBinding

class FollowingAdapter(
    private val onClick: (FollowingUi) -> Unit,
) : RecyclerView.Adapter<FollowingAdapter.Vh>() {
    data class FollowingUi(
        val mid: Long,
        val name: String,
        val avatarUrl: String?,
        val isAll: Boolean = false,
    )

    private val items = ArrayList<FollowingUi>()
    private var selectedMid: Long = MID_ALL

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<FollowingUi>, selected: Long = MID_ALL) {
        val prevSelected = selectedMid
        val sameItems =
            items.size == list.size &&
                items.indices.all { idx -> items[idx].mid == list[idx].mid }

        selectedMid = selected
        if (sameItems) {
            val prevPos = items.indexOfFirst { it.mid == prevSelected }
            val newPos = items.indexOfFirst { it.mid == selectedMid }
            if (prevPos >= 0) notifyItemChanged(prevPos)
            if (newPos >= 0 && newPos != prevPos) notifyItemChanged(newPos)
            return
        }

        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].mid

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemFollowingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) = holder.bind(items[position], items[position].mid == selectedMid, onClick)

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemFollowingBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FollowingUi, selected: Boolean, onClick: (FollowingUi) -> Unit) {
            binding.tvName.text = item.name
            if (item.isAll) {
                binding.ivAvatar.setImageResource(blbl.cat3399.R.drawable.ic_all)
                binding.ivAvatar.imageTintList =
                    android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(binding.root.context, blbl.cat3399.R.color.blbl_text),
                    )
            } else {
                binding.ivAvatar.imageTintList = null
                ImageLoader.loadInto(binding.ivAvatar, ImageUrl.avatar(item.avatarUrl))
            }
            binding.vSelected.visibility = if (selected) android.view.View.VISIBLE else android.view.View.GONE
            binding.root.isSelected = selected
            binding.tvName.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (selected) blbl.cat3399.R.color.blbl_text else blbl.cat3399.R.color.blbl_text_secondary,
                ),
            )
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        const val MID_ALL: Long = -1L
    }
}
