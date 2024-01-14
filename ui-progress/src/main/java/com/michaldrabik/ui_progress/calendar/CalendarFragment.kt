package com.michaldrabik.ui_progress.calendar

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateMargins
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView.LayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.michaldrabik.repository.settings.SettingsViewModeRepository
import com.michaldrabik.ui_base.BaseFragment
import com.michaldrabik.ui_base.common.OnScrollResetListener
import com.michaldrabik.ui_base.common.OnSearchClickListener
import com.michaldrabik.ui_base.utilities.events.Event
import com.michaldrabik.ui_base.utilities.extensions.dimenToPx
import com.michaldrabik.ui_base.utilities.extensions.doOnApplyWindowInsets
import com.michaldrabik.ui_base.utilities.extensions.fadeIn
import com.michaldrabik.ui_base.utilities.extensions.visibleIf
import com.michaldrabik.ui_base.utilities.extensions.withSpanSizeLookup
import com.michaldrabik.ui_base.utilities.viewBinding
import com.michaldrabik.ui_model.CalendarMode.PRESENT_FUTURE
import com.michaldrabik.ui_model.CalendarMode.RECENTS
import com.michaldrabik.ui_progress.R
import com.michaldrabik.ui_progress.calendar.recycler.CalendarAdapter
import com.michaldrabik.ui_progress.calendar.recycler.CalendarListItem
import com.michaldrabik.ui_progress.databinding.FragmentCalendarBinding
import com.michaldrabik.ui_progress.helpers.ProgressLayoutManagerProvider
import com.michaldrabik.ui_progress.helpers.TopOverscrollAdapter
import com.michaldrabik.ui_progress.main.EpisodeCheckActionUiEvent
import com.michaldrabik.ui_progress.main.ProgressMainFragment
import com.michaldrabik.ui_progress.main.ProgressMainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.everything.android.ui.overscroll.IOverScrollDecor
import me.everything.android.ui.overscroll.IOverScrollState
import me.everything.android.ui.overscroll.OverScrollBounceEffectDecoratorBase.DEFAULT_DECELERATE_FACTOR
import me.everything.android.ui.overscroll.OverScrollBounceEffectDecoratorBase.DEFAULT_TOUCH_DRAG_MOVE_RATIO_BCK
import me.everything.android.ui.overscroll.VerticalOverScrollBounceEffectDecorator
import javax.inject.Inject

