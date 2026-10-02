package com.example.suffery // <-- tomar package name dao

import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private class Tab(val web: WebView, val incognito: Boolean)

    private val tabs = mutableListOf<Tab>()
    private var cur = 0
    private val tab get() = tabs[cur]

    private lateinit var root: LinearLayout
    private lateinit var holder: FrameLayout
    private lateinit var bar: ProgressBar
    private lateinit var urlRow: LinearLayout
    private lateinit var urlBox: EditText
    private lateinit var btnBack: Button
    private lateinit var btnFwd: Button
    private lateinit var btnTabs: Button
    private lateinit var prefs: SharedPreferences

    private val home = "https://www.google.com"
    private val desktopUA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val M = ViewGroup.LayoutParams.MATCH_PARENT
    private val W = ViewGroup.LayoutParams.WRAP_CONTENT
    private val blue = 0xFF007AFF.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = getSharedPreferences("suffery", MODE_PRIVATE)
        buildUi()

        val saved = JSONArray(prefs.getString("tabs", "[]"))
        for (i in 0 until saved.length()) newTab(saved.getString(i))
        intent?.dataString?.let { newTab(it) }
        if (tabs.isEmpty()) newTab(home)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    urlBox.hasFocus() -> unfocus()
                    tab.web.canGoBack() -> tab.web.goBack()
                    tabs.size > 1 -> closeTab(cur)
                    else -> finish()
                }
            }
        })
    }

    override fun onPause() {
        super.onPause()
        val a = JSONArray()
        tabs.filter { !it.incognito }.forEach { t -> t.web.url?.let { a.put(it) } }
        prefs.edit().putString("tabs", a.toString()).apply()
    }

    // ---------------------------------------------------------------- UI

    private fun btn(t: String, size: Float = 22f, click: (View) -> Unit) = Button(this).apply {
        text = t
        textSize = size
        isAllCaps = false
        background = null
        minWidth = 0
        minimumWidth = 0
        setTextColor(blue)
        setOnClickListener(click)
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(bar, LinearLayout.LayoutParams(M, dp(3)))

        holder = FrameLayout(this)
        root.addView(holder, LinearLayout.LayoutParams(M, 0, 1f))

        // Safari-style address pill (bottom)
        urlRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat() }
            setPadding(dp(4), 0, dp(4), 0)
        }
        urlRow.addView(btn("aA", 14f) { showPageMenu(it) })
        urlBox = EditText(this).apply {
            setSingleLine()
            background = null
            gravity = Gravity.CENTER
            textSize = 15f
            hint = "Search or enter website name"
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnFocusChangeListener { _, focused ->
                if (focused) {
                    setText(tab.web.url ?: "")
                    selectAll()
                } else showHost()
            }
            setOnEditorActionListener { v, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO) {
                    go(v.text.toString())
                    unfocus()
                    true
                } else false
            }
        }
        urlRow.addView(urlBox, LinearLayout.LayoutParams(0, W, 1f))
        urlRow.addView(btn("⟳", 18f) { tab.web.reload() })
        root.addView(urlRow, LinearLayout.LayoutParams(M, W).apply { setMargins(dp(10), dp(4), dp(10), dp(2)) })

        // Bottom toolbar
        btnBack = btn("‹", 30f) { if (tab.web.canGoBack()) tab.web.goBack() }
        btnFwd = btn("›", 30f) { if (tab.web.canGoForward()) tab.web.goForward() }
        val btnShare = btn("⇧", 22f) { share() }
        val btnBook = btn("☰", 22f) { showLibrary() }
        btnTabs = btn("1 ▢", 18f) { showTabs() }
        val tools = LinearLayout(this)
        listOf(btnBack, btnFwd, btnShare, btnBook, btnTabs).forEach {
            tools.addView(it, LinearLayout.LayoutParams(0, W, 1f))
        }
        root.addView(tools, LinearLayout.LayoutParams(M, W))
        setContentView(root)
    }

    private fun refreshUi() {
        btnBack.alpha = if (tab.web.canGoBack()) 1f else 0.3f
        btnFwd.alpha = if (tab.web.canGoForward()) 1f else 0.3f
        btnTabs.text = "${tabs.size} ▢"
        (urlRow.background as GradientDrawable).setColor(if (tab.incognito) 0xFF3A3A3C.toInt() else 0xFFEFEFF0.toInt())
        urlBox.setTextColor(if (tab.incognito) Color.WHITE else Color.BLACK)
        urlBox.setHintTextColor(Color.GRAY)
        if (!urlBox.hasFocus()) showHost()
    }

    private fun showHost() {
        val u = tab.web.url
        urlBox.setText(if (u.isNullOrBlank()) "" else Uri.parse(u).host?.removePrefix("www.") ?: u)
    }

    private fun unfocus() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBox.windowToken, 0)
        root.requestFocus()
    }

    // ---------------------------------------------------------------- tabs

    private fun makeWeb(priv: Boolean) = WebView(this).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            if (priv) cacheMode = WebSettings.LOAD_NO_CACHE
        }
        setDownloadListener { u, ua, cd, mime, _ ->
            val r = DownloadManager.Request(Uri.parse(u))
                .setMimeType(mime)
                .addRequestHeader("User-Agent", ua)
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(u) ?: "")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(u, cd, mime))
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(r)
            toast("Downloading…")
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val s = r.url.scheme
                if (s == "http" || s == "https") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, r.url)) } catch (_: Exception) {}
                return true
            }

            override fun onPageStarted(v: WebView, u: String?, f: Bitmap?) {
                if (v === tab.web) {
                    bar.visibility = View.VISIBLE
                    refreshUi()
                }
            }

            override fun onPageFinished(v: WebView, u: String?) {
                if (v === tab.web) {
                    bar.visibility = View.GONE
                    refreshUi()
                }
                if (!priv && u != null && !u.startsWith("about:")) addHistory(v.title ?: u, u)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                if (v === tab.web) bar.progress = p
            }
        }
    }

    private fun newTab(url: String?, priv: Boolean = false) {
        val t = Tab(makeWeb(priv), priv)
        tabs.add(t)
        switchTo(tabs.size - 1)
        url?.let { t.web.loadUrl(it) }
    }

    private fun switchTo(i: Int) {
        cur = i
        holder.removeAllViews()
        holder.addView(tab.web, FrameLayout.LayoutParams(M, M))
        bar.visibility = View.GONE
        refreshUi()
    }

    private fun closeTab(i: Int) {
        val t = tabs.removeAt(i)
        holder.removeView(t.web)
        t.web.destroy()
        if (tabs.isEmpty()) {
            newTab(home)
            return
        }
        switchTo(minOf(if (i < cur) cur - 1 else cur, tabs.size - 1))
    }

    private fun showTabs() {
        val d = Dialog(this, android.R.style.Theme_Material_Light_NoActionBar)
        val grid = GridLayout(this).apply { columnCount = 2 }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(40), dp(12), dp(24))
        }

        fun render() {
            grid.removeAllViews()
            tabs.forEachIndexed { i, t ->
                val fg = if (t.incognito) Color.WHITE else Color.BLACK
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(16))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(if (t.incognito) 0xFF3A3A3C.toInt() else 0xFFF2F2F7.toInt())
                        if (i == cur) setStroke(dp(2), blue)
                    }
                    addView(TextView(context).apply {
                        text = "✕"
                        textSize = 16f
                        gravity = Gravity.END
                        setTextColor(fg)
                        setOnClickListener {
                            closeTab(i)
                            render()
                        }
                    })
                    addView(TextView(context).apply {
                        text = t.web.title?.takeIf { it.isNotBlank() } ?: "Start Page"
                        maxLines = 2
                        textSize = 15f
                        setTextColor(fg)
                    })
                    addView(TextView(context).apply {
                        text = t.web.url?.let { Uri.parse(it).host } ?: ""
                        textSize = 12f
                        setTextColor(Color.GRAY)
                    })
                    setOnClickListener {
                        switchTo(i)
                        d.dismiss()
                    }
                }
                val lp = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply {
                    width = 0
                    setMargins(dp(6), dp(6), dp(6), dp(6))
                }
                grid.addView(card, lp)
            }
        }

        val row = LinearLayout(this)
        row.addView(btn("+ New", 16f) { newTab(home); d.dismiss() }, LinearLayout.LayoutParams(0, W, 1f))
        row.addView(btn("Private", 16f) { newTab(home, true); d.dismiss() }, LinearLayout.LayoutParams(0, W, 1f))
        row.addView(btn("Done", 16f) { d.dismiss() }, LinearLayout.LayoutParams(0, W, 1f))
        panel.addView(ScrollView(this).apply { addView(grid) }, LinearLayout.LayoutParams(M, 0, 1f))
        panel.addView(row, LinearLayout.LayoutParams(M, W))
        d.setContentView(panel)
        d.window?.setLayout(M, M)
        render()
        d.show()
    }

    // ---------------------------------------------------------------- navigation

    private fun go(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        tab.web.loadUrl(url)
    }

    private fun share() {
        val u = tab.web.url ?: return
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u), null
            )
        )
    }

    private fun showPageMenu(anchor: View) {
        val w = tab.web
        val desktop = w.settings.userAgentString == desktopUA
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, if (desktop) "Request Mobile Website" else "Request Desktop Website")
            menu.add(0, 2, 0, "Find on Page")
            menu.add(0, 3, 0, "Find Next")
            menu.add(0, 4, 0, if (isBookmarked(w.url)) "Remove Bookmark" else "Add Bookmark")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> {
                        w.settings.userAgentString = if (desktop) null else desktopUA
                        w.reload()
                    }
                    2 -> findOnPage()
                    3 -> w.findNext(true)
                    4 -> toggleBookmark()
                }
                true
            }
        }.show()
    }

    private fun findOnPage() {
        val et = EditText(this)
        tab.web.setFindListener { _, total, done -> if (done) toast("$total match") }
        AlertDialog.Builder(this).setTitle("Find on Page").setView(et)
            .setPositiveButton("Find") { _, _ -> tab.web.findAllAsync(et.text.toString()) }
            .setNegativeButton("Clear") { _, _ -> tab.web.clearMatches() }
            .show()
    }

    // ---------------------------------------------------------------- bookmarks & history

    private fun load(key: String) = JSONArray(prefs.getString(key, "[]"))
    private fun save(key: String, a: JSONArray) = prefs.edit().putString(key, a.toString()).apply()
    private fun entry(t: String, u: String) = JSONObject().put("t", t).put("u", u)

    private fun addHistory(t: String, u: String) {
        val a = load("hist")
        if (a.length() > 0 && a.getJSONObject(0).getString("u") == u) return
        val n = JSONArray().put(entry(t, u))
        for (i in 0 until minOf(a.length(), 299)) n.put(a.get(i))
        save("hist", n)
    }

    private fun isBookmarked(u: String?): Boolean {
        val a = load("bm")
        return (0 until a.length()).any { a.getJSONObject(it).getString("u") == u }
    }

    private fun toggleBookmark() {
        val u = tab.web.url ?: return
        val a = load("bm")
        if (isBookmarked(u)) {
            val n = JSONArray()
            for (i in 0 until a.length()) if (a.getJSONObject(i).getString("u") != u) n.put(a.get(i))
            save("bm", n)
            toast("Bookmark removed")
        } else {
            save("bm", a.put(entry(tab.web.title ?: u, u)))
            toast("Bookmark added")
        }
    }

    private fun showLibrary() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Bookmarks", "History", "Clear History")) { _, i ->
                when (i) {
                    0 -> showList("Bookmarks", "bm")
                    1 -> showList("History", "hist")
                    2 -> {
                        save("hist", JSONArray())
                        toast("History cleared")
                    }
                }
            }.show()
    }

    private fun showList(title: String, key: String) {
        val a = load(key)
        if (a.length() == 0) {
            toast("Empty")
            return
        }
        val names = Array(a.length()) {
            val o = a.getJSONObject(it)
            o.getString("t").ifBlank { o.getString("u") }
        }
        AlertDialog.Builder(this).setTitle(title)
            .setItems(names) { _, i -> tab.web.loadUrl(a.getJSONObject(i).getString("u")) }
            .setNegativeButton("Close", null)
            .show()
    }
}
