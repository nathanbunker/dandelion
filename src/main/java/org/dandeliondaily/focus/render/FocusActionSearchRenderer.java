package org.dandeliondaily.focus.render;

import java.io.PrintWriter;

import org.dandeliondaily.focus.service.FocusActionSearchService;

/**
 * Search for any of the user's actions by words in the action or project name, and start one at
 * once. A button (or the / key) opens a dialog that asks the server as the user types; picking a
 * result switches to it through switchAction, which also brings an open action to today.
 * Self-contained: markup, styles and script, so any page can include it. Row styles come from
 * {@link FocusActionPickerRenderer#printStyles}.
 */
public class FocusActionSearchRenderer {

        private final String servletUrl;

        /** {@code servletUrl} answers action=searchActions and action=switchAction. */
        public FocusActionSearchRenderer(String servletUrl) {
                this.servletUrl = servletUrl;
        }

        public void renderButton(PrintWriter out) {
                out.println("    <button type=\"button\" class=\"fa-search-btn\" title=\"Search actions (/)\" aria-label=\"Search actions\""
                                + " onclick=\"faSearchOpen()\">"
                                + "<svg viewBox=\"0 0 24 24\" width=\"20\" height=\"20\" aria-hidden=\"true\"><circle cx=\"10.5\" cy=\"10.5\" r=\"6.5\""
                                + " fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.2\"/><line x1=\"15.5\" y1=\"15.5\" x2=\"21\" y2=\"21\""
                                + " stroke=\"currentColor\" stroke-width=\"2.4\" stroke-linecap=\"round\"/></svg></button>");
        }

        public void renderDialog(PrintWriter out) {
                out.println("<div id=\"faSearchModal\" class=\"fa-modal-overlay fa-search-overlay\" onclick=\"if (event.target === this) { faSearchClose(); }\">");
                out.println("  <div class=\"fa-modal fa-search-modal fa-picker\" role=\"dialog\" aria-label=\"Search actions\">");
                out.println("    <div class=\"fa-search-head\">");
                out.println("      <svg viewBox=\"0 0 24 24\" width=\"18\" height=\"18\" aria-hidden=\"true\"><circle cx=\"10.5\" cy=\"10.5\" r=\"6.5\" fill=\"none\""
                                + " stroke=\"currentColor\" stroke-width=\"2.2\"/><line x1=\"15.5\" y1=\"15.5\" x2=\"21\" y2=\"21\" stroke=\"currentColor\""
                                + " stroke-width=\"2.4\" stroke-linecap=\"round\"/></svg>");
                out.println("      <input type=\"text\" id=\"faSearchInput\" class=\"fa-search-input\" placeholder=\"Search actions and projects...\""
                                + " autocomplete=\"off\" spellcheck=\"false\" oninput=\"faSearchInput()\" onkeydown=\"faSearchKeydown(event)\" />");
                out.println("      <button type=\"button\" class=\"fa-modal-close\" title=\"Close (Esc)\" onclick=\"faSearchClose()\">&times;</button>");
                out.println("    </div>");
                out.println("    <div id=\"faSearchResults\" class=\"fa-search-results\"></div>");
                out.println("    <div class=\"fa-search-foot\">&uarr;&darr; move &middot; Enter start &middot; Esc close"
                                + " &middot; open actions move to today when started</div>");
                out.println("  </div>");
                out.println("</div>");
                printStyles(out);
                printScript(out);
        }

