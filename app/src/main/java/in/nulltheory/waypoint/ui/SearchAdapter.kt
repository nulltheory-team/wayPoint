package `in`.nulltheory.waypoint.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import `in`.nulltheory.waypoint.databinding.ItemPlaceBinding
import `in`.nulltheory.waypoint.net.Place

/** Photon results. No animations: the list must not fade or shuffle under a laggy pointer. */
class SearchAdapter(private val onPick: (Place) -> Unit) :
    RecyclerView.Adapter<SearchAdapter.PlaceHolder>() {

    private val items = mutableListOf<Place>()

    class PlaceHolder(val binding: ItemPlaceBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(places: List<Place>) {
        items.clear()
        items += places
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceHolder =
        PlaceHolder(
            ItemPlaceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: PlaceHolder, position: Int) {
        val place = items[position]
        holder.binding.placeName.text = place.name
        holder.binding.placeDetail.text = place.detail
        holder.binding.placeDetail.visibility =
            if (place.detail.isBlank()) ViewGroup.GONE else ViewGroup.VISIBLE
        holder.binding.root.setOnClickListener { onPick(place) }
    }

    override fun getItemCount(): Int = items.size
}
