package de.wagenknecht.backloggd;


public final class ApiConstants {


    private ApiConstants() {}


    public static final String BACKLOGGD_URL = "https://backloggd.com";
    public static final String NOTIFICATION_URL = BACKLOGGD_URL + "/notifications";
    public static final String SETTINGS_URL = BACKLOGGD_URL + "/settings";
    public static final String LOGIN_URL = BACKLOGGD_URL + "/login";
    public static final String LOGOUT_URL = BACKLOGGD_URL + "/users/sign_out";
    public static final String GITHUB_RELEASES_LATEST = "https://github.com/wagenknecht/Backloggd-Android-App/releases/latest";
    public static final String GITHUB_LATEST_RELEASE_API_URL = "https://api.github.com/repos/wagenknecht/Backloggd-Android-App/releases/latest";
    /** Append a tag name to look up that specific release. */
    public static final String GITHUB_RELEASE_BY_TAG_API_URL = "https://api.github.com/repos/wagenknecht/Backloggd-Android-App/releases/tags/";
    /** Lists the changelog files of a given tag; append the tag name. */
    public static final String GITHUB_CHANGELOGS_API_URL = "https://api.github.com/repos/wagenknecht/Backloggd-Android-App/contents/fastlane/metadata/android/en-US/changelogs?ref=";
    public static final String GITHUB_REPO_URL = "https://github.com/wagenknecht/Backloggd-Android-App";


}