@AndroidEntryPoint
class CalendarFragment :
  BaseFragment<CalendarViewModel>(R.layout.fragment_calendar),
  OnSearchClickListener,
  OnScrollResetListener {

  private companion object {
    const val OVERSCROLL_OFFSET = 100F
    const val OVERSCROLL_OFFSET_TRANSLATION = 5F
  }

  @Inject lateinit var settings: SettingsViewModeRepository

  override val viewModel by viewModels<CalendarViewModel>()
  private val parentViewModel by viewModels<ProgressMainViewModel>({ requireParentFragment() })
  private val binding by viewBinding(FragmentCalendarBinding::bind)

  private var adapter: CalendarAdapter? = null
  private var layoutManager: LayoutManager? = null
  private var overscroll: IOverScrollDecor? = null
  private var statusBarHeight = 0
  private var overscrollEnabled = true
  private var isSearching = false

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    setupRecycler()
    setupStatusBar()

    viewLifecycleOwner.lifecycleScope.launch {
      viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        with(parentViewModel) {
          launch { uiState.collect { viewModel.handleParentAction(it) } }
        }
        with(viewModel) {
          launch { uiState.collect { render(it) } }
          launch { messageFlow.collect { showSnack(it) } }
          launch { eventFlow.collect { handleEvent(it) } }
        }
      }
    }
  }

  private fun setupRecycler() {
    val gridSpanSize = settings.tabletGridSpanSize
    layoutManager = ProgressLayoutManagerProvider.provideLayoutManger(requireContext(), gridSpanSize)
    (layoutManager as? GridLayoutManager)?.run {
      withSpanSizeLookup { position ->
        when (adapter?.getItems()?.get(position)) {
          is CalendarListItem.Header -> gridSpanSize
          is CalendarListItem.Episode -> 1
          else -> throw IllegalStateException()
        }
      }
    }
    adapter = CalendarAdapter(
      itemClickListener = { requireMainFragment().openShowDetails(it.show) },
      missingImageListener = { item, force -> viewModel.findMissingImage(item, force) },
      checkClickListener = { viewModel.onEpisodeChecked(it) },
      detailsClickListener = {
        requireMainFragment().openEpisodeDetails(
          show = it.show,
          episode = it.episode,
          season = it.season
        )
      },
      missingTranslationListener = { viewModel.findMissingTranslation(it) }
    )
    binding.progressCalendarRecycler.apply {
      adapter = this@CalendarFragment.adapter
      layoutManager = this@CalendarFragment.layoutManager
      (itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
      setHasFixedSize(true)
    }
    setupOverscroll()
  }

  private fun setupOverscroll() {
    if (overscroll != null || view == null) {
      return
    }
    val adapt = TopOverscrollAdapter(binding.progressCalendarRecycler)
    overscroll = VerticalOverScrollBounceEffectDecorator(
      adapt,
      1.75F,
      DEFAULT_TOUCH_DRAG_MOVE_RATIO_BCK,
      DEFAULT_DECELERATE_FACTOR
    ).apply {
      setOverScrollUpdateListener { _, state, offset ->
        binding.progressCalendarOverscrollIcon.run {
          if (offset > 0) {
            val value = (offset / OVERSCROLL_OFFSET).coerceAtMost(1F)
            val valueTranslation = offset / OVERSCROLL_OFFSET_TRANSLATION
            when (state) {
              IOverScrollState.STATE_DRAG_START_SIDE -> {
                alpha = value
                scaleX = value
                scaleY = value
                translationY = valueTranslation
                overscrollEnabled = true
              }
              IOverScrollState.STATE_BOUNCE_BACK -> {
                alpha = value
                scaleX = value
                scaleY = value
                translationY = valueTranslation
                if (offset >= OVERSCROLL_OFFSET && overscrollEnabled) {
                  overscrollEnabled = false
                  requireMainFragment().toggleCalendarMode()
                }
              }
            }
          } else {
            alpha = 0F
            scaleX = 0F
            scaleY = 0F
            translationY = 0F
          }
        }
      }
    }
  }

  private fun setupStatusBar() {
    val recyclerPadding = if (moviesEnabled) R.dimen.progressCalendarTabsViewPadding else R.dimen.progressCalendarTabsViewPaddingNoModes
    val overscrollPadding = if (moviesEnabled) R.dimen.progressOverscrollPadding else R.dimen.progressOverscrollPaddingNoModes

    if (statusBarHeight != 0) {
      binding.progressCalendarRecycler.updatePadding(top = statusBarHeight + dimenToPx(recyclerPadding))
      (binding.progressCalendarOverscrollIcon.layoutParams as ViewGroup.MarginLayoutParams)
        .updateMargins(top = statusBarHeight + dimenToPx(overscrollPadding))
      return
    }

    binding.progressCalendarRecycler.doOnApplyWindowInsets { view, insets, _, _ ->
      val tabletOffset = if (isTablet) dimenToPx(R.dimen.spaceMedium) else 0
      statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top + tabletOffset
      view.updatePadding(top = statusBarHeight + dimenToPx(recyclerPadding))
      (binding.progressCalendarOverscrollIcon.layoutParams as ViewGroup.MarginLayoutParams)
        .updateMargins(top = statusBarHeight + dimenToPx(overscrollPadding))
    }
  }

  override fun onEnterSearch() {
    isSearching = true

    with(binding) {
      progressCalendarRecycler.translationY = dimenToPx(R.dimen.progressSearchLocalOffset).toFloat()
      progressCalendarRecycler.smoothScrollToPosition(0)
    }

    overscroll?.detach()
    overscroll = null
  }

  override fun onExitSearch() {
    isSearching = false

    with(binding) {
      progressCalendarRecycler.translationY = 0F
      progressCalendarRecycler.smoothScrollToPosition(0)
    }

    setupOverscroll()
  }

  private fun handleEvent(event: Event<*>) {
    when (event) {
      is EpisodeCheckActionUiEvent -> {
        parentViewModel.setWatchedEpisode(event.episode)
      }
    }
  }

  private fun render(uiState: CalendarUiState) {
    uiState.run {
      with(binding) {
        items?.let {
          adapter?.setItems(it)
          progressCalendarRecycler.fadeIn(150, withHardware = true)
          progressCalendarEmptyFutureView.root.visibleIf(items.isEmpty() && mode == PRESENT_FUTURE && !isSearching)
          progressCalendarEmptyRecentsView.root.visibleIf(items.isEmpty() && mode == RECENTS && !isSearching)
        }
        mode.let {
          viewLifecycleOwner.lifecycleScope.launch {
            delay(300)
            when (it) {
              PRESENT_FUTURE -> progressCalendarOverscrollIcon.setImageResource(R.drawable.ic_history)
              RECENTS -> progressCalendarOverscrollIcon.setImageResource(R.drawable.ic_calendar)
            }
          }
        }
      }
    }
  }

  override fun onScrollReset() = binding.progressCalendarRecycler.smoothScrollToPosition(0)

  private fun requireMainFragment() = requireParentFragment() as ProgressMainFragment

  override fun onDestroyView() {
    overscroll?.detach()
    overscroll = null
    adapter = null
    layoutManager = null
    super.onDestroyView()
  }

  override fun setupBackPressed() = Unit
}
