package site.ragdollp.virusscanner.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import site.ragdollp.virusscanner.databinding.ItemDetectedAppBinding
import site.ragdollp.virusscanner.model.AppScanResult

class DetectionAdapter(
    private val items: List<AppScanResult>,
    private val onUninstallOne: (AppScanResult) -> Unit
) : RecyclerView.Adapter<DetectionAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemDetectedAppBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDetectedAppBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val b = holder.binding
        b.appIcon.setImageDrawable(item.icon)
        b.appName.text = item.label
        b.packageName.text = item.packageName
        b.permissionList.text = buildString {
            append(item.suspiciousPermissions.joinToString("\n") { "・$it" })
            if (item.extraFindings.isNotEmpty()) {
                if (item.suspiciousPermissions.isNotEmpty()) append("\n")
                append(item.extraFindings.joinToString("\n") { "・$it" })
            }
        }
        b.uninstallButton.setOnClickListener { onUninstallOne(item) }
    }

    override fun getItemCount(): Int = items.size
}
