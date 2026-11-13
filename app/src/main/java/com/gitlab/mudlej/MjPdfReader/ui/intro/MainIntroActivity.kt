// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.intro

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityIntroBinding

class MainIntroActivity : AppCompatActivity() {

    private val pages = listOf(
        IntroPage(
            R.layout.intro_art_welcome,
            R.string.intro_welcome_title,
            R.string.intro_thoughts_welcome_tagline,
            listOf(
                IntroFeature(R.drawable.ic_lock, R.string.intro_thoughts_welcome_private),
                IntroFeature(R.drawable.code_icon, R.string.intro_welcome_foss),
            ),
        ),
        IntroPage(
            R.layout.intro_art_library,
            R.string.intro_thoughts_library_title,
            R.string.intro_thoughts_library_tagline,
            listOf(
                IntroFeature(R.drawable.ic_edit, R.string.intro_thoughts_library_source),
                IntroFeature(R.drawable.search_icon, R.string.intro_thoughts_library_search),
            ),
        ),
        IntroPage(
            R.layout.intro_art_annotate,
            R.string.intro_thoughts_comments_title,
            R.string.intro_thoughts_comments_tagline,
            listOf(
                IntroFeature(R.drawable.ic_comment_add, R.string.intro_thoughts_comments_notes),
                IntroFeature(R.drawable.ic_schedule_small, R.string.intro_thoughts_comments_review),
                IntroFeature(R.drawable.ic_agent_edits, R.string.intro_thoughts_comments_proposals),
            ),
        ),
        IntroPage(
            R.layout.intro_art_controls,
            R.string.intro_thoughts_sync_title,
            R.string.intro_thoughts_sync_tagline,
            listOf(
                IntroFeature(R.drawable.ic_sync_done, R.string.intro_thoughts_sync_offline),
                IntroFeature(R.drawable.ic_link, R.string.intro_thoughts_sync_server),
            ),
            showMadeWithLove = true,
        ),
    )

    private lateinit var binding: ActivityIntroBinding
    private val dots = mutableListOf<ImageView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIntroBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        binding.introPager.adapter = IntroPagerAdapter(pages)
        createDots()
        binding.introPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateControls(position)
            }
        })

        binding.skipButton.setOnClickListener { finish() }
        binding.backButton.setOnClickListener { showPreviousPage() }
        binding.nextButton.setOnClickListener {
            if (binding.introPager.currentItem == pages.lastIndex) finish()
            else binding.introPager.currentItem += 1
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.introPager.currentItem == 0) finish()
                else showPreviousPage()
            }
        })
    }

    private fun showPreviousPage() {
        binding.introPager.currentItem = (binding.introPager.currentItem - 1).coerceAtLeast(0)
    }

    private fun createDots() {
        val margin = resources.getDimensionPixelSize(R.dimen.intro_dot_margin)
        repeat(pages.size) {
            val dot = ImageView(this)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.marginStart = margin
            params.marginEnd = margin
            dot.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            binding.dotsLayout.addView(dot, params)
            dots.add(dot)
        }
        updateControls(0)
    }

    private fun updateControls(position: Int) {
        dots.forEachIndexed { index, dot ->
            dot.setImageResource(
                if (index == position) R.drawable.intro_dot_active
                else R.drawable.intro_dot_inactive
            )
        }
        val isLastPage = position == pages.lastIndex
        binding.dotsLayout.contentDescription = getString(
            R.string.intro_page_indicator,
            position + 1,
            pages.size,
        )
        binding.backButton.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
        binding.skipButton.visibility = if (isLastPage) View.INVISIBLE else View.VISIBLE
        binding.nextButton.setText(if (isLastPage) R.string.intro_get_started else R.string.intro_next)
    }
}