        private void printStyles(PrintWriter out) {
                out.println("<style>");
                out.println("  .fa-search-btn { flex: 0 0 auto; align-self: center; display: inline-flex; align-items: center; justify-content: center; width: 40px; height: 40px; border: 1px solid #8ca08c; border-radius: 8px; background: #f5faf4; color: #274127; cursor: pointer; }");
                out.println("  .fa-search-btn:hover { background: #e6f0e3; }");
                out.println("  .fa-search-overlay { align-items: flex-start; padding-top: 9vh; }");
                out.println("  .fa-search-modal { width: min(94vw, 680px); max-height: 78vh; display: flex; flex-direction: column; overflow: hidden; }");
                out.println("  .fa-search-head { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border-bottom: 1px solid #d8e0d6; color: #4a6350; }");
                out.println("  .fa-search-input { flex: 1; min-width: 0; border: none; outline: none; background: transparent; font: inherit; font-size: 18px; color: #233423; padding: 6px 2px; }");
                out.println("  .fa-search-results { flex: 1; overflow-y: auto; padding: 8px 10px; min-height: 60px; }");
                out.println("  .fa-search-msg { padding: 14px 6px; color: #6a7a68; font-size: 14px; }");
                out.println("  .fa-search-foot { flex: 0 0 auto; padding: 7px 12px; border-top: 1px solid #e3e9e1; font-size: 12px; color: #6a7a68; }");
                out.println("  .fa-search-results mark { background: #f6e7a6; color: inherit; padding: 0; border-radius: 2px; }");
                out.println("  .fa-search-done .fa-picker-desc { font-weight: 400; color: #5b6b58; }");
                out.println("  .fa-search-done .fa-picker-when { color: #2f7a2f; }");
                out.println("  .fa-search-overdue .fa-picker-when { color: #a8471f; }");
                out.println("</style>");
        }

