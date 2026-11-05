(function () {
    'use strict';

    const state = {
        sessionId: null,
        issues: [],
        index: 0,
        answers: [],
        result: null,
        bothMode: false,
        semesterQueue: [],
        currentSemester: null,
        needsConfirmBanner: false,
        committing: false
    };

    const SEMESTER_LABEL = {1: '1 (осень)', 2: '2 (весна)'};

    const API = '/api/schedule-generation';

    async function post(url, body) {
        let response;
        try {
            response = await fetch(url, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: body === undefined ? undefined : JSON.stringify(body)
            });
        } catch (networkError) {
            return failure('Сервер не ответил: ' + networkError.message
                + '. Проверьте соединение и попробуйте снова.');
        }
        if (response.status === 403) {
            return failure('Автосоставление доступно только администратору.');
        }
        const text = await response.text();
        if (!text) {
            return failure('Сервер вернул пустой ответ (код ' + response.status + ').');
        }
        try {
            return JSON.parse(text);
        } catch (parseError) {
            return failure('Не удалось разобрать ответ сервера (код ' + response.status + ').');
        }
    }

    function failure(message) {
        return {
            sessionId: state.sessionId,
            status: 'FAILED',
            successSchedule: [],
            warnings: [],
            metrics: {},
            missingData: [{
                id: 'client-' + Date.now(),
                code: 'DATA_ERROR',
                severity: 'BLOCKING',
                title: 'Не удалось выполнить запрос',
                message: message,
                context: {},
                options: [{actionCode: 'KEEP_AS_IS', label: 'Понятно', payload: {}, input: null}]
            }]
        };
    }

    async function generate(semesterOverride) {
        const yearSelect = document.getElementById('yearSelect');
        const academicYear = yearSelect ? Number(yearSelect.value) : null;
        const tarificationSelect = document.getElementById('genTarificationSelect');
        const tarificationId = tarificationSelect && tarificationSelect.value ? Number(tarificationSelect.value) : null;

        let semester;
        if (typeof semesterOverride === 'number') {
            semester = semesterOverride;
        } else {
            const semesterSelect = document.getElementById('genSemesterSelect');
            const raw = semesterSelect ? semesterSelect.value : null;
            if (raw === 'both') {
                state.bothMode = true;
                state.semesterQueue = [2];
                semester = 1;
            } else {
                state.bothMode = false;
                state.semesterQueue = [];
                semester = raw ? Number(raw) : null;
            }
            state.needsConfirmBanner = false;
        }
        state.currentSemester = semester;

        setBusy(true, 'Считаем расписание...');

        const manualLoad = typeof window.collectManualLoad === 'function' ? window.collectManualLoad() : [];
        const result = await post(API + '/run', {
            academicYear: academicYear,
            tarificationId: tarificationId,
            semester: semester,
            groupIds: [],
            manualLoad: manualLoad,
            persist: false
        });
        setBusy(false);
        handleResult(result);
        refreshDraftButton();
    }

    function isNetworkFailure(result) {
        return result.status === 'FAILED' && (result.missingData || []).some(function (issue) {
            return String(issue.id || '').indexOf('client-') === 0;
        });
    }

    // связь могла оборваться уже после записи пар поэтому сначала спрашиваем сервер что с этим черновиком
    async function recoverCommit(sessionId, failed) {
        const job = await fetchAfterSave();
        if (job && job.sessionId === sessionId) {
            return {
                sessionId: sessionId, status: 'OK', persisted: true, successSchedule: [], missingData: [],
                warnings: ['Связь с сервером прервалась, но расписание сохранено. Список пар виден в расписании.'],
                metrics: {}
            };
        }
        return failed;
    }

    async function fetchAfterSave() {
        try {
            const response = await fetch(API + '/after-save');
            return response.status === 200 ? await response.json() : null;
        } catch (e) {
            return null;
        }
    }

    let afterSaveTimer = null;

    // ход фонового досчёта после сохранения пока не закончится
    function watchAfterSave() {
        if (afterSaveTimer) {
            return;
        }
        const box = document.getElementById('afterSaveStatus');
        const tick = async function () {
            const job = await fetchAfterSave();
            if (!job || !box) {
                afterSaveTimer = null;
                return;
            }
            if (job.status === 'QUEUED' || job.status === 'RUNNING') {
                box.style.display = '';
                box.style.color = 'var(--muted)';
                box.textContent = '⏳ ' + job.title + ': ' + job.step.toLowerCase() + '… ' + job.seconds + ' с';
                afterSaveTimer = setTimeout(tick, 3000);
                return;
            }
            afterSaveTimer = null;
            const ok = job.status === 'DONE';
            box.style.display = '';
            box.style.color = ok ? 'var(--color-success-text)' : 'var(--color-danger-text)';
            box.textContent = (ok ? '✓ Досчёт после сохранения закончен' : '⚠ Досчёт закончился с ошибками')
                + (job.notes.length ? ': ' + job.notes.join('; ') : '')
                + (job.errors.length ? '. ' + job.errors.join('; ') : '');
            if (typeof showToast === 'function') {
                showToast(ok ? 'Досчёт после сохранения закончен' : 'Досчёт после сохранения закончился с ошибками',
                    ok ? 'success' : 'danger');
            }
            if (typeof window.loadSchedule === 'function') {
                window.loadSchedule();
            }
        };
        afterSaveTimer = setTimeout(tick, 1000);
    }

    async function showRunningAfterSave() {
        const job = await fetchAfterSave();
        if (job && (job.status === 'QUEUED' || job.status === 'RUNNING')) {
            watchAfterSave();
        }
    }

    function currentYear() {
        const yearSelect = document.getElementById('yearSelect');
        return yearSelect && yearSelect.value ? Number(yearSelect.value) : null;
    }

    async function fetchDraft() {
        const year = currentYear();
        try {
            const response = await fetch(API + '/draft' + (year ? '?academicYear=' + year : ''));
            return response.status === 200 ? await response.json() : null;
        } catch (e) {
            return null;
        }
    }

    function pairsWord(n) {
        const mod10 = n % 10;
        const mod100 = n % 100;
        if (mod10 === 1 && mod100 !== 11) return 'пара';
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return 'пары';
        return 'пар';
    }

    // кнопка нужна когда черновик скрыли или ушли со страницы а на сервере он ещё лежит
    async function refreshDraftButton() {
        const button = document.getElementById('genDraftBtn');
        if (!button) {
            return;
        }
        const card = document.getElementById('genResultCard');
        const cardOpen = card && card.style.display !== 'none' && state.result && !state.result.persisted;
        const draft = cardOpen || state.committing ? null : await fetchDraft();
        if (!draft) {
            button.style.display = 'none';
            return;
        }
        button.textContent = 'Черновик: ' + draft.semester + ' семестр, ' + draft.pairs + ' ' + pairsWord(draft.pairs);
        const time = draft.updatedAt ? new Date(draft.updatedAt).toLocaleTimeString('ru-RU', {hour: '2-digit', minute: '2-digit'}) : '';
        button.title = 'Несохранённый черновик автосоставления' + (time ? ' от ' + time : '') + ': откройте, проверьте и сохраните';
        button.style.display = '';
    }

    async function openDraft() {
        const draft = await fetchDraft();
        if (!draft || !draft.result) {
            if (typeof showToast === 'function') {
                showToast('Черновика уже нет: его сохранили или он устарел. Запустите автосоставление заново.', 'warning');
            }
            refreshDraftButton();
            return;
        }
        state.bothMode = false;
        state.semesterQueue = [];
        state.needsConfirmBanner = false;
        state.currentSemester = draft.semester;
        state.sessionId = draft.sessionId;
        handleResult(draft.result);
        document.getElementById('genDraftBtn').style.display = 'none';
        const card = document.getElementById('genResultCard');
        if (card) {
            card.scrollIntoView({behavior: 'smooth', block: 'start'});
        }
    }

    function hideResult() {
        const card = document.getElementById('genResultCard');
        if (card) {
            card.style.display = 'none';
        }
        refreshDraftButton();
    }

    function formatImportDate(iso) {
        try {
            const d = new Date(iso);
            return d.toLocaleDateString('ru-RU') + ' ' + d.toLocaleTimeString('ru-RU', {hour: '2-digit', minute: '2-digit'});
        } catch (e) {
            return iso;
        }
    }

    async function loadTarifications() {
        const select = document.getElementById('genTarificationSelect');
        if (!select) {
            return;
        }
        try {
            const res = await fetch('/api/tarifications');
            const tarifications = await res.json();
            select.innerHTML = '';
            if (!tarifications.length) {
                select.innerHTML = '<option value="">Нет загруженных тарификаций</option>';
                return;
            }
            tarifications.forEach(function (t) {
                const option = document.createElement('option');
                option.value = t.id;
                const dateLabel = t.importedAt ? formatImportDate(t.importedAt) : '';
                const countLabel = t.loadCount === 0 ? 'пусто, 0 записей' : t.loadCount + ' записей';
                option.textContent = (t.loadCount === 0 ? '⚠ ' : '') + t.name + ' (' + t.academicYear + '/'
                    + (t.academicYear + 1) + ') — ' + dateLabel + ', ' + countLabel;
                select.appendChild(option);
            });
            const firstNonEmpty = tarifications.find(function (t) { return t.loadCount > 0; });
            if (firstNonEmpty) {
                select.value = firstNonEmpty.id;
            }
        } catch (e) {
            select.innerHTML = '<option value="">Список тарификаций недоступен</option>';
        }
    }

    async function commit() {
        if (!state.sessionId || state.committing) {
            return;
        }
        state.committing = true;
        const commitBtn = document.getElementById('genCommitBtn');
        commitBtn.disabled = true;
        commitBtn.textContent = 'Сохраняем...';
        setBusy(true, 'Сохраняем расписание...');
        const sessionId = state.sessionId;
        let result = await post(API + '/' + encodeURIComponent(sessionId) + '/commit');
        if (!result.persisted && isNetworkFailure(result)) {
            result = await recoverCommit(sessionId, result);
        }
        setBusy(false);
        state.committing = false;
        handleResult(result);
        refreshDraftButton();
        if (result.persisted && typeof showToast === 'function') {
            showToast(result.metrics && result.metrics.alreadySaved
                ? 'Этот черновик уже был сохранён, повторно ничего не записано.'
                : 'Расписание сохранено. Прошедшие пары и часы досчитываются в фоне, ход виден вверху.',
                'success');
        }
        if (result.persisted) {
            watchAfterSave();
        }
        if (result.persisted && typeof window.loadSchedule === 'function') {
            window.loadSchedule();
        }
        if (result.persisted && state.bothMode && state.semesterQueue.length > 0) {
            const next = state.semesterQueue.shift();
            if (typeof showToast === 'function') {
                showToast('Семестр ' + SEMESTER_LABEL[state.currentSemester] + ' сохранён. Считаем семестр '
                    + SEMESTER_LABEL[next] + '...', 'success');
            }
            state.needsConfirmBanner = true;
            generate(next);
        }
    }

    async function sendAnswers() {
        setBusy(true, 'Пересобираем расписание...');
        const result = await post(API + '/' + encodeURIComponent(state.sessionId) + '/resolve', state.answers);
        state.answers = [];
        setBusy(false);
        handleResult(result);
    }

    function handleResult(result) {
        state.result = result;
        if (result.sessionId) {
            state.sessionId = result.sessionId;
        }
        renderResult(result);
        const issues = result.missingData || [];
        const blockingIssues = issues.filter(function (issue) { return issue.severity === 'BLOCKING'; });
        if (blockingIssues.length > 0) {
            state.issues = blockingIssues;
            state.index = 0;
            state.answers = [];
            showIssue();
        } else {
            closeModal();
        }
    }

    function showIssue() {
        const issue = state.issues[state.index];
        if (!issue) {
            closeModal();
            return;
        }
        const overlay = document.getElementById('genIssueModal');
        overlay.classList.add('active');
        overlay.style.display = 'flex';

        document.getElementById('genIssueCounter').textContent =
            'Решение ' + (state.index + 1) + ' из ' + state.issues.length;
        const badge = document.getElementById('genIssueSeverity');
        const blocking = issue.severity === 'BLOCKING';
        badge.textContent = blocking ? 'Нужно решить' : 'Можно пропустить';
        badge.className = 'badge ' + (blocking ? 'badge-danger' : 'badge-warning');

        document.getElementById('genIssueTitle').textContent = issue.title || 'Требуется решение';
        document.getElementById('genIssueMessage').textContent = issue.message || '';

        const optionsWrap = document.getElementById('genIssueOptions');
        optionsWrap.innerHTML = '';
        (issue.options || []).forEach(function (option, idx) {
            optionsWrap.appendChild(buildOption(issue, option, idx));
        });

        const skipBtn = document.getElementById('genIssueSkipBtn');
        skipBtn.style.display = blocking ? 'none' : 'inline-block';
    }

    function buildOption(issue, option, idx) {
        const row = document.createElement('div');
        row.className = 'gen-option';

        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'btn ' + (idx === 0 ? 'btn-primary' : 'btn-secondary');
        button.textContent = option.label;
        button.style.minWidth = '220px';

        if (option.input) {
            const field = buildInput(option.input);
            row.appendChild(field.element);
            button.addEventListener('click', function () {
                const value = field.read();
                if (value === null || value === '') {
                    field.element.focus();
                    return;
                }
                const payload = Object.assign({}, option.payload || {});
                payload[option.input.field] = value;
                answer(issue, option.actionCode, payload);
            });
        } else {
            button.addEventListener('click', function () {
                answer(issue, option.actionCode, option.payload || {});
            });
        }
        row.appendChild(button);
        return row;
    }

    function buildInput(spec) {
        if (spec.type === 'SELECT') {
            const select = document.createElement('select');
            select.className = 'gen-input';
            (spec.choices || []).forEach(function (choice) {
                const opt = document.createElement('option');
                opt.value = choice.value;
                opt.textContent = choice.label;
                select.appendChild(opt);
            });
            return {element: select, read: function () { return select.value; }};
        }
        const input = document.createElement('input');
        input.type = 'number';
        input.className = 'gen-input';
        input.placeholder = spec.label || '';
        if (spec.min !== null && spec.min !== undefined) input.min = spec.min;
        if (spec.max !== null && spec.max !== undefined) input.max = spec.max;
        if (spec.defaultValue !== null && spec.defaultValue !== undefined) input.value = spec.defaultValue;
        return {
            element: input,
            read: function () {
                return input.value === '' ? null : Number(input.value);
            }
        };
    }

    function answer(issue, actionCode, payload) {
        state.answers.push({requestId: issue.id, actionCode: actionCode, payload: payload});
        next();
    }

    function skip() {
        next();
    }

    function next() {
        state.index += 1;
        if (state.index < state.issues.length) {
            showIssue();
            return;
        }
        closeModal();
        if (state.answers.length > 0) {
            sendAnswers();
        }
    }

    function closeModal() {
        const overlay = document.getElementById('genIssueModal');
        if (overlay) {
            overlay.classList.remove('active');
            overlay.style.display = 'none';
        }
    }

    function renderResult(result) {
        const card = document.getElementById('genResultCard');
        if (!card) {
            return;
        }
        card.style.display = 'block';

        const statusText = {
            OK: 'Расписание собрано полностью',
            NEEDS_INPUT: 'Нужны решения администратора',
            PARTIAL: 'Собрано частично',
            FAILED: 'Не выполнено'
        }[result.status] || result.status;

        const metrics = result.metrics || {};
        const semesterLabel = state.currentSemester ? SEMESTER_LABEL[state.currentSemester] : null;
        const progressLabel = state.bothMode
            ? ' (семестр ' + (2 - state.semesterQueue.length) + ' из 2)'
            : '';

        const showConfirmBanner = state.needsConfirmBanner && !result.persisted;
        const banner = showConfirmBanner
            ? '<div style="background:var(--danger-bg,#fdecea); border:1px solid var(--danger,#c0392b); ' +
              'color:var(--danger,#c0392b); border-radius:var(--radius-sm); padding:0.6rem 0.9rem; ' +
              'margin-bottom:0.75rem; font-weight:600;">' +
              '⚠ Это черновик семестра ' + semesterLabel + ' — он ещё НЕ сохранён. ' +
              'Проверьте и нажмите «Сохранить в расписание» ниже ещё раз.</div>'
            : '';

        document.getElementById('genSummary').innerHTML =
            banner +
            (semesterLabel ? '<div style="margin-bottom:0.35rem; font-size:0.85rem; color:var(--muted);">Семестр '
                + semesterLabel + progressLabel + '</div>' : '') +
            '<b>' + statusText + '</b>' + (result.persisted ? ' — сохранено в расписании' : ' — черновик') +
            '<div class="gen-metrics">' +
            metric('Пар расставлено', (result.successSchedule || []).length) +
            metric('Не встало пар', metrics.missingPairs || 0) +
            metric('«Окна» у групп', metrics.groupGaps || 0) +
            metric('«Окна» у преподавателей', metrics.teacherGaps || 0) +
            metric('Одинаковых пар подряд', metrics.adjacentSameSubject || 0) +
            metric('Нежелательных пар у преподавателей', metrics.undesiredTeacherPairs || 0) +
            metric('Макс. нагрузка, ч/нед', metrics.maxTeacherWeeklyHours || 0) +
            '</div>';

        renderWarnings(result.warnings || []);
        renderIssueList(result.missingData || []);
        renderLessons(result.successSchedule || []);

        const commitBtn = document.getElementById('genCommitBtn');
        const blocking = (result.missingData || []).some(function (i) { return i.severity === 'BLOCKING'; });
        commitBtn.disabled = blocking || result.persisted || (result.successSchedule || []).length === 0;
        commitBtn.textContent = result.persisted ? 'Сохранено' : 'Сохранить в расписание';

        if (showConfirmBanner && !blocking) {
            card.scrollIntoView({behavior: 'smooth', block: 'start'});
        }
        if (result.persisted) {
            state.needsConfirmBanner = false;
        }
    }

    function metric(label, value) {
        return '<span class="gen-metric"><span class="gen-metric-value">' + value +
            '</span><span class="gen-metric-label">' + label + '</span></span>';
    }

    function renderWarnings(warnings) {
        const wrap = document.getElementById('genWarnings');
        wrap.innerHTML = '';
        if (!warnings.length) {
            return;
        }
        const box = document.createElement('div');
        box.className = 'gen-warnings';
        const title = document.createElement('div');
        title.className = 'gen-warnings-title';
        title.textContent = 'Предупреждения (' + warnings.length + ')';
        box.appendChild(title);
        const list = document.createElement('ul');
        warnings.forEach(function (text) {
            const li = document.createElement('li');
            li.textContent = text;
            list.appendChild(li);
        });
        box.appendChild(list);
        wrap.appendChild(box);
    }

    function renderIssueList(issues) {
        const wrap = document.getElementById('genIssues');
        wrap.innerHTML = '';
        if (!issues.length) {
            return;
        }
        const box = document.createElement('div');
        box.className = 'gen-issues';
        const title = document.createElement('div');
        title.className = 'gen-issues-title';
        title.textContent = 'Требуют решения (' + issues.length + ')';
        box.appendChild(title);

        issues.forEach(function (issue, idx) {
            const row = document.createElement('button');
            row.type = 'button';
            row.className = 'gen-issue-row';
            row.innerHTML = '<span class="gen-issue-dot ' +
                (issue.severity === 'BLOCKING' ? 'blocking' : 'warning') + '"></span>' +
                '<span>' + escapeHtml(issue.title) + '</span>';
            row.addEventListener('click', function () {
                state.issues = issues;
                state.index = idx;
                showIssue();
            });
            box.appendChild(row);
        });
        wrap.appendChild(box);
    }

    function renderLessons(lessons) {
        const body = document.getElementById('genLessonsBody');
        body.innerHTML = '';
        if (!lessons.length) {
            body.innerHTML = '<tr><td colspan="6" class="text-muted">Пока ничего не расставлено</td></tr>';
            return;
        }
        lessons.slice(0, 400).forEach(function (lesson) {
            const tr = document.createElement('tr');
            tr.innerHTML =
                '<td>' + escapeHtml(lesson.dayName || '') + '</td>' +
                '<td>' + (lesson.pairNumber || '') + ' пара, ' + (lesson.startTime || '') + '</td>' +
                '<td>' + escapeHtml(lesson.disciplineName || '') + '</td>' +
                '<td>' + escapeHtml(lesson.groupName || '') + '</td>' +
                '<td>' + escapeHtml(lesson.teacherName || '') + '</td>' +
                '<td>' + escapeHtml(lesson.classroom || '') + '</td>';
            body.appendChild(tr);
        });
        if (lessons.length > 400) {
            const tr = document.createElement('tr');
            tr.innerHTML = '<td colspan="6" class="text-muted">…и ещё ' + (lessons.length - 400) +
                ' пар — полный список будет виден в расписании после сохранения</td>';
            body.appendChild(tr);
        }
    }

    function setBusy(busy, label) {
        const button = document.getElementById('genRunBtn');
        if (button) {
            button.disabled = busy;
            button.textContent = busy ? (label || 'Считаем...') : 'Автосоставление';
        }
    }

    function escapeHtml(value) {
        return String(value === null || value === undefined ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    async function submitSickLeave() {
        const teacherId = document.getElementById('sickTeacher').value;
        const startDate = document.getElementById('sickStart').value;
        const endDate = document.getElementById('sickEnd').value;
        const output = document.getElementById('sickResult');

        if (!teacherId || !startDate || !endDate) {
            output.innerHTML = '<div class="gen-warnings">Выберите преподавателя и период отсутствия.</div>';
            return;
        }
        output.innerHTML = '<div class="text-muted">Ищем замены и свободные слоты...</div>';
        const result = await post(API + '/sick-leave', {
            teacherId: Number(teacherId),
            startDate: startDate,
            endDate: endDate
        });
        renderSickLeave(result, output);
    }

    function renderSickLeave(result, output) {
        if (result.status === 'FAILED' || (result.missingData && !result.affectedLessons)) {
            const first = (result.missingData || [])[0];
            if (first && !result.affectedLessons) {
                output.innerHTML = '<div class="gen-warnings"><b>' + escapeHtml(first.title) + '</b><br>' +
                    escapeHtml(first.message) + '</div>';
                return;
            }
        }
        let html = '<div class="gen-metrics">' +
            metric('Затронуто пар', result.affectedLessons || 0) +
            metric('Закрыто заменой', (result.substitutions || []).length) +
            metric('Перенесено', (result.moved || []).length) +
            metric('Требует решения', (result.unresolved || []).length) +
            '</div>';

        (result.substitutions || []).forEach(function (s) {
            html += '<div class="gen-line"><b>Замена</b> ' + escapeHtml(s.date) + ' ' + escapeHtml(s.startTime || '') +
                ' — ' + escapeHtml(s.disciplineName) + ', ' + escapeHtml(s.groupName) + ': вместо ' +
                escapeHtml(s.previousTeacherName) + ' проведёт <b>' + escapeHtml(s.substituteTeacherName) +
                '</b>. ' + escapeHtml(s.reason) + (s.overload ? ' (переработка)' : '') + '</div>';
        });
        (result.moved || []).forEach(function (m) {
            html += '<div class="gen-line"><b>Перенос</b> ' + escapeHtml(m.disciplineName) + ', ' +
                escapeHtml(m.groupName) + ': ' + escapeHtml(m.fromDate) + ' ' + escapeHtml(m.fromTime || '') +
                ' → ' + escapeHtml(m.toDate) + ' ' + escapeHtml(m.toTime || '') +
                ', ауд. ' + escapeHtml(m.classroom) + '</div>';
        });
        (result.unresolved || []).forEach(function (u) {
            html += '<div class="gen-line gen-line-danger"><b>Не закрыто</b> ' + escapeHtml(u.date) + ' ' +
                escapeHtml(u.startTime || '') + ' — ' + escapeHtml(u.disciplineName) + ', ' +
                escapeHtml(u.groupName) + ': ' + escapeHtml(u.reason) + '</div>';
        });
        (result.warnings || []).forEach(function (w) {
            html += '<div class="gen-line text-muted">' + escapeHtml(w) + '</div>';
        });
        output.innerHTML = html;

        if ((result.missingData || []).length) {
            const blockingIssues = (result.missingData || []).filter(function (i) { return i.severity === 'BLOCKING'; });
            if (blockingIssues.length > 0) {
                state.issues = blockingIssues;
                state.index = 0;
                state.answers = [];
                showIssue();
            }
        }
    }

    const DAY_LABELS = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт'];
    const DAY_TITLES = ['Понедельник', 'Вторник', 'Среда', 'Четверг', 'Пятница'];
    let availRows = [];

    async function loadAvailability() {
        const body = document.getElementById('genAvailBody');
        if (!body) {
            return;
        }
        const tarificationSelect = document.getElementById('genTarificationSelect');
        const semesterSelect = document.getElementById('genSemesterSelect');
        const params = new URLSearchParams();
        if (tarificationSelect && tarificationSelect.value) params.set('tarificationId', tarificationSelect.value);
        params.set('semester', semesterSelect && semesterSelect.value === '2' ? '2' : '1');
        try {
            const res = await fetch('/api/teacher-availability?' + params.toString());
            if (!res.ok) throw new Error('код ' + res.status);
            availRows = await res.json();
            renderAvailability();
        } catch (e) {
            body.innerHTML = '<tr><td colspan="6" class="text-muted">Не удалось загрузить дни преподавателей ('
                + escapeHtml(e.message) + ')</td></tr>';
        }
    }

    function renderAvailability() {
        const body = document.getElementById('genAvailBody');
        body.innerHTML = '';
        if (!availRows.length) {
            body.innerHTML = '<tr><td colspan="6" class="text-muted">В выбранной тарификации нет преподавателей</td></tr>';
        }
        availRows.forEach(function (row) { body.appendChild(buildAvailRow(row)); });
        const limited = availRows.filter(function (r) { return r.summary; }).length;
        document.getElementById('genAvailCount').textContent = availRows.length
            ? '· ограничения у ' + limited + ' из ' + availRows.length : '';
    }

    function dayState(row, day) {
        if (row.daysOff.indexOf(day) >= 0) return 'off';
        if (row.optionalDays.indexOf(day) >= 0) return 'soft';
        return 'on';
    }

    function pairState(row, pair) {
        if (row.forbiddenPairs.indexOf(pair) >= 0) return 'off';
        if (row.undesiredPairs.indexOf(pair) >= 0) return 'soft';
        return 'on';
    }

    function toggleButton(label, state, title) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'gen-toggle' + (state === 'off' ? ' is-off' : state === 'soft' ? ' is-soft' : '');
        button.textContent = label;
        button.title = title;
        button.setAttribute('aria-label', title);
        return button;
    }

    function buildAvailRow(row) {
        const tr = document.createElement('tr');
        const name = document.createElement('td');
        name.className = 'gen-avail-name';
        name.textContent = row.fullName;
        tr.appendChild(name);

        const daysCell = document.createElement('td');
        const days = document.createElement('div');
        days.className = 'gen-avail-group';
        DAY_LABELS.forEach(function (label, i) {
            const day = i + 1;
            const state = dayState(row, day);
            const stateText = {on: 'работает', off: 'выходной', soft: 'день на выбор'}[state];
            const button = toggleButton(label, state, DAY_TITLES[i] + ': ' + stateText);
            button.addEventListener('click', function () {
                const next = {on: 'off', off: 'soft', soft: 'on'}[dayState(row, day)];
                const draft = copyRow(row);
                draft.daysOff = draft.daysOff.filter(function (d) { return d !== day; });
                draft.optionalDays = draft.optionalDays.filter(function (d) { return d !== day; });
                if (next === 'off') draft.daysOff.push(day);
                if (next === 'soft') draft.optionalDays.push(day);
                saveAvailability(row, draft, tr);
            });
            days.appendChild(button);
        });
        daysCell.appendChild(days);
        tr.appendChild(daysCell);

        const maxCell = document.createElement('td');
        const select = document.createElement('select');
        select.className = 'gen-avail-days';
        select.setAttribute('aria-label', 'Дней в неделю: ' + row.fullName);
        select.add(new Option('без лимита', ''));
        for (let n = 1; n <= 4; n++) select.add(new Option(String(n), String(n)));
        select.value = row.maxDaysPerWeek ? String(row.maxDaysPerWeek) : '';
        select.addEventListener('change', function () {
            const draft = copyRow(row);
            draft.maxDaysPerWeek = select.value ? Number(select.value) : null;
            saveAvailability(row, draft, tr);
        });
        maxCell.appendChild(select);
        tr.appendChild(maxCell);

        const pairsCell = document.createElement('td');
        const pairs = document.createElement('div');
        pairs.className = 'gen-avail-group';
        for (let pair = 1; pair <= 5; pair++) {
            const state = pairState(row, pair);
            const stateText = {on: 'можно', off: 'нельзя', soft: 'нежелательно'}[state];
            const button = toggleButton(String(pair), state, pair + '-я пара: ' + stateText);
            button.addEventListener('click', function () {
                const next = {on: 'off', off: 'soft', soft: 'on'}[pairState(row, pair)];
                const draft = copyRow(row);
                draft.forbiddenPairs = draft.forbiddenPairs.filter(function (p) { return p !== pair; });
                draft.undesiredPairs = draft.undesiredPairs.filter(function (p) { return p !== pair; });
                if (next === 'off') draft.forbiddenPairs.push(pair);
                if (next === 'soft') draft.undesiredPairs.push(pair);
                saveAvailability(row, draft, tr);
            });
            pairs.appendChild(button);
        }
        pairsCell.appendChild(pairs);
        tr.appendChild(pairsCell);

        const capCell = document.createElement('td');
        const need = row.weeklyPairs;
        capCell.className = 'gen-avail-cap' + (need != null && need > row.slotsPerWeek ? ' is-over' : '');
        capCell.textContent = (need == null ? '—' : '≈' + need) + ' из ' + row.slotsPerWeek;
        capCell.title = 'Нужно пар в неделю по плану семестра из доступных преподавателю слотов';
        tr.appendChild(capCell);

        const summaryCell = document.createElement('td');
        summaryCell.className = 'gen-avail-summary';
        let summary = row.summary || 'без ограничений';
        if (row.optionalDays.length && !row.maxDaysPerWeek) {
            summary += (row.summary ? '; ' : '. ') + 'дни «на выбор» работают только вместе с «дней в неделю»';
        }
        summaryCell.textContent = summary;
        tr.appendChild(summaryCell);
        return tr;
    }

    function copyRow(row) {
        return {
            daysOff: row.daysOff.slice(),
            optionalDays: row.optionalDays.slice(),
            maxDaysPerWeek: row.maxDaysPerWeek,
            forbiddenPairs: row.forbiddenPairs.slice(),
            undesiredPairs: row.undesiredPairs.slice()
        };
    }

    async function saveAvailability(row, draft, tr) {
        tr.querySelectorAll('button, select').forEach(function (el) { el.disabled = true; });
        try {
            const res = await fetch('/api/teacher-availability/' + encodeURIComponent(row.teacherId), {
                method: 'PUT',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(draft)
            });
            const data = await res.json().catch(function () { return null; });
            if (!res.ok || !data) {
                throw new Error(data && data.error ? data.error : 'код ' + res.status);
            }
            data.weeklyPairs = row.weeklyPairs;
            Object.assign(row, data);
        } catch (e) {
            if (typeof showToast === 'function') {
                showToast('Не сохранено: ' + e.message, 'danger');
            }
        }
        tr.replaceWith(buildAvailRow(row));
        const limited = availRows.filter(function (r) { return r.summary; }).length;
        document.getElementById('genAvailCount').textContent = '· ограничения у ' + limited + ' из ' + availRows.length;
    }

    function rememberAvailOpen() {
        const box = document.getElementById('genAvailBox');
        if (!box) return;
        try {
            const saved = localStorage.getItem('genAvailOpen');
            if (saved !== null) box.open = saved === '1';
        } catch (e) {
        }
        box.addEventListener('toggle', function () {
            try {
                localStorage.setItem('genAvailOpen', box.open ? '1' : '0');
            } catch (e) {
            }
        });
    }

    async function loadTeachers() {
        const select = document.getElementById('sickTeacher');
        if (!select) {
            return;
        }
        try {
            const response = await fetch('/api/teachers');
            const teachers = await response.json();
            (teachers || []).forEach(function (teacher) {
                const option = document.createElement('option');
                option.value = teacher.id;
                option.textContent = teacher.fullName;
                select.appendChild(option);
            });
        } catch (e) {
            select.innerHTML = '<option value="">Список преподавателей недоступен</option>';
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        bind('genRunBtn', function () { generate(); });
        bind('genCommitBtn', commit);
        bind('genIssueSkipBtn', skip);
        bind('genIssueCloseBtn', closeModal);
        bind('genHideBtn', hideResult);
        bind('genDraftBtn', openDraft);
        bind('sickSubmitBtn', submitSickLeave);
        refreshDraftButton();
        showRunningAfterSave();
        const yearSelect = document.getElementById('yearSelect');
        if (yearSelect) yearSelect.addEventListener('change', refreshDraftButton);
        loadTeachers();
        rememberAvailOpen();
        loadTarifications().then(loadAvailability);
        ['genTarificationSelect', 'genSemesterSelect'].forEach(function (id) {
            const element = document.getElementById(id);
            if (element) element.addEventListener('change', loadAvailability);
        });
    });

    function bind(id, handler) {
        const element = document.getElementById(id);
        if (element) {
            element.addEventListener('click', handler);
        }
    }

    window.scheduleGeneration = {
        run: generate,
        commit: commit,
        sickLeave: submitSickLeave,
        watchAfterSave: watchAfterSave
    };
})();
