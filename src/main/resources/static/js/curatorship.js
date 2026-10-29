(function () {
    var EVENT_TYPES = ['Классный час', 'Родительское собрание', 'Индивидуальная беседа', 'Внеучебное мероприятие',
        'Профилактика', 'Другое'];
    var MONTHS = [9, 10, 11, 12, 1, 2, 3, 4, 5, 6];
    var MONTH_NAMES = {9: 'Сентябрь', 10: 'Октябрь', 11: 'Ноябрь', 12: 'Декабрь', 1: 'Январь', 2: 'Февраль',
        3: 'Март', 4: 'Апрель', 5: 'Май', 6: 'Июнь'};

    var state = {year: new Date().getFullYear(), current: null, editIdx: null, logIdx: null, onChange: null};

    function $(id) { return document.getElementById(id); }
    function esc(s) { var d = document.createElement('div'); d.textContent = s == null ? '' : s; return d.innerHTML; }
    function fmt(iso) { if (!iso) return '—'; var p = String(iso).split('-'); return p.length === 3 ? p[2] + '.' + p[1] + '.' + p[0] : iso; }
    function round1(x) { return Math.round(x * 10) / 10; }
    function pad(n) { return n < 10 ? '0' + n : '' + n; }
    function todayIso() { var d = new Date(); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
    function inYear(iso) { return iso && iso >= state.year + '-09-01' && iso <= (state.year + 1) + '-08-31'; }
    function toast(msg, kind) { if (typeof showToast === 'function') showToast(msg, kind); else alert(msg); }

    function request(method, url, body) {
        return fetch(url, {
            method: method,
            headers: body ? {'Content-Type': 'application/json'} : {},
            body: body ? JSON.stringify(body) : undefined
        }).then(function (r) {
            return r.text().then(function (t) {
                var data = null;
                try { data = t ? JSON.parse(t) : null; } catch (e) { data = null; }
                if (!r.ok) throw new Error((data && data.error) || ('Ошибка ' + r.status));
                return data;
            });
        });
    }

    function yearEvents() {
        var c = state.current;
        return (c.events || []).map(function (e, idx) { return {e: e, idx: idx}; })
            .filter(function (x) { return inYear(x.e.date); })
            .sort(function (a, b) { return String(b.e.date).localeCompare(String(a.e.date)); });
    }

    function render() {
        var c = state.current;
        if (!c) return;
        $('curCard').style.display = '';
        $('curGroupName').textContent = 'Группа ' + c.group.name;
        var chips = [];
        if (c.group.course) chips.push(c.group.course + ' курс');
        if (c.group.specialty) chips.push(c.group.specialty);
        if (c.group.studentCount) chips.push(c.group.studentCount + ' студентов');
        $('curChips').innerHTML = chips.map(function (x) { return '<span class="chip chip-neutral">' + esc(x) + '</span>'; }).join('');
        $('curTeacherName').textContent = c.teacher ? 'Куратор: ' + c.teacher.fullName : '';

        var events = yearEvents();
        var plan = c.hours != null ? Number(c.hours) : 0;
        var done = events.reduce(function (s, x) { return s + (x.e.hours != null ? Number(x.e.hours) : 0); }, 0);
        var pct = plan > 0 ? Math.min(100, done * 100 / plan) : 0;
        $('curPlan').textContent = plan;
        $('curDone').textContent = round1(done);
        $('curLeft').textContent = round1(Math.max(0, plan - done));
        $('curEventsCount').textContent = events.length;
        $('curDoneBar').style.width = pct + '%';

        renderMonths(events, plan);
        renderEvents(events);
        renderLogs();
    }

    function renderMonths(events, plan) {
        var perMonth = plan > 0 ? Math.round(plan / MONTHS.length * 2) / 2 : 0;
        var fact = {};
        events.forEach(function (x) {
            var m = parseInt(String(x.e.date).split('-')[1], 10);
            fact[m] = (fact[m] || 0) + (x.e.hours != null ? Number(x.e.hours) : 0);
        });
        var now = new Date();
        var html = '<div class="cur-months">';
        MONTHS.forEach(function (m) {
            var f = round1(fact[m] || 0);
            var calYear = m >= 9 ? state.year : state.year + 1;
            var passed = new Date(calYear, m, 0) < now;
            var current = calYear === now.getFullYear() && m === now.getMonth() + 1;
            var cls = f >= perMonth && perMonth > 0 ? 'ok' : (f > 0 ? 'part' : (passed ? 'miss' : ''));
            if (current) cls += ' now';
            var width = perMonth > 0 ? Math.min(100, f * 100 / perMonth) : (f > 0 ? 100 : 0);
            html += '<div class="cur-month ' + cls + '"' + (current ? ' aria-current="date"' : '') + '>'
                + '<span class="cur-month-name">' + MONTH_NAMES[m] + (current ? ' <span class="cur-month-now">сейчас</span>' : '') + '</span>'
                + '<span class="cur-month-bar"><span style="width:' + width + '%"></span></span>'
                + '<span class="cur-month-val">' + f + ' / ' + perMonth + '</span></div>';
        });
        html += '</div>';
        $('curMonths').innerHTML = html;
    }

    function renderEvents(events) {
        var filter = $('curTypeFilter').value;
        var rows = events.filter(function (x) { return !filter || (x.e.type || 'Другое') === filter; });
        var body = $('curEventsBody');
        if (!rows.length) {
            body.innerHTML = '<tr><td colspan="5" class="text-muted" style="text-align:center; padding:18px;">'
                + (events.length ? 'Нет мероприятий этого типа' : 'За этот учебный год мероприятий пока нет') + '</td></tr>';
            return;
        }
        body.innerHTML = rows.map(function (x) {
            return '<tr><td>' + fmt(x.e.date) + '</td><td><span class="chip chip-neutral">' + esc(x.e.type || 'Другое') + '</span></td>'
                + '<td>' + esc(x.e.description) + '</td><td>' + (x.e.hours != null ? x.e.hours : 0) + '</td>'
                + '<td class="cur-actions"><button type="button" class="btn btn-sm btn-secondary" data-edit="' + x.idx + '">Изменить</button>'
                + '<button type="button" class="btn btn-sm btn-ghost" data-del="' + x.idx + '" aria-label="Удалить">×</button></td></tr>';
        }).join('');
        body.querySelectorAll('[data-edit]').forEach(function (b) {
            b.addEventListener('click', function () { openEvent(Number(b.dataset.edit)); });
        });
        body.querySelectorAll('[data-del]').forEach(function (b) {
            b.addEventListener('click', function () { deleteEvent(Number(b.dataset.del)); });
        });
    }

    function parseLog(raw) {
        var m = /^(\d{4}-\d{2}-\d{2})\|([\s\S]*)$/.exec(raw || '');
        return m ? {date: m[1], text: m[2]} : {date: null, text: raw || ''};
    }

    function renderLogs() {
        var logs = (state.current.logs || []).map(function (raw, idx) { return {idx: idx, entry: parseLog(raw)}; });
        logs.reverse();
        var box = $('curLogs');
        if (!logs.length) { box.innerHTML = '<p class="text-muted" style="margin:6px 0 0;">Записей пока нет</p>'; return; }
        box.innerHTML = logs.map(function (x) {
            return '<div class="cur-log"><span class="cur-log-date">' + (x.entry.date ? fmt(x.entry.date) : '') + '</span>'
                + '<span class="cur-log-text">' + esc(x.entry.text) + '</span>'
                + '<span class="cur-actions"><button type="button" class="btn btn-sm btn-secondary" data-log-edit="' + x.idx + '">Изменить</button>'
                + '<button type="button" class="btn btn-sm btn-ghost" data-log-del="' + x.idx + '" aria-label="Удалить">×</button></span></div>';
        }).join('');
        box.querySelectorAll('[data-log-edit]').forEach(function (b) {
            b.addEventListener('click', function () { openLog(Number(b.dataset.logEdit)); });
        });
        box.querySelectorAll('[data-log-del]').forEach(function (b) {
            b.addEventListener('click', function () { deleteLog(Number(b.dataset.logDel)); });
        });
    }

    function loadGroupInfo() {
        var box = $('curUpcoming');
        box.innerHTML = '<p class="text-muted">Загрузка...</p>';
        request('GET', '/api/curatorships/' + state.current.id + '/group-info').then(function (info) {
            var html = '';
            (info.upcomingEvents || []).forEach(function (e) {
                var kind = e.type === 'DRIVING' ? 'driving' : (e.type === 'EXAM' ? 'exam' : 'practice');
                html += '<div class="cur-upcoming ' + kind + '"><span><b>' + esc(e.typeLabel) + '</b>'
                    + (e.disciplineName ? ' · ' + esc(e.disciplineName) : '') + '</span><span>' + fmt(e.startDate)
                    + (e.endDate !== e.startDate ? ' по ' + fmt(e.endDate) : '') + '</span></div>';
            });
            var today = info.todayLessons || [];
            html += '<div class="cur-today"><div class="cur-sub">Сегодня у группы ' + (today.length ? today.length + ' пар' : 'пар нет') + '</div>';
            today.forEach(function (l) {
                html += '<div class="cur-today-row">' + (l.pairNumber || '') + ' · ' + esc(l.discipline) + ' · ' + esc(l.teacher)
                    + (l.substituted ? ' <span class="substitute-badge">замена</span>' : '')
                    + (l.classroom ? ' · ауд. ' + esc(l.classroom) : '') + '</div>';
            });
            html += '</div>';
            if (info.upcomingChanges) {
                html += '<p class="text-muted" style="margin:8px 0 0; font-size:12.5px;">Замен и переносов у группы в ближайшие три недели: '
                    + info.upcomingChanges + '</p>';
            }
            if (!(info.upcomingEvents || []).length) {
                html = '<p class="text-muted" style="margin:0 0 8px; font-size:12.5px;">Экзаменов и практик в ближайшие три недели нет</p>' + html;
            }
            box.innerHTML = html;
        }).catch(function () { box.innerHTML = '<p class="text-muted">Не удалось загрузить</p>'; });
    }

    function openModal(id) { $(id).classList.add('active'); }
    function closeModal(id) { $(id).classList.remove('active'); }

    function openEvent(idx) {
        state.editIdx = idx == null ? null : idx;
        var e = idx == null ? null : state.current.events[idx];
        $('curEventModalTitle').textContent = e ? 'Изменить мероприятие' : 'Новое мероприятие';
        $('curEventDate').value = e ? e.date : todayIso();
        $('curEventHours').value = e && e.hours != null ? e.hours : 1;
        $('curEventType').value = e && e.type ? e.type : EVENT_TYPES[0];
        $('curEventDescription').value = e ? e.description || '' : '';
        openModal('curEventModal');
    }

    function saveEvent() {
        var payload = {
            date: $('curEventDate').value,
            hours: $('curEventHours').value,
            type: $('curEventType').value,
            description: $('curEventDescription').value.trim()
        };
        if (!payload.description) { toast('Опишите что проведено', 'danger'); return; }
        if (!payload.date) { toast('Укажите дату', 'danger'); return; }
        var method = 'POST';
        if (state.editIdx != null) { method = 'PUT'; payload.eventIdx = state.editIdx; }
        request(method, '/api/curatorships/' + state.current.id + '/event', payload).then(function (c) {
            closeModal('curEventModal');
            updated(c);
            toast('Сохранено', 'success');
        }).catch(function (e) { toast(e.message, 'danger'); });
    }

    function deleteEvent(idx) {
        if (!confirm('Удалить мероприятие?')) return;
        request('DELETE', '/api/curatorships/' + state.current.id + '/event/' + idx)
            .then(updated).catch(function (e) { toast(e.message, 'danger'); });
    }

    function addLog() {
        var text = $('curLogInput').value.trim();
        if (!text) return;
        request('POST', '/api/curatorships/' + state.current.id + '/log', {logEntry: text}).then(function (c) {
            $('curLogInput').value = '';
            updated(c);
        }).catch(function (e) { toast(e.message, 'danger'); });
    }

    function openLog(idx) {
        state.logIdx = idx;
        $('curLogEdit').value = parseLog(state.current.logs[idx]).text;
        openModal('curLogModal');
    }

    function saveLog() {
        var text = $('curLogEdit').value.trim();
        if (!text) { toast('Пустая запись', 'danger'); return; }
        request('PUT', '/api/curatorships/' + state.current.id + '/log', {logIdx: state.logIdx, logEntry: text})
            .then(function (c) { closeModal('curLogModal'); updated(c); })
            .catch(function (e) { toast(e.message, 'danger'); });
    }

    function deleteLog(idx) {
        if (!confirm('Удалить запись из журнала?')) return;
        request('DELETE', '/api/curatorships/' + state.current.id + '/log/' + idx)
            .then(updated).catch(function (e) { toast(e.message, 'danger'); });
    }

    function updated(c) {
        if (c) state.current = c;
        render();
        if (typeof state.onChange === 'function') state.onChange(state.current);
    }

    window.CuratorCard = {
        init: function (opts) {
            state.year = opts && opts.year ? Number(opts.year) : state.year;
            state.onChange = opts ? opts.onChange : null;
            var typeSel = $('curEventType');
            var filterSel = $('curTypeFilter');
            EVENT_TYPES.forEach(function (t) {
                typeSel.add(new Option(t, t));
                filterSel.add(new Option(t, t));
            });
            filterSel.addEventListener('change', function () { if (state.current) renderEvents(yearEvents()); });
            $('curAddEventBtn').addEventListener('click', function () { openEvent(null); });
            $('curEventSaveBtn').addEventListener('click', saveEvent);
            $('curLogAddBtn').addEventListener('click', addLog);
            $('curLogInput').addEventListener('keydown', function (e) { if (e.key === 'Enter') addLog(); });
            $('curLogSaveBtn').addEventListener('click', saveLog);
            document.querySelectorAll('[data-close]').forEach(function (b) {
                b.addEventListener('click', function () { closeModal(b.dataset.close); });
            });
        },
        setYear: function (year) {
            state.year = Number(year);
            render();
        },
        show: function (curatorship) {
            state.current = curatorship;
            render();
            loadGroupInfo();
        },
        open: function (id) {
            return request('GET', '/api/curatorships/by-id/' + id).then(function (c) { window.CuratorCard.show(c); return c; });
        },
        doneHours: function (c, year) {
            var from = year + '-09-01', to = (year + 1) + '-08-31';
            return (c.events || []).filter(function (e) { return e.date && e.date >= from && e.date <= to; })
                .reduce(function (s, e) { return s + (e.hours != null ? Number(e.hours) : 0); }, 0);
        }
    };
})();
