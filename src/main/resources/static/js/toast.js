function showToast(message, type, title) {
    var stack = document.getElementById('toast-stack');
    if (!stack) {
        stack = document.createElement('div');
        stack.id = 'toast-stack';
        stack.className = 'toast-stack';
        document.body.appendChild(stack);
    }
    var toast = document.createElement('div');
    toast.className = 'toast' + (type ? ' toast-' + type : '');
    var titleHtml = title ? '<div class="toast-title">' + title + '</div>' : '';
    toast.innerHTML = titleHtml + '<div>' + message + '</div>';
    stack.appendChild(toast);
    setTimeout(function () {
        toast.style.transition = 'opacity .2s';
        toast.style.opacity = '0';
        setTimeout(function () { toast.remove(); }, 200);
    }, 4000);
}
