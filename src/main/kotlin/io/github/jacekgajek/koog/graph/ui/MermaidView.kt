package io.github.jacekgajek.koog.graph.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import java.awt.BorderLayout
import java.awt.Color
import java.io.File
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Renders a Mermaid diagram in an embedded JCEF browser using the bundled
 * `mermaid.min.js`. Falls back to showing the raw diagram / message as text when
 * JCEF isn't available (e.g. a runtime without the bundled Chromium).
 */
class MermaidView : JPanel(BorderLayout()), Disposable {

    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null

    /** Invoked (on the EDT) with a node/subgraph id when the user clicks it in the diagram. */
    var onNodeClick: (String) -> Unit = {}

    private val jsQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }

    private val fallback = JTextArea().apply {
        isEditable = false
        lineWrap = false
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13)
    }

    /** Per-view temp dir holding mermaid.min.js + the rendered page files. */
    private val workDir = FileUtil.createTempDirectory("koog-mermaid-view", null, true)

    // CEF caches by URL: reloading the same file path shows the stale page. So each
    // render gets a fresh page-N.html URL; we delete the previous one to bound disk.
    private var pageSeq = 0
    private var lastPage: File? = null

    init {
        val b = browser
        LOG.info("MermaidView init: JCEF supported=${JBCefApp.isSupported()}, workDir=${workDir.absolutePath}")
        if (b != null) {
            Disposer.register(this, b)
            add(b.component, BorderLayout.CENTER)
            copyAsset()
            jsQuery?.let { q ->
                Disposer.register(this, q)
                q.addHandler { raw ->
                    val id = raw?.trim().orEmpty()
                    if (id.isNotEmpty()) ApplicationManager.getApplication().invokeLater { onNodeClick(id) }
                    null
                }
            }
        } else {
            LOG.warn("MermaidView: JCEF not supported — using plain-text fallback")
            add(JScrollPane(fallback), BorderLayout.CENTER)
        }
        showMessage("Loading strategy graph…", "")
    }

    fun showDiagram(mermaid: String) {
        val b = browser ?: run { fallback.text = mermaid; return }
        load(b, "<pre class=\"mermaid\">${esc(mermaid)}</pre>")
    }

    fun showMessage(title: String, detail: String) {
        val b = browser ?: run {
            fallback.text = if (detail.isBlank()) title else "$title\n\n$detail"
            return
        }
        val body = buildString {
            append("<div class=\"msg\"><h3>").append(esc(title)).append("</h3>")
            if (detail.isNotBlank()) append("<pre class=\"detail\">").append(esc(detail)).append("</pre>")
            append("</div>")
        }
        load(b, body)
    }

    private fun load(b: JBCefBrowser, body: String) {
        val file = File(workDir, "page-${++pageSeq}.html")
        file.writeText(page(body))
        LOG.info("MermaidView: loading ${file.toURI()} (body=${body.length} chars)")
        b.loadURL(file.toURI().toString())
        lastPage?.delete()
        lastPage = file
    }

    private fun copyAsset() {
        val asset = File(workDir, "mermaid.min.js")
        javaClass.getResourceAsStream("/mermaid/mermaid.min.js")?.use { input ->
            asset.outputStream().use { input.copyTo(it) }
        } ?: LOG.warn("Bundled mermaid.min.js not found on classpath")
    }

    private fun page(body: String): String {
        val bg = hex(JBColor.background())
        val fg = hex(JBColor.foreground())
        val theme = if (JBColor.isBright()) "default" else "dark"
        // Click → source navigation. Only wired when JCEF (and the query) is available.
        val clickJs = jsQuery?.let { q ->
            """
                function __koogNodeClick(id) { ${q.inject("id")} }
                function koogBindClicks() {
                  document.querySelectorAll('g.node').forEach(function (n) {
                    n.addEventListener('click', function () {
                      var l = n.querySelector('.nodeLabel, text');
                      var id = ((l ? l.textContent : n.textContent) || '').trim();
                      if (id) __koogNodeClick(id);
                    });
                  });
                  document.querySelectorAll('g.cluster').forEach(function (c) {
                    var l = c.querySelector('.cluster-label, text');
                    if (!l) return;
                    l.style.cursor = 'pointer';
                    l.addEventListener('click', function (ev) {
                      var id = (l.textContent || '').trim();
                      if (id) __koogNodeClick(id);
                      ev.stopPropagation();
                    });
                  });
                }
            """.trimIndent()
        } ?: "function koogBindClicks() {}"

        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8">
              <style>
                html, body { margin: 0; padding: 0; height: 100%; background: $bg; color: $fg;
                  font-family: -apple-system, Segoe UI, sans-serif; }
                #root { padding: 12px; }
                .mermaid { line-height: 1.2; }
                /* Make graph nodes look clickable. */
                g.node { cursor: pointer; }
                g.node:hover * { filter: brightness(1.18); }
                g.cluster > .cluster-label:hover, g.cluster text:hover { text-decoration: underline; }
                .msg h3 { margin: 0 0 8px; font-weight: 600; }
                .msg .detail { white-space: pre-wrap; color: #c0392b; font-family: monospace;
                  font-size: 12px; }
              </style>
            </head>
            <body>
              <div id="root">$body</div>
              <script src="mermaid.min.js"></script>
              <script>
                $clickJs
                try {
                  mermaid.initialize({ startOnLoad: false, theme: '$theme', securityLevel: 'loose' });
                  mermaid.run().then(koogBindClicks).catch(function (e) {
                    document.getElementById('root').innerHTML =
                      '<pre class="detail">' + String(e && e.message ? e.message : e) + '</pre>';
                  });
                } catch (e) {
                  document.getElementById('root').innerHTML =
                    '<pre class="detail">' + String(e) + '</pre>';
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    override fun dispose() {
        // browser is disposed via Disposer registration; temp dir is delete-on-exit.
    }

    companion object {
        private val LOG = logger<MermaidView>()

        private fun esc(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun hex(c: Color): String = String.format("#%02x%02x%02x", c.red, c.green, c.blue)
    }
}
