package com.michaldrabik.ui_lists.details.views

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.michaldrabik.common.Config.SPOILERS_HIDE_SYMBOL
import com.michaldrabik.common.Config.SPOILERS_RATINGS_HIDE_SYMBOL
import com.michaldrabik.common.Config.SPOILERS_REGEX
import com.michaldrabik.ui_base.utilities.extensions.colorFromAttr
import com.michaldrabik.ui_base.utilities.extensions.dimenToPx
import com.michaldrabik.ui_base.utilities.extensions.expandTouch
import com.michaldrabik.ui_base.utilities.extensions.onClick
import com.michaldrabik.ui_base.utilities.extensions.setOutboundRipple
import com.michaldrabik.ui_base.utilities.extensions.visibleIf
import com.michaldrabik.ui_lists.R
import com.michaldrabik.ui_lists.databinding.ViewListDetailsShowItemBinding
import com.michaldrabik.ui_lists.details.recycler.ListDetailsItem
import com.michaldrabik.ui_model.Show
import java.util.Locale.ENGLISH
import kotlin.math.abs

@SuppressLint("ClickableViewAccessibility")
class ListDetailsShowItemView : ListDetailsItemView {

  constructor(context: Context) : super(context)
  constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
  constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

  private val binding = ViewListDetailsShowItemBinding.inflate(LayoutInflater.from(context), this)

  init {
    layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    setBackgroundColor(context.colorFromAttr(android.R.attr.windowBackground))

    clipChildren = false
    clipToPadding = false

    imageLoadCompleteListener = {
      if (item.translation == null) {
        missingTranslationListener?.invoke(item)
      }
    }

    with(binding) {
      listDetailsShowHandle.expandTouch(100)
      listDetailsShowHandle.setOnTouchListener { _, event ->
        if (item.isManageMode && event.action == ACTION_DOWN) {
          itemDragStartListener?.invoke()
        }
        false
      }

      var x = 0F
      listDetailsShowRoot.setOnTouchListener { _, event ->
        if (item.isManageMode) {
          return@setOnTouchListener false
        }
        if (event.action == ACTION_DOWN) x = event.x
        if (event.action == ACTION_UP) x = 0F
        if (event.action == ACTION_MOVE && abs(x - event.x) > 50F) {
          itemSwipeStartListener?.invoke()
          return@setOnTouchListener true
        }
        false
      }

      listDetailsShowRoot.onClick {
        if (!item.isManageMode) itemClickListener?.invoke(item)
      }
      listDetailsShowRoot.setOutboundRipple(
        size = (context.dimenToPx(R.dimen.collectionItemRippleSpace)).toFloat(),
        corner = context.dimenToPx(R.dimen.mediaTileCorner).toFloat(),
      )
    }
  }

  override val imageView: ImageView = binding.listDetailsShowImage
  override val placeholderView: ImageView = binding.listDetailsShowPlaceholder

  override fun bind(item: ListDetailsItem) {
    super.bind(item)

    with(binding) {
      Glide.with(this@ListDetailsShowItemView).clear(listDetailsShowImage)

      val show = item.requireShow()

      listDetailsShowProgress.visibleIf(item.isLoading)

      listDetailsShowTitle.text =
        if (item.translation?.title.isNullOrBlank()) {
          show.title
        } else {
          item.translation?.title
        }

      bindDescription(item, show)
      bindRating(item, show)

      listDetailsShowHeader.text =
        if (show.year > 0) {
          context.getString(R.string.textNetwork, show.year.toString(), show.network)
        } else {
          String.format("%s", show.network)
        }

      listDetailsShowUserRating.text = String.format(ENGLISH, "%d", item.userRating)

      listDetailsShowRank.visibleIf(item.isRankDisplayed)
      listDetailsShowRank.text = String.format(ENGLISH, "%d", item.rankDisplay)

      listDetailsShowHandle.visibleIf(item.isManageMode)
      listDetailsShowStarIcon.visibleIf(!item.isManageMode)
      listDetailsShowUserStarIcon.visibleIf(!item.isManageMode && item.userRating != null)
      listDetailsShowUserRating.visibleIf(!item.isManageMode && item.userRating != null)

      with(listDetailsShowHeaderBadge) {
        val inCollection = item.isWatched || item.isWatchlist
        visibleIf(inCollection)
        if (inCollection) {
          val color = if (item.isWatched) R.color.colorAccent else R.color.colorGrayLight
          imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, color))
        }
      }
    }

    loadImage(item)
  }

  private fun bindDescription(
    item: ListDetailsItem,
    show: Show,
  ) {
    var description = when {
      item.translation?.overview.isNullOrBlank() -> show.overview.ifBlank {
        context.getString(R.string.textNoDescription)
      }
      else -> item.translation?.overview
    }

    val isMyHidden = item.spoilers.isMyShowsHidden && item.isWatched
    val isWatchlistHidden = item.spoilers.isWatchlistShowsHidden && item.isWatchlist
    val isNotCollectedHidden = item.spoilers.isNotCollectedShowsHidden && (!item.isWatched && !item.isWatchlist)
    if (isMyHidden || isWatchlistHidden || isNotCollectedHidden) {
      binding.listDetailsShowDescription.tag = description
      description = SPOILERS_REGEX.replace(description.toString(), SPOILERS_HIDE_SYMBOL)
    }

    binding.listDetailsShowDescription.text = description
    if (item.spoilers.isTapToReveal) {
      with(binding.listDetailsShowDescription) {
        onClick {
          tag?.let { text = it.toString() }
          isClickable = false
        }
      }
    }
  }

  private fun bindRating(
    item: ListDetailsItem,
    show: Show,
  ) {
    var rating = String.format(ENGLISH, "%.1f", show.rating)

    val isMyHidden = item.spoilers.isMyShowsRatingsHidden && item.isWatched
    val isWatchlistHidden = item.spoilers.isWatchlistShowsRatingsHidden && item.isWatchlist
    val isNotCollectedHidden = item.spoilers.isNotCollectedShowsRatingsHidden && (!item.isWatched && !item.isWatchlist)
    if (isMyHidden || isWatchlistHidden || isNotCollectedHidden) {
      binding.listDetailsShowRating.tag = rating
      rating = SPOILERS_RATINGS_HIDE_SYMBOL
    }

    binding.listDetailsShowRating.visibleIf(!item.isManageMode)
    binding.listDetailsShowRating.text = rating

    if (item.spoilers.isTapToReveal) {
      with(binding.listDetailsShowRating) {
        onClick {
          tag?.let { text = it.toString() }
          isClickable = false
        }
      }
    }
  }
}
