// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.gitlab.mudlej.MjPdfReader.databinding.PageHomeLibraryBinding
import com.gitlab.mudlej.MjPdfReader.databinding.PageHomeReviewBinding

class HomeTabsAdapter(
    private val onPageAttached: (HomeTab, SwipeRefreshLayout) -> Unit,
) : RecyclerView.Adapter<HomeTabsAdapter.PageViewHolder>() {

    class PageViewHolder(
        val swipeRefresh: SwipeRefreshLayout,
    ) : RecyclerView.ViewHolder(swipeRefresh)

    override fun getItemCount() = HomeTab.entries.size

    override fun getItemViewType(position: Int) = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val swipeRefresh = when (HomeTab.entries[viewType]) {
            HomeTab.DIARY -> inflater.inflate(
                com.gitlab.mudlej.MjPdfReader.R.layout.page_home_diary,
                parent,
                false,
            ) as SwipeRefreshLayout
            HomeTab.NOTES -> inflater.inflate(
                com.gitlab.mudlej.MjPdfReader.R.layout.page_home_overview,
                parent,
                false,
            ) as SwipeRefreshLayout
            HomeTab.LIBRARY -> PageHomeLibraryBinding.inflate(inflater, parent, false).pageSwipeRefresh
            HomeTab.REVIEW -> PageHomeReviewBinding.inflate(inflater, parent, false).pageSwipeRefresh
            HomeTab.ANNOTATIONS -> inflater.inflate(
                com.gitlab.mudlej.MjPdfReader.R.layout.page_home_overview,
                parent,
                false,
            ) as SwipeRefreshLayout
        }
        return PageViewHolder(swipeRefresh)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        onPageAttached(HomeTab.entries[position], holder.swipeRefresh)
    }
}
