// Results Panel Infrastructure
// Shared rendering for the result panels ({panel}-results / {panel}-details) and the
// extracted-object lists ({x}-list / {x}-items).
//
// Why this exists: a full migration run produces thousands of views, tables and functions.
// Rendering every row of every panel into one document pushed the DOM past 100k nodes and
// made the page unusable. Three rules fix that, and they are implemented here once instead
// of in each of the ~25 panels:
//
//   1. Deferred detail  - a panel renders its cheap summary immediately and builds the
//                         per-object rows only when expanded. Collapsing empties them again,
//                         so a collapsed panel costs nothing.
//   2. Capped rows      - at most RESULTS_ROW_CAP rows are rendered; the rest are reachable
//                         through the plain-text report (which the browser handles happily
//                         at sizes where the DOM does not).
//   3. Deferred code    - SQL blocks are the most expensive nodes there are. They are held
//                         as data and turned into <pre> only on click.

// Maximum number of object rows rendered into any one list section.
const RESULTS_ROW_CAP = 200;

// panelId -> { renderDetail } for panels whose detail is built on expand.
const resultsPanelRegistry = new Map();

// listId -> { render } for extracted-object lists built on expand.
const deferredListRegistry = new Map();

// code block id -> source text, materialized into the DOM only when opened.
const deferredCodeBlocks = new Map();

// groupId -> renderer for schema groups inside an expanded object list.
const schemaGroupRenderers = new Map();

// Monotonic counter for generated element ids.
let resultsPanelSeq = 0;

/**
 * Escape text for safe interpolation into innerHTML.
 * Oracle SQL routinely contains '<' and '&', which silently corrupted panels before.
 */
