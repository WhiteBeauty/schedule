(function () {
    function textOf(cell) {
        return (cell.getAttribute('data-sort-value') || cell.textContent || '').trim();
    }

    function compare(a, b) {
        var na = parseFloat(a.replace(',', '.'));
        var nb = parseFloat(b.replace(',', '.'));
        if (!isNaN(na) && !isNaN(nb)) return na - nb;
        return a.localeCompare(b, 'ru');
    }

    function sortTable(table, columnIndex, ascending) {
        var tbody = table.tBodies[0];
        var rows = Array.prototype.slice.call(tbody.rows);
        rows.sort(function (r1, r2) {
            var v1 = textOf(r1.cells[columnIndex]);
            var v2 = textOf(r2.cells[columnIndex]);
            var result = compare(v1, v2);
            return ascending ? result : -result;
        });
        rows.forEach(function (row) { tbody.appendChild(row); });
    }

    function attachTableSort(table) {
        var headers = table.tHead ? Array.prototype.slice.call(table.tHead.rows[0].cells) : [];
        headers.forEach(function (th, index) {
            if (!th.classList.contains('sortable')) return;
            th.addEventListener('click', function () {
                var ascending = !th.classList.contains('sort-asc');
                headers.forEach(function (h) { h.classList.remove('sort-asc', 'sort-desc'); });
                th.classList.add(ascending ? 'sort-asc' : 'sort-desc');
                sortTable(table, index, ascending);
            });
        });
    }

    document.querySelectorAll('table[data-sortable]').forEach(attachTableSort);
    window.attachTableSort = attachTableSort;
})();
