// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.ItemHomeEmptyStateBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemHomeFedoraStatusBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemHomeHeroSectionBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemHomeSectionHeadingBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemHomePermissionCardBinding
import com.gitlab.mudlej.MjPdfReader.ui.sync.LibrarySyncStatusPresentation
import com.gitlab.mudlej.MjPdfReader.ui.sync.bindLibrarySyncStatus
import kotlinx.coroutines.CoroutineScope

class HomeSectionsAdapter(
    private val coverCache: CoverCache,
    private val scope: CoroutineScope,
    private val functions: HomeItemFunctions,
    private val onGrantAccessClicked: () -> Unit = {},
    private val onFedoraStatusClicked: () -> Unit = {},
    private val onHeadingClicked: (HomeSection.Heading) -> Unit = {},
) : ListAdapter<HomeSection, RecyclerView.ViewHolder>(SectionComparator()) {

    private var coverEpoch = 0

    fun rebindCovers() {
        coverEpoch++
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is HomeSection.PermissionCard -> TYPE_PERMISSION_CARD
            is HomeSection.Hero -> TYPE_HERO
            is HomeSection.Heading -> TYPE_HEADING
            is HomeSection.EmptyState -> TYPE_EMPTY_STATE
            is HomeSection.FedoraStatus -> TYPE_FEDORA_STATUS
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_PERMISSION_CARD -> PermissionCardViewHolder(
                ItemHomePermissionCardBinding.inflate(inflater, parent, false)
            )
            TYPE_HERO -> HeroSectionViewHolder(
                ItemHomeHeroSectionBinding.inflate(inflater, parent, false)
            )
            TYPE_HEADING -> HeadingViewHolder(
                ItemHomeSectionHeadingBinding.inflate(inflater, parent, false)
            )
            TYPE_FEDORA_STATUS -> FedoraStatusViewHolder(
                ItemHomeFedoraStatusBinding.inflate(inflater, parent, false)
            )
            else -> EmptyStateViewHolder(
                ItemHomeEmptyStateBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val section = getItem(position)) {
            is HomeSection.PermissionCard -> (holder as PermissionCardViewHolder).bind(section)
            is HomeSection.Hero -> (holder as HeroSectionViewHolder).bind(section)
            is HomeSection.Heading -> (holder as HeadingViewHolder).bind(section)
            is HomeSection.EmptyState -> (holder as EmptyStateViewHolder).bind(section)
            is HomeSection.FedoraStatus -> (holder as FedoraStatusViewHolder).bind(section)
        }
    }

    inner class PermissionCardViewHolder(
        private val binding: ItemHomePermissionCardBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(section: HomeSection.PermissionCard) {
            binding.permissionMessage.setText(section.messageRes)
            binding.grantAccessButton.setOnClickListener { onGrantAccessClicked() }
        }
    }

    inner class HeroSectionViewHolder(
        binding: ItemHomeHeroSectionBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private val heroAdapter = HeroCarouselAdapter(coverCache, scope, functions)
        private val heroRecyclerView = binding.heroRecyclerView
        private var boundCoverEpoch = -1

        init {
            heroRecyclerView.layoutManager = LinearLayoutManager(
                binding.root.context, LinearLayoutManager.HORIZONTAL, false
            )
            heroRecyclerView.adapter = heroAdapter
            PagerSnapHelper().attachToRecyclerView(heroRecyclerView)
        }

        fun bind(section: HomeSection.Hero) {
            val previousFirstHash = heroAdapter.currentList.firstOrNull()?.hash
            heroAdapter.submitList(section.items) {
                if (section.items.firstOrNull()?.hash != previousFirstHash) {
                    heroRecyclerView.scrollToPosition(0)
                }
            }
            if (boundCoverEpoch != coverEpoch) {
                boundCoverEpoch = coverEpoch
                heroAdapter.notifyDataSetChanged()
            }
        }
    }

    inner class HeadingViewHolder(
        private val binding: ItemHomeSectionHeadingBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(section: HomeSection.Heading) {
            val context = binding.root.context
            val expanded = section.expanded
            binding.sectionHeading.setText(section.titleRes)
            binding.sectionIcon.setImageResource(section.iconRes)
            binding.sectionCount.text = section.count.toString()
            binding.sectionDivider.isVisible = section.showDivider
            binding.sectionExpandIcon.isVisible = expanded != null
            binding.sectionCard.isClickable = expanded != null
            binding.sectionCard.isFocusable = expanded != null
            if (expanded != null) {
                binding.sectionExpandIcon.setImageResource(
                    if (expanded) R.drawable.ic_small_arrow_up else R.drawable.ic_small_arrow_down
                )
                binding.sectionCard.contentDescription = context.resources.getQuantityString(
                    if (expanded) {
                        R.plurals.home_library_section_expanded_description
                    } else {
                        R.plurals.home_library_section_collapsed_description
                    },
                    section.count,
                    context.getString(section.titleRes),
                    section.count,
                )
                binding.sectionCard.setOnClickListener { onHeadingClicked(section) }
            } else {
                binding.sectionCard.contentDescription = null
                binding.sectionCard.setOnClickListener(null)
            }
        }
    }

    class EmptyStateViewHolder(
        private val binding: ItemHomeEmptyStateBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(section: HomeSection.EmptyState) {
            binding.emptyTitle.setText(section.titleRes)
            binding.emptyMessage.setText(section.messageRes)
        }
    }

    inner class FedoraStatusViewHolder(
        private val binding: ItemHomeFedoraStatusBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(section: HomeSection.FedoraStatus) {
            bindLibrarySyncStatus(
                binding = binding,
                presentation = LibrarySyncStatusPresentation(
                    label = section.label,
                    timestamp = section.timestamp,
                    hasSyncFailure = section.hasSyncFailure,
                ),
                onClick = onFedoraStatusClicked,
            )
        }
    }

    class SectionComparator : DiffUtil.ItemCallback<HomeSection>() {

        override fun areItemsTheSame(oldItem: HomeSection, newItem: HomeSection): Boolean {
            return oldItem::class == newItem::class
        }

        override fun areContentsTheSame(oldItem: HomeSection, newItem: HomeSection): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        private const val TYPE_PERMISSION_CARD = 0
        private const val TYPE_HERO = 1
        private const val TYPE_EMPTY_STATE = 2
        private const val TYPE_HEADING = 5
        private const val TYPE_FEDORA_STATUS = 6
    }
}
