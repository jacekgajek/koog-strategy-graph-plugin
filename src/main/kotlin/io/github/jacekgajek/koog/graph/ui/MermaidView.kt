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

    /** Invoked (on the EDT) with the from/to node ids when the user clicks an edge. */
    var onEdgeClick: (from: String, to: String) -> Unit = { _, _ -> }

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
                    dispatch(raw)
                    null
                }
            }
        } else {
            LOG.warn("MermaidView: JCEF not supported — using plain-text fallback")
            add(JScrollPane(fallback), BorderLayout.CENTER)
        }
        showMessage("Loading strategy graph…", "")
    }

    /** Decode a `kindarg…` message from the page and fire the matching callback on the EDT. */
    private fun dispatch(raw: String?) {
        val parts = raw.orEmpty().split('\u0001')
        when (parts.getOrNull(0)) {
            "node" -> parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { id -> ApplicationManager.getApplication().invokeLater { onNodeClick(id) } }
            "edge" -> {
                val from = parts.getOrNull(1)?.trim().orEmpty()
                val to = parts.getOrNull(2)?.trim().orEmpty()
                if (from.isNotEmpty() && to.isNotEmpty()) {
                    ApplicationManager.getApplication().invokeLater { onEdgeClick(from, to) }
                }
            }
        }
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
                function __koogSend(msg) { ${q.inject("msg")} }
                function __koogNodeClick(id) { __koogSend('node\u0001' + id); }
                function __koogEdgeClick(f, t) { __koogSend('edge\u0001' + f + '\u0001' + t); }

                // The Mermaid source survives in window.__koogSrc (captured before run()
                // replaces the <pre>). edges[N] is the Nth edge in declaration order;
                // Mermaid tags each rendered path with that same N in its id ("…-edgeN"),
                // so we map a path to its endpoints by that index — NOT by DOM order,
                // which the layout engine reshuffles (especially with subgraphs).
                function koogEdges() {
                  var out = [];
                  (window.__koogSrc || '').split('\n').forEach(function (line) {
                    var m = line.match(/^\s*([A-Za-z0-9_]+|\[\*\])\s*-->\s*([A-Za-z0-9_]+|\[\*\])(?:\s*:.*)?$/);
                    if (m) out.push([m[1], m[2]]);
                  });
                  return out;
                }
                function koogEdgeIndex(p) {
                  var m = (p.getAttribute('id') || '').match(/edge(\d+)$/);
                  return m ? parseInt(m[1], 10) : -1;
                }
                // [*] is Mermaid's start/finish marker: it's the start node on the left
                // of an edge and the finish node on the right. Koog writes those as the
                // nodeStart / nodeFinish builder properties.
                function koogEndpoint(tok, isSource) {
                  return tok === '[*]' ? (isSource ? 'nodeStart' : 'nodeFinish') : tok;
                }

                function koogBindNodes() {
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

                function koogBindEdges() {
                  var edges = koogEdges();
                  var paths = document.querySelectorAll('g.edgePaths > path');
                  if (!paths.length) paths = document.querySelectorAll('path.transition');
                  paths.forEach(function (p, i) {
                    var n = koogEdgeIndex(p);
                    var e = (n >= 0 ? edges[n] : null) || edges[i];
                    if (!e) return;
                    var from = koogEndpoint(e[0], true), to = koogEndpoint(e[1], false);
                    // A wide, invisible companion path so the user needn't hit the
                    // thin stroke pixel-perfectly. It carries the click; hovering it
                    // highlights the real edge underneath.
                    var hit = p.cloneNode(false);
                    hit.removeAttribute('marker-end');
                    hit.removeAttribute('marker-start');
                    hit.removeAttribute('id');
                    hit.style.stroke = 'transparent';
                    hit.style.strokeWidth = '14px';
                    hit.style.fill = 'none';
                    hit.style.pointerEvents = 'stroke';
                    hit.style.cursor = 'pointer';
                    hit.addEventListener('click', function () { __koogEdgeClick(from, to); });
                    hit.addEventListener('mouseenter', function () {
                      p.dataset.koogSw = p.style.strokeWidth || '';
                      p.style.strokeWidth = '3px';
                      p.style.filter = 'brightness(1.6)';
                    });
                    hit.addEventListener('mouseleave', function () {
                      p.style.strokeWidth = p.dataset.koogSw || '';
                      p.style.filter = '';
                    });
                    p.parentNode.insertBefore(hit, p.nextSibling);
                  });
                }

                function koogBindClicks() { koogBindNodes(); koogBindEdges(); }
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
                g.edgePaths path { cursor: pointer; }
                /* Let clicks on a condition label fall through to the edge beneath it. */
                g.edgeLabels { pointer-events: none; }
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
                // Stash the raw diagram text before mermaid.run() swaps in the SVG.
                window.__koogSrc = (document.querySelector('.mermaid') || {}).textContent || '';
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
