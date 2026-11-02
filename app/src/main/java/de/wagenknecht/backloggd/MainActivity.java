package de.wagenknecht.backloggd;

import static de.wagenknecht.backloggd.ApiConstants.GITHUB_RELEASES_LATEST;
import static de.wagenknecht.backloggd.ApiConstants.GITHUB_REPO_URL;
import static de.wagenknecht.backloggd.ApiConstants.BACKLOGGD_URL;
import static de.wagenknecht.backloggd.ApiConstants.LOGIN_URL;
import static de.wagenknecht.backloggd.ApiConstants.LOGOUT_URL;
import static de.wagenknecht.backloggd.ApiConstants.NOTIFICATION_URL;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import android.view.inputmethod.InputMethodManager;

import java.util.HashMap;
import java.util.Map;

import de.wagenknecht.backloggd.util.BackloggdUrls;
import de.wagenknecht.backloggd.util.PageScripts;
import de.wagenknecht.backloggd.util.SystemBars;
import de.wagenknecht.backloggd.util.UpdateChecker;
import de.wagenknecht.backloggd.util.UsernameHelper;
import de.wagenknecht.backloggd.worker.NotificationCheckWorker;
import de.wagenknecht.backloggd.worker.WishlistCheckerWorker;

public class MainActivity extends AppCompatActivity {
    private WebView myWeb;
    private LinearLayout errorLayout;
    private BottomNavigationView bottomNav;
    private DrawerLayout drawerLayout;
    private NavigationView drawerNav;
    private LinearProgressIndicator pageProgress;
    private SwipeRefreshLayout swipeRefresh;
    private boolean receivedError = false;
    private OnBackPressedCallback backCallback;
    /** Held while the system file picker is open for an {@code <input type="file">}. */
    @Nullable
    private ValueCallback<Uri[]> pendingFileCallback;
    /** A shortcut action that has to wait until the first page is there to act on. */
    @Nullable
    private String pendingPostLaunchAction;
    private boolean firstPageShown = false;
    /** Reported by the page through {@link PageBridge}, read by pull-to-refresh. */
    private volatile boolean pageHasDialogOpen = false;
    private static final String TAG = "MainActivity";
    /** The splash screen never waits longer than this for the first page. */
    private static final long SPLASH_MAX_MS = 2000;
    private static final String STATE_URL = "current_url";

    /** One entry of the About dialog. */
    private static final class AboutLink {
        @StringRes
        final int label;
        final String url;

        AboutLink(@StringRes int label, String url) {
            this.label = label;
            this.url = url;
        }
    }

    /** The links of the site's hidden footer, plus this app's own page. */
    private static final AboutLink[] ABOUT_LINKS = {
            new AboutLink(R.string.about_this_app, GITHUB_REPO_URL),
            new AboutLink(R.string.about_backloggd, BACKLOGGD_URL + "/about/"),
            new AboutLink(R.string.about_contact, BACKLOGGD_URL + "/contact/"),
            new AboutLink(R.string.about_backers, BACKLOGGD_URL + "/backers/"),
            new AboutLink(R.string.about_roadmap, BACKLOGGD_URL + "/roadmap/"),
            new AboutLink(R.string.about_terms, BACKLOGGD_URL + "/about/terms-of-service/"),
            new AboutLink(R.string.about_privacy, BACKLOGGD_URL + "/about/privacy/"),
            new AboutLink(R.string.about_changelog, BACKLOGGD_URL + "/changelog/"),
            new AboutLink(R.string.about_igdb, "https://igdb.com/"),
    };

    /** What the app runs on Backloggd pages, from assets/web/. */
    private PageScripts scripts;

    /**
     * What the page may tell the app. Every frame sees it, ads included, so it only ever takes
     * harmless signals, never data the app would act on.
     */
    private final class PageBridge {
        @JavascriptInterface
        public void setDialogOpen(boolean open) {
            pageHasDialogOpen = open;
        }

        /** A Turbo visit showed a new page; the app looks at it itself. */
        @JavascriptInterface
        public void pageShown() {
            myWeb.post(MainActivity.this::onTurboPageShown);
        }
    }

    /**
     * Does for a page reached through Turbo what onPageFinished does for a loaded one, since the
     * WebView reports no page load for it. Runs for whatever the main frame shows now.
     */
    private void onTurboPageShown() {
        String url = myWeb.getUrl();
        if (isDestroyed() || url == null || !BackloggdUrls.isBackloggdHost(Uri.parse(url))) {
            return;
        }
        myWeb.evaluateJavascript(scripts.injectStyle, null);
        keepUsernameInStep(myWeb, Uri.parse(url));
    }

