/**
 * ИНТЕРАКТИВНОЕ АВТОСОСТАВЛЕНИЕ РАСПИСАНИЯ.
 *
 * Сервер никогда не отвечает пустой ошибкой: всё, чего не хватает или что противоречит
 * друг другу, приходит в missingData — списком вопросов с готовыми вариантами действий.
 * Этот файл показывает вопросы по одному в модальном окне, собирает ответы, отправляет
 * их пачкой на /resolve и повторяет цикл, пока не останется блокирующих вопросов.
 *
 * Подключение (см. INTEGRATION.md):
 *   <div th:replace="~{fragments/generation-modal :: modal}"></div>
 *   <script src="/js/schedule-generation.js"></script>
 */
(function () {
    'use strict';

    const state = {
        sessionId: null,
        issues: [],
        index: 0,
        answers: [],
        result: null
    };

    const API = '/api/schedule-generation';

    // ---------------------------------------------------------------- сеть

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

    // ---------------------------------------------------------------- запуск

    async function generate() {
        const yearSelect = document.getElementById('yearSelect');
        const academicYear = yearSelect ? Number(yearSelect.value) : null;
        setBusy(true, 'Считаем расписание...');

        const manualLoad = typeof window.collectManualLoad === 'function' ? window.collectManualLoad() : [];
        const result = await post(API + '/run', {
            academicYear: academicYear,
            groupIds: [],
            manualLoad: manualLoad,
            persist: false
        });
        setBusy(false);
        handleResult(result);
    }

    async function commit() {
        if (!state.sessionId) {
            return;
        }
        setBusy(true, 'Сохраняем расписание...');
        const result = await post(API + '/' + encodeURIComponent(state.sessionId) + '/commit');
        setBusy(false);
        handleResult(result);
        if (result.persisted && typeof window.loadSchedule === 'function') {
            window.loadSchedule();
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
        if (issues.length > 0) {
            state.issues = issues;
            state.index = 0;
            state.answers = [];
            showIssue();
        } else {
            closeModal();
        }
    }

    // ---------------------------------------------------------------- модальное окно с вопросами

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
        button.className = 'btn ' + (idx === 0 ? '' : 'btn-outline');
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

    // ---------------------------------------------------------------- отрисовка результата

    function renderResult(result) {
        const card = document.getElementById('genResultCard');
        if (!card) {
            return;
        }
        card.style.display = 'block';

        const statusText = {
            OK: '✅ Расписание собрано полностью',
            NEEDS_INPUT: '❓ Нужны решения администратора',
            PARTIAL: '⚠️ Собрано частично',
            FAILED: '⛔ Не выполнено'
        }[result.status] || result.status;

        const metrics = result.metrics || {};
        document.getElementById('genSummary').innerHTML =
            '<b>' + statusText + '</b>' + (result.persisted ? ' — сохранено в расписании' : ' — черновик') +
            '<div class="gen-metrics">' +
            metric('Пар расставлено', (result.successSchedule || []).length) +
            metric('Не встало пар', metrics.missingPairs || 0) +
            metric('«Окна» у групп', metrics.groupGaps || 0) +
            metric('«Окна» у преподавателей', metrics.teacherGaps || 0) +
            metric('Одинаковых пар подряд', metrics.adjacentSameSubject || 0) +
            metric('Макс. нагрузка, ч/нед', metrics.maxTeacherWeeklyHours || 0) +
            '</div>';

        renderWarnings(result.warnings || []);
        renderIssueList(result.missingData || []);
        renderLessons(result.successSchedule || []);

        const commitBtn = document.getElementById('genCommitBtn');
        const blocking = (result.missingData || []).some(function (i) { return i.severity === 'BLOCKING'; });
        commitBtn.disabled = blocking || result.persisted || (result.successSchedule || []).length === 0;
        commitBtn.textContent = result.persisted ? 'Сохранено' : 'Сохранить в расписание';
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
            button.textContent = busy ? (label || 'Считаем...') : '🧮 Автосоставление';
        }
    }

    function escapeHtml(value) {
        return String(value === null || value === undefined ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    // ---------------------------------------------------------------- больничный

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
            state.issues = result.missingData;
            state.index = 0;
            state.answers = [];
            showIssue();
        }
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

    // ---------------------------------------------------------------- инициализация

    document.addEventListener('DOMContentLoaded', function () {
        bind('genRunBtn', generate);
        bind('genCommitBtn', commit);
        bind('genIssueSkipBtn', skip);
        bind('genIssueCloseBtn', closeModal);
        bind('sickSubmitBtn', submitSickLeave);
        loadTeachers();
    });

    function bind(id, handler) {
        const element = document.getElementById(id);
        if (element) {
            element.addEventListener('click', handler);
        }
    }

    // Наружу — чтобы кнопки в разметке могли вызывать напрямую.
    window.scheduleGeneration = {
        run: generate,
        commit: commit,
        sickLeave: submitSickLeave
    };
})();
