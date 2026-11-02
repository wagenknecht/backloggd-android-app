// The username on the settings page, as saved: the value attribute, not what may have been typed
// into the field since. Null on any other page.
(function () {
    var field = document.querySelector('input#user_username');
    return field ? field.getAttribute('value') : null;
})();
