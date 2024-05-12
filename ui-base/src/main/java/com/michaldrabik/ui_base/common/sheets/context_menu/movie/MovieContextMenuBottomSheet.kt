package com.michaldrabik.ui_base.common.sheets.context_menu.movie

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import com.michaldrabik.common.Config.SPOILERS_HIDE_SYMBOL
import com.michaldrabik.common.Config.SPOILERS_RATINGS_HIDE_SYMBOL
import com.michaldrabik.common.Config.SPOILERS_REGEX
import com.michaldrabik.ui_base.R
import com.michaldrabik.ui_base.common.sheets.context_menu.ContextMenuBottomSheet
import com.michaldrabik.ui_base.common.sheets.context_menu.events.FinishUiEvent
import com.michaldrabik.ui_base.common.sheets.context_menu.events.RemoveTraktUiEvent
import com.michaldrabik.ui_base.common.sheets.context_menu.events.SelectDateUiEvent
import com.michaldrabik.ui_base.common.sheets.context_menu.movie.helpers.MovieContextItem
import com.michaldrabik.ui_base.common.sheets.date_selection.DateSelectionBottomSheet
import com.michaldrabik.ui_base.common.sheets.date_selection.DateSelectionBottomSheet.Result
import com.michaldrabik.ui_base.common.sheets.remove_trakt.RemoveTraktBottomSheet.Mode
import com.michaldrabik.ui_base.utilities.events.Event
import com.michaldrabik.ui_base.utilities.extensions.capitalizeWords
import com.michaldrabik.ui_base.utilities.extensions.launchAndRepeatStarted
import com.michaldrabik.ui_base.utilities.extensions.onClick
import com.michaldrabik.ui_base.utilities.extensions.requireParcelable
import com.michaldrabik.ui_base.utilities.extensions.visibleIf
import com.michaldrabik.ui_navigation.java.NavigationArgs
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

@AndroidEntryPoint
class MovieContextMenuBottomSheet : ContextMenuBottomSheet() {

  private val viewModel by viewModels<MovieContextMenuViewModel>()

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    setupView()

