/**
 * Pre-Flight Compatibility Report Module
 *
 * Runs the compatibility analysis job and renders its aggregated result: how many extracted
 * Oracle views transform, and which Oracle constructs are responsible for the ones that do not.
 *
 * The aggregate is rendered from a single small response; per-object detail is loaded lazily
 * per construct or failure group, so the panel stays responsive with thousands of views.
 *
 * Key Functions:
 * - runPreFlightAnalysis(): Starts the analysis job and polls until it completes
 * - loadPreFlightReport(): Fetches and renders the aggregated report
 * - togglePreFlightDetail(): Lazily loads the objects behind one construct or failure group
 */

const PREFLIGHT_POLL_INTERVAL_MS = 1000;

async function runPreFlightAnalysis() {
    const button = document.getElementById('preflight-run-btn');
    const container = document.getElementById('preflight-report');

    if (button) {
        button.disabled = true;
        button.textContent = 'Analysing...';
    }
    container.innerHTML = '<div class="config-help">Analysing extracted Oracle views...</div>';

    try {
        const response = await fetch('/api/preflight/oracle/analyze', { method: 'POST' });
        const result = await response.json();

        if (result.status !== 'success') {
            throw new Error(result.message || 'Failed to start analysis');
        }

        await waitForPreFlightJob(result.jobId);
        await loadPreFlightReport();

    } catch (error) {
        console.error('Pre-flight analysis failed:', error);
        container.innerHTML = `<div class="table-item error">Analysis failed: ${escapeHtml(error.message)}</div>`;
        updateMessage('Pre-flight analysis failed: ' + error.message);
    } finally {
        if (button) {
            button.disabled = false;
            button.textContent = 'Run Analysis';
        }
    }
}

async function waitForPreFlightJob(jobId) {
    while (true) {
        const response = await fetch(`/api/jobs/${jobId}/status`);
        const status = await response.json();

        if (status.progress) {
            updateProgress(status.progress.percentage, status.progress.currentTask || 'Analysing');
            if (status.progress.details) {
                updateMessage(status.progress.details);
            }
        }

        if (status.isComplete) {
            if (status.status === 'FAILED') {
                throw new Error(status.error || 'Analysis job failed');
            }
            return;
        }

        await delay(PREFLIGHT_POLL_INTERVAL_MS);
    }
}

async function loadPreFlightReport() {
    const container = document.getElementById('preflight-report');

    try {
        const response = await fetch('/api/preflight/report');
        const report = await response.json();

        if (report.status === 'empty') {
            container.innerHTML = `<div class="config-help">${escapeHtml(report.message)}</div>`;
            return;
        }

        container.innerHTML = renderPreFlightReport(report);

    } catch (error) {
        console.error('Failed to load pre-flight report:', error);
        container.innerHTML = `<div class="table-item error">Failed to load report: ${escapeHtml(error.message)}</div>`;
    }
}

function renderPreFlightReport(report) {
    const counts = report.statusCounts || {};

    let html = '<div class="table-creation-summary"><div class="summary-stats">';
    html += `<span class="stat-item created">OK: ${counts.OK || 0}</span>`;
    html += `<span class="stat-item skipped">With warnings: ${counts.OK_WITH_WARNINGS || 0}</span>`;
    html += `<span class="stat-item errors">Truncated: ${counts.TRUNCATED_PARSE || 0}</span>`;
    html += `<span class="stat-item errors">Parse errors: ${counts.PARSE_ERROR || 0}</span>`;
    html += `<span class="stat-item errors">Transform errors: ${counts.TRANSFORM_ERROR || 0}</span>`;
    html += `<span class="stat-item skipped">No source: ${counts.NO_SOURCE || 0}</span>`;
    html += '</div></div>';

    html += `<div class="config-help">${report.analyzedObjectCount} views analysed, `
          + `${report.failureCount} will not migrate correctly.</div>`;

    html += renderConstructSection(report.constructs || []);
    html += renderFailureSection(report.failureGroups || []);

    return html;
}

