// Tells the app whenever one of the site's dialogs opens or closes, so pull-to-refresh can stay
// off meanwhile. Bootstrap marks an open one with the modal-open class on the body.
(function () {
    if (window.__appWatchesDialogs || !window.BackloggdApp || !document.body) {
        return;
    }
    window.__appWatchesDialogs = true;
    var report = function () {
        BackloggdApp.setDialogOpen(document.body.classList.contains('modal-open'));
    };
    new MutationObserver(report).observe(document.body, {attributes: true, attributeFilter: ['class']});
    report();
})();
