// Shows the site's own search field out of the hidden navbar and puts the cursor in it. The
// navbar hides again once the field loses focus.
(function () {
    document.body.classList.add('app-show-search');
    var content = document.getElementById('navbarSupportedContent');
    if (content) {
        content.classList.add('show');
        content.classList.remove('collapse');
    }
    // Give the navbar a moment to lay out before scrolling to the field.
    setTimeout(function () {
        var selectors = ['#nav-bar-search', 'input.search-bar', 'input[name="query"]', 'input[type="search"]'];
        for (var i = 0; i < selectors.length; i++) {
            var field = document.querySelector(selectors[i]);
            if (field) {
                field.scrollIntoView({block: 'center'});
                field.focus();
                field.click();
                field.addEventListener('blur', function () {
                    setTimeout(function () {
                        document.body.classList.remove('app-show-search');
                    }, 200);
                }, {once: true});
                return;
            }
        }
    }, 50);
})();