function escapeHtml(value) {
    if (value === null || value === undefined) return '';
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

/** Format a count with thousands separators. */
function formatCount(n) {
    return Number(n || 0).toLocaleString();
}

// Report links
// ============

/** URL of the plain-text report for a job. */
function jobReportUrl(jobId) {
    return `/api/jobs/${encodeURIComponent(jobId)}/report`;
}

/**
 * Link that opens the full plain-text report in a new tab.
 * Returns '' when no job id is known, so callers can interpolate unconditionally.
 */
function renderReportLink(jobId, text = 'Open full report') {
    if (!jobId) return '';
    return `<a class="report-link" href="${escapeHtml(jobReportUrl(jobId))}" target="_blank" rel="noopener">${escapeHtml(text)} ↗</a>`;
}

// Result panels
// =============

/**
 * Populate a result panel.
 *
 * The summary is rendered immediately (it is a handful of nodes); the detail closure is
 * stored and invoked only when the user expands the panel.
 *
 * @param {string} panelId    common prefix of the '-results' and '-details' element ids
 * @param {object} options    { summaryHtml, renderDetail, detailLabel }
 *                            renderDetail is a function returning an HTML string.
 */
function setResultsPanel(panelId, options = {}) {
    const resultsDiv = document.getElementById(`${panelId}-results`);
    const detailsDiv = document.getElementById(`${panelId}-details`);

    if (!resultsDiv || !detailsDiv) {
        console.error(`Results panel not found: ${panelId}`);
        return;
    }

    const summaryHtml = options.summaryHtml || '';
    const renderDetail = options.renderDetail || null;
    const detailLabel = options.detailLabel || 'details';

    resultsPanelRegistry.set(panelId, { renderDetail: renderDetail, expanded: false });

    let html = `<div class="panel-summary">${summaryHtml}</div>`;
    if (renderDetail) {
        html += `<div class="panel-detail-hint" onclick="toggleResultsPanel('${panelId}')">`;
        html += `▼ Show ${escapeHtml(detailLabel)}`;
        html += `</div>`;
        html += `<div class="panel-detail" id="${panelId}-panel-detail" style="display: none;"></div>`;
    }

    detailsDiv.innerHTML = html;
    detailsDiv.style.display = 'block';
    resultsDiv.style.display = 'block';
    setToggleIndicator(resultsDiv, false);
}

/**
 * Expand or collapse a panel's detail section.
 * Expanding builds the rows; collapsing discards them so they stop costing memory and layout.
 *
 * The expanded flag lives in the registry rather than being read back out of the DOM: an
 * element's inline style is not the place to keep application state, and inferring it there
 * breaks as soon as a stylesheet has an opinion about the same property.
 */
function toggleResultsPanel(panelId) {
    const resultsDiv = document.getElementById(`${panelId}-results`);
    const detail = document.getElementById(`${panelId}-panel-detail`);
    const entry = resultsPanelRegistry.get(panelId);

    if (!detail || !entry) {
        console.warn(`No deferred detail registered for panel: ${panelId}`);
        return;
    }

    const hint = detail.previousElementSibling;
    const expanding = !entry.expanded;
    entry.expanded = expanding;

    if (expanding) {
        detail.innerHTML = entry.renderDetail ? entry.renderDetail() : '';
        detail.style.display = 'block';
        if (hint) hint.textContent = '▲ Hide details';
    } else {
        // Emptying, not just hiding: a hidden subtree still occupies memory and is still
        // walked by style recalculation.
        detail.innerHTML = '';
        detail.style.display = 'none';
        if (hint) hint.textContent = '▼ Show details';
    }

    setToggleIndicator(resultsDiv, expanding);
}

/** Point a panel header's ▼/▲ indicator in the right direction. */
function setToggleIndicator(container, expanded) {
    if (!container) return;
    const indicator = container.querySelector('.toggle-indicator');
    if (indicator) {
        indicator.textContent = expanded ? '▲' : '▼';
    }
}

// Capped lists
// ============

/**
 * Render at most RESULTS_ROW_CAP rows, followed by a note pointing at the full report.
 *
 * @param {Array}    items      rows to render
 * @param {Function} renderItem item -> HTML string
 * @param {object}   options    { cap, jobId, label }
 */
function renderCappedList(items, renderItem, options = {}) {
    const list = items || [];
    const cap = options.cap || RESULTS_ROW_CAP;
    const label = options.label || 'entries';

    let html = '';
    const shown = Math.min(list.length, cap);
    for (let i = 0; i < shown; i++) {
        html += renderItem(list[i], i);
    }

    if (list.length > cap) {
        const link = renderReportLink(options.jobId);
        html += `<div class="list-cap-note">`;
        html += `Showing ${formatCount(cap)} of ${formatCount(list.length)} ${escapeHtml(label)}.`;
        html += link ? ` ${link}` : '';
        html += `</div>`;
    }

    return html;
}

// Creation-result rendering
// =========================
//
// Every creation/implementation job reports the same shape — a few counts, then a
// created / skipped / failed breakdown — so the rendering lives here once.

/**
 * Normalize a result collection to a sorted array.
 * Backends hand these over as either a map keyed by object name or a plain list.
 *
 * @param {object|Array} collection
 * @param {...string}    sortKeys  fields to sort by, in order (e.g. 'schema', 'viewName')
 */
function toSortedArray(collection, ...sortKeys) {
    if (!collection) return [];
    const array = Array.isArray(collection) ? collection.slice() : Object.values(collection);
    const keys = sortKeys.length > 0 ? sortKeys : ['name'];

    return array.sort((a, b) => {
        for (const key of keys) {
            const compare = String(a[key] || '').localeCompare(String(b[key] || ''));
            if (compare !== 0) return compare;
        }
        return 0;
    });
}

/**
 * The count line at the top of a result panel — always rendered, always cheap.
 * @param {Array} stats [{ label, value, cssClass }]
 */
function renderSummaryStats(stats) {
    let html = '<div class="table-creation-summary"><div class="summary-stats">';
    stats.forEach(stat => {
        html += `<span class="stat-item ${stat.cssClass || ''}">${escapeHtml(stat.label)}: ${formatCount(stat.value)}</span>`;
    });
    html += '</div></div>';
    return html;
}

/**
 * Render the created / skipped / failed breakdown of a creation result.
 * Each section is capped independently and failure SQL stays deferred.
 *
 * @param {Array}  sections [{ title, items, cssClass, nameKey, suffix, showError, renderItem }]
 *                          renderItem overrides the default row rendering for that section.
 * @param {object} options  { jobId, label }
 */
function renderOutcomeSections(sections, options = {}) {
    const label = options.label || 'objects';
    let html = '';

    sections.forEach(section => {
        const items = section.items || [];
        if (items.length === 0) return;

        const nameKey = section.nameKey || 'name';
        const renderItem = section.renderItem || (item => renderOutcomeItem(item, {
            cssClass: section.cssClass,
            nameKey: nameKey,
            suffix: section.suffix,
            showError: section.showError
        }));

        html += `<div class="outcome-section">`;
        html += `<h4>${escapeHtml(section.title)} (${formatCount(items.length)})</h4>`;
        html += `<div class="table-items">`;
        html += renderCappedList(items, renderItem, { jobId: options.jobId, label: label });
        html += `</div></div>`;
    });

    return html;
}

/** One row of a creation-result section. */
function renderOutcomeItem(item, options = {}) {
    const name = item[options.nameKey] || item.name || item.qualifiedName || '(unnamed)';

    let html = `<div class="table-item ${options.cssClass || ''}">`;
    if (options.showError) {
        html += `<strong>${escapeHtml(name)}</strong>: ${escapeHtml(item.error || item.errorMessage || item.reason || '')}`;
        html += renderDeferredCode(item.sql || item.statement);
    } else {
        html += escapeHtml(name) + (options.suffix || '');
        if (item.reason) {
            html += ` <span class="item-reason">(${escapeHtml(item.reason)})</span>`;
        }
    }
    html += `</div>`;
    return html;
}

// Deferred code blocks
// ====================

/**
 * Render a "show SQL" link whose <pre> block is created only when clicked.
 * The source is held in JS until then — a few thousand <pre> nodes is what made the
 * failed-view lists unusable.
 *
 * @param {string} code  the SQL or PL/SQL source
 * @param {string} label link text
 */
function renderDeferredCode(code, label = 'Show SQL') {
    if (!code) return '';
    const id = `code-block-${++resultsPanelSeq}`;
    deferredCodeBlocks.set(id, code);
    return `<div class="code-toggle" onclick="toggleDeferredCode('${id}')">▸ ${escapeHtml(label)}</div>`
         + `<div class="sql-statement" id="${id}" style="display: none;"></div>`;
}

/** Materialize or discard a deferred code block. */
function toggleDeferredCode(id) {
    const block = document.getElementById(id);
    if (!block) return;

    const toggle = block.previousElementSibling;
    const expanding = block.style.display === 'none';

    if (expanding) {
        block.innerHTML = `<pre>${escapeHtml(deferredCodeBlocks.get(id) || '')}</pre>`;
        block.style.display = 'block';
        if (toggle) toggle.textContent = toggle.textContent.replace('▸', '▾');
    } else {
        block.innerHTML = '';
        block.style.display = 'none';
        if (toggle) toggle.textContent = toggle.textContent.replace('▾', '▸');
    }
}

// Extracted-object lists
// ======================

/**
 * Register an extracted-object list ({x}-list / {x}-items) for deferred rendering.
 * The list container is shown, but its rows are built only when the header is clicked.
 *
 * @param {string}   listId   id of the outer list container
 * @param {string}   itemsId  id of the inner items container
 * @param {Function} render   returns the items HTML
 * @param {string}   summary  one-line header summary, always visible
 */
function setDeferredList(listId, itemsId, render, summary) {
    const listDiv = document.getElementById(listId);
    const itemsDiv = document.getElementById(itemsId);

    if (!listDiv || !itemsDiv) {
        console.warn(`Object list not found: ${listId}`);
        return;
    }

    deferredListRegistry.set(listId, { itemsId: itemsId, render: render, expanded: false });

    itemsDiv.innerHTML = '';
    itemsDiv.style.display = 'none';
    listDiv.style.display = 'block';

    const header = listDiv.firstElementChild;
    if (header) {
        header.classList.add('collapsed');
        if (summary) {
            const indicator = header.querySelector('.toggle-indicator');
            const arrow = indicator ? indicator.outerHTML : '<span class="toggle-indicator">▼</span>';
            header.innerHTML = `${arrow} ${escapeHtml(summary)}`;
        }
    }
}

/** Expand or collapse a registered object list, building or discarding its rows. */
function toggleDeferredList(listId) {
    const listDiv = document.getElementById(listId);
    const entry = deferredListRegistry.get(listId);

    if (!listDiv || !entry) {
        console.warn(`No deferred renderer registered for list: ${listId}`);
        return false;
    }

    const itemsDiv = document.getElementById(entry.itemsId);
    if (!itemsDiv) return false;

    const header = listDiv.firstElementChild;
    const expanding = !entry.expanded;
    entry.expanded = expanding;

    if (expanding) {
        itemsDiv.innerHTML = entry.render();
        itemsDiv.style.display = 'block';
        if (header) header.classList.remove('collapsed');
    } else {
        itemsDiv.innerHTML = '';
        itemsDiv.style.display = 'none';
        if (header) header.classList.add('collapsed');
    }

    setToggleIndicator(listDiv, expanding);
    return expanding;
}

/**
 * Group objects by schema and render each group as a capped, collapsible section.
 * Used by every extraction list; groups start collapsed so expanding a 5,000-object list
 * costs one row per schema rather than 5,000 rows.
 *
 * @param {Array}    objects    the extracted objects
 * @param {Function} renderItem object -> HTML string for one row
 * @param {object}   options    { schemaKey, label, jobId, groupIdPrefix, groupSummary }
 *                              groupSummary(group) adds text to the group header, for figures
 *                              that are worth seeing without expanding (e.g. total rows).
 */
function renderSchemaGroups(objects, renderItem, options = {}) {
    const schemaKey = options.schemaKey || 'schema';
    const label = options.label || 'objects';
    const groupIdPrefix = options.groupIdPrefix || `group-${++resultsPanelSeq}`;

    if (!objects || objects.length === 0) {
        return `<div class="table-item" style="font-style: italic; color: #999;">No ${escapeHtml(label)} found</div>`;
    }

    const bySchema = {};
    objects.forEach(obj => {
        const schema = obj[schemaKey] || 'UNKNOWN';
        if (!bySchema[schema]) bySchema[schema] = [];
        bySchema[schema].push(obj);
    });

    let html = '';
    Object.keys(bySchema).sort().forEach(schema => {
        const group = bySchema[schema];
        const groupId = `${groupIdPrefix}-${schema}`;

        html += `<div class="table-schema-group">`;
        html += `<div class="table-schema-header collapsed" onclick="toggleSchemaGroupRows('${groupId}')">`;
        const extra = options.groupSummary ? options.groupSummary(group) : '';
        html += `<span class="toggle-indicator">▼</span> ${escapeHtml(schema)} `;
        html += `(${formatCount(group.length)} ${escapeHtml(label)}${extra})`;
        html += `</div>`;
        html += `<div class="table-items-list" id="${groupId}" style="display: none;"></div>`;
        html += `</div>`;

        schemaGroupRenderers.set(groupId, { expanded: false, render: () => renderCappedList(group, renderItem, {
            label: label,
            jobId: options.jobId
        }) });
    });

    return html;
}

/** Expand or collapse one schema group inside an object list. */
function toggleSchemaGroupRows(groupId) {
    const rows = document.getElementById(groupId);
    const entry = schemaGroupRenderers.get(groupId);

    if (!rows || !entry) return;

    const header = rows.previousElementSibling;
    const expanding = !entry.expanded;
    entry.expanded = expanding;

    if (expanding) {
        rows.innerHTML = entry.render();
        rows.style.display = 'block';
        if (header) header.classList.remove('collapsed');
    } else {
        rows.innerHTML = '';
        rows.style.display = 'none';
        if (header) header.classList.add('collapsed');
    }

    if (header) setToggleIndicator(header.parentElement, expanding);
}
