package nya.kitsunyan.foxydroid.screen

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PermissionGroupInfo
import android.content.pm.PermissionInfo
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Parcel
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.format.DateFormat
import android.text.style.BulletSpan
import android.text.style.ClickableSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.text.util.LinkifyCompat
import androidx.recyclerview.widget.RecyclerView
import coil.api.*
import nya.kitsunyan.foxydroid.R
import nya.kitsunyan.foxydroid.content.Preferences
import nya.kitsunyan.foxydroid.content.ProductPreferences
import nya.kitsunyan.foxydroid.entity.InstalledItem
import nya.kitsunyan.foxydroid.entity.Product
import nya.kitsunyan.foxydroid.entity.ProductPreference
import nya.kitsunyan.foxydroid.entity.Release
import nya.kitsunyan.foxydroid.entity.Repository
import nya.kitsunyan.foxydroid.graphics.PaddingDrawable
import nya.kitsunyan.foxydroid.network.CoilDownloader
import nya.kitsunyan.foxydroid.utility.KParcelable
import nya.kitsunyan.foxydroid.utility.PackageItemResolver
import nya.kitsunyan.foxydroid.utility.Utils
import nya.kitsunyan.foxydroid.utility.extension.android.*
import nya.kitsunyan.foxydroid.utility.extension.resources.*
import nya.kitsunyan.foxydroid.utility.extension.text.*
import nya.kitsunyan.foxydroid.widget.ClickableMovementMethod
import nya.kitsunyan.foxydroid.widget.DividerItemDecoration
import nya.kitsunyan.foxydroid.widget.StableRecyclerAdapter
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.*