        private void printScript(PrintWriter out) {
                String url = jsString(servletUrl);
                out.println("<script>");
                out.println("  var faSearch = { timer: null, seq: 0, minLength: " + FocusActionSearchService.MIN_QUERY_LENGTH + " };");
                out.println("  function faSearchOpen() {");
                out.println("    var modal = document.getElementById('faSearchModal'); if (!modal) { return; }");
                out.println("    modal.classList.add('fa-modal-open');");
                out.println("    var input = document.getElementById('faSearchInput');");
                out.println("    input.value = ''; faSearchShowMessage('Type at least ' + faSearch.minLength + ' characters of an action or project.');");
                out.println("    setTimeout(function() { input.focus(); }, 0);");
                out.println("  }");
                out.println("  function faSearchClose() {");
                out.println("    var modal = document.getElementById('faSearchModal'); if (modal) { modal.classList.remove('fa-modal-open'); }");
                out.println("    faSearch.seq++; clearTimeout(faSearch.timer);");
                out.println("  }");
                out.println("  function faSearchShowMessage(text) {");
                out.println("    var box = document.getElementById('faSearchResults'); box.innerHTML = '';");
                out.println("    var msg = document.createElement('div'); msg.className = 'fa-search-msg'; msg.textContent = text; box.appendChild(msg);");
                out.println("  }");
                out.println("  function faSearchInput() {");
                out.println("    clearTimeout(faSearch.timer);");
                out.println("    var text = document.getElementById('faSearchInput').value.trim();");
                out.println("    if (text.length < faSearch.minLength) { faSearch.seq++; faSearchShowMessage('Type at least ' + faSearch.minLength + ' characters of an action or project.'); return; }");
                out.println("    faSearch.timer = setTimeout(function() { faSearchRun(text); }, 200);");
                out.println("  }");
                out.println("  function faSearchRun(text) {");
                out.println("    var seq = ++faSearch.seq;");
                out.println("    fetch('" + url + "?action=searchActions&q=' + encodeURIComponent(text), { credentials: 'same-origin' })");
                out.println("      .then(function(r) { if (!r.ok) { throw new Error('HTTP ' + r.status); } return r.json(); })");
                out.println("      .then(function(data) { if (seq === faSearch.seq) { faSearchRender(data && data.results ? data.results : [], text); } })");
                out.println("      .catch(function() { if (seq === faSearch.seq) { faSearchShowMessage('Search failed. Try again.'); } });");
                out.println("  }");
                out.println("  function faSearchEscape(s) { return String(s == null ? '' : s).replace(/[&<>\"']/g, function(c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '\"': '&quot;', \"'\": '&#39;' }[c]; }); }");
                out.println("  function faSearchMark(text, words) {");
                out.println("    var html = faSearchEscape(text);");
                out.println("    var parts = words.map(function(w) { return faSearchEscape(w).replace(/[.*+?^${}()|[\\]\\\\]/g, '\\\\$&'); }).filter(function(w) { return w.length; });");
                out.println("    if (!parts.length) { return html; }");
                out.println("    // Only mark text outside entities and tags.");
                out.println("    return html.replace(new RegExp('(&[a-z#0-9]+;)|(' + parts.join('|') + ')', 'gi'), function(m, ent) { return ent ? m : '<mark>' + m + '</mark>'; });");
                out.println("  }");
                out.println("  function faSearchMinutes(m) { if (m == null) { return ''; } var h = Math.floor(m / 60), r = m % 60; return h ? (r ? h + 'h ' + r + 'm' : h + 'h') : r + 'm'; }");
                out.println("  function faSearchRender(results, text) {");
                out.println("    var box = document.getElementById('faSearchResults');");
                out.println("    if (!results.length) { faSearchShowMessage('No actions match \\u201c' + text + '\\u201d.'); return; }");
                out.println("    var words = text.toLowerCase().split(/\\s+/).filter(function(w) { return w.length; });");
                out.println("    var html = ''; var group = null;");
                out.println("    results.forEach(function(r) {");
                out.println("      if (r.group !== group) { if (group !== null) { html += '</div>'; } group = r.group; html += '<div class=\"fa-picker-group\"><div class=\"fa-picker-group-title\">' + faSearchEscape(group) + '</div>'; }");
                out.println("      var cls = 'fa-picker-row' + (r.current ? ' fa-picker-current' : '') + (r.done ? ' fa-search-done' : '') + (r.group === '" + FocusActionSearchService.GROUP_OVERDUE + "' ? ' fa-search-overdue' : '');");
                out.println("      html += '<a class=\"' + cls + '\" href=\"" + url + "?action=switchAction&amp;completingActionNextId=' + r.id + '\">'");
                out.println("        + '<span class=\"fa-picker-icon\" aria-hidden=\"true\">' + faSearchEscape(r.icon) + '</span>'");
                out.println("        + '<span class=\"fa-picker-main\"><span class=\"fa-picker-project\">' + faSearchMark(r.project, words)");
                out.println("        + (r.current ? ' <span class=\"fa-picker-tag fa-picker-tag-current\">Current</span>' : '') + '</span>'");
                out.println("        + '<span class=\"fa-picker-desc\">' + (r.done ? '&#10003; ' : '') + faSearchMark(r.description, words) + '</span></span>'");
                out.println("        + '<span class=\"fa-picker-side\"><span class=\"fa-picker-when\">' + faSearchEscape(r.when) + '</span>'");
                out.println("        + (r.estimate ? '<span class=\"fa-picker-meta\">est ' + faSearchMinutes(r.estimate) + '</span>' : '') + '</span></a>';");
                out.println("    });");
                out.println("    html += '</div>';");
                out.println("    box.innerHTML = html; box.scrollTop = 0;");
                out.println("    faSearchSetActive(box.querySelector('.fa-picker-row'));");
                out.println("  }");
                out.println("  function faSearchSetActive(row) {");
                out.println("    var box = document.getElementById('faSearchResults');");
                out.println("    var old = box.querySelector('.fa-picker-active'); if (old) { old.classList.remove('fa-picker-active'); }");
                out.println("    if (row) { row.classList.add('fa-picker-active'); row.scrollIntoView({ block: 'nearest' }); }");
                out.println("  }");
                out.println("  function faSearchKeydown(e) {");
                out.println("    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); faSearchClose(); return; }");
                out.println("    var rows = Array.prototype.slice.call(document.querySelectorAll('#faSearchResults .fa-picker-row'));");
                out.println("    if (!rows.length) { return; }");
                out.println("    var index = rows.indexOf(document.querySelector('#faSearchResults .fa-picker-active'));");
                out.println("    if (e.key === 'ArrowDown') { e.preventDefault(); faSearchSetActive(rows[Math.min(rows.length - 1, index + 1)]); }");
                out.println("    else if (e.key === 'ArrowUp') { e.preventDefault(); faSearchSetActive(rows[Math.max(0, index - 1)]); }");
                out.println("    else if (e.key === 'Enter') { e.preventDefault(); if (index >= 0) { rows[index].click(); } }");
                out.println("  }");
                out.println("  document.addEventListener('keydown', function(e) {");
                out.println("    if (e.key !== '/' || e.altKey || e.ctrlKey || e.metaKey) { return; }");
                out.println("    if (document.querySelector('.fa-modal-open')) { return; }");
                out.println("    var el = document.activeElement;");
                out.println("    if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable)) { return; }");
                out.println("    e.preventDefault(); faSearchOpen();");
                out.println("  });");
                out.println("</script>");
        }

        private static String jsString(String value) {
                return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
        }
}