    launchAndRepeatStarted(
      { viewModel.messageFlow.collect { renderSnackbar(it) } },
      { viewModel.eventFlow.collect { handleEvent(it) } },
      { viewModel.uiState.collect { render(it) } },
      doAfterLaunch = { viewModel.loadMovie(itemId) }
    )
  }

  override fun setupView() {
    super.setupView()
    with(binding) {
      contextMenuItemMoveToMyButton.text = getString(R.string.textMoveToMyMovies)
      contextMenuItemRemoveFromMyButton.text = getString(R.string.textRemoveFromMyMovies)

      contextMenuItemMoveToMyButton.onClick { viewModel.moveToMyMovies() }
      contextMenuItemRemoveFromMyButton.onClick { viewModel.removeFromMyMovies() }
      contextMenuItemMoveToWatchlistButton.onClick { viewModel.moveToWatchlist() }
      contextMenuItemRemoveFromWatchlistButton.onClick { viewModel.removeFromWatchlist() }
      contextMenuItemMoveToHiddenButton.onClick { viewModel.moveToHidden() }
      contextMenuItemRemoveFromHiddenButton.onClick { viewModel.removeFromHidden() }
      contextMenuItemPinButton.onClick { viewModel.addToTopPinned() }
      contextMenuItemUnpinButton.onClick { viewModel.removeFromTopPinned() }
    }
  }

  private fun render(uiState: MovieContextMenuUiState) {
    uiState.run {
      isLoading?.let { isLoading ->
        when {
          isLoading -> binding.contextMenuItemProgress.show()
          else -> binding.contextMenuItemProgress.hide()
        }
        binding.contextMenuItemButtonsLayout.visibleIf(!isLoading, gone = false)
      }
      item?.let {
        renderItem(it)
        renderImage(it.image)
      }
    }
  }

  private fun renderItem(item: MovieContextItem) {
    with(binding) {
      contextMenuItemTitle.text =
        if (item.translation?.title.isNullOrBlank()) item.movie.title
        else item.translation?.title

      renderItemDescription(item)
      renderItemRating(item)

      contextMenuItemNetwork.text = when {
        item.movie.released != null -> item.dateFormat?.format(item.movie.released)?.capitalizeWords()
        else -> String.format(Locale.ENGLISH, "%d", item.movie.year)
      }

      contextMenuRatingStar.visibleIf(item.movie.rating > 0)

      contextMenuUserRating.text = String.format(Locale.ENGLISH, "%d", item.userRating)
      contextMenuUserRating.visibleIf(item.userRating != null)
      contextMenuUserRatingStar.visibleIf(item.userRating != null)

      contextMenuItemDescription.visibleIf(item.movie.overview.isNotBlank())
      contextMenuItemNetwork.visibleIf(item.movie.released != null || item.movie.year > 0)

      contextMenuItemPinButton.visibleIf(!item.isPinnedTop)
      contextMenuItemUnpinButton.visibleIf(item.isPinnedTop)

      contextMenuItemMoveToMyButton.visibleIf(!item.isMyMovie)
      contextMenuItemMoveToWatchlistButton.visibleIf(!item.isWatchlist)
      contextMenuItemMoveToHiddenButton.visibleIf(!item.isHidden)

      contextMenuItemRemoveFromMyButton.visibleIf(item.isMyMovie)
      contextMenuItemRemoveFromWatchlistButton.visibleIf(item.isWatchlist)
      contextMenuItemRemoveFromHiddenButton.visibleIf(item.isHidden)

      contextMenuItemBadge.visibleIf(item.isMyMovie || item.isWatchlist)
      val color = if (item.isMyMovie) colorAccent else colorGray
      ImageViewCompat.setImageTintList(contextMenuItemBadge, ColorStateList.valueOf(color))

      if (!item.isInCollection()) {
        contextMenuItemMoveToMyButton.text = getString(R.string.textAddToMyMovies)
        contextMenuItemMoveToWatchlistButton.text = getString(R.string.textAddToWatchlist)
        contextMenuItemMoveToHiddenButton.text = getString(R.string.textHide)
      }
    }
  }

  private fun renderItemDescription(item: MovieContextItem) {
    with(binding) {
      contextMenuItemDescription.text =
        if (item.translation?.overview.isNullOrBlank()) item.movie.overview
        else item.translation?.overview

      val isMyMovieHidden = item.spoilers.isMyMoviesHidden && item.isMyMovie
      val isWatchlistHidden = item.spoilers.isWatchlistMoviesHidden && item.isWatchlist
      val isHiddenMovieHidden = item.spoilers.isHiddenMoviesHidden && item.isHidden
      val isNotCollectedHidden = item.spoilers.isNotCollectedMoviesHidden && (!item.isInCollection())

      if (isMyMovieHidden || isWatchlistHidden || isHiddenMovieHidden || isNotCollectedHidden) {
        val spoilerDescription = contextMenuItemDescription.text.toString()
        val hiddenDescription = SPOILERS_REGEX.replace(spoilerDescription, SPOILERS_HIDE_SYMBOL)
        contextMenuItemDescription.tag = spoilerDescription
        contextMenuItemDescription.text = hiddenDescription
      }

      if (item.spoilers.isTapToReveal) {
        with(contextMenuItemDescription) {
          onClick {
            tag?.let { text = it.toString() }
            enableFoldOnClick()
          }
        }
      }
    }
  }

  private fun renderItemRating(item: MovieContextItem) {
    with(binding) {
      var rating = String.format(Locale.ENGLISH, "%.1f", item.movie.rating)

      val isMyHidden = item.spoilers.isMyMoviesRatingsHidden && item.isMyMovie
      val isWatchlistHidden = item.spoilers.isWatchlistMoviesRatingsHidden && item.isWatchlist
      val isHiddenShowHidden = item.spoilers.isHiddenMoviesRatingsHidden && item.isHidden
      val isNotCollectedHidden = item.spoilers.isNotCollectedMoviesRatingsHidden && (!item.isInCollection())

      if (isMyHidden || isWatchlistHidden || isHiddenShowHidden || isNotCollectedHidden) {
        contextMenuRating.tag = rating
        rating = SPOILERS_RATINGS_HIDE_SYMBOL
      }

      contextMenuRating.visibleIf(item.movie.rating > 0)
      contextMenuRatingStar.visibleIf(item.movie.rating > 0)
      contextMenuRating.text = rating

      if (item.spoilers.isTapToReveal) {
        with(contextMenuRating) {
          onClick {
            tag?.let { text = it.toString() }
          }
        }
      }
    }
  }

  private fun handleEvent(event: Event<*>) {
    when (val result = event.peek()) {
      is RemoveTraktUiEvent -> when {
        result.removeProgress -> openRemoveTraktSheet(R.id.actionMovieItemContextDialogToRemoveTraktProgress, Mode.MOVIE)
        result.removeWatchlist -> openRemoveTraktSheet(R.id.actionMovieItemContextDialogToRemoveTraktWatchlist, Mode.MOVIE)
        result.removeHidden -> openRemoveTraktSheet(R.id.actionMovieItemContextDialogToRemoveTraktHidden, Mode.MOVIE)
        else -> close()
      }
      is SelectDateUiEvent -> openDateSelection()
      is FinishUiEvent -> if (result.isSuccess) close()
      else -> throw IllegalStateException()
    }
  }

  private fun openDateSelection() {
    setFragmentResultListener(DateSelectionBottomSheet.REQUEST_DATE_SELECTION) { _, bundle ->
      when (val result = bundle.requireParcelable<Result>(DateSelectionBottomSheet.RESULT_DATE_SELECTION)) {
        is Result.Now -> viewModel.moveToMyMovies(isCustomDateSelected = true)
        is Result.CustomDate -> viewModel.moveToMyMovies(isCustomDateSelected = true, customDate = result.date)
      }
    }
    navigateTo(R.id.actionMovieItemContextDialogToDateSelection)
  }

  override fun openDetails() {
    val bundle = bundleOf(NavigationArgs.ARG_MOVIE_ID to itemId.id)
    navigateTo(R.id.actionMovieItemContextDialogToMovieDetails, bundle)
  }
}