class ProductAdapter(private val callbacks: Callbacks, private val columns: Int):
  StableRecyclerAdapter<ProductAdapter.ViewType, RecyclerView.ViewHolder>() {
  companion object {
    private const val GRID_SPACING_OUTER_DP = 16
    private const val GRID_SPACING_INNER_DP = 4
  }

  interface Callbacks {
    fun onActionClick(action: Action)
    fun onPreferenceChanged(preference: ProductPreference)
    fun onPermissionsClick(group: String?, permissions: List<String>)
    fun onScreenshotClick(screenshot: Product.Screenshot)
    fun onReleaseClick(release: Release)
    fun onUriClick(uri: Uri, shouldConfirm: Boolean): Boolean
  }

  enum class Action(val titleResId: Int) {
    INSTALL(R.string.install),
    UPDATE(R.string.update),
    LAUNCH(R.string.launch),
    DETAILS(R.string.details),
    UNINSTALL(R.string.uninstall),
    CANCEL(R.string.cancel)
  }

  sealed class Status {
    object Pending: Status()
    object Connecting: Status()
    data class Downloading(val read: Long, val total: Long?): Status()
  }

  enum class ViewType { HEADER, SWITCH, SECTION, EXPAND, TEXT, LINK, PERMISSIONS, SCREENSHOT, RELEASE, EMPTY }

  private enum class SwitchType(val titleResId: Int) {
    IGNORE_ALL_UPDATES(R.string.ignore_all_updates),
    IGNORE_THIS_UPDATE(R.string.ignore_this_update)
  }

  private enum class SectionType(val titleResId: Int, val colorAttrResId: Int) {
    ANTI_FEATURES(R.string.anti_features, R.attr.colorError),
    WHATS_NEW(R.string.whats_new, R.attr.colorAccent),
    LINKS(R.string.links, R.attr.colorAccent),
    DONATE(R.string.donate, R.attr.colorAccent),
    PERMISSIONS(R.string.permissions, R.attr.colorAccent),
    SCREENSHOTS(R.string.screenshots, R.attr.colorAccent),
    RELEASES(R.string.releases, R.attr.colorAccent)
  }

  internal enum class ExpandType { NOTHING, DESCRIPTION, WHATS_NEW,
    LINKS, DONATES, PERMISSIONS, SCREENSHOTS, RELEASES }
  private enum class TextType { DESCRIPTION, ANTI_FEATURES, WHATS_NEW }

  private enum class LinkType(val iconResId: Int, val titleResId: Int,
    val format: ((Context, String) -> String)? = null) {
    AUTHOR(R.drawable.ic_person, R.string.authors_website),
    EMAIL(R.drawable.ic_email, R.string.authors_email),
    LICENSE(R.drawable.ic_copyright, R.string.license,
      format = { context, text -> context.getString(R.string.license_format, text) }),
    SOURCE(R.drawable.ic_code, R.string.source_code),
    TRACKER(R.drawable.ic_bug_report, R.string.bug_tracker),
    CHANGELOG(R.drawable.ic_history, R.string.changelog),
    WEB(R.drawable.ic_public, R.string.project_website)
  }

  private sealed class Item {
    abstract val descriptor: String
    abstract val viewType: ViewType

    class HeaderItem(val repository: Repository, val product: Product): Item() {
      override val descriptor: String
        get() = "header"

      override val viewType: ViewType
        get() = ViewType.HEADER
    }

    class SwitchItem(val switchType: SwitchType, val packageName: String, val versionCode: Long): Item() {
      override val descriptor: String
        get() = "switch.${switchType.name}"

      override val viewType: ViewType
        get() = ViewType.SWITCH
    }

    class SectionItem(val sectionType: SectionType, val expandType: ExpandType,
      val items: List<Item>, val collapseCount: Int): Item() {
      constructor(sectionType: SectionType): this(sectionType, ExpandType.NOTHING, emptyList(), 0)

      override val descriptor: String
        get() = "section.${sectionType.name}"

      override val viewType: ViewType
        get() = ViewType.SECTION
    }

    class ExpandItem(val expandType: ExpandType, val replace: Boolean, val items: List<Item>): Item() {
      override val descriptor: String
        get() = "expand.${expandType.name}"

      override val viewType: ViewType
        get() = ViewType.EXPAND
    }

    class TextItem(val textType: TextType, val text: CharSequence): Item() {
      override val descriptor: String
        get() = "text.${textType.name}"

      override val viewType: ViewType
        get() = ViewType.TEXT
    }

    sealed class LinkItem: Item() {
      override val viewType: ViewType
        get() = ViewType.LINK

      abstract val iconResId: Int
      abstract fun getTitle(context: Context): String
      abstract val uri: Uri?

      val displayLink: String?
        get() = uri?.schemeSpecificPart?.nullIfEmpty()
          ?.let { if (it.startsWith("//")) null else it } ?: uri?.toString()

      class Typed(val linkType: LinkType, val text: String, override val uri: Uri?): LinkItem() {
        override val descriptor: String
          get() = "link.typed.${linkType.name}"

        override val iconResId: Int
          get() = linkType.iconResId

        override fun getTitle(context: Context): String {
          return text.nullIfEmpty()?.let { linkType.format?.invoke(context, it) ?: it }
            ?: context.getString(linkType.titleResId)
        }
      }

      class Donate(val donate: Product.Donate): LinkItem() {
        override val descriptor: String
          get() = "link.donate.$donate"

        override val iconResId: Int
          get() = when (donate) {
            is Product.Donate.Regular -> R.drawable.ic_public
            is Product.Donate.Bitcoin -> R.drawable.ic_donate_bitcoin
            is Product.Donate.Litecoin -> R.drawable.ic_donate_litecoin
            is Product.Donate.Flattr -> R.drawable.ic_donate_flattr
            is Product.Donate.Liberapay -> R.drawable.ic_donate_liberapay
          }

        override fun getTitle(context: Context): String = when (donate) {
          is Product.Donate.Regular -> context.getString(R.string.website)
          is Product.Donate.Bitcoin -> "Bitcoin"
          is Product.Donate.Litecoin -> "Litecoin"
          is Product.Donate.Flattr -> "Flattr"
          is Product.Donate.Liberapay -> "Liberapay"
        }

        override val uri: Uri? = when (donate) {
          is Product.Donate.Regular -> Uri.parse(donate.url)
          is Product.Donate.Bitcoin -> Uri.parse("bitcoin:${donate.address}")
          is Product.Donate.Litecoin -> Uri.parse("liberapay:${donate.address}")
          is Product.Donate.Flattr -> Uri.parse("https://flattr.com/thing/${donate.id}")
          is Product.Donate.Liberapay -> Uri.parse("https://liberapay.com/~${donate.id}")
        }
      }
    }

    class PermissionsItem(val group: PermissionGroupInfo?, val permissions: List<PermissionInfo>): Item() {
      override val descriptor: String
        get() = "permissions.${group?.name}.${permissions.joinToString(separator = ".") { it.name }}"

      override val viewType: ViewType
        get() = ViewType.PERMISSIONS
    }

    class ScreenshotItem(val repository: Repository, val packageName: String,
      val screenshot: Product.Screenshot): Item() {
      override val descriptor: String
        get() = "screenshot.${repository.id}.${screenshot.identifier}"

      override val viewType: ViewType
        get() = ViewType.SCREENSHOT
    }

    class ReleaseItem(val repository: Repository, val release: Release,
      val selectedRepository: Boolean): Item() {
      override val descriptor: String
        get() = "release.${repository.id}.${release.identifier}"

      override val viewType: ViewType
        get() = ViewType.RELEASE
    }

    class EmptyItem(val packageName: String): Item() {
      override val descriptor: String
        get() = "empty"

      override val viewType: ViewType
        get() = ViewType.EMPTY
    }
  }

  private class Measurement<T: Any> {
    private var density = 0f
    private var scaledDensity = 0f
    private lateinit var metric: T

    fun measure(view: View) {
      View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED).let { view.measure(it, it) }
    }

    fun invalidate(resources: Resources, callback: () -> T): T {
      val (density, scaledDensity) = resources.displayMetrics.let { Pair(it.density, it.scaledDensity) }
      if (this.density != density || this.scaledDensity != scaledDensity) {
        this.density = density
        this.scaledDensity = scaledDensity
        metric = callback()
      }
      return metric
    }
  }

  private class HeaderViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
    val icon = itemView.findViewById<ImageView>(R.id.icon)!!
    val name = itemView.findViewById<TextView>(R.id.name)!!
    val version = itemView.findViewById<TextView>(R.id.version)!!
    val packageName = itemView.findViewById<TextView>(R.id.package_name)!!
    val action = itemView.findViewById<Button>(R.id.action)!!
    val statusLayout = itemView.findViewById<View>(R.id.status_layout)!!
    val status = itemView.findViewById<TextView>(R.id.status)!!
    val progress = itemView.findViewById<ProgressBar>(R.id.progress)!!

    val progressIcon: Drawable
    val defaultIcon: Drawable

    val actionTintNormal = action.context.getColorFromAttr(R.attr.colorAccent)
    val actionTintCancel = action.context.getColorFromAttr(R.attr.colorError)

    init {
      val (progressIcon, defaultIcon) = Utils.getDefaultApplicationIcons(icon.context)
      this.progressIcon = progressIcon
      this.defaultIcon = defaultIcon
    }
  }

  private class SwitchViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
    val title = itemView.findViewById<TextView>(R.id.title)!!
    val enabled = itemView.findViewById<SwitchCompat>(R.id.enabled)!!

    val statefulViews: Sequence<View>
      get() = sequenceOf(itemView, title, enabled)
  }

  private class SectionViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
    val title = itemView.findViewById<TextView>(R.id.title)!!
    val icon = itemView.findViewById<ImageView>(R.id.icon)!!
  }

  private class ExpandViewHolder(context: Context): RecyclerView.ViewHolder(AppCompatTextView(context)) {
    val text: TextView
      get() = itemView as TextView

    init {
      itemView as TextView
      itemView.typeface = TypefaceExtra.medium
      itemView.setTextSizeScaled(14)
      itemView.setTextColor(itemView.context.getColorFromAttr(R.attr.colorAccent))
      itemView.background = itemView.context.getDrawableFromAttr(R.attr.selectableItemBackground)
      itemView.gravity = Gravity.CENTER
      itemView.isAllCaps = true
      itemView.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
        itemView.resources.sizeScaled(48)).apply { topMargin = -itemView.resources.sizeScaled(16) }
    }
  }

  private class TextViewHolder(context: Context): RecyclerView.ViewHolder(AppCompatTextView(context)) {
    val text: TextView
      get() = itemView as TextView

    init {
      itemView as TextView
      itemView.setTextSizeScaled(14)
      itemView.setTextColor(itemView.context.getColorFromAttr(android.R.attr.textColorPrimary))
      itemView.resources.sizeScaled(16).let { itemView.setPadding(it, it, it, it) }
      itemView.movementMethod = ClickableMovementMethod
      itemView.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
        RecyclerView.LayoutParams.WRAP_CONTENT)
    }
  }

  private open class OverlappingViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
    init {
      // Block touch events if touched above negative margin
      itemView.setOnTouchListener { _, event ->
        event.action == MotionEvent.ACTION_DOWN && run {
          val top = (itemView.layoutParams as ViewGroup.MarginLayoutParams).topMargin
          top < 0 && event.y < -top
        }
      }
    }
  }

  private class LinkViewHolder(itemView: View): OverlappingViewHolder(itemView) {
    companion object {
      private val measurement = Measurement<Int>()
    }

    val icon = itemView.findViewById<ImageView>(R.id.icon)!!
    val text = itemView.findViewById<TextView>(R.id.text)!!
    val link = itemView.findViewById<TextView>(R.id.link)!!

    init {
      val margin = measurement.invalidate(itemView.resources) {
        @SuppressLint("SetTextI18n")
        text.text = "measure"
        link.visibility = View.GONE
        measurement.measure(itemView)
        ((itemView.measuredHeight - icon.measuredHeight) / 2f).roundToInt()
      }
      (icon.layoutParams as ViewGroup.MarginLayoutParams).apply {
        topMargin += margin
        bottomMargin += margin
      }
    }
  }

  private class PermissionsViewHolder(itemView: View): OverlappingViewHolder(itemView) {
    companion object {
      private val measurement = Measurement<Int>()
    }

    val icon = itemView.findViewById<ImageView>(R.id.icon)!!
    val text = itemView.findViewById<TextView>(R.id.text)!!

    init {
      val margin = measurement.invalidate(itemView.resources) {
        @SuppressLint("SetTextI18n")
        text.text = "measure"
        measurement.measure(itemView)
        ((itemView.measuredHeight - icon.measuredHeight) / 2f).roundToInt()
      }
      (icon.layoutParams as ViewGroup.MarginLayoutParams).apply {
        topMargin += margin
        bottomMargin += margin
      }
    }
  }

  private class ScreenshotViewHolder(context: Context): RecyclerView.ViewHolder(FrameLayout(context)) {
    val image: ImageView

    val placeholder: Drawable

    var gridPosition = -1
    var gridCount = -1

    init {
      itemView as FrameLayout
      itemView.foreground = itemView.context.getDrawableFromAttr(R.attr.selectableItemBackground)
      val backgroundColor = itemView.context.getColorFromAttr(android.R.attr.colorBackground).defaultColor
      val accentColor = itemView.context.getColorFromAttr(R.attr.colorAccent).defaultColor
      val primaryColor = itemView.context.getColorFromAttr(android.R.attr.textColorPrimary).defaultColor

      image = object: AppCompatImageView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
          super.onMeasure(widthMeasureSpec, heightMeasureSpec)
          setMeasuredDimension(measuredWidth, measuredWidth)
        }
      }
      image.scaleType = ImageView.ScaleType.CENTER_CROP
      image.setBackgroundColor(ColorUtils.blendARGB(backgroundColor, accentColor, 0.1f))
      itemView.addView(image, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
      itemView.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
        RecyclerView.LayoutParams.WRAP_CONTENT)

      val placeholder = ContextCompat.getDrawable(image.context, R.drawable.ic_photo_camera)!!.mutate()
      placeholder.setTint(ColorUtils.blendARGB(primaryColor, accentColor, 0.5f)
        .let { ColorUtils.blendARGB(0x00ffffff and it, it, 0.2f) })
      this.placeholder = PaddingDrawable(placeholder, 2f)
    }
  }

  private class ReleaseViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
    val dateFormat = DateFormat.getDateFormat(itemView.context)!!

    val version = itemView.findViewById<TextView>(R.id.version)!!
    val status = itemView.findViewById<TextView>(R.id.status)!!
    val source = itemView.findViewById<TextView>(R.id.source)!!
    val added = itemView.findViewById<TextView>(R.id.added)!!
    val size = itemView.findViewById<TextView>(R.id.size)!!
    val error = itemView.findViewById<TextView>(R.id.error)!!

    val statefulViews: Sequence<View>
      get() = sequenceOf(itemView, version, status, source, added, size, error)

    val setStatusActive: (Boolean) -> Unit

    init {
      status.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, null).apply {
        setStatusActive = { active -> color = itemView.context.getColorFromAttr(if (active)
          R.attr.colorAccent else android.R.attr.textColorSecondary) }
        cornerRadius = itemView.resources.sizeScaled(2).toFloat()
      }
    }
  }

  private class EmptyViewHolder(context: Context): RecyclerView.ViewHolder(LinearLayout(context)) {
    val packageName: TextView

    init {
      itemView as LinearLayout
      itemView.orientation = LinearLayout.VERTICAL
      itemView.gravity = Gravity.CENTER
      itemView.resources.sizeScaled(20).let { itemView.setPadding(it, it, it, it) }
      val title = AppCompatTextView(itemView.context)
      title.gravity = Gravity.CENTER
      title.typeface = TypefaceExtra.light
      title.setTextColor(context.getColorFromAttr(android.R.attr.textColorPrimary))
      title.setTextSizeScaled(20)
      title.setText(R.string.application_not_found)
      itemView.addView(title, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
      val packageName = AppCompatTextView(itemView.context)
      packageName.gravity = Gravity.CENTER
      packageName.setTextColor(context.getColorFromAttr(android.R.attr.textColorPrimary))
      packageName.setTextSizeScaled(16)
      itemView.addView(packageName, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
      itemView.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
        RecyclerView.LayoutParams.MATCH_PARENT)
      this.packageName = packageName
    }
  }

  val gridItemDecoration = object: RecyclerView.ItemDecoration() {
    private fun decorateScreenshot(outRect: Rect, parent: RecyclerView, position: Int, count: Int) {
      val column = position % columns
      val rows = (count + columns - 1) / columns
      val row = position / columns
      val outer = parent.resources.sizeScaled(GRID_SPACING_OUTER_DP)
      val inner = parent.resources.sizeScaled(GRID_SPACING_INNER_DP)
      val total = 2 * outer + (columns - 1) * inner
      // Each column should have this spacing
      val average = total / columns
      // Start spacing is in the range from [outer] to [average - outer]
      // Interpolate between these values
      val start = (outer - ((2 * outer - average) * (column.toFloat() / (columns - 1)))).roundToInt()
      val end = average - start
      val bottom = if (row + 1 >= rows) outer else 0
      val rtl = parent.layoutDirection == View.LAYOUT_DIRECTION_RTL
      if (rtl) {
        outRect.set(end, inner, start, bottom)
      } else {
        outRect.set(start, inner, end, bottom)
      }
    }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
      val holder = parent.getChildViewHolder(view)
      if (holder is ScreenshotViewHolder) {
        val position = holder.adapterPosition
        if (position >= 0) {
          val first = items.subList(0, position).indexOfLast { it !is Item.ScreenshotItem } + 1
          val gridCount = items.subList(first, items.size).indexOfFirst { it !is Item.ScreenshotItem }
            .let { if (it < 0) items.size - first else it }
          val gridPosition = position - first
          decorateScreenshot(outRect, parent, gridPosition, gridCount)
          holder.gridPosition = gridPosition
          holder.gridCount = gridCount
        } else if (position == -1 && holder.gridPosition >= 0 && holder.gridCount >= 0) {
          decorateScreenshot(outRect, parent, holder.gridPosition, holder.gridCount)
        }
      }
    }
  }

  fun requiresGrid(position: Int): Boolean {
    return items[position] is Item.ScreenshotItem
  }

  fun configureDivider(context: Context, position: Int, configuration: DividerItemDecoration.Configuration) {
    val currentItem = items[position]
    val nextItem = items.getOrNull(position + 1)
    when {
      nextItem == null || currentItem is Item.HeaderItem ||
        nextItem is Item.TextItem && nextItem.textType == TextType.DESCRIPTION -> {
        configuration.set(true, false, 0, 0)
      }
      nextItem is Item.SectionItem -> {
        configuration.set(true, true, 0, 0)
      }
      currentItem is Item.LinkItem && nextItem is Item.LinkItem ||
        currentItem is Item.PermissionsItem && nextItem is Item.PermissionsItem -> {
        configuration.set(true, false, context.resources.sizeScaled(72), 0)
      }
      currentItem is Item.SwitchItem && nextItem is Item.SwitchItem ||
        currentItem is Item.ReleaseItem && nextItem is Item.ReleaseItem -> {
        configuration.set(true, false, context.resources.sizeScaled(16), 0)
      }
      else -> {
        configuration.set(false, false, 0, 0)
      }
    }
  }

  private val items = mutableListOf<Item>()
  private val expanded = mutableSetOf<ExpandType>()
  private var product: Product? = null

  fun setProducts(context: Context, products: List<Pair<Product, Repository>>, packageName: String) {
    val productRepository = Product.findSuggested(products) { it.first }
    items.clear()

    if (productRepository != null) {
      items += Item.HeaderItem(productRepository.second, productRepository.first)

      val textViewHolder = TextViewHolder(context)
      val textViewWidthSpec = context.resources.displayMetrics.widthPixels
        .let { View.MeasureSpec.makeMeasureSpec(it, View.MeasureSpec.EXACTLY) }
      val textViewHeightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

      fun CharSequence.lineCropped(maxLines: Int, cropLines: Int): CharSequence? {
        assert(cropLines <= maxLines)
        textViewHolder.text.text = this
        textViewHolder.itemView.measure(textViewWidthSpec, textViewHeightSpec)
        textViewHolder.itemView.layout(0, 0, textViewHolder.itemView.measuredWidth,
          textViewHolder.itemView.measuredHeight)
        val layout = textViewHolder.text.layout
        val cropLineOffset = if (layout.lineCount <= maxLines) -1 else layout.getLineEnd(cropLines - 1)
        val paragraphEndIndex = if (cropLineOffset < 0) -1 else
          indexOf("\n\n", cropLineOffset).let { if (it >= 0) it else length }
        val paragraphEndLine = if (paragraphEndIndex < 0) -1 else
          layout.getLineForOffset(paragraphEndIndex).apply { assert(this >= 0) }
        val end = when {
          cropLineOffset < 0 -> -1
          paragraphEndLine >= 0 && paragraphEndLine - (cropLines - 1) <= 3 ->
            if (paragraphEndIndex < length) paragraphEndIndex else -1
          else -> cropLineOffset
        }
        val length = if (end < 0) -1 else asSequence().take(end)
          .indexOfLast { it != '\n' }.let { if (it >= 0) it + 1 else end }
        return if (length >= 0) subSequence(0, length) else null
      }

      val description = formatHtml(productRepository.first.description).apply {
        if (productRepository.first.let { it.summary.isNotEmpty() && it.name != it.summary }) {
          if (isNotEmpty()) {
            insert(0, "\n\n")
          }
          insert(0, productRepository.first.summary)
          if (isNotEmpty()) {
            setSpan(TypefaceSpan("sans-serif-medium"), 0, productRepository.first.summary.length,
              SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
          }
        }
      }
      if (description.isNotEmpty()) {
        val cropped = if (ExpandType.DESCRIPTION !in expanded) description.lineCropped(12, 10) else null
        val item = Item.TextItem(TextType.DESCRIPTION, description)
        if (cropped != null) {
          items += listOf(Item.TextItem(TextType.DESCRIPTION, cropped),
            Item.ExpandItem(ExpandType.DESCRIPTION, true, listOf(item)))
        } else {
          items += item
        }
      }

      val antiFeatures = productRepository.first.antiFeatures.map {
        when (it) {
          "Ads" -> context.getString(R.string.anti_feature_advertising)
          "ApplicationDebuggable" -> context.getString(R.string.anti_feature_application_debuggable)
          "DisabledAlgorithm" -> context.getString(R.string.anti_feature_disabled_algorithm)
          "KnownVuln" -> context.getString(R.string.anti_feature_known_vulnerabilities)
          "NoSourceSince" -> context.getString(R.string.anti_feature_no_source_since)
          "NonFreeAdd" -> context.getString(R.string.anti_feature_non_free_addons)
          "NonFreeAssets" -> context.getString(R.string.anti_feature_non_free_assets)
          "NonFreeDep" -> context.getString(R.string.anti_feature_non_free_dependencies)
          "NonFreeNet" -> context.getString(R.string.anti_feature_non_free_network)
          "Tracking" -> context.getString(R.string.anti_feature_tracking)
          "UpstreamNonFree" -> context.getString(R.string.anti_feature_upstream_non_free)
          else -> context.getString(R.string.unknown_format, it)
        }
      }.joinToString(separator = "\n") { "\u2022 $it" }
      if (antiFeatures.isNotEmpty()) {
        items += Item.SectionItem(SectionType.ANTI_FEATURES)
        items += Item.TextItem(TextType.ANTI_FEATURES, antiFeatures)
      }

      val whatsNew = formatHtml(productRepository.first.whatsNew)
      if (whatsNew.isNotEmpty()) {
        items += Item.SectionItem(SectionType.WHATS_NEW)
        val cropped = if (ExpandType.WHATS_NEW !in expanded) whatsNew.lineCropped(12, 10) else null
        val item = Item.TextItem(TextType.WHATS_NEW, whatsNew)
        if (cropped != null) {
          items += listOf(Item.TextItem(TextType.WHATS_NEW, cropped),
            Item.ExpandItem(ExpandType.WHATS_NEW, true, listOf(item)))
        } else {
          items += item
        }
      }

      val linkItems = mutableListOf<Item>()
      productRepository.first.apply {
        if (author.name.isNotEmpty() || author.web.isNotEmpty()) {
          linkItems += Item.LinkItem.Typed(LinkType.AUTHOR, author.name, author.web.nullIfEmpty()?.let(Uri::parse))
        }
        author.email.nullIfEmpty()?.let { linkItems += Item.LinkItem
          .Typed(LinkType.EMAIL, "", Uri.parse("mailto:$it")) }
        linkItems += licenses.asSequence().map { Item.LinkItem.Typed(LinkType.LICENSE, it,
          Uri.parse("https://spdx.org/licenses/$it.html")) }
        source.nullIfEmpty()?.let { linkItems += Item.LinkItem.Typed(LinkType.SOURCE, "", Uri.parse(it)) }
        tracker.nullIfEmpty()?.let { linkItems += Item.LinkItem.Typed(LinkType.TRACKER, "", Uri.parse(it)) }
        changelog.nullIfEmpty()?.let { linkItems += Item.LinkItem.Typed(LinkType.CHANGELOG, "", Uri.parse(it)) }
        web.nullIfEmpty()?.let { linkItems += Item.LinkItem.Typed(LinkType.WEB, "", Uri.parse(it)) }
      }
      if (linkItems.isNotEmpty()) {
        if (ExpandType.LINKS in expanded) {
          items += Item.SectionItem(SectionType.LINKS, ExpandType.LINKS, emptyList(), linkItems.size)
          items += linkItems
        } else {
          items += Item.SectionItem(SectionType.LINKS, ExpandType.LINKS, linkItems, 0)
        }
      }

      val donateItems = productRepository.first.donates.map(Item.LinkItem::Donate)
      if (donateItems.isNotEmpty()) {
        if (ExpandType.DONATES in expanded) {
          items += Item.SectionItem(SectionType.DONATE, ExpandType.DONATES, emptyList(), donateItems.size)
          items += donateItems
        } else {
          items += Item.SectionItem(SectionType.DONATE, ExpandType.DONATES, donateItems, 0)
        }
      }

      val release = productRepository.first.displayRelease
      if (release != null) {
        val packageManager = context.packageManager
        val permissions = release.permissions
          .asSequence().mapNotNull {
            try {
              packageManager.getPermissionInfo(it, 0)
            } catch (e: Exception) {
              null
            }
          }
          .groupBy(PackageItemResolver::getPermissionGroup)
          .asSequence().map { (group, permissionInfo) ->
            val permissionGroupInfo = try {
              group?.let { packageManager.getPermissionGroupInfo(it, 0) }
            } catch (e: Exception) {
              null
            }
            Pair(permissionGroupInfo, permissionInfo)
          }
          .groupBy({ it.first }, { it.second })
        if (permissions.isNotEmpty()) {
          val permissionsItems = mutableListOf<Item>()
          permissionsItems += permissions.asSequence().filter { it.key != null }
            .map { Item.PermissionsItem(it.key, it.value.flatten()) }
          permissions.asSequence().find { it.key == null }
            ?.let { permissionsItems += Item.PermissionsItem(null, it.value.flatten()) }
          if (ExpandType.PERMISSIONS in expanded) {
            items += Item.SectionItem(SectionType.PERMISSIONS, ExpandType.PERMISSIONS,
              emptyList(), permissionsItems.size)
            items += permissionsItems
          } else {
            items += Item.SectionItem(SectionType.PERMISSIONS, ExpandType.PERMISSIONS, permissionsItems, 0)
          }
        }
      }

      val screenshotItems = productRepository.first.screenshots
        .map { Item.ScreenshotItem(productRepository.second, productRepository.first.packageName, it) }
      if (screenshotItems.isNotEmpty()) {
        if (ExpandType.SCREENSHOTS in expanded) {
          items += Item.SectionItem(SectionType.SCREENSHOTS, ExpandType.SCREENSHOTS, emptyList(), screenshotItems.size)
          items += screenshotItems
        } else {
          items += Item.SectionItem(SectionType.SCREENSHOTS, ExpandType.SCREENSHOTS, screenshotItems, 0)
        }
      }
    }

    val incompatible = Preferences[Preferences.Key.IncompatibleVersions]
    val releases = products.flatMap { (product, repository) -> product.releases
      .map { Item.ReleaseItem(repository, it, repository.id == productRepository?.second?.id) } }
      .filter { incompatible || it.release.incompatibilities.isEmpty() }
      .sortedByDescending { it.release.versionCode }
    if (releases.isNotEmpty()) {
      items += Item.SectionItem(SectionType.RELEASES)
      val maxReleases = 5
      if (releases.size > maxReleases && ExpandType.RELEASES !in expanded) {
        items += releases.take(maxReleases)
        items += Item.ExpandItem(ExpandType.RELEASES, false, releases.takeLast(releases.size - maxReleases))
      } else {
        items += releases
      }
    }

    if (items.isEmpty()) {
      items += Item.EmptyItem(packageName)
    }
    this.product = productRepository?.first
    updateSwitches()
    notifyDataSetChanged()
  }

  var installedItem: InstalledItem? = null
    set(value) {
      field = value
      updateSwitches()
      notifyDataSetChanged()
    }

  private var action: Action? = null

  fun setAction(recyclerView: RecyclerView, action: Action?) {
    if (this.action != action) {
      this.action = action
      if (items.getOrNull(0) is Item.HeaderItem) {
        val holder = recyclerView.findViewHolderForAdapterPosition(0)
        holder?.let { bindViewHolder(holder, 0) }
      }
    }
  }

  private var status: Status? = null

  fun setStatus(recyclerView: RecyclerView, status: Status?) {
    val notify = (this.status == null) != (status == null)
    if (this.status != status) {
      this.status = status
      if (items.getOrNull(0) is Item.HeaderItem) {
        if (notify) {
          notifyItemChanged(0)
          val from = items.indexOfFirst { it is Item.ReleaseItem }
          val to = items.indexOfLast { it is Item.ReleaseItem }
          if (from in 0 .. to) {
            notifyItemRangeChanged(from, to - from + 1)
          }
        } else {
          val holder = recyclerView.findViewHolderForAdapterPosition(0)
          holder?.let { bindViewHolder(holder, 0) }
        }
      }
    }
  }

  private fun updateSwitches() {
    val product = product
    val installedItem = installedItem
    items.removeAll { it is Item.SwitchItem }
    val index = items.indexOfFirst { it is Item.HeaderItem }
    if (index >= 0 && product != null && installedItem != null) {
      items.add(index + 1, Item.SwitchItem(SwitchType.IGNORE_ALL_UPDATES, product.packageName, product.versionCode))
      if (product.canUpdate(installedItem)) {
        items.add(index + 2, Item.SwitchItem(SwitchType.IGNORE_THIS_UPDATE, product.packageName, product.versionCode))
      }
    }
  }

  override val viewTypeClass: Class<ViewType>
    get() = ViewType::class.java

  override fun getItemCount(): Int = items.size
  override fun getItemDescriptor(position: Int): String = items[position].descriptor
  override fun getItemEnumViewType(position: Int): ViewType = items[position].viewType

  override fun onCreateViewHolder(parent: ViewGroup, viewType: ViewType): RecyclerView.ViewHolder {
    return when (viewType) {
      ViewType.HEADER -> HeaderViewHolder(parent.inflate(R.layout.product_header_item)).apply {
        action.setOnClickListener { this@ProductAdapter.action?.let(callbacks::onActionClick) }
      }
      ViewType.SWITCH -> SwitchViewHolder(parent.inflate(R.layout.switch_item)).apply {
        itemView.setOnClickListener {
          val positions = items.asSequence().mapIndexedNotNull { index, item -> if (item is Item.HeaderItem ||
            item is Item.SwitchItem) index else null }
          val switchItem = items[adapterPosition] as Item.SwitchItem
          val productPreference = when (switchItem.switchType) {
            SwitchType.IGNORE_ALL_UPDATES -> {
              ProductPreferences[switchItem.packageName].let { it.copy(ignoreUpdates = !it.ignoreUpdates) }
            }
            SwitchType.IGNORE_THIS_UPDATE -> {
              ProductPreferences[switchItem.packageName].let { it.copy(ignoreVersionCode =
                if (it.ignoreVersionCode == switchItem.versionCode) 0 else switchItem.versionCode) }
            }
          }
          ProductPreferences[switchItem.packageName] = productPreference
          val recyclerView = itemView.parent as RecyclerView
          positions.forEach {
            val holder = recyclerView.findViewHolderForAdapterPosition(it)
            if (holder != null) {
              bindViewHolder(holder, it)
            }
          }
          callbacks.onPreferenceChanged(productPreference)
        }
      }
      ViewType.SECTION -> SectionViewHolder(parent.inflate(R.layout.section_item)).apply {
        itemView.setOnClickListener {
          val position = adapterPosition
          val sectionItem = items[position] as Item.SectionItem
          if (sectionItem.items.isNotEmpty()) {
            expanded += sectionItem.expandType
            items[position] = Item.SectionItem(sectionItem.sectionType, sectionItem.expandType, emptyList(),
              sectionItem.items.size + sectionItem.collapseCount)
            bindViewHolder(this, position)
            items.addAll(position + 1, sectionItem.items)
            notifyItemRangeInserted(position + 1, sectionItem.items.size)
          } else if (sectionItem.collapseCount > 0) {
            expanded -= sectionItem.expandType
            items[position] = Item.SectionItem(sectionItem.sectionType, sectionItem.expandType,
              items.subList(position + 1, position + 1 + sectionItem.collapseCount).toList(), 0)
            bindViewHolder(this, position)
            repeat(sectionItem.collapseCount) { items.removeAt(position + 1) }
            notifyItemRangeRemoved(position + 1, sectionItem.collapseCount)
          }
        }
      }
      ViewType.EXPAND -> ExpandViewHolder(parent.context).apply {
        itemView.setOnClickListener {
          val position = adapterPosition
          val expandItem = items[position] as Item.ExpandItem
          items.removeAt(position)
          expanded += expandItem.expandType
          if (expandItem.replace) {
            expandItem.items.forEachIndexed { index, item ->
              items[position - expandItem.items.size + index] = item
            }
            notifyItemRangeChanged(position - expandItem.items.size, expandItem.items.size)
            notifyItemRemoved(position)
          } else {
            items.addAll(position, expandItem.items)
            if (position > 0) {
              // Update decorations
              notifyItemChanged(position - 1)
            }
            notifyItemRemoved(position)
            notifyItemRangeInserted(position, expandItem.items.size)
          }
        }
      }
      ViewType.TEXT -> TextViewHolder(parent.context)
      ViewType.LINK -> LinkViewHolder(parent.inflate(R.layout.link_item)).apply {
        itemView.setOnClickListener {
          val linkItem = items[adapterPosition] as Item.LinkItem
          if (linkItem.uri?.let { callbacks.onUriClick(it, false) } != true) {
            linkItem.displayLink?.let { copyLinkToClipboard(itemView.context, it) }
          }
        }
        itemView.setOnLongClickListener {
          val linkItem = items[adapterPosition] as Item.LinkItem
          linkItem.displayLink?.let { copyLinkToClipboard(itemView.context, it) }
          true
        }
      }
      ViewType.PERMISSIONS -> PermissionsViewHolder(parent.inflate(R.layout.permissions_item)).apply {
        itemView.setOnClickListener {
          val permissionsItem = items[adapterPosition] as Item.PermissionsItem
          callbacks.onPermissionsClick(permissionsItem.group?.name, permissionsItem.permissions.map { it.name })
        }
      }
      ViewType.SCREENSHOT -> ScreenshotViewHolder(parent.context).apply {
        itemView.setOnClickListener {
          val screenshotItem = items[adapterPosition] as Item.ScreenshotItem
          callbacks.onScreenshotClick(screenshotItem.screenshot)
        }
      }
      ViewType.RELEASE -> ReleaseViewHolder(parent.inflate(R.layout.release_item)).apply {
        itemView.setOnClickListener {
          val releaseItem = items[adapterPosition] as Item.ReleaseItem
          callbacks.onReleaseClick(releaseItem.release)
        }
      }
      ViewType.EMPTY -> EmptyViewHolder(parent.context)
    }
  }

  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    val context = holder.itemView.context
    val item = items[position]
    when (getItemEnumViewType(position)) {
      ViewType.HEADER -> {
        holder as HeaderViewHolder
        item as Item.HeaderItem
        val installedItem = installedItem
        if (item.product.icon.isNotEmpty()) {
          holder.icon.load(CoilDownloader.createIconUri(holder.icon, item.product.icon, item.repository)) {
            placeholder(holder.progressIcon)
            error(holder.defaultIcon)
          }
        } else {
          holder.icon.clear()
          holder.icon.setImageDrawable(holder.defaultIcon)
        }
        holder.name.text = item.product.name
        val canUpdate = item.product.canUpdate(installedItem) &&
          !ProductPreferences[item.product.packageName].shouldIgnoreUpdate(item.product.versionCode)
        val version = (if (canUpdate) item.product.version else installedItem?.version)?.nullIfEmpty()
          ?: item.product.version.nullIfEmpty()
        holder.version.text = version?.let { context.getString(R.string.version_format, it) }
          ?: context.getString(R.string.unknown)
        holder.packageName.text = item.product.packageName
        val action = action
        val status = status
        holder.action.visibility = if (action == null) View.GONE else View.VISIBLE
        if (action != null) {
          holder.action.setText(action.titleResId)
        }
        holder.action.backgroundTintList = if (action == Action.CANCEL)
          holder.actionTintCancel else holder.actionTintNormal
        holder.statusLayout.visibility = if (status != null) View.VISIBLE else View.GONE
        if (status != null) {
          when (status) {
            is Status.Pending -> {
              holder.status.setText(R.string.waiting_to_start_download)
              holder.progress.isIndeterminate = true
            }
            is Status.Connecting -> {
              holder.status.setText(R.string.connecting)
              holder.progress.isIndeterminate = true
            }
            is Status.Downloading -> {
              holder.status.text = context.getString(R.string.downloading_format, if (status.total == null)
                status.read.formatSize() else "${status.read.formatSize()} / ${status.total.formatSize()}")
              holder.progress.isIndeterminate = status.total == null
              if (status.total != null) {
                holder.progress.progress = (holder.progress.max.toFloat() * status.read / status.total).roundToInt()
              }
              Unit
            }
          }::class
        }
        Unit
      }
      ViewType.SWITCH -> {
        holder as SwitchViewHolder
        item as Item.SwitchItem
        holder.title.setText(item.switchType.titleResId)
        val (checked, enabled) = when (item.switchType) {
          SwitchType.IGNORE_ALL_UPDATES -> {
            val productPreference = ProductPreferences[item.packageName]
            Pair(productPreference.ignoreUpdates, true)
          }
          SwitchType.IGNORE_THIS_UPDATE -> {
            val productPreference = ProductPreferences[item.packageName]
            Pair(productPreference.ignoreUpdates || productPreference.ignoreVersionCode == item.versionCode,
              !productPreference.ignoreUpdates)
          }
        }
        holder.enabled.isChecked = checked
        holder.statefulViews.forEach { it.isEnabled = enabled }
      }
      ViewType.SECTION -> {
        holder as SectionViewHolder
        item as Item.SectionItem
        val expandable = item.items.isNotEmpty() || item.collapseCount > 0
        holder.itemView.isEnabled = expandable
        holder.itemView.let { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight,
          if (expandable) it.paddingTop else 0) }
        val color = context.getColorFromAttr(item.sectionType.colorAttrResId)
        holder.title.setTextColor(color)
        holder.title.text = context.getString(item.sectionType.titleResId)
        holder.icon.visibility = if (expandable) View.VISIBLE else View.GONE
        holder.icon.scaleY = if (item.collapseCount > 0) -1f else 1f
        holder.icon.imageTintList = color
      }
      ViewType.EXPAND -> {
        holder as ExpandViewHolder
        item as Item.ExpandItem
        holder.text.setText(when (item.expandType) {
          ExpandType.RELEASES -> R.string.show_older_releases
          else -> R.string.show_more
        })
      }
      ViewType.TEXT -> {
        holder as TextViewHolder
        item as Item.TextItem
        holder.text.text = item.text
      }
      ViewType.LINK -> {
        holder as LinkViewHolder
        item as Item.LinkItem
        val layoutParams = holder.itemView.layoutParams as RecyclerView.LayoutParams
        layoutParams.topMargin = if (position > 0 && items[position - 1] !is Item.LinkItem)
          -context.resources.sizeScaled(8) else 0
        holder.itemView.isEnabled = item.uri != null
        holder.icon.setImageResource(item.iconResId)
        holder.text.text = item.getTitle(context)
        holder.link.visibility = if (item.uri != null) View.VISIBLE else View.GONE
        holder.link.text = item.displayLink
      }
      ViewType.PERMISSIONS -> {
        holder as PermissionsViewHolder
        item as Item.PermissionsItem
        val layoutParams = holder.itemView.layoutParams as RecyclerView.LayoutParams
        layoutParams.topMargin = if (position > 0 && items[position - 1] !is Item.PermissionsItem)
          -context.resources.sizeScaled(8) else 0
        val packageManager = context.packageManager
        holder.icon.setImageDrawable(if (item.group != null && item.group.icon != 0) {
          if (Android.sdk(22)) {
            item.group.loadUnbadgedIcon(packageManager)
          } else {
            item.group.loadIcon(packageManager)
          }
        } else {
          null
        } ?: ContextCompat.getDrawable(context, R.drawable.ic_perm_device_information))
        val localCache = PackageItemResolver.LocalCache()
        val labels = item.permissions.map { permission ->
          val labelFromPackage = PackageItemResolver.loadLabel(context, localCache, permission)
          val label = labelFromPackage ?: run {
            val prefixes = listOf("android.permission.", "com.android.browser.permission.")
            prefixes.find { permission.name.startsWith(it) }?.let {
              val transform = permission.name.substring(it.length)
              if (transform.matches("[A-Z_]+".toRegex())) {
                transform.split("_").joinToString(separator = " ") { it.toLowerCase(Locale.US) }
              } else {
                null
              }
            }
          }
          if (label == null) {
            Pair(false, permission.name)
          } else {
            Pair(true, label.first().toUpperCase() + label.substring(1, label.length))
          }
        }
        val builder = SpannableStringBuilder()
        (labels.asSequence().filter { it.first } + labels.asSequence().filter { !it.first }).forEach {
          if (builder.isNotEmpty()) {
            builder.append("\n\n")
            builder.setSpan(RelativeSizeSpan(1f / 3f), builder.length - 2, builder.length,
              SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
          }
          builder.append(it.second)
          if (!it.first) {
            // Replace dots with spans to enable word wrap
            it.second.asSequence()
              .mapIndexedNotNull { index, c -> if (c == '.') index else null }
              .map { index -> index + builder.length - it.second.length }
              .forEach { index -> builder.setSpan(DotSpan(), index, index + 1,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE) }
          }
        }
        holder.text.text = builder
      }
      ViewType.SCREENSHOT -> {
        holder as ScreenshotViewHolder
        item as Item.ScreenshotItem
        val screenWidth = context.resources.displayMetrics.widthPixels
        val outer = context.resources.sizeScaled(GRID_SPACING_OUTER_DP)
        val inner = context.resources.sizeScaled(GRID_SPACING_INNER_DP)
        val cellSize = (screenWidth - 2 * outer - (columns - 1) * inner) / columns
        holder.image.load(CoilDownloader.createScreenshotUri(item.repository, item.packageName, item.screenshot)) {
          size(cellSize)
          placeholder(holder.placeholder)
          error(holder.placeholder)
        }
        Unit
      }
      ViewType.RELEASE -> {
        holder as ReleaseViewHolder
        item as Item.ReleaseItem
        val incompatibility = item.release.incompatibilities.firstOrNull()
        val installed = installedItem?.versionCode == item.release.versionCode &&
          installedItem?.signature == item.release.signature
        val suggested = incompatibility == null && item.release.selected && item.selectedRepository
        val olderOrSignature = installedItem?.let { it.versionCode > item.release.versionCode ||
          it.signature != item.release.signature } == true
        val grayOut = incompatibility != null || olderOrSignature
        val primarySecondaryColor = context.getColorFromAttr(if (grayOut)
          android.R.attr.textColorSecondary else android.R.attr.textColorPrimary)
        holder.version.text = context.getString(R.string.version_format, item.release.version)
        holder.version.setTextColor(primarySecondaryColor)
        holder.setStatusActive(!grayOut)
        holder.status.visibility = if (installed || suggested) View.VISIBLE else View.GONE
        holder.status.setText(when {
          installed -> R.string.installed
          suggested -> R.string.suggested
          else -> R.string.unknown
        })
        holder.source.text = context.getString(R.string.provided_by_format, item.repository.name)
        holder.added.text = holder.dateFormat.format(item.release.added)
        holder.added.setTextColor(primarySecondaryColor)
        holder.size.text = item.release.size.formatSize()
        holder.error.visibility = if (incompatibility != null) View.VISIBLE else View.GONE
        if (incompatibility != null) {
          holder.error.text = when (incompatibility) {
            is Release.Incompatibility.MinSdk,
            is Release.Incompatibility.MaxSdk -> context.getString(R.string.is_not_supported_format, Android.name)
            is Release.Incompatibility.Platform -> context.getString(R.string.is_not_supported_format,
              Android.primaryPlatform ?: context.getString(R.string.unknown))
            is Release.Incompatibility.Feature -> context.getString(R.string.requires_format, incompatibility.feature)
          }
        }
        val enabled = status == null
        holder.statefulViews.forEach { it.isEnabled = enabled }
      }
      ViewType.EMPTY -> {
        holder as EmptyViewHolder
        item as Item.EmptyItem
        holder.packageName.text = item.packageName
      }
    }::class
  }

  private fun formatHtml(text: String): SpannableStringBuilder {
    val html = if (Android.sdk(24)) Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY) else
      @Suppress("DEPRECATION") Html.fromHtml(text)
    val builder = run {
      val builder = SpannableStringBuilder(html)
      val last = builder.indexOfLast { it != '\n' }
      val first = builder.indexOfFirst { it != '\n' }
      if (last >= 0) {
        builder.delete(last + 1, builder.length)
      }
      if (first in 1 until last) {
        builder.delete(0, first - 1)
      }
      generateSequence(builder) {
        val index = it.indexOf("\n\n\n")
        if (index >= 0) it.delete(index, index + 1) else null
      }.last()
    }
    LinkifyCompat.addLinks(builder, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)
    val urlSpans = builder.getSpans(0, builder.length, URLSpan::class.java).orEmpty()
    for (span in urlSpans) {
      val start = builder.getSpanStart(span)
      val end = builder.getSpanEnd(span)
      val flags = builder.getSpanFlags(span)
      builder.removeSpan(span)
      builder.setSpan(LinkSpan(span.url, this), start, end, flags)
    }
    val bulletSpans = builder.getSpans(0, builder.length, BulletSpan::class.java).orEmpty()
      .asSequence().map { Pair(it, builder.getSpanStart(it)) }.sortedByDescending { it.second }
    for (spanPair in bulletSpans) {
      val (span, start) = spanPair
      builder.removeSpan(span)
      builder.insert(start, "\u2022 ")
    }
    return builder
  }

  private fun copyLinkToClipboard(context: Context, link: String) {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboardManager.setPrimaryClip(ClipData.newPlainText(null, link))
    Toast.makeText(context, context.getString(R.string.link_copied_to_clipboard), Toast.LENGTH_SHORT).show()
  }

  private class LinkSpan(private val url: String, productAdapter: ProductAdapter): ClickableSpan() {
    private val productAdapterReference = WeakReference(productAdapter)

    override fun onClick(view: View) {
      val productAdapter = productAdapterReference.get()
      val uri = try {
        Uri.parse(url)
      } catch (e: Exception) {
        e.printStackTrace()
        null
      }
      if (productAdapter != null && uri != null) {
        productAdapter.callbacks.onUriClick(uri, true)
      }
    }
  }

  private class DotSpan: ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
      return paint.measureText(".").roundToInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int,
      x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
      canvas.drawText(".", x, y.toFloat(), paint)
    }
  }

  class SavedState internal constructor(internal val expanded: Set<ExpandType>): KParcelable {
    override fun writeToParcel(dest: Parcel, flags: Int) {
      dest.writeStringList(expanded.map { it.name }.toList())
    }

    companion object {
      @Suppress("unused") @JvmField val CREATOR = KParcelable.creator {
        val expanded = it.createStringArrayList()!!.map(ExpandType::valueOf).toSet()
        SavedState(expanded)
      }
    }
  }

  fun saveState(): SavedState? {
    return if (expanded.isNotEmpty()) {
      SavedState(expanded)
    } else {
      null
    }
  }

  fun restoreState(savedState: SavedState) {
    expanded.clear()
    expanded += savedState.expanded
  }
}