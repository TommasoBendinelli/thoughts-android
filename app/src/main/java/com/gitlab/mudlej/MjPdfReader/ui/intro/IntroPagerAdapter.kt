// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.intro

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.databinding.IntroFeatureRowItemBinding
import com.gitlab.mudlej.MjPdfReader.databinding.IntroPageItemBinding

class IntroPage(
    @LayoutRes val artworkLayoutRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    val features: List<IntroFeature>,
    val showMadeWithLove: Boolean = false,
)

class IntroFeature(
    @DrawableRes val iconRes: Int,
    @StringRes val textRes: Int,
)

class IntroPagerAdapter(
    private val pages: List<IntroPage>,
) : RecyclerView.Adapter<IntroPagerAdapter.PageViewHolder>() {

    class PageViewHolder(val binding: IntroPageItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return PageViewHolder(IntroPageItemBinding.inflate(inflater, parent, false))
    }

    override fun getItemCount(): Int = pages.size

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val page = pages[position]
        bindArtwork(holder.binding, page)
        holder.binding.pageTitle.setText(page.titleRes)
        holder.binding.pageBody.setText(page.bodyRes)
        bindFeatures(holder.binding, page)
        holder.binding.madeWithLove.visibility =
            if (page.showMadeWithLove) View.VISIBLE else View.GONE
    }

    private fun bindArtwork(binding: IntroPageItemBinding, page: IntroPage) {
        binding.artworkContainer.removeAllViews()
        LayoutInflater.from(binding.artworkContainer.context).inflate(
            page.artworkLayoutRes,
            binding.artworkContainer,
            true,
        )
    }

    private fun bindFeatures(binding: IntroPageItemBinding, page: IntroPage) {
        val featuresLayout = binding.featuresLayout
        featuresLayout.removeAllViews()
        val inflater = LayoutInflater.from(featuresLayout.context)
        for (feature in page.features) {
            val row = IntroFeatureRowItemBinding.inflate(inflater, featuresLayout, true)
            row.featureText.setText(feature.textRes)
            row.featureIcon.setImageResource(feature.iconRes)
        }
    }
}
