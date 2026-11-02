// Keeps the app informed about the page. Backloggd navigates with Turbo Drive: a tapped link or a
// sent form swaps in a new body without loading a new page, so the WebView never reports it.
// Turbo fires turbo:load after every such visit instead.
(function () {
    if (window.__appWatchesPage || !window.BackloggdApp) {
        return;
    }
    window.__appWatchesPage = true;

    // Pull-to-refresh stays off while one of the site's dialogs is open. Bootstrap marks an open
    // one with the modal-open class on the body.
    var report = function () {
        BackloggdApp.setDialogOpen(!!document.body && document.body.classList.contains('modal-open'));
    };
    var observer = new MutationObserver(report);
    var watchBody = function () {
        observer.disconnect();
        if (document.body) {
            observer.observe(document.body, {attributes: true, attributeFilter: ['class']});
        }
        report();
    };

    document.addEventListener('turbo:load', function () {
        watchBody();
        BackloggdApp.pageShown();
    });
    watchBody();
})();
