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
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.Color
import java.io.File
import java.util.Base64
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Renders a Mermaid diagram in an embedded JCEF browser using the bundled
 * `mermaid.min.js`. Falls back to showing the raw diagram / message as text when
 * JCEF isn't available (e.g. a runtime without the bundled Chromium).
 *
 * The page is loaded exactly once; every subsequent diagram/message is pushed in
 * via `executeJavaScript`. Diagrams are rendered off-DOM and swapped in a single
 * step, so the previous diagram stays on screen until the new one is ready — no
 * blank "reloading" flash between refreshes.
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

    /** Per-view temp dir holding mermaid.min.js + the single page file. */
    private val workDir = FileUtil.createTempDirectory("koog-mermaid-view", null, true)

    // The page loads asynchronously; JS pushed before it's ready is coalesced into
    // `pending` (latest state wins) and flushed on load end.
    private var pageReady = false
    private var pending: String? = null

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
            b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    ApplicationManager.getApplication().invokeLater {
                        pageReady = true
                        pending?.let { js -> pending = null; exec(js) }
                    }
                }
            }, b.cefBrowser)
            val shell = File(workDir, "index.html").apply { writeText(shellHtml()) }
            b.loadURL(shell.toURI().toString())
        } else {
            LOG.warn("MermaidView: JCEF not supported — using plain-text fallback")
            add(JScrollPane(fallback), BorderLayout.CENTER)
        }
        showMessage("Loading strategy graph…", "")
    }

    /** Decode a `kindarg…` message from the page and fire the matching callback on the EDT. */
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
        runJs("koogRender('${b64(mermaid)}')")
    }

    fun showMessage(title: String, detail: String) {
        val b = browser ?: run {
            fallback.text = if (detail.isBlank()) title else "$title\n\n$detail"
            return
        }
        runJs("koogMessage('${b64(title)}', '${b64(detail)}')")
    }

    /**
     * Toggle a small, static gray hourglass in the top-left corner of the canvas while a
     * refresh is in flight. Deliberately unobtrusive — the previous diagram stays fully
     * visible underneath. No-op until the page has loaded (a refresh only happens once a
     * diagram is on screen, so the indicator is moot before then).
     */
    fun setRefreshing(refreshing: Boolean) {
        if (browser == null || !pageReady) return
        exec("koogLoading($refreshing)")
    }

    /**
     * Highlight a node (by its display label) and/or an edge (by its from/to Mermaid ids)
     * in the diagram; pass all-null to clear. No-op until the page has loaded — the caret
     * that drives this re-fires, so a dropped early call self-corrects.
     */
    fun highlight(node: String?, from: String?, to: String?) {
        if (browser == null || !pageReady) return
        exec("koogHighlight(${jsArg(node)}, ${jsArg(from)}, ${jsArg(to)})")
    }

    private fun jsArg(s: String?): String = if (s == null) "null" else "'${b64(s)}'"

    /** Run JS in the page now, or queue it (latest wins) until the page has loaded. */
    private fun runJs(js: String) {
        if (browser == null) return
        if (pageReady) exec(js) else pending = js
    }

    private fun exec(js: String) {
        val b = browser ?: return
        b.cefBrowser.executeJavaScript(js, b.cefBrowser.url ?: "", 0)
    }

    private fun copyAsset() {
        val asset = File(workDir, "mermaid.min.js")
        javaClass.getResourceAsStream("/mermaid/mermaid.min.js")?.use { input ->
            asset.outputStream().use { input.copyTo(it) }
        } ?: LOG.warn("Bundled mermaid.min.js not found on classpath")
    }

    private fun shellHtml(): String {
        val bg = hex(JBColor.background())
        val fg = hex(JBColor.foreground())
        val theme = if (JBColor.isBright()) "default" else "dark"
        // Click → source navigation. Only wired when JCEF (and the query) is available.
        val clickJs = jsQuery?.let { q ->
            """
                function __koogSend(msg) { ${q.inject("msg")} }
                function __koogNodeClick(id) { __koogSend('node\u0001' + id); }
                function __koogEdgeClick(f, t) { __koogSend('edge\u0001' + f + '\u0001' + t); }

                // edges[N] is the Nth edge in declaration order; Mermaid tags each
                // rendered path with that same N in its id ("…-edgeN"), so we map a
                // path to its endpoints by that index — NOT by DOM order, which the
                // layout engine reshuffles (especially with subgraphs).
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
                    var l = n.querySelector('.nodeLabel, text');
                    var id = ((l ? l.textContent : n.textContent) || '').trim();
                    if (id) window.__koogNodeMap[id] = n;
                    n.addEventListener('click', function () {
                      if (id) __koogNodeClick(id);
                    });
                  });
                  // Subgraphs are composite states: in mermaid's state renderer their
                  // group carries 'statediagram-cluster' (not the flowchart 'cluster'),
                  // with the title in '.cluster-label' and the box in 'rect.outer/.inner'.
                  document.querySelectorAll('g.cluster, g.statediagram-cluster').forEach(function (c) {
                    var l = c.querySelector('.cluster-label, text');
                    if (!l) return;
                    var id = (l.textContent || '').trim();
                    if (!id) return;
                    window.__koogNodeMap[id] = c;
                    var dataId = c.getAttribute('data-id');
                    if (dataId) window.__koogNodeMap[dataId] = c;
                    l.style.cursor = 'pointer';

                    // The whole header strip navigates — not just the title text. We can't
                    // overlay a rect (a sibling after the label's foreignObject would hide
                    // the HTML title in Chromium), so we listen on the cluster group and only
                    // act when the click falls in the title band. Inner nodes live in a
                    // separate group, so their clicks never bubble here.
                    var box = c.querySelector('rect.outer') || c.querySelector('rect');
                    var headerHeight;
                    try { headerHeight = l.getBBox().height + 12; } catch (e) { headerHeight = 24; }
                    c.addEventListener('click', function (ev) {
                      if (box) {
                        var svg = c.ownerSVGElement, ctm = c.getScreenCTM();
                        if (svg && ctm) {
                          var pt = svg.createSVGPoint();
                          pt.x = ev.clientX; pt.y = ev.clientY;
                          var p = pt.matrixTransform(ctm.inverse());
                          var x = parseFloat(box.getAttribute('x')) || 0;
                          var y = parseFloat(box.getAttribute('y')) || 0;
                          var w = parseFloat(box.getAttribute('width')) || 0;
                          var inHeader = p.x >= x && p.x <= x + w && p.y >= y && p.y <= y + headerHeight;
                          if (!inHeader) return;
                        }
                      }
                      __koogNodeClick(id);
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
                    window.__koogEdgeMap[from + '|' + to] = p;
                    p.parentNode.insertBefore(hit, p.nextSibling);
                  });
                }

                function koogBindClicks() {
                  window.__koogNodeMap = {};
                  window.__koogEdgeMap = {};
                  koogBindNodes();
                  koogBindEdges();
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
                /* Make graph nodes look clickable. */
                g.node { cursor: pointer; }
                g.node:hover * { filter: brightness(1.18); }
                g.cluster > .cluster-label:hover, g.cluster text:hover { text-decoration: underline; }
                g.edgePaths path { cursor: pointer; }
                /* Let clicks on a condition label fall through to the edge beneath it. */
                g.edgeLabels { pointer-events: none; }
                .msg h3 { margin: 0 0 8px; font-weight: 600; }
                .msg .detail { white-space: pre-wrap; color: $fg; opacity: 0.8;
                  font-family: monospace; font-size: 12px; }
                .error { white-space: pre-wrap; color: #c0392b; font-family: monospace;
                  font-size: 12px; }
                /* Caret-driven highlight of the node/edge under the editor cursor. */
                .koog-hl rect, .koog-hl polygon, .koog-hl circle, .koog-hl ellipse, .koog-hl path {
                  stroke: #4c9aff !important; stroke-width: 3px !important; }
                path.koog-hl-edge { stroke: #4c9aff !important; stroke-width: 3.5px !important;
                  filter: drop-shadow(0 0 2px #4c9aff) !important; }
              </style>
            </head>
            <body>
              <div id="root"></div>
              <!-- Refresh indicator: a static gray hourglass, hidden unless a render is in flight. -->
              <div id="koog-loading" title="Refreshing…" aria-hidden="true"
                style="position: fixed; top: 6px; left: 6px; display: none; opacity: 0.45;
                       pointer-events: none; z-index: 10;">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="#808080">
                  <path d="M6 2 L18 2 L18 7 L12 12 L18 17 L18 22 L6 22 L6 17 L12 12 L6 7 Z"/>
                </svg>
              </div>
              <script src="mermaid.min.js"></script>
              <script>
                $clickJs

                // UTF-8-safe base64 decode: the IDE hands us diagram/message text as
                // base64 to dodge JS string-escaping pitfalls.
                function __b64(s) {
                  return decodeURIComponent(Array.prototype.map.call(atob(s), function (c) {
                    return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2);
                  }).join(''));
                }
                function __esc(s) {
                  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
                }

                mermaid.initialize({ startOnLoad: false, theme: '$theme', securityLevel: 'loose' });

                var __seq = 0;
                // Render off-DOM, then swap the whole #root in one shot: the old diagram
                // stays visible until the new SVG is ready (effective double-buffering).
                function koogRender(b64) {
                  var text = __b64(b64);
                  window.__koogSrc = text;
                  mermaid.render('koogsvg' + (++__seq), text).then(function (res) {
                    var root = document.getElementById('root');
                    root.innerHTML = res.svg;
                    if (res.bindFunctions) res.bindFunctions(root);
                    koogBindClicks();
                    koogApplyHighlight();
                  }).catch(function (e) {
                    document.getElementById('root').innerHTML =
                      '<pre class="error">' + __esc(String(e && e.message ? e.message : e)) + '</pre>';
                  });
                }
                // Show/hide the static refresh hourglass in the corner.
                function koogLoading(show) {
                  var el = document.getElementById('koog-loading');
                  if (el) el.style.display = show ? 'block' : 'none';
                }
                function koogMessage(b64t, b64d) {
                  var t = __b64(b64t), d = __b64(b64d);
                  var h = '<div class="msg"><h3>' + __esc(t) + '</h3>';
                  if (d) h += '<pre class="detail">' + __esc(d) + '</pre>';
                  h += '</div>';
                  document.getElementById('root').innerHTML = h;
                }

                // Caret-driven highlight. The desired target is remembered in __koogWant
                // so it survives a re-render (the .then above re-applies it).
                window.__koogWant = null;
                window.__koogHi = [];
                function koogHighlight(b64n, b64f, b64t) {
                  window.__koogWant = {
                    node: b64n ? __b64(b64n) : null,
                    from: b64f ? __b64(b64f) : null,
                    to:   b64t ? __b64(b64t) : null
                  };
                  koogApplyHighlight();
                }
                function koogApplyHighlight() {
                  (window.__koogHi || []).forEach(function (el) {
                    el.classList.remove('koog-hl'); el.classList.remove('koog-hl-edge');
                  });
                  window.__koogHi = [];
                  var w = window.__koogWant; if (!w) return;
                  var nm = window.__koogNodeMap || {}, em = window.__koogEdgeMap || {};
                  if (w.node && nm[w.node]) {
                    var el = nm[w.node]; el.classList.add('koog-hl'); window.__koogHi.push(el);
                    try { el.scrollIntoView({ block: 'nearest', inline: 'nearest' }); } catch (e) {}
                  }
                  if (w.from && w.to) {
                    var p = em[w.from + '|' + w.to];
                    if (p) { p.classList.add('koog-hl-edge'); window.__koogHi.push(p); }
                  }
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

        private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

        private fun hex(c: Color): String = String.format("#%02x%02x%02x", c.red, c.green, c.blue)
    }
}
