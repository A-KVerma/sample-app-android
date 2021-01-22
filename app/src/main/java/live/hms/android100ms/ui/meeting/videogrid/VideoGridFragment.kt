package live.hms.android100ms.ui.meeting.videogrid

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import androidx.core.view.children
import androidx.fragment.app.Fragment
import com.brytecam.lib.webrtc.HMSWebRTCEglUtils
import live.hms.android100ms.BuildConfig
import live.hms.android100ms.databinding.FragmentVideoGridBinding
import live.hms.android100ms.databinding.GridItemVideoBinding
import live.hms.android100ms.ui.meeting.MeetingTrack
import live.hms.android100ms.util.crashlyticsLog
import live.hms.android100ms.util.viewLifecycle
import org.webrtc.RendererCommon
import kotlin.math.max
import kotlin.math.min

/**
 * @param initialVideos: List of videos which needs to shown in a grid
 * @param maxRows: Maximum number of rows in the grid
 * @param maxColumns: Maximum number columns in the grid
 *
 * The Grid is created by building column by column.
 * Example: For 4x2 (rows x columns)
 *  - 3 videos will have 3 rows, 1 column
 *  - 5 videos will have 4 rows, 2 columns
 *  - 8 videos will have 4 rows, 2 columns
 */
class VideoGridFragment(
  private val initialVideos: MutableList<MeetingTrack>,
  private val maxRows: Int, private val maxColumns: Int,
  private val onVideoItemClick: (video: MeetingTrack) -> Unit
) : Fragment() {

  companion object {
    const val TAG = "VideoGridFragment"
  }

  init {
    crashlyticsLog(
      TAG,
      "Received ${initialVideos.size} initial videos for ${maxRows}x${maxColumns}"
    )
    if (BuildConfig.DEBUG && initialVideos.size > (maxRows * maxColumns)) {
      error("Cannot show ${initialVideos.size} videos in a ${maxRows}x${maxColumns} grid")
    }
  }

  private var binding by viewLifecycle<FragmentVideoGridBinding>()

  private data class RenderedViewPair(
    val binding: GridItemVideoBinding,
    val video: MeetingTrack
  )

  private val renderedViews = ArrayList<RenderedViewPair>()

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    binding = FragmentVideoGridBinding.inflate(inflater, container, false)
    initGridLayout()
    return binding.root
  }

  private val rows: Int
    get() = min(max(1, renderedViews.size), maxRows)

  private val columns: Int
    get() {
      val result = max(1, (renderedViews.size + rows - 1) / rows)
      if (result > maxColumns) {
        val videos = renderedViews.map { it.video }
        throw IllegalStateException(
          "At most ${maxRows * maxColumns} videos are allowed. Provided $videos"
        )
      }

      return result
    }

  private fun updateGridLayoutDimensions() {
    crashlyticsLog(TAG, "updateGridLayoutDimensions: ${rows}x${columns}")

    var childIdx: Pair<Int, Int>? = null
    var colIdx = 0
    var rowIdx = 0

    binding.container.apply {
      crashlyticsLog(TAG, "Updating GridLayout.spec for ${children.count()} children")
      for (child in children) {
        childIdx = Pair(rowIdx, colIdx)

        val params = child.layoutParams as GridLayout.LayoutParams
        params.rowSpec = GridLayout.spec(rowIdx, 1, 1f)
        params.columnSpec = GridLayout.spec(colIdx, 1, 1f)

        if (colIdx + 1 == columns) {
          rowIdx += 1
          colIdx = 0
        } else {
          colIdx += 1
        }
      }

      crashlyticsLog(TAG, "Changed GridLayout's children spec with bottom-right at $childIdx")

      // Forces maxIndex to be recalculated when rowCount/columnCount is set
      requestLayout()

      rowCount = rows
      columnCount = columns
    }
  }

  private fun initGridLayout() {
    updateGridLayoutDimensions()

    binding.container.apply {
      for (video in initialVideos) {
        val videoBinding = createVideoView(this)
        bindVideo(videoBinding, video)
        addView(videoBinding.root)
        renderedViews.add(RenderedViewPair(videoBinding, video))
      }
    }

    crashlyticsLog(TAG, "Initialized GridLayout with ${initialVideos.size} views")

    updateGridLayoutDimensions()
  }

  override fun onDestroyView() {
    super.onDestroyView()

    // Unbind all the views attached clearing the EglContext
    binding.container.apply {
      for (currentRenderedView in renderedViews) {
        unbindVideo(currentRenderedView.binding, currentRenderedView.video)
        removeView(currentRenderedView.binding.root)
      }
    }
  }

  fun updateVideos(newVideos: List<MeetingTrack>) {
    crashlyticsLog(
      TAG,
      "updateVideos(${newVideos.size}) -- presently ${renderedViews.size} items in grid"
    )

    val newRenderedViews = ArrayList<RenderedViewPair>()

    // Remove all the views which are not required now
    for (currentRenderedView in renderedViews) {
      val newVideo = newVideos.find { it == currentRenderedView.video }
      if (newVideo == null) {
        crashlyticsLog(
          TAG,
          "updateVideos: Removing view for video=${currentRenderedView.video} from fragment=$tag"
        )

        binding.container.apply {
          unbindVideo(currentRenderedView.binding, currentRenderedView.video)
          removeView(currentRenderedView.binding.root)
        }
      }
    }

    for (newVideo in newVideos) {
      // Check if video already rendered
      val renderedViewPair = renderedViews.find { it.video == newVideo }
      if (renderedViewPair != null) {
        crashlyticsLog(TAG, "updateVideos: Keeping view for video=$newVideo in fragment=$tag")
        newRenderedViews.add(renderedViewPair)
      } else {
        // Create a new view
        val videoBinding = createVideoView(binding.container)
        bindVideo(videoBinding, newVideo)
        crashlyticsLog(TAG, "updateVideos: Creating view for video=${newVideo} from fragment=$tag")
        binding.container.addView(videoBinding.root)
        newRenderedViews.add(RenderedViewPair(videoBinding, newVideo))
      }
    }

    crashlyticsLog(
      TAG,
      "updateVideos: Change grid items from ${renderedViews.size} -> ${newRenderedViews.size}"
    )

    renderedViews.clear()
    renderedViews.addAll(newRenderedViews)

    updateGridLayoutDimensions()
  }

  private fun bindVideo(binding: GridItemVideoBinding, item: MeetingTrack) {
    binding.container.setOnClickListener { onVideoItemClick(item) }

    binding.name.text = item.peer.userName

    binding.nameInitials.text = item.peer.userName.let { value ->
      if (value.isEmpty()) {
        "--"
      } else {
        value.split(' ')
          .mapNotNull { it.firstOrNull()?.toString() }
          .reduce { acc, s -> acc + s }
      }
    }

    // TODO: Add listener for video stream on/off -> Change visibility of surface renderer

    val isVideoAvailable = item.videoTrack != null

    binding.nameInitials.visibility = if (isVideoAvailable) View.GONE else View.VISIBLE
    binding.surfaceView.visibility = if (isVideoAvailable) View.VISIBLE else View.GONE

    if (isVideoAvailable) binding.surfaceView.apply {
      init(HMSWebRTCEglUtils.getRootEglBaseContext(), null)
      setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
      setEnableHardwareScaler(true)
      item.videoTrack?.addSink(this)
    }
  }

  private fun unbindVideo(binding: GridItemVideoBinding, item: MeetingTrack) {
    binding.surfaceView.apply {
      item.videoTrack?.removeSink(this)
      release()
      clearImage()
    }
  }

  private fun createVideoView(parent: ViewGroup): GridItemVideoBinding {
    return GridItemVideoBinding.inflate(
      LayoutInflater.from(context),
      parent,
      false
    )
  }

}