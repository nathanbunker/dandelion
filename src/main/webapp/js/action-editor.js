/*
 * The one Edit Action box, shared by every page. ActionEditorRenderer prints the markup and
 * loads this file; pages call ActionEditor.configure({...}) once for their after-save handling
 * and ActionEditor.open(actionNextId) to edit. Saves go through the dashboard JSON endpoints
 * (loadActionData / editAction / CompleteActionEdit / deleteAction), which update both twins of
 * a linked-project action.
 */
(function () {
  'use strict';

  // Verb menu entries ({type, label}), read from the rendered menu at init.
  var VERBS = [];
  // Phrases typed at the start of the description that switch the verb, longest first; the
  // extra ones match how Quick Capture sentences are worded.
  var PREFIXES = [];
  var EXTRA_PREFIXES = [
    { type: 'COMMITTED_TO', text: 'have committed' },
    { type: 'GOAL', text: 'have set goal to' },
    { type: 'WAITING', text: 'am waiting' }
  ];
  var WHO_TYPES = { WILL_CONTACT: true, WILL_MEET: true, WAITING: true };
  var WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  // Kept out of the source as a literal so the file stays ASCII whatever charset it is read with.
  var DOT = ' ' + String.fromCharCode(183) + ' ';

  var settings = {
    endpoint: 'DandelionDashboardServlet',
    onSaved: null,
    onCompleted: null,
    onDeleted: null
  };
  var state = {
    id: 0, options: {}, personal: false, type: 'WILL', originalDate: '', busy: false,
    lastText: '', prefixPending: false, baseNote: ''
  };

  function el(id) { return document.getElementById(id); }
  function val(id) { var e = el(id); return e ? (e.value || '') : ''; }
  function setVal(id, v) { var e = el(id); if (e) { e.value = v == null ? '' : String(v); } }
  function show(id, visible) { var e = el(id); if (e) { e.hidden = !visible; } }

  function post(params, label) {
    var body = new URLSearchParams();
    Object.keys(params).forEach(function (k) { body.append(k, params[k] == null ? '' : String(params[k])); });
    return fetch(settings.endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
      body: body.toString()
    }).then(function (response) {
      return response.text().then(function (text) {
        if (!response.ok) { throw new Error(label + ' failed (HTTP ' + response.status + ')'); }
        try { return JSON.parse(text); } catch (e) { throw new Error(label + ' returned an unexpected response'); }
      });
    });
  }

  // ---- dates ----
  function parseIso(iso) {
    var p = (iso || '').split('-');
    if (p.length !== 3) { return null; }
    return new Date(parseInt(p[0], 10), parseInt(p[1], 10) - 1, parseInt(p[2], 10));
  }
  function toIso(d) {
    var m = String(d.getMonth() + 1); var day = String(d.getDate());
    return d.getFullYear() + '-' + (m.length < 2 ? '0' + m : m) + '-' + (day.length < 2 ? '0' + day : day);
  }
  function addDays(d, n) { return new Date(d.getFullYear(), d.getMonth(), d.getDate() + n); }
  function today() {
    var overlay = el('aeOverlay');
    var fromServer = overlay ? parseIso(overlay.getAttribute('data-today')) : null;
    if (fromServer) { return fromServer; }
    var now = new Date();
    return new Date(now.getFullYear(), now.getMonth(), now.getDate());
  }
  function describeDate(iso) {
    var d = parseIso(iso);
    if (!d) { return 'No date'; }
    var diff = Math.round((d - today()) / 86400000);
    var label = WEEKDAYS[d.getDay()] + ' ' + (d.getMonth() + 1) + '/' + d.getDate();
    if (diff === 0) { return label + DOT + 'today'; }
    if (diff === 1) { return label + DOT + 'tomorrow'; }
    if (diff < 0) { return label + DOT + (-diff) + (diff === -1 ? ' day ago' : ' days ago'); }
    return label + DOT + 'in ' + diff + ' days';
  }

  function buildDateChips() {
    var box = el('aeDateChips');
    if (!box) { return; }
    box.innerHTML = '';
    var base = today();
    var chips = [{ label: 'Today', date: base }, { label: 'Tomorrow', date: addDays(base, 1) }];
    for (var i = 2; i <= 6; i++) {
      var d = addDays(base, i);
      if (d.getDay() !== 0 && d.getDay() !== 6) { chips.push({ label: WEEKDAYS[d.getDay()], date: d }); }
    }
    chips.push({ label: '+1 week', date: addDays(base, 7) });
    chips.forEach(function (chip) {
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'ae-chip';
      b.textContent = chip.label;
      b.setAttribute('data-date', toIso(chip.date));
      b.title = describeDate(toIso(chip.date));
      b.addEventListener('click', function () { setVal('aeDate', b.getAttribute('data-date')); refreshDate(); });
      box.appendChild(b);
    });
  }
  function refreshDate() {
    var iso = val('aeDate');
    var hint = el('aeDateHint');
    if (hint) { hint.textContent = describeDate(iso); }
    markActive('#aeDateChips .ae-chip', 'data-date', iso);
  }

  function markActive(selector, attr, value) {
    var nodes = document.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].classList.toggle('ae-chip-active', nodes[i].getAttribute(attr) === String(value));
    }
  }

  // ---- verb ----
  function verbLabel(type) {
    for (var i = 0; i < VERBS.length; i++) { if (VERBS[i].type === type) { return VERBS[i].label; } }
    return 'will';
  }
  function setType(type) {
    state.type = type || 'WILL';
    var btn = el('aeVerbBtn');
    if (btn) { btn.firstChild.nodeValue = verbLabel(state.type) + ' '; }
    markActive('#aeVerbMenu .ae-verb-option', 'data-type', state.type);
    var who = el('aeWhoField');
    if (who) { who.classList.toggle('ae-who-emphasized', !!WHO_TYPES[state.type]); }
  }
  function toggleVerbMenu(open) {
    var menu = el('aeVerbMenu');
    if (!menu) { return; }
    var willOpen = open === undefined ? menu.hidden : open;
    menu.hidden = !willOpen;
    var btn = el('aeVerbBtn');
    if (btn) { btn.setAttribute('aria-expanded', willOpen ? 'true' : 'false'); }
    if (willOpen) {
      var active = menu.querySelector('.ae-chip-active') || menu.querySelector('.ae-verb-option');
      if (active) { active.focus(); }
    }
  }

  // Typing "might ", "am waiting for ", "I will meet " ... at the start of the description
  // switches the verb and drops the words. A phrase that could still grow into a longer one
  // ("will " before "will meet ") waits for the next word.
  // Only a phrase just typed counts: a description that already started with "might ..." when
  // it was loaded is left alone.
  function leadText(value) {
    var lead = /^\s*i\s+/i.exec(value || '');
    return { offset: lead ? lead[0].length : 0, text: (value || '').substring(lead ? lead[0].length : 0).toLowerCase() };
  }
  function applyTypedPrefix() {
    var area = el('aeDescription');
    if (!area || state.personal) { return; }
    var value = area.value;
    var parsed = leadText(value);
    var text = parsed.text;
    var previous = state.lastText;
    state.lastText = text;
    for (var i = 0; i < PREFIXES.length; i++) {
      var phrase = PREFIXES[i].text + ' ';
      if (text.indexOf(phrase) !== 0) { continue; }
      if (!state.prefixPending && previous.indexOf(phrase) === 0) { break; }
      for (var j = 0; j < PREFIXES.length; j++) {
        var longer = PREFIXES[j].text + ' ';
        if (longer.length > phrase.length && longer.indexOf(phrase) === 0 && longer.indexOf(text) === 0) {
          state.prefixPending = true;
          return;
        }
      }
      setType(PREFIXES[i].type);
      var cut = parsed.offset + phrase.length;
      var caret = area.selectionStart == null ? value.length : area.selectionStart;
      area.value = value.substring(cut);
      var pos = Math.max(0, caret - cut);
      area.setSelectionRange(pos, pos);
      state.lastText = leadText(area.value).text;
      state.prefixPending = false;
      return;
    }
    state.prefixPending = false;
  }

  // ---- estimate / link ----
  function refreshEstimate() { markActive('#aeEstimateChips .ae-chip', 'data-minutes', parseInt(val('aeEstimate') || '0', 10)); }
  function refreshLink() {
    var link = el('aeLinkOpen');
    var url = val('aeLink').trim();
    if (!link) { return; }
    var ok = /^https?:\/\//i.test(url);
    link.hidden = !ok;
    if (ok) { link.href = url; }
  }

  // ---- errors / busy ----
  function showError(message) {
    var box = el('aeError');
    if (!box) { return; }
    box.textContent = message || '';
    box.hidden = !message;
  }
  function setBusy(busy) {
    state.busy = busy;
    var buttons = document.querySelectorAll('#aeOverlay .ae-foot button, #aeCompleteSubmit');
    for (var i = 0; i < buttons.length; i++) { buttons[i].disabled = busy; }
  }

  // ---- open / close ----
  function open(actionNextId, options) {
    var id = parseInt(actionNextId, 10);
    if (!id) { return; }
    state.id = id;
    state.options = options || {};
    showError('');
    post({ action: 'loadActionData', actionNextId: id }, 'Loading the action').then(function (data) {
      if (!data || !data.success) { throw new Error((data && data.message) || 'Unable to load the action'); }
      fill(data);
      var overlay = el('aeOverlay');
      overlay.hidden = false;
      document.body.classList.add('ae-open');
      var focusId = state.options.focus === 'note' ? 'aeNote' : 'aeDescription';
      var target = el(focusId);
      if (target) { target.focus(); target.setSelectionRange(target.value.length, target.value.length); }
    }).catch(function (err) {
      window.alert(err.message || 'Unable to load the action');
    });
  }

  // The thread: earlier steps of this action, newest first, built with textContent only.
  function fillHistory(steps, more) {
    var box = el('aeHistory'), list = el('aeHistoryList');
    if (!box || !list) { return; }
    list.textContent = '';
    box.hidden = steps.length === 0;
    box.open = false;
    el('aeHistoryCount').textContent = steps.length ? '(' + steps.length + (more ? '+' : '') + ')' : '';
    function line(parent, cls, text) {
      if (!text) { return; }
      var d = document.createElement('div'); d.className = cls; d.textContent = text; parent.appendChild(d);
    }
    for (var i = 0; i < steps.length; i++) {
      var s = steps[i];
      var li = document.createElement('li'); li.className = 'ae-history-step';
      var when = document.createElement('div'); when.className = 'ae-history-when';
      when.textContent = [s.dateLabel, s.minutes].filter(function (v) { return !!v; }).join(DOT);
      if (s.status) { var tag = document.createElement('span'); tag.className = 'ae-history-tag'; tag.textContent = s.status; when.appendChild(tag); }
      li.appendChild(when);
      line(li, 'ae-history-head', s.headline);
      line(li, 'ae-history-planned', s.planned ? 'Planned: ' + s.planned : '');
      line(li, 'ae-history-outcome', s.outcome);
      line(li, 'ae-history-notes', s.notes ? 'Notes: ' + s.notes : '');
      list.appendChild(li);
    }
    if (more) { var m = document.createElement('li'); m.className = 'ae-history-more'; m.textContent = 'Earlier steps not shown.'; list.appendChild(m); }
  }

  function fill(data) {
    state.personal = !!data.isPersonal;
    state.originalDate = data.nextActionDateIso || '';
    var subtitle = data.displayProjectName || data.projectName || '';
    if (state.options.fromCapture) { subtitle = 'Saved from Quick Capture' + (subtitle ? DOT + subtitle : ''); }
    var sub = el('aeSubtitle');
    if (sub) { sub.textContent = subtitle; }
    setVal('aeDescription', data.nextDescription);
    state.lastText = leadText(data.nextDescription).text;
    state.prefixPending = false;
    setVal('aeDate', state.originalDate);
    setVal('aeEstimate', data.nextTimeEstimate || 0);
    setVal('aeContact', data.nextContactId);
    setVal('aeLink', data.linkUrl);
    setVal('aeNote', data.nextNote);
    // The server re-attaches anything appended since this text was loaded, and refuses other changes.
    state.baseNote = data.nextNote == null ? '' : String(data.nextNote);
    setVal('aeTarget', data.nextTargetDate);
    setVal('aeDeadline', data.nextDeadlineDate);
    setVal('aeTimeSlot', data.timeSlot || 'AFTERNOON');
    setType(data.nextActionType || 'WILL');
    fillHistory(data.history || [], !!data.historyMore);
    show('aeVerbWrap', !state.personal);
    show('aeSlotField', state.personal);
    show('aeEstimateField', !state.personal);
    var cancel = el('aeCancel');
    if (cancel) { cancel.textContent = state.options.fromCapture ? 'Close' : 'Cancel'; }
    toggleVerbMenu(false);
    resetComplete();
    buildDateChips();
    refreshDate();
    refreshEstimate();
    refreshLink();
  }

  function close() {
    var overlay = el('aeOverlay');
    if (overlay) { overlay.hidden = true; }
    document.body.classList.remove('ae-open');
    toggleVerbMenu(false);
    state.id = 0;
  }
  function isOpen() { var overlay = el('aeOverlay'); return !!overlay && !overlay.hidden; }

  // ---- save / complete / delete ----
  function editParams() {
    var date = val('aeDate').trim() || state.originalDate;
    var params = {
      actionNextId: state.id,
      nextActionDate: date,
      nextActionType: state.personal ? '' : state.type,
      nextDescription: val('aeDescription'),
      nextTimeEstimate: state.personal ? '0' : (val('aeEstimate').trim() || '0'),
      nextContactId: val('aeContact'),
      nextTargetDate: val('aeTarget').trim(),
      nextDeadlineDate: val('aeDeadline').trim(),
      linkUrl: val('aeLink').trim(),
      nextNote: val('aeNote'),
      baseNote: state.baseNote
    };
    if (state.personal) { params.timeSlot = val('aeTimeSlot'); }
    return params;
  }

  function finish(handler, data, mode) {
    var id = state.id;
    close();
    if (typeof handler === 'function') { handler(data, mode, id); } else { window.location.reload(); }
  }

  function save(mode) {
    if (state.busy || !state.id) { return; }
    var params = editParams();
    params.action = 'editAction';
    params.saveMode = mode;
    setBusy(true);
    showError('');
    post(params, 'Saving').then(function (data) {
      setBusy(false);
      if (!data || !data.success) { showError((data && data.message) || 'Unable to save the action.'); return; }
      finish(settings.onSaved, data, mode);
    }).catch(function (err) { setBusy(false); showError(err.message); });
  }

  function resetComplete() {
    show('aeCompleteSection', false);
    var now = new Date();
    var pad = function (v) { return (v < 10 ? '0' : '') + v; };
    setVal('aeCompleteDescription', '');
    setVal('aeCompleteNote', '');
    setVal('aeFollowUp', '');
    setVal('aeFollowUpNotes', '');
    show('aeFollowUpNotesField', false);
    setVal('aeCompleteDate', toIso(now));
    setVal('aeCompleteTime', pad(now.getHours()) + ':' + pad(now.getMinutes()));
    setVal('aeCompleteDuration', '0');
  }
  function toggleComplete() {
    var section = el('aeCompleteSection');
    if (!section) { return; }
    section.hidden = !section.hidden;
    if (!section.hidden) { el('aeCompleteDescription').focus(); }
  }
  function complete() {
    if (state.busy || !state.id) { return; }
    var params = editParams();
    params.action = 'CompleteActionEdit';
    params.completeDescription = val('aeCompleteDescription');
    params.completeNote = val('aeCompleteNote');
    params.followUp = val('aeFollowUp');
    params.followUpNotes = val('aeFollowUp').trim() ? val('aeFollowUpNotes') : '';
    params.completeDate = val('aeCompleteDate');
    params.completeTime = val('aeCompleteTime');
    params.completeDuration = val('aeCompleteDuration');
    setBusy(true);
    showError('');
    post(params, 'Completing').then(function (data) {
      setBusy(false);
      if (!data || !data.success) { showError((data && data.message) || 'Unable to complete the action.'); return; }
      finish(settings.onCompleted || settings.onSaved, data, 'complete');
    }).catch(function (err) { setBusy(false); showError(err.message); });
  }

  function remove() {
    if (state.busy || !state.id) { return; }
    setBusy(true);
    showError('');
    post({ action: 'deleteAction', actionNextId: state.id }, 'Deleting').then(function (data) {
      setBusy(false);
      if (!data || !data.success) { showError((data && data.message) || 'Unable to delete the action.'); return; }
      finish(settings.onDeleted || settings.onSaved, data, 'delete');
    }).catch(function (err) { setBusy(false); showError(err.message); });
  }

  // ---- wiring ----
  function init() {
    var overlay = el('aeOverlay');
    if (!overlay || overlay.getAttribute('data-ready')) { return; }
    overlay.setAttribute('data-ready', 'true');

    var verbNodes = document.querySelectorAll('#aeVerbMenu .ae-verb-option');
    for (var v = 0; v < verbNodes.length; v++) {
      VERBS.push({ type: verbNodes[v].getAttribute('data-type'), label: verbNodes[v].textContent });
    }
    PREFIXES = VERBS.map(function (verb) { return { type: verb.type, text: verb.label }; })
      .concat(EXTRA_PREFIXES)
      .sort(function (a, b) { return b.text.length - a.text.length; });

    overlay.addEventListener('mousedown', function (e) { if (e.target === overlay) { close(); } });
    el('aeClose').addEventListener('click', close);
    el('aeCancel').addEventListener('click', close);
    el('aeSave').addEventListener('click', function () { save('save'); });
    el('aeSaveStart').addEventListener('click', function () { save('saveAndStart'); });
    el('aeCompleteToggle').addEventListener('click', toggleComplete);
    el('aeCompleteSubmit').addEventListener('click', complete);
    el('aeFollowUp').addEventListener('input', function () {
      show('aeFollowUpNotesField', val('aeFollowUp').trim().length > 0);
    });
    el('aeDelete').addEventListener('click', remove);

    el('aeVerbBtn').addEventListener('click', function (e) { e.stopPropagation(); toggleVerbMenu(); });
    var options = document.querySelectorAll('#aeVerbMenu .ae-verb-option');
    for (var i = 0; i < options.length; i++) {
      options[i].addEventListener('click', function (e) {
        setType(e.currentTarget.getAttribute('data-type'));
        toggleVerbMenu(false);
        el('aeDescription').focus();
      });
    }
    el('aeVerbMenu').addEventListener('keydown', function (e) {
      if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') { return; }
      e.preventDefault();
      var list = Array.prototype.slice.call(document.querySelectorAll('#aeVerbMenu .ae-verb-option'));
      var at = list.indexOf(document.activeElement);
      var next = e.key === 'ArrowDown' ? (at + 1) % list.length : (at - 1 + list.length) % list.length;
      list[next].focus();
    });
    document.addEventListener('click', function (e) {
      var wrap = el('aeVerbWrap');
      if (wrap && !wrap.contains(e.target)) { toggleVerbMenu(false); }
    });

    el('aeDescription').addEventListener('input', applyTypedPrefix);
    el('aeDate').addEventListener('input', refreshDate);
    el('aeDate').addEventListener('change', refreshDate);
    el('aeEstimate').addEventListener('input', refreshEstimate);
    var estimateChips = document.querySelectorAll('#aeEstimateChips .ae-chip');
    for (var k = 0; k < estimateChips.length; k++) {
      estimateChips[k].addEventListener('click', function (e) {
        setVal('aeEstimate', e.currentTarget.getAttribute('data-minutes'));
        refreshEstimate();
      });
    }
    el('aeLink').addEventListener('input', refreshLink);

    document.addEventListener('keydown', function (e) {
      if (!isOpen()) { return; }
      if (e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation();
        var menu = el('aeVerbMenu');
        if (menu && !menu.hidden) { toggleVerbMenu(false); el('aeVerbBtn').focus(); } else { close(); }
        return;
      }
      if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
        e.preventDefault();
        var section = el('aeCompleteSection');
        if (section && !section.hidden && section.contains(document.activeElement)) { complete(); } else { save('save'); }
      }
    }, true);

    var autoOpen = parseInt(overlay.getAttribute('data-open-action') || '0', 10);
    if (autoOpen) {
      // The page is the response to a Quick Capture POST; make a reload a plain GET so it
      // can't save the sentence a second time.
      try { window.history.replaceState(null, '', overlay.getAttribute('data-reload-url') || window.location.pathname); } catch (e) { /* ignore */ }
      open(autoOpen, { fromCapture: true, focus: 'note' });
    }
  }

  window.ActionEditor = {
    configure: function (options) {
      Object.keys(options || {}).forEach(function (k) { settings[k] = options[k]; });
    },
    open: open,
    close: close
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