function renderConstructSection(constructs) {
    if (constructs.length === 0) {
        return '';
    }

    let html = '<div class="error-tables-section"><h4>Oracle constructs (ranked by failing views)</h4>';
    html += '<div class="table-items">';

    constructs.forEach(construct => {
        const badge = construct.silentLoss ? ' - SILENT LOSS' : '';
        const cssClass = construct.failingObjectCount > 0 || construct.silentLoss ? 'error' : 'skipped';
        const detailId = `preflight-construct-${construct.constructId}`;

        html += `<div class="table-item ${cssClass}">`;
        html += `<strong>${escapeHtml(construct.displayName)}</strong> `
              + `(${construct.support}${badge}): `
              + `${construct.failingObjectCount} failing, ${construct.passingObjectCount} passing, `
              + `${construct.occurrences} occurrences`;
        html += `<div class="config-help">${escapeHtml(construct.note || '')}</div>`;
        html += `<button class="refresh-btn" `
              + `onclick="togglePreFlightDetail('${detailId}', 'construct=${encodeURIComponent(construct.constructId)}')">`
              + `show views</button>`;
        html += `<div id="${detailId}" class="table-items-list" style="display: none;"></div>`;
        html += '</div>';
    });

    html += '</div></div>';
    return html;
}

function renderFailureSection(failureGroups) {
    if (failureGroups.length === 0) {
        return '';
    }

    let html = '<div class="error-tables-section"><h4>Failures grouped by cause</h4>';
    html += '<div class="table-items">';

    failureGroups.forEach((group, index) => {
        const detailId = `preflight-failure-${index}`;

        html += '<div class="table-item error">';
        html += `<strong>${group.status}</strong> x${group.objectCount}: `
              + `${escapeHtml(group.signature)}`;
        if (group.exampleMessage && group.exampleMessage !== group.signature) {
            html += `<div class="config-help">${escapeHtml(group.exampleMessage)}</div>`;
        }
        const groupQuery = `status=${encodeURIComponent(group.status)}`
                         + `&signature=${encodeURIComponent(group.signature)}`;
        html += `<button class="refresh-btn" `
              + `onclick="togglePreFlightDetail('${detailId}', '${groupQuery}')">`
              + `show views</button>`;
        html += `<div id="${detailId}" class="table-items-list" style="display: none;"></div>`;
        html += '</div>';
    });

    html += '</div></div>';
    return html;
}

/**
 * Loads the objects behind one construct or failure group on first expand, then toggles.
 */
async function togglePreFlightDetail(detailId, query) {
    const detail = document.getElementById(detailId);
    if (!detail) {
        return;
    }

    if (detail.style.display !== 'none') {
        detail.style.display = 'none';
        return;
    }

    detail.style.display = 'block';

    if (detail.dataset.loaded === 'true') {
        return;
    }

    detail.innerHTML = '<div class="config-help">Loading...</div>';

    try {
        const response = await fetch(`/api/preflight/report/findings?${query}&limit=50`);
        const result = await response.json();

        let html = `<div class="config-help">${result.totalMatching} matching views`
                 + `${result.totalMatching > result.limit ? `, showing first ${result.limit}` : ''}</div>`;

        result.findings.forEach(finding => {
            html += `<div class="table-item">`;
            html += `<strong>${escapeHtml(finding.qualifiedName)}</strong> (${finding.status})`;
            if (finding.errorMessage) {
                html += `<div class="config-help">${escapeHtml(finding.errorMessage)}</div>`;
            }
            finding.constructs.forEach(construct => {
                html += `<div class="config-help">line ${construct.line}: `
                      + `${escapeHtml(construct.displayName)} - ${escapeHtml(construct.snippet || '')}</div>`;
            });
            html += '</div>';
        });

        detail.innerHTML = html;
        detail.dataset.loaded = 'true';

    } catch (error) {
        console.error('Failed to load pre-flight findings:', error);
        detail.innerHTML = `<div class="table-item error">Failed to load: ${escapeHtml(error.message)}</div>`;
    }
}
