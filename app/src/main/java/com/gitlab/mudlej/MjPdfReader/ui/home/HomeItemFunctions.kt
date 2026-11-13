// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderJobStatus

interface HomeItemFunctions {

    fun onItemClicked(item: HomeItem)
    fun onProjectDocumentClicked(item: HomeItem, pageIndex: Int, documentId: String? = null, displayTitle: String? = null, projectId: String? = null) = onItemClicked(item)

    fun onItemLongClicked(item: HomeItem): Boolean

    fun onItemOptionsClicked(item: HomeItem)

    fun onRenderStatusClicked(status: PhoneRenderJobStatus) = Unit
}
