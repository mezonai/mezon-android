package com.mezon.mobile.home.clans

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.util.CanvasBodyContent
import com.mezon.mobile.util.CanvasContentHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ChannelCanvasFragment : BaseFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CHANNEL_TYPE = "channelType"
        private const val ARG_CANVAS_ID = "canvasId"
        private const val ARG_INITIAL_TITLE = "initialTitle"
        private const val BODY_MARKER = "<!--BODY-->"

        fun newInstance(
            clanId: Long,
            channelId: Long,
            channelType: Int,
            canvasId: Long,
            initialTitle: String = ""
        ): ChannelCanvasFragment = ChannelCanvasFragment().apply {
            arguments = android.os.Bundle().apply {
                putLong(ARG_CLAN_ID, clanId)
                putLong(ARG_CHANNEL_ID, channelId)
                putInt(ARG_CHANNEL_TYPE, channelType)
                putLong(ARG_CANVAS_ID, canvasId)
                putString(ARG_INITIAL_TITLE, initialTitle)
            }
        }
    }

    private var clanId = 0L
    private var channelId = 0L
    private var channelType = 0
    private var canvasId = 0L
    private var initialTitle = ""

    private lateinit var channelCanvasController: ChannelCanvasController

    private var webView: WebView? = null
    private var loadingView: ProgressBar? = null
    private var errorView: TextView? = null
    private var loadedCanvas: ChannelCanvasData? = null
    private var renderJob: Job? = null
    private var lastRenderedContent: String? = null
    private var documentShell: String? = null
    private var documentBodyOffset = 0
    private var cachedDocumentThemeKey = 0

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        channelType = arguments?.getInt(ARG_CHANNEL_TYPE) ?: 0
        canvasId = arguments?.getLong(ARG_CANVAS_ID) ?: 0L
        initialTitle = arguments?.getString(ARG_INITIAL_TITLE).orEmpty()

        observe(NotificationCenter.channelCanvasDetailDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val ch = args.firstOrNull() as? Long ?: return@observe
            val id = args.getOrNull(1) as? Long ?: return@observe
            if (ch == channelId && id == canvasId) applyLoadedCanvas()
        }
        observe(NotificationCenter.channelCanvasDetailLoadError) { _, _, args ->
            if (isPaused) return@observe
            val ch = args.firstOrNull() as? Long ?: return@observe
            val id = args.getOrNull(1) as? Long ?: return@observe
            if (ch == channelId && id == canvasId) showLoadError()
        }

        channelCanvasController.loadCanvasDetail(channelId, clanId, channelType, canvasId)
        return true
    }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        channelCanvasController = entryPoint.channelCanvasController()
    }

    override fun createView(context: Context): View {
        val root = FrameLayout(context).apply {
            setBackgroundColor(themeColors.background)
        }

        val currentLocale = context.resources.configuration.locales[0]
        val canvasWebView = WebView(context)
        restoreLocale(context, currentLocale)
        webView = canvasWebView.apply {
            settings.apply {
                javaScriptEnabled = false
                domStorageEnabled = false
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                loadsImagesAutomatically = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = true
                }
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
            }
            setBackgroundColor(themeColors.background)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return true
                    val scheme = uri.scheme?.lowercase().orEmpty()
                    if (scheme == "http" || scheme == "https" || scheme == "mailto") {
                        runCatching {
                            view?.context?.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        }
                    }
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loadingView?.visibility = View.GONE
                }
            }
        }
        root.addView(
            webView,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT)
        )

        errorView = TextView(context).apply {
            setTextColor(themeColors.textDisabled)
            textSize = 15f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(
            errorView,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER)
        )

        loadingView = ProgressBar(context)
        root.addView(loadingView, LayoutHelper.createFrame(48, 48, Gravity.CENTER))

        val title = initialTitle.ifBlank { getString(R.string.channel_canvas_untitled) }
        val content = wrapWithActionBar(title, root)
        setupActionBar()
        applyLoadedCanvas()
        return content
    }

    override fun onFragmentDestroy() {
        renderJob?.cancel()
        renderJob = null
        documentShell = null
        cachedDocumentThemeKey = 0
        webView?.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        super.onFragmentDestroy()
    }

    private fun setupActionBar() {
        actionBar?.setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })
    }

    private fun applyLoadedCanvas() {
        val data = channelCanvasController.getCanvasDetail(channelId, canvasId)
        val fetching = channelCanvasController.isFetchingDetail(channelId, canvasId)
        loadingView?.visibility = if (fetching && data == null) View.VISIBLE else View.GONE
        if (data == null) return

        loadedCanvas = data
        val displayTitle = data.title.replace("\n", " ").ifBlank { getString(R.string.channel_canvas_untitled) }
        actionBar?.setTitle(displayTitle)
        errorView?.visibility = View.GONE
        webView?.visibility = View.VISIBLE
        renderCanvasContent(data.content)
    }

    private fun renderCanvasContent(apiContent: String) {
        if (apiContent == lastRenderedContent) return
        renderJob?.cancel()
        val emptyFallback = getString(R.string.channel_canvas_empty)
        val textColor = colorHex(themeColors.onSurface)
        val backgroundColor = colorHex(themeColors.background)
        val linkColor = colorHex(themeColors.blurple)
        val codeBackground = colorHex(themeColors.surfaceVariant)
        renderJob = fragmentScope.launch(Dispatchers.Main.immediate) {
            val documentHtml = withContext(Dispatchers.Default) {
                buildCanvasWebDocument(
                    apiContent = apiContent,
                    emptyFallback = emptyFallback,
                    textColor = textColor,
                    backgroundColor = backgroundColor,
                    linkColor = linkColor,
                    codeBackground = codeBackground,
                )
            }
            if (isFinished || apiContent != loadedCanvas?.content) return@launch
            lastRenderedContent = apiContent
            webView?.loadDataWithBaseURL(null, documentHtml, "text/html", "UTF-8", null)
        }
    }

    private fun buildCanvasWebDocument(
        apiContent: String,
        emptyFallback: String,
        textColor: String,
        backgroundColor: String,
        linkColor: String,
        codeBackground: String,
    ): String {
        val bodyHtml = when (val body = CanvasContentHtml.resolve(apiContent)) {
            is CanvasBodyContent.Html -> body.body.ifBlank { "<p>${escapeHtml(emptyFallback)}</p>" }
            is CanvasBodyContent.PlainText -> {
                val escaped = escapeHtml(body.text.ifBlank { emptyFallback })
                "<p>$escaped</p>"
            }
        }

        return htmlDocument(
            bodyHtml = bodyHtml,
            textColor = textColor,
            backgroundColor = backgroundColor,
            linkColor = linkColor,
            codeBackground = codeBackground,
        )
    }

    private fun htmlDocument(
        bodyHtml: String,
        textColor: String,
        backgroundColor: String,
        linkColor: String,
        codeBackground: String,
    ): String {
        val themeKey = documentThemeKey(textColor, backgroundColor, linkColor, codeBackground)
        val shell = documentShell?.takeIf { cachedDocumentThemeKey == themeKey } ?: buildDocumentShell(
            textColor = textColor,
            backgroundColor = backgroundColor,
            linkColor = linkColor,
            codeBackground = codeBackground,
        ).also {
            documentShell = it
            documentBodyOffset = it.indexOf(BODY_MARKER)
            cachedDocumentThemeKey = themeKey
        }
        return buildString(shell.length + bodyHtml.length) {
            append(shell, 0, documentBodyOffset)
            append(bodyHtml)
            append(shell, documentBodyOffset + BODY_MARKER.length, shell.length)
        }
    }

    private fun documentThemeKey(
        textColor: String,
        backgroundColor: String,
        linkColor: String,
        codeBackground: String,
    ): Int {
        return textColor.hashCode() xor
            backgroundColor.hashCode() xor
            linkColor.hashCode() xor
            codeBackground.hashCode()
    }

    private fun buildDocumentShell(
        textColor: String,
        backgroundColor: String,
        linkColor: String,
        codeBackground: String,
    ): String {
        return buildString {
            append("<!DOCTYPE html><html><head>")
            append("<meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">")
            append("<style>")
            append("*{box-sizing:border-box;}")
            append("body{margin:0;padding:16px;font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,sans-serif;")
            append("font-size:15px;line-height:1.4;color:")
            append(textColor)
            append(";background:")
            append(backgroundColor)
            append(";word-wrap:break-word;overflow-wrap:break-word;}")
            append("p,h1,h2,h3,h4,h5,h6,blockquote,pre,ul,ol{margin:0 0 12px 0;}")
            append("li{margin:0 0 12px 0;}")
            append("ul,ol{padding-left:1.5em;}")
            append("a{color:")
            append(linkColor)
            append(";text-decoration:underline;}")
            append("code{background:")
            append(codeBackground)
            append(";border-radius:4px;padding:1px 4px;}")
            append("pre{background:")
            append(codeBackground)
            append(";border-radius:8px;padding:12px;overflow-x:auto;}")
            append("img{max-width:100%;height:auto;}")
            append("</style></head><body>")
            append(BODY_MARKER)
            append("</body></html>")
        }
    }

    private fun escapeHtml(text: String): String {
        var needsEscape = false
        for (ch in text) {
            if (ch == '&' || ch == '<' || ch == '>') {
                needsEscape = true
                break
            }
        }
        if (!needsEscape) return text

        val out = StringBuilder(text.length + 8)
        for (ch in text) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun colorHex(color: Int): String {
        return String.format("#%06X", 0xFFFFFF and color)
    }

    @Suppress("DEPRECATION")
    private fun restoreLocale(context: Context, locale: Locale) {
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    private fun showLoadError() {
        loadingView?.visibility = View.GONE
        if (loadedCanvas != null) return
        webView?.visibility = View.GONE
        errorView?.apply {
            text = getString(R.string.channel_canvas_load_error)
            visibility = View.VISIBLE
        }
    }
}