    private final ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Log.d(TAG, "Notification permission granted.");
                } else {
                    Log.w(TAG, "Notification permission denied.");
                }
            });

    private final ActivityResultLauncher<Intent> fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), this::deliverChosenFiles);

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        // Hold the splash until the first page is drawn, so the start never shows an empty WebView.
        long splashUntil = SystemClock.uptimeMillis() + SPLASH_MAX_MS;
        splashScreen.setKeepOnScreenCondition(
                () -> !firstPageShown && SystemClock.uptimeMillis() < splashUntil);
        SystemBars.blendIn(this);
        setContentView(R.layout.activity_main);
        scripts = PageScripts.load(this);

        myWeb = findViewById(R.id.myWeb);
        errorLayout = findViewById(R.id.errorLayout);
        bottomNav = findViewById(R.id.bottomNav);
        drawerLayout = findViewById(R.id.drawerLayout);
        drawerNav = findViewById(R.id.drawerNav);
        pageProgress = findViewById(R.id.pageProgress);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        Button retryButton = findViewById(R.id.retryButton);

        swipeRefresh.setColorSchemeResources(R.color.back_pink);
        swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.back_secondary);
        swipeRefresh.setOnRefreshListener(() -> myWeb.reload());
        // A pull inside an open dialog that is already at its top would reload the page and throw
        // away whatever was typed into it. Pulls that scroll the dialog never reach this far, the
        // WebView keeps them for itself.
        swipeRefresh.setOnChildScrollUpCallback(
                (parent, child) -> pageHasDialogOpen || myWeb.canScrollVertically(-1));

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            bottomNav.setPadding(0, 0, 0, systemBars.bottom);
            return insets;
        });

        setupBottomNav();

        myWeb.getSettings().setJavaScriptEnabled(true);
        myWeb.getSettings().setDomStorageEnabled(true);
        // Off by default only from Android 11 on. The app never loads local files, and a page
        // that holds the login has no business reading them.
        myWeb.getSettings().setAllowFileAccess(false);
        // The WebView is white until the first paint; match the site instead.
        myWeb.setBackgroundColor(ContextCompat.getColor(this, R.color.back_primary));
        // Pull-to-refresh is the overscroll gesture here. The page's own scrollbars are hidden via
        // CSS; the WebView's native one, which fades out after scrolling, stays.
        myWeb.setOverScrollMode(View.OVER_SCROLL_NEVER);
        myWeb.setHorizontalScrollBarEnabled(false);
        myWeb.setDownloadListener(this::startDownload);
        myWeb.addJavascriptInterface(new PageBridge(), "BackloggdApp");

        retryButton.setOnClickListener(v -> {
            myWeb.setVisibility(View.VISIBLE);
            errorLayout.setVisibility(View.GONE);
            myWeb.reload();
        });

        myWeb.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (BackloggdUrls.isBackloggdHost(uri)) {
                    return false;
                }

                openExternally(uri);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                receivedError = false;
                // A dialog of the previous page is gone; the new page reports its own.
                pageHasDialogOpen = false;
                errorLayout.setVisibility(View.GONE);
                myWeb.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                firstPageShown = true;
                // Earlier than onPageFinished, so the hidden parts of the site do not flash up.
                if (url != null && BackloggdUrls.isBackloggdHost(Uri.parse(url))) {
                    view.evaluateJavascript(scripts.injectStyle, null);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    firstPageShown = true;
                    receivedError = true;
                    myWeb.setVisibility(View.GONE);
                    errorLayout.setVisibility(View.VISIBLE);
                    swipeRefresh.setRefreshing(false);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!receivedError) {
                    myWeb.setVisibility(View.VISIBLE);
                    errorLayout.setVisibility(View.GONE);
                }
                if (url != null && BackloggdUrls.isBackloggdHost(Uri.parse(url))) {
                    view.evaluateJavascript(scripts.injectStyle, null);
                    view.evaluateJavascript(scripts.watchPage, null);
                    if (!receivedError) {
                        keepUsernameInStep(view, Uri.parse(url));
                    }
                    if (pendingPostLaunchAction != null && !receivedError) {
                        runPendingPostLaunchActionWhenReady(view);
                    }
                }
                updateUiForUrl(url);
                updateBackCallback();
                swipeRefresh.setRefreshing(false);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                updateUiForUrl(url);
                // The back/forward list is not updated yet while this runs.
                view.post(MainActivity.this::updateBackCallback);
            }
        });

        myWeb.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                if (progress < 100) {
                    if (pageProgress.getVisibility() != View.VISIBLE) {
                        pageProgress.setVisibility(View.VISIBLE);
                    }
                    pageProgress.setProgressCompat(progress, true);
                } else {
                    pageProgress.setVisibility(View.GONE);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams params) {
                return showFileChooser(filePathCallback, params);
            }
        });

        // Only enabled while there is something to go back to inside the app. Otherwise the system
        // handles back itself, which lets the predictive back gesture preview the home screen.
        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                } else if (myWeb.canGoBack()) {
                    myWeb.goBack();
                }
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(@NonNull View drawerView) {
                updateBackCallback();
            }

            @Override
            public void onDrawerClosed(@NonNull View drawerView) {
                updateBackCallback();
            }
        });

        // Only on a fresh start, so a rotation does not check again. Right after an update the
        // release notes take precedence; the update check resumes on the next launch, which also
        // keeps the two dialogs from stacking.
        if (savedInstanceState == null) {
            boolean justUpdated = UpdateChecker.wasUpdatedSinceLastLaunch(this);
            UpdateChecker.rememberInstalledVersion(this);
            if (justUpdated) {
                UpdateChecker.fetchInstalledReleaseNotes(this, this::showWhatsNewDialog);
            } else {
                UpdateChecker.checkAsync(this, this::showUpdateDialog);
            }
        }
        askNotificationPermission();
        NotificationCheckWorker.schedule(this);
        WishlistCheckerWorker.ensureScheduled(this);

        // A recreation, e.g. after a font size change or once the system killed the app in the
        // background, comes back with the intent it was started with. Handling that again would
        // replace the page the user was on with the link or shortcut they opened back then.
        if (savedInstanceState == null) {
            handleIntent(getIntent());
        } else {
            restorePage(savedInstanceState);
        }
    }

    /**
     * Keeps only the current page, not the WebView's whole history: WebView.saveState can outgrow
     * what a saved state may hold and crash the app. The common changes (rotation, resizing, dark
     * mode) do not get here at all, the manifest keeps the activity alive through them.
     */
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_URL, myWeb.getUrl());
    }

    private void restorePage(@NonNull Bundle savedInstanceState) {
        String url = savedInstanceState.getString(STATE_URL);
        myWeb.loadUrl(BackloggdUrls.isSafeToLoad(url) ? url : BACKLOGGD_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        myWeb.onResume();
    }

    @Override
    protected void onPause() {
        myWeb.onPause();
        // The WebView only writes cookies to disk now and then. Once in the background the app
        // can be killed at any moment, and a login that never reached the disk would be gone for
        // the next start and for the workers.
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // A WebView holds its renderer until destroyed, which it must not be while still attached.
        ((ViewGroup) myWeb.getParent()).removeView(myWeb);
        myWeb.destroy();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(@NonNull Intent intent) {
        String action = intent.getAction();
        Uri data = intent.getData();

        if (Intent.ACTION_VIEW.equals(action) && data != null) {
            if (BackloggdUrls.isSafeToLoad(data.toString())) {
                myWeb.loadUrl(data.toString());
                return;
            }
            Log.w(TAG, "Ignoring a link that is not a Backloggd page: " + data);
        }
        if (intent.hasExtra("urlToLoad")) {
            String urlToLoad = intent.getStringExtra("urlToLoad");
            intent.removeExtra("urlToLoad");
            if (BackloggdUrls.isSafeToLoad(urlToLoad)) {
                myWeb.loadUrl(urlToLoad);
                return;
            }
            Log.w(TAG, "Ignoring urlToLoad that is not a Backloggd page: " + urlToLoad);
        }
        if (intent.hasExtra("postLaunchAction")) {
            String postLaunchAction = intent.getStringExtra("postLaunchAction");
            // Consumed once, so the same shortcut never runs twice.
            intent.removeExtra("postLaunchAction");
            if (myWeb.getUrl() == null && ("log_game".equals(postLaunchAction) || "search".equals(postLaunchAction))) {
                // A launcher shortcut starts with an empty WebView; these act on the loaded page.
                pendingPostLaunchAction = postLaunchAction;
                myWeb.loadUrl(BACKLOGGD_URL);
                return;
            }
            runPostLaunchAction(postLaunchAction);
            if (myWeb.getUrl() == null && "more".equals(postLaunchAction)) {
                myWeb.loadUrl(BACKLOGGD_URL);
            }
            return;
        }
        if (myWeb.getUrl() == null) {
            myWeb.loadUrl(BACKLOGGD_URL);
        }
    }

    private void runPostLaunchAction(String action) {
        if (action == null) return;
        switch (action) {
            case "home":
                myWeb.loadUrl(BACKLOGGD_URL);
                break;
            case "log_game":
                myWeb.evaluateJavascript(scripts.logGame, null);
                break;
            case "search":
                triggerSearch();
                break;
            case "profile":
                withUsername(u -> myWeb.loadUrl(BackloggdUrls.profile(u)));
                break;
            case "more":
                drawerLayout.openDrawer(GravityCompat.START);
                break;
        }
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting notification permission.");
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    /**
     * The cached username builds the profile and library links, so it has to follow the account.
     * On the login page whoever signs in next may be someone else. The settings page shows the
     * current name, also right after the user renamed themselves there.
     */
    private void keepUsernameInStep(WebView view, Uri uri) {
        String path = uri.getPath() != null ? uri.getPath() : "";
        if (path.equals("/login") || path.startsWith("/login/")) {
            UsernameHelper.clearCached(this);
        } else if (path.equals("/settings") || path.startsWith("/settings/")) {
            view.evaluateJavascript(scripts.readUsername, result -> {
                String username = UsernameHelper.fromJsResult(result);
                if (username != null) {
                    UsernameHelper.setCached(this, username);
                }
            });
        }
    }

    private void updateUiForUrl(String url) {
        Log.d(TAG, "Current URL: " + url);
        updateBottomNavSelection(url);
    }

    private void setupBottomNav() {
        bottomNav.setOnItemSelectedListener(item -> {
            bottomNav.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            int id = item.getItemId();
            openBottomNavItem(id);
            // Only the two tabs that are places stay highlighted; the others are actions.
            return id == R.id.nav_home || id == R.id.nav_profile;
        });

        setupDrawerNav();

        // Tapping the current tab again starts it over, e.g. reloads the home page.
        bottomNav.setOnItemReselectedListener(item -> {
            bottomNav.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            openBottomNavItem(item.getItemId());
        });
    }

    private void openBottomNavItem(int id) {
        if (id == R.id.nav_home) {
            myWeb.loadUrl(BACKLOGGD_URL);
        } else if (id == R.id.nav_log_game) {
            myWeb.evaluateJavascript(scripts.logGame, null);
        } else if (id == R.id.nav_search) {
            triggerSearch();
        } else if (id == R.id.nav_profile) {
            withUsername(u -> myWeb.loadUrl(BackloggdUrls.profile(u)));
        } else if (id == R.id.nav_more) {
            drawerLayout.openDrawer(GravityCompat.START);
        }
    }

    private interface UsernameAction {
        void run(String username);
    }

    private void withUsername(UsernameAction action) {
        String cached = UsernameHelper.getCached(this);
        if (cached != null) {
            action.run(cached);
            return;
        }
        showMessage(R.string.fetching_username);
        UsernameHelper.fetchAsync(this, username -> {
            if (isFinishing() || isDestroyed()) return;
            if (username != null) {
                action.run(username);
            } else {
                myWeb.loadUrl(LOGIN_URL);
            }
        });
    }

    private void triggerSearch() {
        myWeb.requestFocus();
        myWeb.evaluateJavascript(scripts.search, null);
        myWeb.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(myWeb, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 150);
    }

    private void setupDrawerNav() {
        // Drawer entries that open a part of the user's own profile, with that part's path.
        Map<Integer, String> profileSections = new HashMap<>();
        profileSections.put(R.id.drawer_played, "games");
        profileSections.put(R.id.drawer_playing, "playing");
        profileSections.put(R.id.drawer_backlog, "backlog");
        profileSections.put(R.id.drawer_wishlist, "wishlist");
        profileSections.put(R.id.drawer_journal, "journal");
        profileSections.put(R.id.drawer_activity, "activity");
        profileSections.put(R.id.drawer_reviews, "reviews");
        profileSections.put(R.id.drawer_lists, "lists");
        profileSections.put(R.id.drawer_friends, "following");
        profileSections.put(R.id.drawer_likes, "likes");
        profileSections.put(R.id.drawer_stats, "stats");

        drawerNav.setNavigationItemSelectedListener(item -> {
            drawerNav.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            drawerLayout.closeDrawer(GravityCompat.START);
            int id = item.getItemId();
            String section = profileSections.get(id);
            if (section != null) {
                withUsername(u -> myWeb.loadUrl(BackloggdUrls.profileSection(u, section)));
            } else if (id == R.id.drawer_notifications) {
                myWeb.loadUrl(NOTIFICATION_URL);
            } else if (id == R.id.drawer_backloggd_settings) {
                myWeb.loadUrl(BACKLOGGD_URL + "/settings/");
            } else if (id == R.id.drawer_app_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
            } else if (id == R.id.drawer_logout) {
                UsernameHelper.clearCached(this);
                myWeb.loadUrl(LOGOUT_URL);
            } else if (id == R.id.drawer_feedback) {
                Intent feedback = new Intent(Intent.ACTION_SENDTO,
                        Uri.parse("mailto:dev.wagenknecht@gmail.com"));
                feedback.putExtra(Intent.EXTRA_SUBJECT, "Backloggd");
                try {
                    startActivity(feedback);
                } catch (android.content.ActivityNotFoundException e) {
                    showMessage(R.string.feedback_no_email_app);
                }
            } else if (id == R.id.drawer_about) {
                showAboutDialog();
            }
            return true;
        });
    }

    private void updateBottomNavSelection(String url) {
        if (url == null || bottomNav == null) return;
        Uri uri = Uri.parse(url);
        if (!BackloggdUrls.isBackloggdHost(uri)) return;

        String path = uri.getPath();
        if (path == null) path = "/";

        Integer itemId = null;
        if (path.equals("/") || path.isEmpty()) {
            itemId = R.id.nav_home;
        } else if (path.startsWith("/search")) {
            itemId = R.id.nav_search;
        } else if (BackloggdUrls.isOnProfileOf(path, UsernameHelper.getCached(this))) {
            itemId = R.id.nav_profile;
        }

        android.view.Menu menu = bottomNav.getMenu();
        if (itemId != null) {
            if (bottomNav.getSelectedItemId() != itemId) {
                menu.findItem(itemId).setChecked(true);
            }
        } else {
            android.view.MenuItem current = menu.findItem(bottomNav.getSelectedItemId());
            if (current != null && current.isChecked()) {
                int groupId = current.getGroupId();
                menu.setGroupCheckable(groupId, true, false);
                current.setChecked(false);
                menu.setGroupCheckable(groupId, true, true);
            }
        }
    }

    private void showUpdateDialog(@NonNull UpdateChecker.Release release) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        // Without an APK asset the releases page is the next best landing spot.
        String downloadUrl = release.apkUrl != null ? release.apkUrl : GITHUB_RELEASES_LATEST;

        StringBuilder message = new StringBuilder(
                getString(R.string.update_available_message, release.version));
        if (!release.notes.isEmpty()) {
            message.append("\n\n").append(release.notes);
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_available_title)
                .setMessage(message)
                .setPositiveButton(R.string.update_download,
                        (dialog, which) -> openExternally(Uri.parse(downloadUrl)))
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    /**
     * The site's footer is hidden, so its links live here instead. Backloggd pages open in the
     * app, everything else in the browser.
     */
    private void showAboutDialog() {
        String[] labels = new String[ABOUT_LINKS.length];
        for (int i = 0; i < ABOUT_LINKS.length; i++) {
            labels[i] = getString(ABOUT_LINKS[i].label);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.drawer_about)
                .setItems(labels, (dialog, which) -> {
                    Uri uri = Uri.parse(ABOUT_LINKS[which].url);
                    if (BackloggdUrls.isBackloggdHost(uri)) {
                        myWeb.loadUrl(uri.toString());
                    } else {
                        openExternally(uri);
                    }
                })
                .setNegativeButton(R.string.about_close, null)
                .show();
    }

    private void showWhatsNewDialog(@NonNull String version, @NonNull String notes) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.whats_new_title, version))
                .setMessage(notes)
                .setPositiveButton(R.string.whats_new_dismiss, null)
                .show();
    }

    /**
     * onPageFinished can fire before the document has a body or the site's scripts are wired up,
     * and the search field and the log dialog both need them. Until the page reports itself
     * complete, the action stays pending for the next onPageFinished.
     */
    private void runPendingPostLaunchActionWhenReady(WebView view) {
        view.evaluateJavascript("document.body!==null&&document.readyState==='complete'", ready -> {
            if (!"true".equals(ready) || pendingPostLaunchAction == null) {
                return;
            }
            String action = pendingPostLaunchAction;
            pendingPostLaunchAction = null;
            runPostLaunchAction(action);
        });
    }

    private void updateBackCallback() {
        backCallback.setEnabled(drawerLayout.isDrawerOpen(GravityCompat.START) || myWeb.canGoBack());
    }

    /** Opens the system picker for an {@code <input type="file">} on the page. */
    private boolean showFileChooser(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        // A new request replaces one that never got an answer; the page must hear back from both.
        if (pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(null);
        }
        pendingFileCallback = callback;
        try {
            fileChooserLauncher.launch(params.createIntent());
        } catch (android.content.ActivityNotFoundException e) {
            Log.w(TAG, "No app can pick a file.", e);
            pendingFileCallback = null;
            callback.onReceiveValue(null);
            showMessage(R.string.file_chooser_unavailable);
        }
        return true;
    }

    private void deliverChosenFiles(ActivityResult result) {
        if (pendingFileCallback == null) {
            return;
        }
        Uri[] uris = null;
        Intent data = result.getData();
        if (result.getResultCode() == Activity.RESULT_OK && data != null) {
            // parseResult only knows about a single file; a multi-select arrives as ClipData.
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                uris = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    uris[i] = clip.getItemAt(i).getUri();
                }
            } else {
                uris = WebChromeClient.FileChooserParams.parseResult(result.getResultCode(), data);
            }
        }
        // null tells the page the picker was cancelled, so it can open it again.
        pendingFileCallback.onReceiveValue(uris);
        pendingFileCallback = null;
    }

    /**
     * Saves a file the page wants to download to the public Downloads folder. Backloggd downloads
     * get the session cookies, so ones that need a login work too.
     */
    private void startDownload(String url, String userAgent, String contentDisposition,
                               String mimeType, long contentLength) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            // blob: and data: URLs only exist inside the page; DownloadManager cannot fetch them.
            Log.w(TAG, "Cannot download " + scheme + " URL.");
            showMessage(R.string.download_unsupported);
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Writing to the public Downloads folder needs a storage permission before Android 10.
            openExternally(uri);
            return;
        }

        // For the catch-all type, guessFileName would swap a real extension for ".bin".
        String typeHint = "application/octet-stream".equalsIgnoreCase(mimeType) ? null : mimeType;
        String fileName = URLUtil.guessFileName(url, contentDisposition, typeHint);
        DownloadManager.Request request = new DownloadManager.Request(uri)
                .setTitle(fileName)
                .setMimeType(mimeType)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .addRequestHeader("User-Agent", userAgent);
        // The login cookie only ever goes to Backloggd itself.
        String cookies = BackloggdUrls.isBackloggdHost(uri) ? CookieManager.getInstance().getCookie(url) : null;
        if (cookies != null && !cookies.isEmpty()) {
            request.addRequestHeader("Cookie", cookies);
        }

        DownloadManager downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager == null) {
            openExternally(uri);
            return;
        }
        downloadManager.enqueue(request);
        snackbar(getString(R.string.download_started, fileName))
                .setAction(R.string.download_show, v -> {
                    try {
                        startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
                    } catch (android.content.ActivityNotFoundException e) {
                        Log.w(TAG, "No app shows the downloads list.", e);
                    }
                })
                .show();
    }

    private void showMessage(@StringRes int text) {
        snackbar(getString(text)).show();
    }

    /** Material's replacement for a toast: above the bottom nav, in the app's colours. */
    private Snackbar snackbar(CharSequence text) {
        return Snackbar.make(findViewById(R.id.contentFrame), text, Snackbar.LENGTH_SHORT)
                .setAnchorView(bottomNav);
    }

    /** Hands a URI to another app, telling the user when nothing can handle it. */
    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (android.content.ActivityNotFoundException e) {
            Log.w(TAG, "No app can handle " + uri, e);
            showMessage(R.string.no_app_for_link);
        }
    }
}
