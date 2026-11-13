package uz.akso.kalkulyator;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.Selection;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.BaseInputConnection;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** AKSO Liquid Glass calculator with persistent dark mode and tactile controls. */
public class MainActivity extends Activity {
    private int GREEN = Color.rgb(11, 179, 75);
    private int DARK_GREEN = Color.rgb(7, 139, 57);
    private int TEXT = Color.rgb(24, 43, 35);
    private int MUTED = Color.rgb(105, 127, 116);
    private int PAGE = Color.rgb(232, 245, 238);
    private int BORDER = Color.rgb(222, 237, 228);

    private SharedPreferences preferences;
    private boolean darkMode = false;
    private boolean soundEnabled = true;
    private boolean vibrationEnabled = true;

    private EditText productInput;
    private EditText upfrontInput;
    private TextView remainingValue;
    private TextView validationText;
    private TextView[] monthlyValues = new TextView[4];
    private TextView[] totalValues = new TextView[4];
    private TextView[] grandValues = new TextView[4];
    private TextView[] discountValues = new TextView[4];
    private final int[] discountRates = {10, 15, 20, 25};
    private final int[] discountColors = {
            Color.rgb(17, 132, 75),
            Color.rgb(25, 145, 83),
            Color.rgb(190, 137, 0),
            Color.rgb(210, 61, 61)
    };
    private Button miniCalculatorButton;

    // The main sales calendar and WASYT seller calendar share one completed-sales feed.
    // Legacy manually entered totals are intentionally no longer read or displayed.
    private Calendar salesDisplayedMonth;
    private Calendar selectedSalesDate;
    private TextView salesMonthTitle;
    private TextView salesMonthlyTotal;
    private TextView salesDaysCount;
    private TextView salesSelectedSummary;
    private TextView salesSourceStatus;
    private Button salesRefreshButton;
    private LinearLayout salesCalendarGrid;
    private LinearLayout salesDayDetails;
    private Dialog dailySalesDialog;
    private boolean salesSyncLoading = false;
    private final Handler salesHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService salesSyncExecutor = Executors.newSingleThreadExecutor();
    private final List<WasytSellerApi.Cart> wasytCompletedSales = new ArrayList<>();

    // Offline-cached sales inspiration drawer.
    private DrawerHost drawerHost;
    private ScrollView mainScrollView;
    private FrameLayout drawerOverlay;
    private View drawerScrim;
    private View drawerPanel;
    private ImageView drawerHeroImage;
    private TextView drawerImageFallback;
    private TextView drawerArticleTitle;
    private TextView drawerArticleBody;
    private TextView drawerUpdateLabel;
    private Button drawerNextArticleButton;
    private int drawerSelectedArticleIndex = -1;
    private int drawerWidthPx;
    private Dialog sellerQuizDialog;
    private final Handler drawerHandler = new Handler(Looper.getMainLooper());
    private Runnable drawerHourlyRefresh;
    private Runnable drawerCachePoll;
    private int drawerCachePollAttempts;

    // Private wallet: all records stay locally on this device.
    private static final String WALLET_PREFS = "akso_personal_wallet";
    private static final String WALLET_TRANSACTIONS_KEY = "transactions_json";
    private static final String WALLET_OPENING_CASH_KEY = "opening_cash";
    private static final String WALLET_OPENING_CARD_KEY = "opening_card";
    private SharedPreferences walletPreferences;
    private TextView walletTotalValue;
    private TextView walletCashValue;
    private TextView walletCardValue;
    private TextView walletMonthIncomeValue;
    private TextView walletMonthExpenseValue;
    private TextView walletMonthLabel;
    private Dialog personalWalletDialog;
    private LinearLayout walletLegendContainer;
    private LinearLayout walletHistoryContainer;
    private WalletDonutChart walletDonutChart;

    private static class WalletSlice {
        final String label;
        final long amount;
        final int color;
        WalletSlice(String label, long amount, int color) {
            this.label = label; this.amount = amount; this.color = color;
        }
    }

    // Seller knowledge quiz: 100 daily questions drawn from a rotating offline bank.
    private static final String QUIZ_PREFS = "akso_seller_quiz";
    private static final String QUIZ_DAY_KEY = "quiz_day";
    private static final String QUIZ_POSITION_KEY = "quiz_position";
    private static final String QUIZ_CORRECT_KEY = "quiz_correct_today";
    private static final String QUIZ_COINS_KEY = "quiz_total_coins";
    private static final String QUIZ_IDS_KEY = "quiz_daily_question_ids";
    private static final String QUIZ_VERSION_KEY = "quiz_bank_version";
    private static final int DAILY_QUIZ_SIZE = 100;
    private static final int QUIZ_BANK_VERSION = 4;
    private SharedPreferences quizPreferences;
    private TextView coinBalanceLabel;
    private TextView quizProgressLabel;
    private ProgressBar quizProgressBar;
    private TextView quizQuestionText;
    private TextView quizFeedbackText;
    private Button[] quizOptionButtons = new Button[4];
    private Button quizNextButton;
    private int quizQuestionIndex = 0;
    private int quizCorrectCount = 0;
    private boolean quizAnswered = false;
    private boolean quizCompleted = false;
    private final ArrayList<SellerQuizQuestion> dailySellerQuizQuestions = new ArrayList<>();

    private static class SellerQuizQuestion {
        final String id;
        final String question;
        final String[] options;
        final int correctIndex;
        final String explanation;

        SellerQuizQuestion(String id, String question, String[] options,
                           int correctIndex, String explanation) {
            this.id = id;
            this.question = question;
            this.options = options;
            this.correctIndex = correctIndex;
            this.explanation = explanation;
        }
    }

    // Guards the short, deferred in-place formatting pass while the IME is idle.
    private boolean formattingAmount = false;

    private BigDecimal lastProduct = BigDecimal.ZERO;
    private BigDecimal lastUpfront = BigDecimal.ZERO;
    private BigDecimal lastRemaining = BigDecimal.ZERO;
    private boolean lastValid = true;

    private final int[] terms = {3, 6, 9, 12};
    private final int[] rates = {0, 18, 27, 36};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("akso_ui_preferences", MODE_PRIVATE);
        quizPreferences = getSharedPreferences(QUIZ_PREFS, MODE_PRIVATE);
        walletPreferences = getSharedPreferences(WALLET_PREFS, MODE_PRIVATE);
        initializeSellerQuizDay();
        darkMode = preferences.getBoolean("dark_mode", false);
        soundEnabled = preferences.getBoolean("sound_enabled", true);
        vibrationEnabled = preferences.getBoolean("vibration_enabled", true);
        scheduleSalesContentRefresh();
        applyThemePalette();
        applySystemBarTheme();
        setContentView(buildScreen());
        productInput.setText("1000000");
        upfrontInput.setText("0");
        normalizeAmountInput(productInput);
        normalizeAmountInput(upfrontInput);
        recalculate();
    }

    private View buildScreen() {
        DrawerHost root = new DrawerHost(this);
        drawerHost = root;
        root.setBackgroundColor(PAGE);

        GlassBackdropView backdrop = new GlassBackdropView(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        mainScrollView = scroll;
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(13), 0, dp(13), dp(10));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset = insets.getSystemWindowInsetTop();
            page.setPadding(dp(13), topInset, dp(13), dp(10));
            return insets;
        });

        page.addView(buildHeader(), matchWrap());
        addSpace(page, 10);
        page.addView(buildInputCard(), matchWrap());
        addSpace(page, 10);

        LinearLayout sectionHeading = new LinearLayout(this);
        sectionHeading.setOrientation(LinearLayout.HORIZONTAL);
        sectionHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView plansHeading = text("TO‘LOV MUDDATLARI", 11, MUTED, true);
        plansHeading.setLetterSpacing(0.08f);
        sectionHeading.addView(plansHeading, new LinearLayout.LayoutParams(0, dp(22), 1f));
        TextView smallHint = text("Oylik to‘lov va jami", 9, MUTED, false);
        sectionHeading.addView(smallHint, wrapWrap());
        page.addView(sectionHeading, matchWrap());
        addSpace(page, 6);

        for (int row = 0; row < 2; row++) {
            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setGravity(Gravity.TOP);
            rowLayout.addView(buildPlanCard(row * 2), new LinearLayout.LayoutParams(0, dp(140), 1f));
            View gap = new View(this);
            rowLayout.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
            rowLayout.addView(buildPlanCard(row * 2 + 1), new LinearLayout.LayoutParams(0, dp(140), 1f));
            page.addView(rowLayout, matchWrap());
            if (row == 0) addSpace(page, 8);
        }

        addSpace(page, 9);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        miniCalculatorButton = new Button(this);
        miniCalculatorButton.setText("MINI KALKULYATOR");
        miniCalculatorButton.setTextSize(11);
        miniCalculatorButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        miniCalculatorButton.setTextColor(Color.WHITE);
        miniCalculatorButton.setAllCaps(false);
        miniCalculatorButton.setMinHeight(0);
        miniCalculatorButton.setMinimumHeight(0);
        miniCalculatorButton.setPadding(dp(6), 0, dp(6), 0);
        miniCalculatorButton.setBackground(glassButtonBackground(true));
        miniCalculatorButton.setElevation(dp(5));
        actions.addView(miniCalculatorButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        attachInteractionFeedback(miniCalculatorButton);
        miniCalculatorButton.setOnClickListener(v -> showMiniCalculator());

        Button clearButton = new Button(this);
        clearButton.setText("Tozalash");
        clearButton.setTextSize(11);
        clearButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        clearButton.setTextColor(DARK_GREEN);
        clearButton.setAllCaps(false);
        clearButton.setMinHeight(0);
        clearButton.setMinimumHeight(0);
        clearButton.setBackground(glassButtonBackground(false));
        clearButton.setElevation(dp(4));
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(dp(96), dp(46));
        clearParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(clearButton, clearParams);
        attachInteractionFeedback(clearButton);
        clearButton.setOnClickListener(v -> clearInputs());

        addSpace(page, 11);
        page.addView(buildDiscountCard(), matchWrap());

        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(scroll, scrollParams);
        return root;
    }

    private View buildHeader() {
        GlassPanel header = new GlassPanel(this, true, 20);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(11), dp(11), dp(15), dp(11));

        // Always-visible drawer shortcut, styled as a circular Liquid Glass control.
        Button drawerMenuButton = new Button(this);
        drawerMenuButton.setText("☰");
        drawerMenuButton.setTextSize(16);
        drawerMenuButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        drawerMenuButton.setAllCaps(false);
        drawerMenuButton.setMinHeight(0);
        drawerMenuButton.setMinimumHeight(0);
        drawerMenuButton.setPadding(0, 0, 0, dp(1));
        drawerMenuButton.setTextColor(darkMode ? Color.rgb(211, 250, 227) : DARK_GREEN);
        GradientDrawable drawerMenuBackground = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                darkMode
                        ? new int[]{0xE83C624D, 0xD51D392A}
                        : new int[]{0xF2FFFFFF, 0xCDE1F5E8});
        drawerMenuBackground.setShape(GradientDrawable.OVAL);
        drawerMenuBackground.setStroke(dp(1), darkMode ? 0xAFFFFFFF : 0xDFFFFFFF);
        drawerMenuButton.setBackground(drawerMenuBackground);
        drawerMenuButton.setElevation(dp(4));
        drawerMenuButton.setContentDescription("Savdo ilhomi yon panelini ochish");
        LinearLayout.LayoutParams drawerMenuParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        drawerMenuParams.setMargins(0, 0, dp(7), 0);
        header.addView(drawerMenuButton, drawerMenuParams);
        attachInteractionFeedback(drawerMenuButton);
        drawerMenuButton.setOnClickListener(v -> openSalesInspirationDrawer());

        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.VERTICAL);
        TextView brand = text("AKSO  /  FINANCE", 9, Color.rgb(225, 255, 237), true);
        brand.setLetterSpacing(0.18f);
        titleGroup.addView(brand, matchWrap());

        TextView title = text("Bo‘lib to‘lash kalkulyatori", 15, Color.WHITE, true);
        title.setPadding(0, dp(3), 0, 0);
        titleGroup.addView(title, matchWrap());
        header.addView(titleGroup, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView subtitle = text("3 · 6 · 9 · 12 oy", 8, Color.WHITE, true);
        subtitle.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        subtitle.setShadowLayer(dp(5), 0, dp(1), 0x55FFFFFF);
        header.addView(subtitle, wrapWrap());

        // Persistent gold coin counter, visible on the calculator home header.
        coinBalanceLabel = text("🪙" + getQuizCoinBalance(), 9,
                Color.rgb(255, 232, 151), true);
        coinBalanceLabel.setGravity(Gravity.CENTER);
        coinBalanceLabel.setSingleLine(true);
        coinBalanceLabel.setPadding(dp(5), 0, dp(5), 0);
        GradientDrawable coinBadge = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                darkMode ? new int[]{0xD94B4527, 0xB933301D}
                        : new int[]{0xE8FFF7D8, 0xD9F3DCA6});
        coinBadge.setCornerRadius(dp(13));
        coinBadge.setStroke(dp(1), 0xA8F6D77A);
        coinBalanceLabel.setBackground(coinBadge);
        coinBalanceLabel.setContentDescription("To‘plangan tangalar: " + getQuizCoinBalance());
        LinearLayout.LayoutParams coinParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(29));
        coinParams.setMargins(dp(4), 0, dp(2), 0);
        header.addView(coinBalanceLabel, coinParams);

        // Dedicated personal-wallet shortcut; the wallet opens as its own full-screen section.
        Button walletButton = calculatorButton("👛", 13, false, true);
        walletButton.setPadding(0, 0, 0, 0);
        walletButton.setContentDescription("Shaxsiy hamyonni ochish");
        LinearLayout.LayoutParams walletButtonParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        walletButtonParams.setMargins(dp(4), 0, 0, 0);
        header.addView(walletButton, walletButtonParams);
        walletButton.setOnClickListener(v -> showPersonalWalletScreen());

        // The former dollar shortcut is now a calendar icon; its sales-calendar action is unchanged.
        Button salesButton = calculatorButton("▦", 18, false, true);
        salesButton.setPadding(0, 0, 0, dp(2));
        salesButton.setContentDescription("Kunlik savdo kalendari");
        LinearLayout.LayoutParams salesParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        salesParams.setMargins(dp(5), 0, 0, 0);
        header.addView(salesButton, salesParams);
        salesButton.setOnClickListener(v -> showDailySalesDialog());

        Button settingsButton = calculatorButton("⚙", 13, false, true);
        settingsButton.setContentDescription("Sozlamalar");
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        settingsParams.setMargins(dp(5), 0, 0, 0);
        header.addView(settingsButton, settingsParams);
        settingsButton.setOnClickListener(v -> showSettingsDialog());

        return header;
    }

    private void showSettingsDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        GlassPanel panel = new GlassPanel(this, false, 21);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView heading = text("AKSO  /  SOZLAMALAR", 12, DARK_GREEN, true);
        heading.setLetterSpacing(0.09f);
        panel.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        TextView note = text("Ilova ko‘rinishi va tugma effektlari", 9, MUTED, false);
        panel.addView(note, matchWrap());
        addSpace(panel, 8);

        // Google account and cloud synchronization shortcut.
        Button accountButton = calculatorButton(
                "👤  GOOGLE AKKAUNTI VA SINXRONLASH", 10, false, true);
        panel.addView(accountButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        accountButton.setOnClickListener(v -> {
            dialog.dismiss();
            android.content.Intent intent = new android.content.Intent(
                    MainActivity.this, GoogleAccountActivity.class);
            intent.setAction(GoogleAccountActivity.ACTION_PROFILE);
            startActivity(intent);
        });

        addSpace(panel, 8);

        Button themeToggle = calculatorButton(themeSettingLabel(), 11, false, true);
        panel.addView(themeToggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        themeToggle.setOnClickListener(v -> {
            String product = productInput.getText().toString();
            String upfront = upfrontInput.getText().toString();
            darkMode = !darkMode;
            preferences.edit().putBoolean("dark_mode", darkMode).apply();
            dialog.dismiss();
            applyThemePalette();
            applySystemBarTheme();
            setContentView(buildScreen());
            productInput.setText(product);
            upfrontInput.setText(upfront);
            recalculate();
        });

        addSpace(panel, 6);
        Button soundToggle = calculatorButton(soundSettingLabel(), 10, false, true);
        panel.addView(soundToggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        soundToggle.setOnClickListener(v -> {
            soundEnabled = !soundEnabled;
            preferences.edit().putBoolean("sound_enabled", soundEnabled).apply();
            soundToggle.setText(soundSettingLabel());
        });

        addSpace(panel, 5);
        Button vibrationToggle = calculatorButton(vibrationSettingLabel(), 10, false, true);
        panel.addView(vibrationToggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        vibrationToggle.setOnClickListener(v -> {
            vibrationEnabled = !vibrationEnabled;
            preferences.edit().putBoolean("vibration_enabled", vibrationEnabled).apply();
            vibrationToggle.setText(vibrationSettingLabel());
        });

        addSpace(panel, 8);
        Button close = calculatorButton("Yopish", 10, true, false);
        panel.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        close.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(panel);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(36), dp(350));
            attributes.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            attributes.gravity = Gravity.CENTER;
            attributes.dimAmount = darkMode ? 0.46f : 0.30f;
            window.setAttributes(attributes);
        }
    }

    private String themeSettingLabel() {
        return darkMode ? "TUNGI REJIM  ·  YOQILGAN" : "TUNGI REJIM  ·  O‘CHIRILGAN";
    }

    private String soundSettingLabel() {
        return soundEnabled ? "TUGMA OVOZI  ·  YOQILGAN" : "TUGMA OVOZI  ·  O‘CHIRILGAN";
    }

    private String vibrationSettingLabel() {
        return vibrationEnabled ? "VIBRATSIYA  ·  YOQILGAN" : "VIBRATSIYA  ·  O‘CHIRILGAN";
    }

    private void applyThemePalette() {
        if (darkMode) {
            GREEN = Color.rgb(38, 190, 116);
            DARK_GREEN = Color.rgb(105, 224, 164);
            TEXT = Color.rgb(233, 245, 237);
            MUTED = Color.rgb(163, 187, 172);
            PAGE = Color.rgb(12, 25, 19);
            BORDER = Color.rgb(67, 94, 77);
        } else {
            GREEN = Color.rgb(11, 179, 75);
            DARK_GREEN = Color.rgb(7, 139, 57);
            TEXT = Color.rgb(24, 43, 35);
            MUTED = Color.rgb(105, 127, 116);
            PAGE = Color.rgb(232, 245, 238);
            BORDER = Color.rgb(222, 237, 228);
        }
    }

    private void applySystemBarTheme() {
        Window window = getWindow();
        window.setStatusBarColor(PAGE);
        window.setNavigationBarColor(PAGE);
        int flags = darkMode ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void attachInteractionFeedback(View view) {
        view.setSoundEffectsEnabled(true);
        view.setHapticFeedbackEnabled(true);
        view.setOnTouchListener((target, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                // Let Android play its normal click sound when enabled; avoid duplicate manual playback.
                target.setSoundEffectsEnabled(soundEnabled);
                if (vibrationEnabled) {
                    target.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
            }
            return false;
        });
    }

    private View buildInputCard() {
        GlassPanel card = new GlassPanel(this, false, 19);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(11), dp(12), dp(11));

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(fields, matchWrap());

        LinearLayout productColumn = new LinearLayout(this);
        productColumn.setOrientation(LinearLayout.VERTICAL);
        fields.addView(productColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        productColumn.addView(fieldLabel("Mahsulot summasi"), matchWrap());
        productInput = amountInput("Mahsulot narxi");
        productColumn.addView(productInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        View fieldGap = new View(this);
        fields.addView(fieldGap, new LinearLayout.LayoutParams(dp(9), 1));

        LinearLayout upfrontColumn = new LinearLayout(this);
        upfrontColumn.setOrientation(LinearLayout.VERTICAL);
        fields.addView(upfrontColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        upfrontColumn.addView(fieldLabel("Oldindan to‘lov"), matchWrap());
        upfrontInput = amountInput("Boshlang‘ich to‘lov");
        upfrontColumn.addView(upfrontInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        addSpace(card, 8);
        Button wasytScanButton = calculatorButton(
                "▦  WASYT shtrix-kod skaneri", 10, true, false);
        card.addView(wasytScanButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        wasytScanButton.setOnClickListener(v -> startWasytBarcodeScan());

        addSpace(card, 7);
        Button wasytSellerButton = calculatorButton(
                "🛒  WASYT SOTUV MODULI", 10, true, false);
        card.addView(wasytSellerButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        wasytSellerButton.setOnClickListener(v ->
                startActivity(new android.content.Intent(
                        MainActivity.this, WasytSellerActivity.class)));

        addSpace(card, 9);
        GlassPanel remaining = new GlassPanel(this, false, 14);
        remaining.setOrientation(LinearLayout.HORIZONTAL);
        remaining.setGravity(Gravity.CENTER_VERTICAL);
        remaining.setPadding(dp(11), dp(8), dp(11), dp(8));
        remaining.setBackground(glassTintBackground());
        TextView caption = text("QOLGAN SUMMA", 9, MUTED, true);
        caption.setLetterSpacing(0.06f);
        remaining.addView(caption, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        remainingValue = text("0 so‘m", 17, DARK_GREEN, true);
        remainingValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        remainingValue.setShadowLayer(dp(3), 0, dp(1), 0x2290DDB0);
        remaining.addView(remainingValue, wrapWrap());
        card.addView(remaining, matchWrap());

        validationText = text("", 10, Color.rgb(180, 55, 55), true);
        validationText.setPadding(dp(2), dp(4), dp(2), 0);
        card.addView(validationText, matchWrap());

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                // The formatter edits the same Editable internally. Recalculate only once,
                // after the user's original text change and formatting are both complete.
                if (!formattingAmount) recalculate();
            }
        };
        productInput.addTextChangedListener(watcher);
        upfrontInput.addTextChangedListener(watcher);
        return card;
    }

    /** Opens Google Code Scanner and looks up the scanned barcode in the connected WASYT account. */
    private void startWasytBarcodeScan() {
        if (WasytSecureStore.getValidToken(this) == null) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("WASYT akkaunti ulanmagan")
                    .setMessage("Mahsulotni skanerlashdan oldin WASYT sotuvchi akkauntini ulang.")
                    .setNegativeButton("Bekor qilish", null)
                    .setPositiveButton("WASYT akkauntini ulash", (dialog, which) ->
                            startActivity(new android.content.Intent(
                                    this, WasytAccountActivity.class)))
                    .show();
            return;
        }

        final android.app.ProgressDialog[] progress = new android.app.ProgressDialog[1];

        WasytBarcodeScanner.start(this, new WasytBarcodeScanner.Listener() {
            @Override
            public void onScanStarted() {
                Toast.makeText(MainActivity.this,
                        "Kamerani mahsulot shtrix-kodiga qarating.",
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onLookupStarted(String barcode) {
                if (isFinishing() || isDestroyed()) return;
                progress[0] = new android.app.ProgressDialog(MainActivity.this);
                progress[0].setTitle("WASYT");
                progress[0].setMessage("Shtrix-kod " + barcode
                        + " bo‘yicha mahsulot qidirilmoqda…");
                progress[0].setIndeterminate(true);
                progress[0].setCancelable(false);
                progress[0].show();
            }

            @Override
            public void onProductsFound(String barcode,
                    List<WasytBarcodeScanner.ProductInfo> products) {
                dismissWasytLookupProgress(progress[0]);
                showWasytProductResults(barcode, products);
            }

            @Override
            public void onCancelled() {
                dismissWasytLookupProgress(progress[0]);
            }

            @Override
            public void onError(String message) {
                dismissWasytLookupProgress(progress[0]);
                if (isFinishing() || isDestroyed()) return;
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("WASYT skaneri")
                        .setMessage(message)
                        .setNegativeButton("Yopish", null)
                        .setPositiveButton("WASYT sozlamalari", (dialog, which) ->
                                startActivity(new android.content.Intent(
                                        MainActivity.this, WasytAccountActivity.class)))
                        .show();
            }
        });
    }

    private void dismissWasytLookupProgress(android.app.ProgressDialog progress) {
        if (progress != null && progress.isShowing()) {
            try {
                progress.dismiss();
            } catch (RuntimeException ignored) {
                // The activity may already be finishing; no action is needed.
            }
        }
    }

    private void showWasytProductResults(String barcode,
            List<WasytBarcodeScanner.ProductInfo> products) {
        if (products == null || products.isEmpty()) {
            Toast.makeText(this, "WASYT bazasida mahsulot topilmadi.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (products.size() == 1) {
            showWasytProductDetails(products.get(0));
            return;
        }

        String[] labels = new String[products.size()];
        for (int i = 0; i < products.size(); i++) {
            WasytBarcodeScanner.ProductInfo item = products.get(i);
            String price = formatWasytPrice(item);
            labels[i] = item.name + " — " + price;
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("Bir nechta mahsulot topildi")
                .setMessage("Shtrix-kod: " + barcode
                        + "\nKerakli mahsulotni tanlang.")
                .setItems(labels, (dialog, which) ->
                        showWasytProductDetails(products.get(which)))
                .setNegativeButton("Bekor qilish", null)
                .show();
    }

    private void showWasytProductDetails(WasytBarcodeScanner.ProductInfo product) {
        String priceText = formatWasytPrice(product);
        String stockText = product.stockQuantity == null
                || product.stockQuantity.trim().isEmpty()
                ? "Ombor qoldig‘i: ko‘rsatilmagan"
                : "Ombor qoldig‘i: " + product.stockQuantity;

        String message = "Shtrix-kod: " + product.barcode
                + "\nSotuv narxi: " + priceText
                + "\n" + stockText
                + "\n\nNarxni AKSO Finance kalkulyatoriga kiritasizmi?";

        android.app.AlertDialog.Builder builder =
                new android.app.AlertDialog.Builder(this)
                        .setTitle(product.name)
                        .setMessage(message)
                        .setNegativeButton("Bekor qilish", null);

        if (product.hasUzsPrice()) {
            builder.setPositiveButton("Narxni kalkulyatorga kiritish",
                    (dialog, which) -> {
                        productInput.setText(String.valueOf(product.priceAmount));
                        normalizeAmountInput(productInput);
                        productInput.requestFocus();
                        recalculate();
                        Toast.makeText(this,
                                "Mahsulot narxi kalkulyatorga kiritildi.",
                                Toast.LENGTH_SHORT).show();
                    });
        } else {
            builder.setPositiveButton("Yopish", null);
            if (product.hasPrice() && !product.hasUzsPrice()) {
                message += "\n\nNarx UZSda emas, shuning uchun kalkulyatorga avtomatik kiritilmaydi.";
                builder.setMessage(message);
            }
        }
        builder.show();
    }

    private String formatWasytPrice(WasytBarcodeScanner.ProductInfo product) {
        if (product == null || !product.hasPrice()) return "Narx ko‘rsatilmagan";
        String amount = formatMoney(BigDecimal.valueOf(product.priceAmount));
        if ("uzs".equalsIgnoreCase(product.currency)) return amount + " so‘m";
        String currency = product.currency == null || product.currency.trim().isEmpty()
                ? "valyuta noma’lum" : product.currency.toUpperCase(java.util.Locale.ROOT);
        return amount + " " + currency;
    }

    private View buildPlanCard(int index) {
        GlassPanel card = new GlassPanel(this, false, 18);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(9), dp(10), dp(8));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(top, matchWrap());

        TextView term = text(terms[index] + " OY", 13, TEXT, true);
        top.addView(term, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView microLabel = text("AKSO", 8, MUTED, true);
        microLabel.setLetterSpacing(0.10f);
        top.addView(microLabel, wrapWrap());

        TextView monthlyCaption = text("OYLIK TO‘LOV", 8, MUTED, true);
        monthlyCaption.setLetterSpacing(0.05f);
        monthlyCaption.setPadding(0, dp(5), 0, 0);
        card.addView(monthlyCaption, matchWrap());

        monthlyValues[index] = text("—", 17, DARK_GREEN, true);
        monthlyValues[index].setSingleLine(true);
        monthlyValues[index].setShadowLayer(dp(3), 0, dp(1), 0x2285D8A6);
        card.addView(monthlyValues[index], matchWrap());

        View divider = new View(this);
        divider.setBackgroundColor(0x55FFFFFF);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(5);
        dividerParams.bottomMargin = dp(5);
        card.addView(divider, dividerParams);

        LinearLayout totalRow = new LinearLayout(this);
        totalRow.setOrientation(LinearLayout.HORIZONTAL);
        totalRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(totalRow, matchWrap());
        totalRow.addView(text("Muddatli jami", 8, MUTED, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        totalValues[index] = text("—", 9, TEXT, true);
        totalValues[index].setSingleLine(true);
        totalRow.addView(totalValues[index], wrapWrap());

        LinearLayout grandRow = new LinearLayout(this);
        grandRow.setOrientation(LinearLayout.HORIZONTAL);
        grandRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams grandParams = matchWrap();
        grandParams.topMargin = dp(3);
        card.addView(grandRow, grandParams);
        grandRow.addView(text("Oldindan bilan", 8, MUTED, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        grandValues[index] = text("—", 9, TEXT, true);
        grandValues[index].setSingleLine(true);
        grandRow.addView(grandValues[index], wrapWrap());
        return card;
    }

    private View buildDiscountCard() {
        GlassPanel card = new GlassPanel(this, false, 18);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(9), dp(12), dp(9));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("CHEGIRMALI NARXLAR", 10, DARK_GREEN, true);
        title.setLetterSpacing(0.07f);
        heading.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView caption = text("Asl narxdan", 8, MUTED, false);
        heading.addView(caption, wrapWrap());
        card.addView(heading, matchWrap());

        for (int i = 0; i < discountRates.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(4), dp(10), dp(4));

            int[] rowColors = darkMode
                    ? new int[]{0xB92E4438, 0x8F1B2E24}
                    : new int[]{0xCFFFFFFF, 0x88FFFFFF};
            GradientDrawable rowBackground = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT, rowColors);
            rowBackground.setCornerRadius(dp(10));
            rowBackground.setStroke(dp(1), darkMode ? 0x776CE0A4 : 0xAFFFFFFF);
            row.setBackground(rowBackground);

            LinearLayout.LayoutParams rowParams = matchWrap();
            rowParams.topMargin = dp(i == 0 ? 6 : 4);
            card.addView(row, rowParams);

            TextView label = text("−" + discountRates[i] + "% CHEGIRMA", 9, MUTED, true);
            label.setLetterSpacing(0.025f);
            row.addView(label, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            discountValues[i] = text("—", 13, discountColors[i], true);
            discountValues[i].setSingleLine(true);
            discountValues[i].setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            row.addView(discountValues[i], wrapWrap());
        }
        return card;
    }

    /** Monthly sales calendar backed only by completed sales from WASYT. */
    private void showDailySalesDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        Calendar now = Calendar.getInstance();
        salesDisplayedMonth = (Calendar) now.clone();
        salesDisplayedMonth.set(Calendar.DAY_OF_MONTH, 1);
        selectedSalesDate = (Calendar) now.clone();

        GlassPanel panel = new GlassPanel(this, false, 20);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(11), dp(12), dp(12));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("AKSO  /  WASYT SAVDOLARI", 11, DARK_GREEN, true);
        title.setLetterSpacing(0.07f);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1f));
        Button close = calculatorButton("✕", 11, false, true);
        titleRow.addView(close, new LinearLayout.LayoutParams(dp(38), dp(36)));
        panel.addView(titleRow, matchWrap());
        close.setOnClickListener(v -> dialog.dismiss());

        LinearLayout monthNav = new LinearLayout(this);
        monthNav.setOrientation(LinearLayout.HORIZONTAL);
        monthNav.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams navParams = matchWrap();
        navParams.topMargin = dp(5);
        panel.addView(monthNav, navParams);
        Button previousMonth = calculatorButton("‹", 19, false, true);
        monthNav.addView(previousMonth, new LinearLayout.LayoutParams(dp(42), dp(38)));
        salesMonthTitle = text("", 13, TEXT, true);
        salesMonthTitle.setGravity(Gravity.CENTER);
        monthNav.addView(salesMonthTitle, new LinearLayout.LayoutParams(0, dp(38), 1f));
        Button nextMonth = calculatorButton("›", 19, false, true);
        monthNav.addView(nextMonth, new LinearLayout.LayoutParams(dp(42), dp(38)));
        previousMonth.setOnClickListener(v -> changeSalesMonth(-1));
        nextMonth.setOnClickListener(v -> changeSalesMonth(1));

        GlassPanel summary = new GlassPanel(this, false, 14);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(dp(11), dp(7), dp(11), dp(7));
        summary.setBackground(glassTintBackground());
        LinearLayout.LayoutParams summaryParams = matchWrap();
        summaryParams.topMargin = dp(7);
        panel.addView(summary, summaryParams);
        LinearLayout summaryText = new LinearLayout(this);
        summaryText.setOrientation(LinearLayout.VERTICAL);
        summary.addView(summaryText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        summaryText.addView(text("OYLIK YAKUNLANGAN SAVDOLAR", 8, MUTED, true), matchWrap());
        salesMonthlyTotal = text("0 so‘m", 16, DARK_GREEN, true);
        summaryText.addView(salesMonthlyTotal, matchWrap());
        salesDaysCount = text("Savdo bo‘lgan kunlar: 0", 8, MUTED, false);
        summary.addView(salesDaysCount, wrapWrap());

        LinearLayout weekdays = new LinearLayout(this);
        weekdays.setOrientation(LinearLayout.HORIZONTAL);
        String[] dayNames = {"Du", "Se", "Ch", "Pa", "Ju", "Sh", "Ya"};
        LinearLayout.LayoutParams weekParams = matchWrap();
        weekParams.topMargin = dp(8);
        panel.addView(weekdays, weekParams);
        for (String dayName : dayNames) {
            TextView label = text(dayName, 8, MUTED, true);
            label.setGravity(Gravity.CENTER);
            weekdays.addView(label, new LinearLayout.LayoutParams(0, dp(21), 1f));
        }

        salesCalendarGrid = new LinearLayout(this);
        salesCalendarGrid.setOrientation(LinearLayout.VERTICAL);
        panel.addView(salesCalendarGrid, matchWrap());

        salesSelectedSummary = text("", 9, DARK_GREEN, true);
        salesSelectedSummary.setPadding(dp(2), dp(8), dp(2), dp(4));
        panel.addView(salesSelectedSummary, matchWrap());

        LinearLayout sourceRow = new LinearLayout(this);
        sourceRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sourceParams = matchWrap();
        sourceParams.topMargin = dp(5);
        panel.addView(sourceRow, sourceParams);
        salesSourceStatus = text("WASYT savdolari yuklanmoqda…", 8, MUTED, false);
        sourceRow.addView(salesSourceStatus,
                new LinearLayout.LayoutParams(0, dp(36), 1f));
        salesRefreshButton = calculatorButton("YANGILASH", 8, true, false);
        sourceRow.addView(salesRefreshButton, new LinearLayout.LayoutParams(dp(94), dp(36)));
        attachInteractionFeedback(salesRefreshButton);
        salesRefreshButton.setOnClickListener(v -> {
            if (WasytSecureStore.getValidToken(this) == null) {
                startActivity(new android.content.Intent(this, WasytAccountActivity.class));
            } else {
                loadWasytSalesForMainCalendar();
            }
        });

        salesDayDetails = new LinearLayout(this);
        salesDayDetails.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams detailsParams = matchWrap();
        detailsParams.topMargin = dp(3);
        panel.addView(salesDayDetails, detailsParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View activityContent = findViewById(android.R.id.content);
        final boolean[] backdropBlurApplied = {false};
        dialog.setOnDismissListener(d -> {
            if (dailySalesDialog == dialog) dailySalesDialog = null;
            salesSourceStatus = null;
            salesRefreshButton = null;
            salesDayDetails = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activityContent != null) {
                try {
                    activityContent.setRenderEffect(null);
                } catch (RuntimeException ignored) {
                    // Some device renderers may not support clearing a render effect.
                }
            }
        });

        dialog.setContentView(scroll);
        dialog.show();
        dailySalesDialog = dialog;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activityContent != null) {
            try {
                activityContent.setRenderEffect(RenderEffect.createBlurEffect(
                        dp(22), dp(22), Shader.TileMode.CLAMP));
                backdropBlurApplied[0] = true;
            } catch (RuntimeException ignored) {
                activityContent.setRenderEffect(null);
            }
        }

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.width = getResources().getDisplayMetrics().widthPixels - dp(28);
            attributes.height = Math.min(
                    getResources().getDisplayMetrics().heightPixels - dp(100), dp(760));
            attributes.gravity = Gravity.CENTER;
            attributes.dimAmount = backdropBlurApplied[0]
                    ? (darkMode ? 0.44f : 0.28f)
                    : (darkMode ? 0.62f : 0.48f);
            window.setAttributes(attributes);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        refreshSalesCalendar();
        loadWasytSalesForMainCalendar();
    }

    private void changeSalesMonth(int amount) {
        int selectedDay = selectedSalesDate.get(Calendar.DAY_OF_MONTH);
        salesDisplayedMonth.add(Calendar.MONTH, amount);
        int maxDay = salesDisplayedMonth.getActualMaximum(Calendar.DAY_OF_MONTH);
        selectedSalesDate.set(salesDisplayedMonth.get(Calendar.YEAR),
                salesDisplayedMonth.get(Calendar.MONTH), Math.min(selectedDay, maxDay));
        refreshSalesCalendar();
    }

    private void selectSalesDay(int day) {
        selectedSalesDate.set(salesDisplayedMonth.get(Calendar.YEAR),
                salesDisplayedMonth.get(Calendar.MONTH), day);
        refreshSalesCalendar();
    }

    private void refreshSalesCalendar() {
        if (salesCalendarGrid == null || salesDisplayedMonth == null) return;
        SimpleDateFormat monthFormat = new SimpleDateFormat("LLLL yyyy", new Locale("uz", "UZ"));
        String monthName = monthFormat.format(salesDisplayedMonth.getTime());
        salesMonthTitle.setText(monthName.substring(0, 1).toUpperCase(new Locale("uz", "UZ"))
                + monthName.substring(1));
        salesCalendarGrid.removeAllViews();

        Calendar first = (Calendar) salesDisplayedMonth.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        int maxDay = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        for (int week = 0; week < 6; week++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = matchWrap();
            if (week > 0) rowParams.topMargin = dp(3);
            salesCalendarGrid.addView(row, rowParams);
            for (int column = 0; column < 7; column++) {
                int day = week * 7 + column - offset + 1;
                LinearLayout.LayoutParams cellParams =
                        new LinearLayout.LayoutParams(0, dp(43), 1f);
                if (column > 0) cellParams.setMargins(dp(2), 0, 0, 0);
                if (day < 1 || day > maxDay) {
                    row.addView(new View(this), cellParams);
                } else {
                    Calendar date = (Calendar) salesDisplayedMonth.clone();
                    date.set(Calendar.DAY_OF_MONTH, day);
                    BigDecimal amount = getDailySales(date);
                    row.addView(buildSalesDayCell(day, amount,
                            isSameSalesDate(date, selectedSalesDate)), cellParams);
                }
            }
        }

        BigDecimal monthTotal = BigDecimal.ZERO;
        int activeDays = 0;
        int monthSaleCount = 0;
        for (int day = 1; day <= maxDay; day++) {
            Calendar date = (Calendar) salesDisplayedMonth.clone();
            date.set(Calendar.DAY_OF_MONTH, day);
            BigDecimal value = getDailySales(date);
            if (value.signum() > 0) {
                monthTotal = monthTotal.add(value);
                activeDays++;
            }
            monthSaleCount += WasytSalesRepository.getDailyCount(wasytCompletedSales, date);
        }
        salesMonthlyTotal.setText(formatMoney(monthTotal) + " so‘m");
        salesDaysCount.setText("Savdo bo‘lgan kunlar: " + activeDays
                + "  ·  " + monthSaleCount + " ta savdo");
        SimpleDateFormat dateFormat = new SimpleDateFormat("d MMMM yyyy", new Locale("uz", "UZ"));
        int selectedCount = WasytSalesRepository.getDailyCount(wasytCompletedSales, selectedSalesDate);
        salesSelectedSummary.setText(dateFormat.format(selectedSalesDate.getTime())
                + "   ·   " + selectedCount + " ta savdo  ·  Kunlik jami: "
                + formatMoney(getDailySales(selectedSalesDate)) + " so‘m");
        renderMainCalendarDayDetails();
    }

    private View buildSalesDayCell(int day, BigDecimal amount, boolean selected) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(1), dp(2), dp(1), dp(2));
        int bg1, bg2, stroke, dayColor, amountColor;
        if (selected) {
            bg1 = darkMode ? 0xE52BC27B : 0xF20DB35B;
            bg2 = darkMode ? 0xD70E8053 : 0xE8078B45;
            stroke = 0xEEFFFFFF;
            dayColor = Color.WHITE;
            amountColor = Color.WHITE;
        } else if (amount.signum() > 0) {
            bg1 = darkMode ? 0xC83B7657 : 0xD7DDF5E6;
            bg2 = darkMode ? 0xA82A5942 : 0xBFEAF5EF;
            stroke = darkMode ? 0x7769D9A0 : 0xAFFFFFFF;
            dayColor = TEXT;
            amountColor = DARK_GREEN;
        } else {
            bg1 = darkMode ? 0x55334B3D : 0x77FFFFFF;
            bg2 = darkMode ? 0x33273B30 : 0x44FFFFFF;
            stroke = darkMode ? 0x446F927D : 0x66FFFFFF;
            dayColor = TEXT;
            amountColor = MUTED;
        }
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{bg1, bg2});
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), stroke);
        cell.setBackground(background);
        TextView dayNumber = text(String.valueOf(day), 9, dayColor, true);
        dayNumber.setGravity(Gravity.CENTER);
        cell.addView(dayNumber, matchWrap());
        TextView amountText = text(amount.signum() > 0 ? compactSalesAmount(amount) : "·",
                7, amountColor, true);
        amountText.setGravity(Gravity.CENTER);
        amountText.setSingleLine(true);
        cell.addView(amountText, matchWrap());
        cell.setOnClickListener(v -> selectSalesDay(day));
        return cell;
    }

    private boolean isSameSalesDate(Calendar first, Calendar second) {
        return WasytSalesRepository.isSameDate(first, second);
    }

    private BigDecimal getDailySales(Calendar date) {
        return WasytSalesRepository.getDailyTotal(wasytCompletedSales, date);
    }

    private String compactSalesAmount(BigDecimal amount) {
        BigDecimal million = BigDecimal.valueOf(1_000_000L);
        BigDecimal thousand = BigDecimal.valueOf(1_000L);
        if (amount.compareTo(million) >= 0) {
            return amount.divide(million, 1, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString() + "m";
        }
        if (amount.compareTo(thousand) >= 0) {
            return amount.divide(thousand, 0, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString() + "k";
        }
        return formatMoney(amount);
    }

    private boolean isDailySalesDialogVisible() {
        return !isFinishing() && !isDestroyed()
                && dailySalesDialog != null && dailySalesDialog.isShowing();
    }

    private void loadWasytSalesForMainCalendar() {
        if (!isDailySalesDialogVisible()) return;
        String token = WasytSecureStore.getValidToken(this);
        if (token == null || token.trim().isEmpty()) {
            if (salesSourceStatus != null) {
                salesSourceStatus.setText("WASYT akkaunti ulanmagan. Avval akkauntni ulang.");
            }
            if (salesRefreshButton != null) salesRefreshButton.setText("AKKAUNTNI ULASH");
            return;
        }
        if (salesSyncLoading) return;

        salesSyncLoading = true;
        if (salesSourceStatus != null) salesSourceStatus.setText("WASYT savdolari yuklanmoqda…");
        if (salesRefreshButton != null) {
            salesRefreshButton.setText("YUKLANMOQDA");
            salesRefreshButton.setEnabled(false);
        }

        salesSyncExecutor.execute(() -> {
            try {
                List<WasytSellerApi.Cart> completed =
                        WasytSalesRepository.loadCompletedSales(this, token);
                ArrayList<WasytSellerApi.Cart> snapshot = new ArrayList<>(completed);
                salesHandler.post(() -> {
                    salesSyncLoading = false;
                    if (!isDailySalesDialogVisible()) return;
                    wasytCompletedSales.clear();
                    wasytCompletedSales.addAll(snapshot);
                    salesSyncLoading = false;
                    if (salesSourceStatus != null) {
                        salesSourceStatus.setText("WASYT bilan sinxronlandi · "
                                + snapshot.size() + " ta yakunlangan savdo");
                    }
                    if (salesRefreshButton != null) {
                        salesRefreshButton.setText("YANGILASH");
                        salesRefreshButton.setEnabled(true);
                    }
                    refreshSalesCalendar();
                });
            } catch (Exception error) {
                String message = error.getMessage() == null
                        ? "WASYT savdolarini yuklab bo‘lmadi." : error.getMessage();
                salesHandler.post(() -> {
                    salesSyncLoading = false;
                    if (!isDailySalesDialogVisible()) return;
                    if (salesSourceStatus != null) {
                        salesSourceStatus.setText("WASYT bilan sinxronlash amalga oshmadi.");
                    }
                    if (salesRefreshButton != null) {
                        salesRefreshButton.setText("QAYTA URINISH");
                        salesRefreshButton.setEnabled(true);
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void renderMainCalendarDayDetails() {
        if (salesDayDetails == null || selectedSalesDate == null) return;
        salesDayDetails.removeAllViews();
        int count = 0;
        for (WasytSellerApi.Cart sale : wasytCompletedSales) {
            Calendar saleDate = WasytSalesRepository.getSaleDate(sale);
            if (!WasytSalesRepository.isSameDate(saleDate, selectedSalesDate)) continue;

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(7), dp(8), dp(7));
            row.setBackground(glassTintBackground());
            LinearLayout.LayoutParams rowParams = matchWrap();
            rowParams.topMargin = dp(4);
            salesDayDetails.addView(row, rowParams);

            LinearLayout detail = new LinearLayout(this);
            detail.setOrientation(LinearLayout.VERTICAL);
            row.addView(detail, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            String saleCode = sale.saleId > 0 ? String.valueOf(sale.saleId) : sale.code;
            detail.addView(text("SAVDO  #" + saleCode, 9, TEXT, true), matchWrap());
            detail.addView(text(sale.clientName.isEmpty()
                    ? "Mijoz tanlanmagan" : sale.clientName, 8, MUTED, false), matchWrap());
            SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.US);
            String meta = time.format(saleDate.getTime()) + "  ·  "
                    + sale.productsCount + " ta mahsulot";
            if (sale.saleId > 0) meta += "  ·  Kassa #" + sale.saleId;
            detail.addView(text(meta, 7, MUTED, false), matchWrap());

            TextView amount = text(formatMoney(BigDecimal.valueOf(sale.totalAmount)) + " so‘m",
                    10, DARK_GREEN, true);
            amount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            row.addView(amount, wrapWrap());
            count++;
        }
        if (count == 0) {
            TextView empty = text("Tanlangan kunda yakunlangan WASYT savdosi yo‘q.",
                    8, MUTED, false);
            empty.setPadding(dp(3), dp(8), dp(3), dp(3));
            salesDayDetails.addView(empty, matchWrap());
        }
    }

    private BigDecimal discountedPrice(BigDecimal originalPrice, int discountRate) {
        return originalPrice.multiply(BigDecimal.valueOf(100L - discountRate))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    }

    private void updateDiscountPrices(BigDecimal originalPrice) {
        for (int i = 0; i < discountRates.length; i++) {
            if (originalPrice.signum() <= 0) {
                discountValues[i].setText("—");
            } else {
                BigDecimal discounted = discountedPrice(originalPrice, discountRates[i]);
                discountValues[i].setText(formatMoney(discounted) + " so‘m");
            }
        }
    }

    private void recalculate() {
        BigDecimal product = readAmount(productInput);
        BigDecimal upfront = readAmount(upfrontInput);
        lastProduct = product;
        lastUpfront = upfront;
        updateDiscountPrices(product);

        if (upfront.compareTo(product) > 0) {
            lastValid = false;
            lastRemaining = BigDecimal.ZERO;
            remainingValue.setText("0 so‘m");
            validationText.setText("Oldindan to‘lov mahsulot summasidan oshmasin.");
            setPlanPlaceholders();
            return;
        }

        lastValid = true;
        lastRemaining = product.subtract(upfront);
        remainingValue.setText(formatMoney(lastRemaining) + " so‘m");
        validationText.setText("");

        for (int i = 0; i < terms.length; i++) {
            BigDecimal financedTotal = lastRemaining
                    .multiply(BigDecimal.valueOf(100L + rates[i]))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
            BigDecimal monthly = financedTotal.divide(BigDecimal.valueOf(terms[i]), 0, RoundingMode.HALF_UP);
            BigDecimal grandTotal = financedTotal.add(upfront);
            monthlyValues[i].setText(formatMoney(monthly));
            totalValues[i].setText(formatMoney(financedTotal));
            grandValues[i].setText(formatMoney(grandTotal));
        }
    }

    private void setPlanPlaceholders() {
        for (int i = 0; i < terms.length; i++) {
            monthlyValues[i].setText("—");
            totalValues[i].setText("—");
            grandValues[i].setText("—");
        }
    }

    /** Opens the built-in arithmetic calculator in a frosted-glass dialog. */
    private void showMiniCalculator() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        GlassPanel panel = new GlassPanel(this, false, 23);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(15), dp(14), dp(15), dp(14));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("AKSO  /  MINI CALCULATOR", 11, DARK_GREEN, true);
        title.setLetterSpacing(0.08f);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(32), 1f));
        Button closeButton = calculatorButton("✕", 10, false, false);
        titleRow.addView(closeButton, new LinearLayout.LayoutParams(dp(38), dp(36)));
        panel.addView(titleRow, matchWrap());

        GlassPanel displayPanel = new GlassPanel(this, false, 14);
        displayPanel.setOrientation(LinearLayout.VERTICAL);
        displayPanel.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams displayParams = matchWrap();
        displayParams.topMargin = dp(8);
        displayParams.bottomMargin = dp(10);
        panel.addView(displayPanel, displayParams);

        // Google Calculator-style expression history: a small, horizontally
        // scrollable line above the large live result.
        HorizontalScrollView expressionScroll = new HorizontalScrollView(this);
        expressionScroll.setHorizontalScrollBarEnabled(false);
        expressionScroll.setFillViewport(true);
        expressionScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        TextView expression = text("Hisoblashni boshlang", 10, MUTED, false);
        expression.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        expression.setSingleLine(true);
        expression.setHorizontallyScrolling(true);
        expression.setTextDirection(View.TEXT_DIRECTION_LTR);
        int screenWidthDp = (int) (getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density);
        int expressionWidth = dp(Math.max(200, Math.min(340, screenWidthDp - 58)));
        expression.setMinWidth(expressionWidth);
        expressionScroll.addView(expression, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        displayPanel.addView(expressionScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

        TextView resultDisplay = text("0", 27, DARK_GREEN, true);
        resultDisplay.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        resultDisplay.setSingleLine(true);
        displayPanel.addView(resultDisplay, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        MiniCalculator calculator = new MiniCalculator(expression, resultDisplay, expressionScroll);
        String[][] keys = {
                {"AC", "⌫", "%", "÷"},
                {"7", "8", "9", "×"},
                {"4", "5", "6", "−"},
                {"1", "2", "3", "+"},
                {"±", "0", ".", "="}
        };

        for (String[] keyRow : keys) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = matchWrap();
            rowParams.topMargin = dp(4);
            panel.addView(row, rowParams);

            for (int i = 0; i < keyRow.length; i++) {
                String key = keyRow[i];
                Button button = calculatorButton(key, key.length() > 1 ? 13 : 18,
                        key.equals("="), isOperatorKey(key));
                LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(
                        0, dp(49), 1f);
                if (i > 0) keyParams.setMargins(dp(5), 0, 0, 0);
                row.addView(button, keyParams);
                button.setOnClickListener(v -> calculator.press(key));
            }
        }

        TextView transferHint = text(
                "Natijani asosiy narx maydonlariga yuborishingiz mumkin.",
                9, MUTED, false);
        transferHint.setPadding(dp(2), dp(9), dp(2), dp(4));
        panel.addView(transferHint, matchWrap());

        LinearLayout transferRow = new LinearLayout(this);
        transferRow.setOrientation(LinearLayout.HORIZONTAL);
        transferRow.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(transferRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        Button toProduct = calculatorButton("Mahsulotga", 10, true, false);
        transferRow.addView(toProduct, new LinearLayout.LayoutParams(0, dp(42), 1f));
        toProduct.setOnClickListener(v -> transferCalculatorValue(calculator, productInput, dialog));

        Button toUpfront = calculatorButton("Oldindan to‘lovga", 10, false, false);
        LinearLayout.LayoutParams upfrontParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        upfrontParams.setMargins(dp(6), 0, 0, 0);
        transferRow.addView(toUpfront, upfrontParams);
        toUpfront.setOnClickListener(v -> transferCalculatorValue(calculator, upfrontInput, dialog));

        dialog.setContentView(panel);
        dialog.show();
        closeButton.setOnClickListener(v -> dialog.dismiss());

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(28), dp(390));
            attributes.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            attributes.gravity = Gravity.CENTER;
            attributes.dimAmount = darkMode ? 0.48f : 0.28f;
            window.setAttributes(attributes);
        }
    }

    private void transferCalculatorValue(MiniCalculator calculator, EditText target, Dialog dialog) {
        BigDecimal value = calculator.getValue();
        if (value == null) {
            Toast.makeText(this, "Avval hisob-kitobni to‘g‘ri kiriting.", Toast.LENGTH_SHORT).show();
            return;
        }
        BigDecimal wholeSum = value.setScale(0, RoundingMode.HALF_UP);
        target.setText(formatMoney(wholeSum));
        target.clearFocus();
        dialog.dismiss();
        Toast.makeText(this, "Qiymat maydonga joylandi.", Toast.LENGTH_SHORT).show();
    }

    private Button calculatorButton(String label, float textSize, boolean primary, boolean operator) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(textSize);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(3), 0, dp(3), 0);
        button.setTextColor(primary ? Color.WHITE : (operator ? DARK_GREEN : TEXT));

        int[] colors;
        if (primary) {
            colors = darkMode
                    ? new int[]{0xF22CCB81, 0xE80D8050}
                    : new int[]{0xF20DBD5B, 0xE8078B45};
        } else if (operator) {
            colors = darkMode
                    ? new int[]{0xD9345945, 0xB922382C}
                    : new int[]{0xE7FFFFFF, 0xBDE0F1E6};
        } else {
            colors = darkMode
                    ? new int[]{0xD92A3A31, 0xB91A2922}
                    : new int[]{0xDFFFFFFF, 0xAFFFFFFF};
        }
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, colors);
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), 0xBFFFFFFF);
        button.setBackground(background);
        button.setElevation(dp(primary ? 3 : 1));
        attachInteractionFeedback(button);
        return button;
    }

    private boolean isOperatorKey(String key) {
        return key.equals("÷") || key.equals("×") || key.equals("−")
                || key.equals("+") || key.equals("%") || key.equals("±");
    }

    /** Small, independent calculator engine. It never changes the installment formulas. */
    private class MiniCalculator {
        private final TextView expressionView;
        private final TextView resultView;
        private final HorizontalScrollView expressionScroll;
        private final StringBuilder expressionHistory = new StringBuilder();

        private String entry = "0";
        private BigDecimal accumulator;
        private String pendingOperator;
        private boolean startNewEntry = true;
        private boolean justEvaluated = false;
        private boolean error;

        MiniCalculator(TextView expressionView, TextView resultView,
                       HorizontalScrollView expressionScroll) {
            this.expressionView = expressionView;
            this.resultView = resultView;
            this.expressionScroll = expressionScroll;
            refresh();
        }

        void press(String key) {
            if (key.equals("AC")) {
                clear();
                refresh();
                return;
            }
            if (error) clear();

            try {
                if (key.length() == 1 && key.charAt(0) >= '0' && key.charAt(0) <= '9') {
                    digit(key);
                } else if (key.equals(".")) {
                    decimalPoint();
                } else if (key.equals("⌫")) {
                    backspace();
                } else if (key.equals("±")) {
                    toggleSign();
                } else if (key.equals("%")) {
                    percent();
                } else if (key.equals("=")) {
                    equals();
                } else if (isMathOperator(key)) {
                    operator(key);
                }
            } catch (ArithmeticException exception) {
                error = true;
                accumulator = null;
                pendingOperator = null;
                expressionView.setText("Nolga bo‘lish mumkin emas");
            } catch (NumberFormatException exception) {
                error = true;
                accumulator = null;
                pendingOperator = null;
                expressionView.setText("Hisoblashda xatolik");
            }
            refresh();
        }

        private boolean isMathOperator(String key) {
            return key.equals("+") || key.equals("−")
                    || key.equals("×") || key.equals("÷");
        }

        private void digit(String digit) {
            if (startNewEntry) {
                if (justEvaluated && pendingOperator == null) {
                    expressionHistory.setLength(0);
                    accumulator = null;
                    justEvaluated = false;
                }
                entry = digit;
                startNewEntry = false;
            } else if (entry.length() < 20) {
                if (entry.equals("0")) entry = digit;
                else if (entry.equals("-0")) entry = "-" + digit;
                else entry += digit;
            }
        }

        private void decimalPoint() {
            if (startNewEntry) {
                if (justEvaluated && pendingOperator == null) {
                    expressionHistory.setLength(0);
                    accumulator = null;
                    justEvaluated = false;
                }
                entry = "0.";
                startNewEntry = false;
            } else if (!entry.contains(".")) {
                entry += ".";
            }
        }

        private void backspace() {
            if (justEvaluated && pendingOperator == null) {
                expressionHistory.setLength(0);
                justEvaluated = false;
                entry = "0";
                startNewEntry = false;
                return;
            }
            if (startNewEntry) {
                entry = "0";
                startNewEntry = false;
                return;
            }
            if (entry.length() <= 1 || (entry.length() == 2 && entry.startsWith("-"))) {
                entry = "0";
            } else {
                entry = entry.substring(0, entry.length() - 1);
            }
        }

        private void toggleSign() {
            if (justEvaluated && pendingOperator == null) {
                expressionHistory.setLength(0);
                justEvaluated = false;
            }
            if (entry.startsWith("-")) entry = entry.substring(1);
            else if (!entry.equals("0")) entry = "-" + entry;
            startNewEntry = false;
        }

        private void percent() {
            BigDecimal value = number();
            if (accumulator != null && pendingOperator != null
                    && (pendingOperator.equals("+") || pendingOperator.equals("−"))) {
                value = accumulator.multiply(value)
                        .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
            } else {
                value = value.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
            }
            entry = value.stripTrailingZeros().toPlainString();
            startNewEntry = false;
        }

        private void operator(String operator) {
            BigDecimal current = number();

            if (accumulator != null && pendingOperator != null) {
                if (!startNewEntry) {
                    // Commit the current operand to the visible expression history.
                    expressionHistory.append(formatCalculatorNumber(current))
                            .append(" ").append(operator).append(" ");
                    accumulator = calculate(accumulator, pendingOperator, current);
                    entry = accumulator.stripTrailingZeros().toPlainString();
                } else {
                    // If an operator is tapped twice, replace the pending operator.
                    replaceTrailingOperator(operator);
                }
            } else {
                // Start a new expression, using the previous answer if one exists.
                if (justEvaluated) expressionHistory.setLength(0);
                accumulator = current;
                expressionHistory.setLength(0);
                expressionHistory.append(formatCalculatorNumber(current))
                        .append(" ").append(operator).append(" ");
            }

            pendingOperator = operator;
            justEvaluated = false;
            startNewEntry = true;
        }

        private void replaceTrailingOperator(String operator) {
            int end = expressionHistory.length();
            while (end > 0 && Character.isWhitespace(expressionHistory.charAt(end - 1))) {
                end--;
            }
            int start = end;
            while (start > 0 && !Character.isWhitespace(expressionHistory.charAt(start - 1))) {
                start--;
            }
            expressionHistory.replace(start, end, operator);
        }

        private void equals() {
            if (accumulator == null || pendingOperator == null) return;

            BigDecimal right = number();
            BigDecimal answer = calculate(accumulator, pendingOperator, right);

            expressionHistory.append(formatCalculatorNumber(right)).append(" =");

            entry = answer.stripTrailingZeros().toPlainString();
            accumulator = null;
            pendingOperator = null;
            startNewEntry = true;
            justEvaluated = true;
        }

        private BigDecimal calculate(BigDecimal left, String operator, BigDecimal right) {
            switch (operator) {
                case "+": return left.add(right);
                case "−": return left.subtract(right);
                case "×": return left.multiply(right);
                case "÷":
                    if (right.compareTo(BigDecimal.ZERO) == 0) {
                        throw new ArithmeticException("division by zero");
                    }
                    return left.divide(right, 8, RoundingMode.HALF_UP).stripTrailingZeros();
                default: return right;
            }
        }

        private BigDecimal number() {
            String safe = entry;
            if (safe.equals("-") || safe.equals("-0.")) safe = "0";
            if (safe.endsWith(".")) safe = safe.substring(0, safe.length() - 1);
            if (safe.isEmpty()) safe = "0";
            return new BigDecimal(safe);
        }

        private String currentExpression() {
            if (pendingOperator != null) {
                if (startNewEntry) {
                    return trimRight(expressionHistory.toString());
                }
                return expressionHistory.toString() + formatCalculatorEntry(entry);
            }
            if (justEvaluated) return expressionHistory.toString();
            if (expressionHistory.length() == 0 && startNewEntry && entry.equals("0")) {
                return "";
            }
            return formatCalculatorEntry(entry);
        }

        private String trimRight(String value) {
            int end = value.length();
            while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) end--;
            return value.substring(0, end);
        }

        private BigDecimal previewValue() {
            if (pendingOperator != null && accumulator != null) {
                if (startNewEntry) return accumulator;
                return calculate(accumulator, pendingOperator, number());
            }
            return number();
        }

        private void clear() {
            entry = "0";
            accumulator = null;
            pendingOperator = null;
            expressionHistory.setLength(0);
            startNewEntry = true;
            justEvaluated = false;
            error = false;
        }

        private void refresh() {
            if (error) {
                resultView.setText("Xatolik");
                return;
            }

            String visibleExpression = currentExpression();
            expressionView.setText(
                    visibleExpression.isEmpty() ? "Hisoblashni boshlang" : visibleExpression);

            expressionScroll.post(() -> expressionScroll.fullScroll(View.FOCUS_RIGHT));

            try {
                resultView.setText(formatCalculatorNumber(previewValue()));
            } catch (ArithmeticException exception) {
                resultView.setText("Xatolik");
            }
        }

        BigDecimal getValue() {
            if (error) return null;
            try {
                return previewValue();
            } catch (ArithmeticException exception) {
                return null;
            }
        }

        private String formatCalculatorEntry(String value) {
            try {
                return formatCalculatorNumber(new BigDecimal(value));
            } catch (NumberFormatException exception) {
                return value;
            }
        }

        private String formatCalculatorNumber(BigDecimal value) {
            BigDecimal normalized = value.stripTrailingZeros();
            String plain = normalized.toPlainString();
            boolean negative = plain.startsWith("-");
            if (negative) plain = plain.substring(1);
            int point = plain.indexOf('.');
            String integerPart = point >= 0 ? plain.substring(0, point) : plain;
            String decimalPart = point >= 0 ? plain.substring(point) : "";
            StringBuilder grouped = new StringBuilder();
            for (int i = 0; i < integerPart.length(); i++) {
                if (i > 0 && (integerPart.length() - i) % 3 == 0) grouped.append(' ');
                grouped.append(integerPart.charAt(i));
            }
            return (negative ? "−" : "") + grouped + decimalPart;
        }
    }

    private void clearInputs() {
        productInput.setText("");
        upfrontInput.setText("");
        productInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(productInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private BigDecimal readAmount(EditText input) {
        if (input == null || input.getText() == null) return BigDecimal.ZERO;
        Editable value = input.getText();
        StringBuilder digits = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= '0' && ch <= '9') digits.append(ch);
        }
        if (digits.length() == 0) return BigDecimal.ZERO;
        try { return new BigDecimal(digits.toString()); }
        catch (NumberFormatException exception) { return BigDecimal.ZERO; }
    }

    private String formatMoney(BigDecimal amount) {
        String raw = amount.setScale(0, RoundingMode.HALF_UP).toPlainString();
        boolean negative = raw.startsWith("-");
        int start = negative ? 1 : 0;
        int length = raw.length() - start;
        StringBuilder formatted = new StringBuilder(raw.length() + raw.length() / 3);
        if (negative) formatted.append('-');
        int firstGroup = length % 3;
        if (firstGroup == 0) firstGroup = 3;
        for (int i = 0; i < length; i++) {
            if (i > 0 && (i - firstGroup) % 3 == 0) formatted.append(' ');
            formatted.append(raw.charAt(start + i));
        }
        return formatted.toString();
    }

    private EditText amountInput(String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(17);
        input.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        input.setTextColor(DARK_GREEN);
        input.setHintTextColor(darkMode ? 0xFF9AAFA2 : 0xFF9BAFA4);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setPadding(dp(11), 0, dp(11), 0);
        input.setBackground(amountFieldBackground());

        // Apply grouping shortly after the keyboard commits a key. The formatter
        // only inserts/removes separator spaces; it never replaces all text and
        // never manually sets the cursor, avoiding the previous IME span crash.
        final boolean[] formattingQueued = {false};
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override public void afterTextChanged(Editable editable) {
                if (formattingAmount || formattingQueued[0]) return;
                formattingQueued[0] = true;
                input.postDelayed(() -> {
                    formattingQueued[0] = false;
                    if (!input.isAttachedToWindow() || formattingAmount) return;
                    Editable current = input.getText();
                    if (current == null) return;
                    // Numeric fields do not need word-composition spans. Remove
                    // stale IME composing markers after the key event has finished.
                    try {
                        BaseInputConnection.removeComposingSpans(current);
                    } catch (RuntimeException ignored) {
                        // Continue with separator-only formatting.
                    }
                    formatAmountInputInPlace(input);
                }, 24L);
            }
        });

        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) normalizeAmountInput(input);
        });
        return input;
    }

    /**
     * Groups digits as 1 234 567 by changing separator characters only.
     * Editable's own span machinery moves the cursor naturally when a space
     * is inserted/deleted before it; this method never calls Selection.setSelection.
     */
    private void formatAmountInputInPlace(EditText input) {
        if (input == null || input.getText() == null || formattingAmount) return;

        Editable editable = input.getText();
        StringBuilder digits = new StringBuilder(editable.length());
        for (int i = 0; i < editable.length(); i++) {
            char ch = editable.charAt(i);
            if (ch >= '0' && ch <= '9') digits.append(ch);
        }

        formattingAmount = true;
        try {
            if (digits.length() == 0) {
                // Remove leftover grouping spaces if the user erased all digits.
                for (int i = editable.length() - 1; i >= 0; i--) {
                    if (editable.charAt(i) == ' ') editable.delete(i, i + 1);
                }
            } else {
                int textPosition = 0;
                int digitCount = digits.length();

                for (int digitIndex = 0; digitIndex < digitCount; digitIndex++) {
                    // Put a separator before every digit that starts a new group
                    // of three, counting from the right side of the number.
                    boolean needsSpace = digitIndex > 0
                            && (digitCount - digitIndex) % 3 == 0;

                    if (needsSpace) {
                        if (textPosition < editable.length()
                                && editable.charAt(textPosition) == ' ') {
                            textPosition++;
                        } else {
                            editable.insert(textPosition, " ");
                            textPosition++;
                        }
                    } else {
                        // Remove separators that are no longer needed after edits.
                        while (textPosition < editable.length()
                                && editable.charAt(textPosition) == ' ') {
                            editable.delete(textPosition, textPosition + 1);
                        }
                    }

                    // Collapse any duplicate spaces at this position.
                    while (textPosition < editable.length()
                            && editable.charAt(textPosition) == ' ') {
                        editable.delete(textPosition, textPosition + 1);
                    }

                    // The non-space characters are the same digit sequence we
                    // collected above. Advance past the next digit without moving
                    // or resetting the caret ourselves.
                    if (textPosition < editable.length()) {
                        if (editable.charAt(textPosition) == digits.charAt(digitIndex)) {
                            textPosition++;
                        } else {
                            // Defensive recovery for pasted text containing a
                            // non-digit separator: remove spaces, then advance.
                            while (textPosition < editable.length()
                                    && editable.charAt(textPosition) == ' ') {
                                editable.delete(textPosition, textPosition + 1);
                            }
                            if (textPosition < editable.length()) textPosition++;
                        }
                    }
                }

                // Remove stray trailing spaces, if any.
                while (textPosition < editable.length()) {
                    if (editable.charAt(textPosition) == ' ') {
                        editable.delete(textPosition, textPosition + 1);
                    } else {
                        textPosition++;
                    }
                }
            }
        } catch (RuntimeException formattingError) {
            // Formatting must never close the app. The raw digits remain readable
            // and readAmount() ignores separators when calculating.
        } finally {
            formattingAmount = false;
        }

        // Main inputs recalculate once using the final digit value. The sales
        // entry field is read when its Add button is pressed.
        if (input == productInput || input == upfrontInput) recalculate();
    }

    private void normalizeAmountInput(EditText input) {
        if (input == null || input.getText() == null) return;
        try {
            BaseInputConnection.removeComposingSpans(input.getText());
        } catch (RuntimeException ignored) {
            // Continue with normalizing the numeric text.
        }
        formatAmountInputInPlace(input);
    }

    private GradientDrawable amountFieldBackground() {
        int[] colors = darkMode
                ? new int[]{0xD92D4136, 0xBC1C2D24}
                : new int[]{0xCFFFFFFF, 0xAFFFFFFF};
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        d.setCornerRadius(dp(13));
        d.setStroke(dp(1), darkMode ? 0x88D9F7E5 : 0xBFFFFFFF);
        return d;
    }

    private GradientDrawable glassTintBackground() {
        int[] colors = darkMode
                ? new int[]{0xC92F7650, 0xA81C4934}
                : new int[]{0xCDEBFAF0, 0xA8D9F0E2};
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        d.setCornerRadius(dp(14));
        d.setStroke(dp(1), darkMode ? 0x885CE09C : 0xDFFFFFFF);
        return d;
    }

    private GradientDrawable glassButtonBackground(boolean primary) {
        int[] colors;
        if (primary) {
            colors = darkMode
                    ? new int[]{0xF22CCB81, 0xE80A7950}
                    : new int[]{0xF20DBD5B, 0xE8078B45};
        } else {
            colors = darkMode
                    ? new int[]{0xD9345945, 0xB91B2D23}
                    : new int[]{0xEFFFFFFF, 0xBFEAF5EF};
        }
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        d.setCornerRadius(dp(15));
        d.setStroke(dp(1), darkMode ? 0x887FE4B0 : (primary ? 0xBFFFFFFF : 0xEFFFFFFF));
        return d;
    }

    private TextView fieldLabel(String value) {
        TextView label = text(value, 10, TEXT, true);
        label.setPadding(dp(1), 0, 0, dp(5));
        return label;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(dp(strokeWidth), strokeColor);
        return drawable;
    }

    private void addSpace(LinearLayout parent, int heightDp) {
        View spacer = new View(this);
        parent.addView(spacer, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private class GlassBackdropView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlassBackdropView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();

            int[] backdropColors = darkMode
                    ? new int[]{0xFF0B1711, 0xFF13271D, 0xFF10231A}
                    : new int[]{0xFFF3FBF6, 0xFFE7F5ED, 0xFFE2F1E9};
            paint.setShader(new LinearGradient(0, 0, w * 0.35f, h,
                    backdropColors, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            if (darkMode) {
                drawGlow(canvas, w * 0.90f, h * 0.10f, w * 0.72f,
                        new int[]{0x5532D889, 0x2225A66B, 0x0025A66B});
                drawGlow(canvas, w * 0.05f, h * 0.46f, w * 0.62f,
                        new int[]{0x22B8F2D0, 0x0CFFFFFF, 0x00FFFFFF});
                drawGlow(canvas, w * 0.83f, h * 0.80f, w * 0.78f,
                        new int[]{0x4430BB79, 0x1A287E57, 0x00287E57});
                drawGlow(canvas, w * 0.15f, h * 0.95f, w * 0.52f,
                        new int[]{0x1FFFFFFF, 0x08FFFFFF, 0x00FFFFFF});
            } else {
                drawGlow(canvas, w * 0.90f, h * 0.10f, w * 0.72f,
                        new int[]{0x665EE59A, 0x2255C982, 0x0055C982});
                drawGlow(canvas, w * 0.05f, h * 0.46f, w * 0.62f,
                        new int[]{0x55FFFFFF, 0x1FFFFFFF, 0x00FFFFFF});
                drawGlow(canvas, w * 0.83f, h * 0.80f, w * 0.78f,
                        new int[]{0x4438C979, 0x1A8FD1AA, 0x008FD1AA});
                drawGlow(canvas, w * 0.15f, h * 0.95f, w * 0.52f,
                        new int[]{0x33FFFFFF, 0x11FFFFFF, 0x00FFFFFF});
            }
        }

        private void drawGlow(Canvas canvas, float x, float y, float radius, int[] colors) {
            paint.setShader(new RadialGradient(x, y, radius, colors, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, radius, paint);
            paint.setShader(null);
        }
    }

    private class GlassPanel extends LinearLayout {
        private final float radiusDp;
        private final boolean emerald;
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlassPanel(Context context, boolean emerald, float radiusDp) {
            super(context);
            this.emerald = emerald;
            this.radiusDp = radiusDp;
            setWillNotDraw(false);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setBackground(makeBackground());
            setElevation(dp(5));
            setClipToOutline(true);
        }

        private GradientDrawable makeBackground() {
            GradientDrawable d;
            if (emerald) {
                d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[]{0xF20CC05A, 0xED079A49, 0xF006803C});
                d.setStroke(dp(1), 0xBFFFFFFF);
            } else if (darkMode) {
                d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[]{0xD92E4438, 0xB91B2D24, 0xC91D3A2C});
                d.setStroke(dp(1), 0x8AFFFFFF);
            } else {
                d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[]{0xCFFFFFFF, 0xAFFFFFFF, 0xBFEFF8F2});
                d.setStroke(dp(1), 0xEFFFFFFF);
            }
            d.setCornerRadius(dp(radiusDp));
            return d;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float r = dp(radiusDp);

            Path clip = new Path();
            clip.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(clip);

            sheen.setShader(new LinearGradient(0, 0, 0, h * 0.78f,
                    emerald
                            ? new int[]{0x44FFFFFF, 0x13FFFFFF, 0x00FFFFFF}
                            : (darkMode
                                ? new int[]{0x33D7FFE6, 0x1599D6B4, 0x0099D6B4}
                                : new int[]{0x66FFFFFF, 0x24FFFFFF, 0x00FFFFFF}),
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h * 0.78f, sheen);
            sheen.setShader(null);

            sheen.setShader(new RadialGradient(w * 0.12f, h * 0.04f, w * 0.72f,
                    new int[]{emerald ? 0x38FFFFFF : (darkMode ? 0x2A9BF2C3 : 0x42FFFFFF), 0x00FFFFFF},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h * 0.55f, sheen);
            sheen.setShader(null);

            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(dp(1));
            rim.setColor(emerald ? 0x88FFFFFF : (darkMode ? 0x7797DDB6 : 0xBFFFFFFF));
            canvas.drawPath(clip, rim);
            rim.setStyle(Paint.Style.FILL);

            canvas.restoreToCount(save);
        }
    }
    // ================== AKSO SAVDO ILHOMI DRAWER ==================

    private static final String SALES_FEED_PREFS = "akso_sales_inspiration";
    private static final String SALES_FEED_KEY = "feed_json";
    private static final String SALES_FEED_UPDATED_KEY = "feed_updated";
    private static final String SALES_FEED_URL =
            "https://raw.githubusercontent.com/Akhror4ek/akso-tolov-kalkulyatori/feature/wasyt-seller-workflow/akso_sales_feed.json";

    private static String defaultSalesFeedJson() {
        return "{\n  \"version\": 2,\n  \"source\": \"AKSO\",\n  \"items\": [\n    {\n      \"id\": \"first_impression\",\n      \"title\": \"Birinchi taassurot ishonch uyg‘otadi\",\n      \"body\": \"Mijoz kirishi bilan samimiy salom bering, lekin darhol mahsulotni maqtashga o‘tmang. Unga erkin ko‘zdan kechirish uchun imkon bering. So‘ng nimani izlayotgani, kim foydalanishi va qaysi muddatda kerakligini ochiq savollar bilan aniqlang. Dastlabki suhbatning maqsadi sotish emas, ehtiyojni to‘g‘ri tushunishdir.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1556742049-0cfed4f6a45d?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"listen_first\",\n      \"title\": \"Avval tinglang, keyin tavsiya bering\",\n      \"body\": \"“Siz uchun eng muhimi nima?” yoki “Hozirgi mahsulotingizda qaysi jihat noqulay?” kabi savollar bering. Javobni bo‘lmasdan tinglang va eshitganingizni qisqacha qaytaring. Shu yo‘l bilan mijozga mos bo‘lmagan qimmat yoki ortiqcha funksiyali mahsulotni tavsiya qilish ehtimoli kamayadi.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1556761175-b413da4baf72?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"three_options\",\n      \"title\": \"Uch xil variantni to‘g‘ri taqqoslang\",\n      \"body\": \"Mijozga tejamkor, muvozanatli va imkoniyati kengroq variantni ko‘rsating. Har birining 2–3 muhim farqini bir xil mezon bo‘yicha tushuntiring: sig‘im, energiya sarfi, kafolat yoki foydalanish qulayligi. Eng qimmat modelni avtomatik ravishda eng yaxshi deb ko‘rsatmang.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1441986300917-64674bd600d8?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"value_not_price\",\n      \"title\": \"Faqat narxni emas, kundalik foydani tushuntiring\",\n      \"body\": \"Mahsulotning narxini aytish bilan cheklanib qolmang. U mijozning vaqtini tejaydimi, ishni yengillashtiradimi yoki oilaning ehtiyojiga qay darajada mos keladimi — shuni aniq misol bilan ko‘rsating. Foydani isbotlanmagan va’dalar bilan emas, mahsulotning real xususiyatlari bilan bog‘lang.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1497366754035-f200968a6e72?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"budget_questions\",\n      \"title\": \"Budjetni noqulaylik tug‘dirmasdan aniqlang\",\n      \"body\": \"“Qancha pulingiz bor?” deb keskin so‘rash o‘rniga, “Qaysi narx oralig‘idagi variantlarni ko‘rib chiqamiz?” deb so‘rang. Naqd xarid va bo‘lib to‘lash imkoniyatini alohida tushuntiring. Mijoz aytgan chegarani hurmat qiling va unga mos variantlarni birinchi navbatda ko‘rsating.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1542838132-92c53300491e?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"washing_capacity\",\n      \"title\": \"Kir yuvish mashinasida sig‘imni ehtiyojga moslang\",\n      \"body\": \"Kilogramm ko‘rsatkichi bir yuvish siklida quruq kirning tavsiya etilgan yuklamasini bildiradi. Oila a’zolari soni, ko‘rpa-to‘shak yuvish odati va joy o‘lchamini so‘rang. Faqat katta sig‘imni maqtamang: ortiqcha katta model xarajat va joy talabini oshirishi mumkin.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1516321318423-f06f85e504b3?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"refrigerator_layout\",\n      \"title\": \"Muzlatkichda faqat litrga qarab tanlamang\",\n      \"body\": \"Umumiy hajm bilan birga muzlatkich va sovutkich bo‘limlari nisbatini, javonlar joylashuvini, eshik ochilish yo‘nalishini va oshxonadagi o‘lchamni tekshiring. No Frost, inverter yoki energiya sinfi kabi xususiyatlarni mijoz foydalanish odatiga bog‘lab izohlang.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1521737711867-e3b97375f902?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"vacuum_filter\",\n      \"title\": \"Changyutgichda quvvat va filtrlashni farqlang\",\n      \"body\": \"Elektr quvvati, so‘rish samaradorligi va filtr turi bir xil tushuncha emas. Mijozda gilam, qattiq pol yoki uy hayvoni bor-yo‘qligini aniqlang. Chang idishi hajmi, filtrni tozalash va sarflanadigan qismlar narxini ham ayting — bu keyingi foydalanish xarajatlarini tushunishga yordam beradi.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1552664730-d307ca884978?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"air_conditioner_area\",\n      \"title\": \"Konditsionerni xona maydoniga mos tanlang\",\n      \"body\": \"Xonaning kvadrati bilan birga shift balandligi, quyosh tushishi, deraza maydoni va xonadagi odamlar sonini ham so‘rang. Quvvatni taxmin bilan belgilamang; kerak bo‘lsa mutaxassisga hisoblatishni taklif qiling. O‘rnatish joyi, drenaj va montaj xarajatlarini oldindan aniqlashtiring.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1522071820081-009f0129c71c?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"tv_distance\",\n      \"title\": \"Televizor tanlashda ko‘rish masofasini inobatga oling\",\n      \"body\": \"Ekran diagonali, xona kattaligi va tomosha masofasini birga ko‘rib chiqing. 4K, HDR, yangilanish chastotasi va Smart TV imkoniyatlarini mijoz haqiqatda ishlatadigan kontent bilan bog‘lab tushuntiring. Faqat texnik atamalarni sanash o‘rniga amaliy farqni ko‘rsating.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1551836022-d5d88e9218df?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"furniture_measure\",\n      \"title\": \"Mebel xaridida o‘lchovni ikki marta tekshiring\",\n      \"body\": \"Xonaning eni, bo‘yi, eshik va yo‘laklardan o‘tish imkoniyatini oldindan aniqlang. Mebelning tashqi o‘lchamlarini, modul joylashuvini va eshik ochilishiga xalaqit bermasligini tekshiring. Suratga qarabgina “albatta sig‘adi” deb va’da bermang.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1497366216548-37526070297c?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"carpet_material\",\n      \"title\": \"Gilamni tanlashda material va parvarishni tushuntiring\",\n      \"body\": \"Gilamning tuk balandligi, zichligi, materiali va tag qismi uning ko‘rinishi hamda parvarishiga ta’sir qiladi. Uyda bolalar yoki uy hayvonlari bo‘lsa, tozalash qulayligini ham muhokama qiling. Rangni do‘kon yoritilishi va uy sharoitida farq qilishi mumkinligini eslatib qo‘ying.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1542744173-8e7e53415bb0?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"installment_clear\",\n      \"title\": \"Bo‘lib to‘lash shartlarini to‘liq ochiqlang\",\n      \"body\": \"Boshlang‘ich to‘lov, muddat, ustama foizi, oylik to‘lov va yakuniy summani birgalikda ko‘rsating. Mijoz faqat kichik oylik raqamni ko‘rib qaror qilmasin. Hisob-kitobni kalkulyatorda qayta tekshiring va shartnomadagi ma’lumot bilan mosligini tasdiqlang.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1553877522-43269d4ea984?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"monthly_vs_total\",\n      \"title\": \"Oylik to‘lov va jami xarajatni aralashtirmang\",\n      \"body\": \"Oylik to‘lovning kichikligi mahsulot arzon degani emas. Mijoz bilan naqd narx, boshlang‘ich to‘lov, bo‘lib to‘lashdagi jami summa va muddatni yonma-yon solishtiring. Har qanday komissiya yoki qo‘shimcha xarajat mavjud bo‘lsa, uni yashirmasdan ayting.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1486312338219-ce68d2c6f44d?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"discount_honesty\",\n      \"title\": \"Chegirmani faqat tasdiqlangan shartda taklif qiling\",\n      \"body\": \"Chegirma berish vakolati, amal qilish muddati va qaysi mahsulotlarga tegishli ekanini tekshiring. “Faqat hozir” kabi sun’iy shoshiltirishdan foydalanmang. Aksiya sharti mijozga tushunarli bo‘lishi va yakuniy narxda to‘g‘ri aks etishi kerak.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1498050108023-c5249f4df085?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"stock_check\",\n      \"title\": \"Qoldiqni va’da berishdan oldin tekshiring\",\n      \"body\": \"Rafdagi mahsulot, ombor qoldig‘i va tizimdagi son har doim bir xil bo‘lmasligi mumkin. Aynan model, rang va komplektatsiyani tekshiring. Yetarli qoldiq bo‘lmasa, mas’ul xodim bilan aniqlashtirib, mijozga real muddat va muqobil variantni ayting.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1519389950473-47ba0277781c?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"delivery_promise\",\n      \"title\": \"Yetkazib berish vaqtini aniq kelishib oling\",\n      \"body\": \"Yetkazish hududi, sana, vaqt oralig‘i, ko‘tarib chiqish va o‘rnatish xizmatlari alohida aniqlashtirilishi kerak. “Bugun yetadi” deb tasdiqsiz va’da bermang. Mijozga kelishilgan shartlarni buyurtmada qayd eting va aloqa raqamini tekshiring.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1504384308090-c894fdcc538d?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"warranty_documents\",\n      \"title\": \"Kafolat haqida hujjatga tayangan holda gapiring\",\n      \"body\": \"Kafolat muddati va qamrovi mahsulot turi hamda ishlab chiqaruvchi shartlariga bog‘liq. Modelni aniqlab, rasmiy kafolat taloni yoki ishlab chiqaruvchi ma’lumotini tekshiring. Kafolat barcha nosozliklarni qamrab oladi deb umumiy va’da bermang.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1552581234-26160f608093?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"demo_safely\",\n      \"title\": \"Mahsulotni xavfsiz va maqsadli namoyish qiling\",\n      \"body\": \"Avval mijoz qaysi funksiyani ko‘rmoqchi ekanini so‘rang. Qurilmani yo‘riqnomaga muvofiq, xavfsizlik qoidalarini buzmasdan namoyish qiling. Har bir tugmani sanab chiqish o‘rniga, mijozga muhim bo‘lgan 2–3 funksiyaning amaliy foydasini ko‘rsating.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1560472354-b33ff0c44a43?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"handle_objection\",\n      \"title\": \"E’tirozni bahsga aylantirmang\",\n      \"body\": \"Mijozning “qimmat”, “o‘ylab ko‘raman” yoki “boshqa joyda arzon” degan gapiga darhol qarshi chiqmang. E’tirozning sababini aniqlashtiring: budjetmi, ishonchmi yoki mahsulot farqimi? Shundan keyingina dalil, muqobil model yoki qayta hisob-kitob taklif qiling.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1507679799987-c73779587ccf?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"complaint_first\",\n      \"title\": \"Shikoyatni avval xolis qayd eting\",\n      \"body\": \"Mijozning gapini bo‘lmasdan tinglang, mahsulot modeli, xarid sanasi, hujjatlar va muammo qachon boshlanganini aniqlang. Tekshiruvdan oldin aybni mijozga yoki ishlab chiqaruvchiga yuklamang. Keyingi qadam, mas’ul xodim va taxminiy aloqa muddatini tushuntiring.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1524758631624-e2822e304c36?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"compatible_accessories\",\n      \"title\": \"Aksessuar mosligini tekshirib taklif qiling\",\n      \"body\": \"Qo‘shimcha mahsulot asosiy qurilmaga texnik va o‘lcham jihatidan mos kelishi kerak. Foydasi, narxi va zarurligini tushuntiring; mijoz rad etsa, bosim qilmang. Mosligi tekshirilmagan aksessuarni faqat qo‘shimcha savdo uchun tavsiya qilish ishonchni pasaytiradi.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1523275335684-37898b6baf30?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"product_care\",\n      \"title\": \"Xariddan keyingi parvarish bo‘yicha qisqa yo‘l-yo‘riq bering\",\n      \"body\": \"Mijozga mahsulotni ilk ishga tushirish, tozalash va xavfsiz foydalanish bo‘yicha ishlab chiqaruvchi tavsiyalarini eslating. Elektr jihozlari uchun kuchlanish, shamollatish yoki suv ulanishi kabi talablarni faqat yo‘riqnomaga tayangan holda tushuntiring.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1526170375885-4d8ecf77b99f?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"price_label\",\n      \"title\": \"Narx yorlig‘i tushunarli va to‘liq bo‘lsin\",\n      \"body\": \"Yorliqda mahsulot nomi yoki modeli, naqd narx va bo‘lib to‘lash shartlari chalkashmasdan ko‘rsatilishi kerak. Oylik to‘lov yonida muddat va jami summani ham aniq yozing. Eskirgan yorliqni vaqtida almashtiring — noto‘g‘ri narx mijoz ishonchiga zarar yetkazadi.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1494438639946-1ebd1d20bf85?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"customer_privacy\",\n      \"title\": \"Mijoz ma’lumotlarini ehtiyot qiling\",\n      \"body\": \"Telefon raqami, manzil, shaxsiy hujjat yoki to‘lov ma’lumotlarini faqat zarur ish jarayonida va vakolatli tizimda ishlating. Mijoz roziligisiz uning ma’lumotlarini begonalarga yubormang. Ekran yoki hujjatlarni boshqa mijoz ko‘rmaydigan tarzda tuting.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1517248135467-4c7edcad34c4?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"no_pressure\",\n      \"title\": \"Mijozga qaror qilish uchun vaqt bering\",\n      \"body\": \"Xaridni tezlashtirish uchun asossiz tanqislik yoki yolg‘on aksiya haqida gapirmang. Mijoz ikkilanayotgan bo‘lsa, qaysi savol ochiq qolganini aniqlang va kerakli ma’lumotni bering. Qaror mijozning ehtiyoji va moliyaviy imkoniyatiga mos bo‘lishi kerak.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1503602642458-232111445657?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"closing_check\",\n      \"title\": \"Buyurtmani yakunlashdan oldin barcha bandlarni tekshiring\",\n      \"body\": \"Model, rang, soni, narx, chegirma, boshlang‘ich to‘lov, muddat, oylik va jami summa, yetkazish manzili hamda kafolatni mijoz bilan qayta tasdiqlang. Ayniqsa, og‘zaki kelishuv bilan tizimga kiritilgan ma’lumotlar bir-biriga mosligini tekshiring.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1555041469-a586c61ea9bc?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"follow_up\",\n      \"title\": \"Savdodan keyingi aloqa takroriy xaridga zamin yaratadi\",\n      \"body\": \"Mahsulot yetkazilgach, mijozdan hammasi joyida ekanini so‘rash uchun qulay aloqa vaqtini kelishib oling. Muammo bo‘lsa, murojaat yo‘lini ko‘rsating. Keraksiz reklama xabarlarini yubormang; keyingi takliflar mijozning roziligi va haqiqiy ehtiyojiga mos bo‘lsin.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1493663284031-b7e3aefcae8e?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"team_handover\",\n      \"title\": \"Mijozni boshqa xodimga topshirganda kontekstni yo‘qotmang\",\n      \"body\": \"Masala boshqa mutaxassisga o‘tsa, mijozning ehtiyoji, ko‘rilgan modellar, kelishilgan narx va ochiq savollarni qisqacha yetkazing. Mijozdan barcha ma’lumotni qayta-qayta so‘rash ishonchni pasaytiradi. Topshirishdan oldin kim javob berishini va qachon bog‘lanishini aniqlang.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1519710164239-da123dc03ef4?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"repeat_customer\",\n      \"title\": \"Doimiy mijozga avvalgi tajribasini inobatga olib xizmat qiling\",\n      \"body\": \"Mijoz oldin xarid qilgan mahsulot yoki avvalgi murojaatini eslasa, tegishli ma’lumotni ruxsat etilgan tizimdan tekshiring. Uning odatlari asosida foydali variant taklif qiling, lekin shaxsiy ma’lumotlardan noo‘rin foydalanmang. E’tiborli xizmat sodiqlikni kuchaytiradi.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1505693416388-ac5ce068fe85?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"kitchen_fit\",\n      \"title\": \"Oshxona texnikasida ulanish shartlarini oldindan aniqlang\",\n      \"body\": \"Gaz, suv, elektr quvvati, ventilyatsiya va o‘rnatish o‘lchamlarini tegishli mahsulot yo‘riqnomasi asosida tekshiring. Kerakli kommunikatsiya tayyor bo‘lmasa, qurilmani ulashga va’da bermang. O‘rnatish mutaxassis talab qilsa, buni xariddan oldin ayting.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1556911220-e15b29be8c8f?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    }\n  ]\n}";
    }

    /** Schedules one initial sync plus a periodic network-constrained background refresh. */
    private void scheduleSalesContentRefresh() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                SalesContentWorker.class, 1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(getApplicationContext()).enqueueUniquePeriodicWork(
                "akso_hourly_sales_inspiration", ExistingPeriodicWorkPolicy.KEEP, periodic);

        OneTimeWorkRequest initial = new OneTimeWorkRequest.Builder(SalesContentWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(
                "akso_sales_inspiration_one_time_sync", ExistingWorkPolicy.KEEP, initial);
    }

    private void openSalesInspirationDrawer() {
        if (drawerOverlay != null || drawerHost == null) return;

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        drawerWidthPx = Math.min(screenWidth, Math.round(screenWidth * 0.85f));

        drawerOverlay = new FrameLayout(this);
        drawerOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(darkMode ? 0xC5000000 : 0x79071910);
        drawerScrim.setAlpha(0f);
        drawerScrim.setOnClickListener(v -> closeSalesInspirationDrawer());
        drawerOverlay.addView(drawerScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        GlassPanel panel = new GlassPanel(this, false, 22);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(13), dp(14), dp(14));
        panel.setElevation(dp(22));
        drawerPanel = panel;

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text("AKSO  /  SAVDO ILHOMI", 11, DARK_GREEN, true);
        heading.setLetterSpacing(0.08f);
        titleRow.addView(heading, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button closeButton = calculatorButton("✕", 12, false, true);
        titleRow.addView(closeButton, new LinearLayout.LayoutParams(dp(38), dp(38)));
        closeButton.setOnClickListener(v -> closeSalesInspirationDrawer());
        panel.addView(titleRow, matchWrap());

        ScrollView contentScroll = new ScrollView(this);
        contentScroll.setFillViewport(false);
        contentScroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        contentScroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(contentScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout imageFrame = new FrameLayout(this);
        GradientDrawable imageBackground = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                darkMode ? new int[]{0xFF244A36, 0xFF11271C}
                        : new int[]{0xFFD8F1E2, 0xFFB8E2C9});
        imageBackground.setCornerRadius(dp(17));
        imageFrame.setBackground(imageBackground);
        imageFrame.setClipToOutline(true);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(205));
        imageParams.topMargin = dp(4);
        content.addView(imageFrame, imageParams);

        drawerHeroImage = new ImageView(this);
        drawerHeroImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageFrame.addView(drawerHeroImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        drawerImageFallback = text("🛍️\nAKSO  /  SAVDO", 18,
                darkMode ? 0xFFD4FBE4 : DARK_GREEN, true);
        drawerImageFallback.setGravity(Gravity.CENTER);
        drawerImageFallback.setLineSpacing(dp(5), 1f);
        imageFrame.addView(drawerImageFallback, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView caption = text("SAVDO VA MIJOZLAR BILAN ISHLASH", 8, MUTED, true);
        caption.setLetterSpacing(0.08f);
        caption.setPadding(dp(2), dp(12), dp(2), dp(6));
        content.addView(caption, matchWrap());

        GlassPanel article = new GlassPanel(this, false, 17);
        article.setOrientation(LinearLayout.VERTICAL);
        article.setPadding(dp(13), dp(12), dp(13), dp(13));
        LinearLayout.LayoutParams articleParams = matchWrap();
        articleParams.topMargin = dp(2);
        content.addView(article, articleParams);

        TextView articleLabel = text("SOATLIK SAVDO MASLAHATI", 9, DARK_GREEN, true);
        articleLabel.setLetterSpacing(0.07f);
        article.addView(articleLabel, matchWrap());

        drawerArticleTitle = text("Savdo bo‘yicha foydali maslahat", 16, TEXT, true);
        drawerArticleTitle.setGravity(Gravity.TOP);
        drawerArticleTitle.setPadding(0, dp(8), 0, dp(7));
        article.addView(drawerArticleTitle, matchWrap());

        drawerArticleBody = text("Mijoz ehtiyojini tinglang, mahsulotning foydasini tushuntiring va to‘lov shartlarini ochiq ayting.",
                11, TEXT, false);
        drawerArticleBody.setGravity(Gravity.TOP);
        drawerArticleBody.setLineSpacing(dp(3), 1f);
        article.addView(drawerArticleBody, matchWrap());

        drawerUpdateLabel = text("Oflayn foydalanish uchun oxirgi ma’lumot saqlanadi.", 8, MUTED, false);
        drawerUpdateLabel.setPadding(dp(2), dp(11), dp(2), 0);
        content.addView(drawerUpdateLabel, matchWrap());

        drawerNextArticleButton = calculatorButton("KEYINGI MASLAHAT  →", 10, true, false);
        LinearLayout.LayoutParams nextArticleParams = matchWrap();
        nextArticleParams.topMargin = dp(12);
        content.addView(drawerNextArticleButton, nextArticleParams);
        drawerNextArticleButton.setOnClickListener(v -> {
            drawerSelectedArticleIndex++;
            refreshSalesDrawerCard();
            contentScroll.smoothScrollTo(0, imageFrame.getTop());
        });

        // Seller knowledge quiz is opened as a separate section from this menu.
        addSellerQuizLaunchButton(content);

        drawerOverlay.addView(panel, new FrameLayout.LayoutParams(
                drawerWidthPx, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START | Gravity.TOP));
        drawerHost.addView(drawerOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel.setTranslationX(-drawerWidthPx);
        drawerScrim.animate().alpha(1f).setDuration(220).start();
        panel.animate().translationX(0).setDuration(190)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mainScrollView != null) {
            mainScrollView.setRenderEffect(RenderEffect.createBlurEffect(
                    dp(3.5f), dp(3.5f), Shader.TileMode.CLAMP));
        }
        refreshSalesDrawerCard();
        enqueueSalesContentSyncIfStale();
        scheduleDrawerCachePoll();
        scheduleDrawerHourlyRefresh();
    }



    // ==================== AKSO PERSONAL WALLET ====================

    private String walletAccountName(String account) {
        return "card".equals(account) ? "Karta" : "Naqd";
    }

    private long walletOpeningBalance(String account) {
        String key = "card".equals(account) ? WALLET_OPENING_CARD_KEY : WALLET_OPENING_CASH_KEY;
        return walletPreferences.getLong(key, 0L);
    }

    private JSONArray readWalletTransactions() {
        try {
            return new JSONArray(walletPreferences.getString(WALLET_TRANSACTIONS_KEY, "[]"));
        } catch (JSONException error) {
            return new JSONArray();
        }
    }

    private long walletAccountBalance(String account) {
        long balance = walletOpeningBalance(account);
        JSONArray transactions = readWalletTransactions();
        for (int i = 0; i < transactions.length(); i++) {
            JSONObject tx = transactions.optJSONObject(i);
            if (tx == null) continue;
            long amount = tx.optLong("amount", 0L);
            String type = tx.optString("type", "");
            String txAccount = tx.optString("account", "cash");
            if ("income".equals(type) && account.equals(txAccount)) balance += amount;
            else if ("expense".equals(type) && account.equals(txAccount)) balance -= amount;
            else if ("transfer".equals(type)) {
                String to = tx.optString("to_account", "card");
                if (account.equals(txAccount)) balance -= amount;
                if (account.equals(to)) balance += amount;
            }
        }
        return balance;
    }

    private long walletTotalBalance() {
        return walletAccountBalance("cash") + walletAccountBalance("card");
    }

    private long parseWalletAmount(EditText input) {
        if (input == null || input.getText() == null) return 0L;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.getText().charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
        }
        if (digits.length() == 0) return 0L;
        try { return Long.parseLong(digits.toString()); }
        catch (NumberFormatException error) { return 0L; }
    }

    private EditText walletAmountField(String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(16);
        field.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        field.setTextColor(DARK_GREEN);
        field.setHintTextColor(MUTED);
        field.setHint(hint);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setPadding(dp(11), 0, dp(11), 0);
        field.setBackground(amountFieldBackground());
        return field;
    }

    private EditText walletNoteField(String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(11);
        field.setTextColor(TEXT);
        field.setHintTextColor(MUTED);
        field.setHint(hint);
        field.setPadding(dp(11), 0, dp(11), 0);
        field.setBackground(amountFieldBackground());
        return field;
    }

    /** Opens the personal wallet as a dedicated, scrollable full-screen app section. */
    private void showPersonalWalletScreen() {
        if (personalWalletDialog != null && personalWalletDialog.isShowing()) return;

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(PAGE);
        GlassBackdropView backdrop = new GlassBackdropView(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        GlassPanel screen = new GlassPanel(this, false, 22);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(13), dp(10), dp(13), dp(12));
        FrameLayout.LayoutParams screenParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        screenParams.setMargins(dp(7), dp(7), dp(7), dp(7));
        root.addView(screen, screenParams);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text("💰  AKSO  /  SHAXSIY HAMYON", 11, DARK_GREEN, true);
        heading.setLetterSpacing(0.035f);
        topBar.addView(heading, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button closeButton = calculatorButton("✕", 13, false, true);
        closeButton.setContentDescription("Hamyondan chiqish");
        topBar.addView(closeButton, new LinearLayout.LayoutParams(dp(38), dp(38)));
        closeButton.setOnClickListener(v -> dialog.dismiss());
        screen.addView(topBar, matchWrap());

        TextView intro = text("Naqd pul, bank kartalari, daromad va xarajatlaringizni bir joyda boshqaring.",
                8, MUTED, false);
        intro.setPadding(dp(2), 0, dp(2), dp(7));
        intro.setLineSpacing(dp(2), 1f);
        screen.addView(intro, matchWrap());

        ScrollView walletScroll = new ScrollView(this);
        walletScroll.setFillViewport(false);
        walletScroll.setVerticalScrollBarEnabled(false);
        LinearLayout walletContent = new LinearLayout(this);
        walletContent.setOrientation(LinearLayout.VERTICAL);
        walletScroll.addView(walletContent, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        screen.addView(walletScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Reuse the existing wallet dashboard and transaction handlers without duplicating data logic.
        addPersonalWalletSection(walletContent);

        TextView footer = text("Ma’lumotlaringiz ushbu qurilmada saqlanadi.", 7, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(5), 0, 0);
        screen.addView(footer, matchWrap());

        dialog.setContentView(root);
        final View activityContent = findViewById(android.R.id.content);
        final boolean blurApplied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && activityContent != null;
        if (blurApplied) {
            try {
                activityContent.setRenderEffect(RenderEffect.createBlurEffect(
                        dp(8), dp(8), Shader.TileMode.CLAMP));
            } catch (RuntimeException ignored) {
                // The wallet remains fully usable even when backdrop blur is unsupported.
            }
        }
        dialog.setOnDismissListener(d -> {
            if (blurApplied && activityContent != null) {
                try { activityContent.setRenderEffect(null); }
                catch (RuntimeException ignored) { /* Ignore unsupported renderer cleanup. */ }
            }
            if (personalWalletDialog == dialog) personalWalletDialog = null;
            walletTotalValue = null;
            walletCashValue = null;
            walletCardValue = null;
            walletMonthIncomeValue = null;
            walletMonthExpenseValue = null;
            walletMonthLabel = null;
            walletLegendContainer = null;
            walletHistoryContainer = null;
            walletDonutChart = null;
        });
        dialog.show();
        personalWalletDialog = dialog;

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.width = ViewGroup.LayoutParams.MATCH_PARENT;
            attributes.height = ViewGroup.LayoutParams.MATCH_PARENT;
            attributes.gravity = Gravity.CENTER;
            attributes.dimAmount = darkMode ? 0.30f : 0.18f;
            window.setAttributes(attributes);
            window.setStatusBarColor(PAGE);
            window.setNavigationBarColor(PAGE);
            int systemUiFlags = darkMode ? 0
                    : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            window.getDecorView().setSystemUiVisibility(systemUiFlags);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

    }

    private void addPersonalWalletSection(LinearLayout parent) {
        GlassPanel wallet = new GlassPanel(this, false, 19);
        wallet.setOrientation(LinearLayout.VERTICAL);
        wallet.setPadding(dp(12), dp(11), dp(12), dp(12));
        LinearLayout.LayoutParams walletParams = matchWrap();
        walletParams.topMargin = dp(10);
        walletParams.bottomMargin = dp(10);
        parent.addView(wallet, walletParams);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("💰  SHAXSIY HAMYON", 10, DARK_GREEN, true);
        title.setLetterSpacing(0.045f);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(29), 1f));
        Button openingButton = calculatorButton("⚙ Qoldiq", 8, false, true);
        titleRow.addView(openingButton, new LinearLayout.LayoutParams(dp(83), dp(30)));
        openingButton.setOnClickListener(v -> showWalletOpeningDialog());
        wallet.addView(titleRow, matchWrap());

        TextView totalCaption = text("UMUMIY BALANS", 8, MUTED, true);
        totalCaption.setLetterSpacing(0.08f);
        totalCaption.setPadding(dp(2), dp(7), dp(2), 0);
        wallet.addView(totalCaption, matchWrap());
        walletTotalValue = text("0 so‘m", 22, DARK_GREEN, true);
        walletTotalValue.setPadding(dp(2), 0, dp(2), dp(3));
        wallet.addView(walletTotalValue, matchWrap());

        LinearLayout accountsRow = new LinearLayout(this);
        accountsRow.setOrientation(LinearLayout.HORIZONTAL);
        accountsRow.setGravity(Gravity.CENTER_VERTICAL);
        wallet.addView(accountsRow, matchWrap());
        GlassPanel cashCard = new GlassPanel(this, false, 12);
        cashCard.setOrientation(LinearLayout.VERTICAL);
        cashCard.setPadding(dp(9), dp(7), dp(8), dp(7));
        accountsRow.addView(cashCard, new LinearLayout.LayoutParams(0, dp(61), 1f));
        TextView cashCaption = text("💵 NAQD", 8, MUTED, true);
        cashCard.addView(cashCaption, matchWrap());
        walletCashValue = text("0 so‘m", 13, DARK_GREEN, true);
        cashCard.addView(walletCashValue, matchWrap());

        View accountGap = new View(this);
        accountsRow.addView(accountGap, new LinearLayout.LayoutParams(dp(7), 1));
        GlassPanel bankCard = new GlassPanel(this, false, 12);
        bankCard.setOrientation(LinearLayout.VERTICAL);
        bankCard.setPadding(dp(9), dp(7), dp(8), dp(7));
        accountsRow.addView(bankCard, new LinearLayout.LayoutParams(0, dp(61), 1f));
        TextView cardCaption = text("💳 KARTA", 8, MUTED, true);
        bankCard.addView(cardCaption, matchWrap());
        walletCardValue = text("0 so‘m", 13, DARK_GREEN, true);
        bankCard.addView(walletCardValue, matchWrap());

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionParams = matchWrap();
        actionParams.topMargin = dp(8);
        wallet.addView(actionRow, actionParams);
        Button incomeButton = calculatorButton("＋ DAROMAD", 9, true, false);
        actionRow.addView(incomeButton, new LinearLayout.LayoutParams(0, dp(39), 1f));
        incomeButton.setOnClickListener(v -> showWalletEntryDialog(true));
        View actionGap = new View(this);
        actionRow.addView(actionGap, new LinearLayout.LayoutParams(dp(7), 1));
        Button expenseButton = calculatorButton("− XARAJAT", 9, false, true);
        expenseButton.setTextColor(Color.rgb(190, 61, 65));
        actionRow.addView(expenseButton, new LinearLayout.LayoutParams(0, dp(39), 1f));
        expenseButton.setOnClickListener(v -> showWalletEntryDialog(false));

        LinearLayout secondaryRow = new LinearLayout(this);
        secondaryRow.setOrientation(LinearLayout.HORIZONTAL);
        secondaryRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams secondaryParams = matchWrap();
        secondaryParams.topMargin = dp(6);
        wallet.addView(secondaryRow, secondaryParams);
        Button transferButton = calculatorButton("⇄ O‘tkazma", 8, false, true);
        secondaryRow.addView(transferButton, new LinearLayout.LayoutParams(0, dp(32), 1f));
        transferButton.setOnClickListener(v -> showWalletTransferDialog());
        View secondaryGap = new View(this);
        secondaryRow.addView(secondaryGap, new LinearLayout.LayoutParams(dp(7), 1));
        Button historyButton = calculatorButton("☷ Tarix", 8, false, true);
        secondaryRow.addView(historyButton, new LinearLayout.LayoutParams(0, dp(32), 1f));
        historyButton.setOnClickListener(v -> showWalletHistoryDialog());

        View divider = new View(this);
        divider.setBackgroundColor(darkMode ? 0x557FA18D : 0x5595B8A2);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(10);
        dividerParams.bottomMargin = dp(7);
        wallet.addView(divider, dividerParams);

        walletMonthLabel = text("BU OY", 8, MUTED, true);
        walletMonthLabel.setLetterSpacing(0.08f);
        wallet.addView(walletMonthLabel, matchWrap());
        LinearLayout monthRow = new LinearLayout(this);
        monthRow.setOrientation(LinearLayout.HORIZONTAL);
        monthRow.setGravity(Gravity.CENTER_VERTICAL);
        wallet.addView(monthRow, matchWrap());
        LinearLayout incomeColumn = new LinearLayout(this);
        incomeColumn.setOrientation(LinearLayout.VERTICAL);
        monthRow.addView(incomeColumn, new LinearLayout.LayoutParams(0, dp(43), 1f));
        incomeColumn.addView(text("KIRIM", 7, MUTED, true), matchWrap());
        walletMonthIncomeValue = text("+0 so‘m", 11, DARK_GREEN, true);
        incomeColumn.addView(walletMonthIncomeValue, matchWrap());
        LinearLayout expenseColumn = new LinearLayout(this);
        expenseColumn.setOrientation(LinearLayout.VERTICAL);
        monthRow.addView(expenseColumn, new LinearLayout.LayoutParams(0, dp(43), 1f));
        expenseColumn.addView(text("CHIQIM", 7, MUTED, true), matchWrap());
        walletMonthExpenseValue = text("−0 so‘m", 11, Color.rgb(190, 61, 65), true);
        expenseColumn.addView(walletMonthExpenseValue, matchWrap());

        TextView chartHeading = text("XARAJATLAR TAQSIMOTI", 8, MUTED, true);
        chartHeading.setLetterSpacing(0.07f);
        chartHeading.setPadding(0, dp(4), 0, dp(5));
        wallet.addView(chartHeading, matchWrap());
        LinearLayout chartRow = new LinearLayout(this);
        chartRow.setOrientation(LinearLayout.HORIZONTAL);
        chartRow.setGravity(Gravity.CENTER_VERTICAL);
        wallet.addView(chartRow, matchWrap());
        walletDonutChart = new WalletDonutChart(this);
        chartRow.addView(walletDonutChart, new LinearLayout.LayoutParams(dp(112), dp(112)));
        walletLegendContainer = new LinearLayout(this);
        walletLegendContainer.setOrientation(LinearLayout.VERTICAL);
        walletLegendContainer.setPadding(dp(7), 0, 0, 0);
        chartRow.addView(walletLegendContainer, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView recentHeading = text("SO‘NGGI OPERATSIYALAR", 8, MUTED, true);
        recentHeading.setLetterSpacing(0.07f);
        recentHeading.setPadding(0, dp(8), 0, dp(4));
        wallet.addView(recentHeading, matchWrap());
        walletHistoryContainer = new LinearLayout(this);
        walletHistoryContainer.setOrientation(LinearLayout.VERTICAL);
        wallet.addView(walletHistoryContainer, matchWrap());

        TextView privacy = text("🔒 Ma’lumotlar faqat ushbu telefonda saqlanadi.", 7, MUTED, false);
        privacy.setPadding(0, dp(8), 0, 0);
        wallet.addView(privacy, matchWrap());
        renderWalletSummary();
    }

    private void renderWalletSummary() {
        if (walletTotalValue == null || walletPreferences == null) return;
        long total = walletTotalBalance();
        long cash = walletAccountBalance("cash");
        long card = walletAccountBalance("card");
        walletTotalValue.setText(formatMoney(BigDecimal.valueOf(total)) + " so‘m");
        walletCashValue.setText(formatMoney(BigDecimal.valueOf(cash)) + " so‘m");
        walletCardValue.setText(formatMoney(BigDecimal.valueOf(card)) + " so‘m");

        Calendar now = Calendar.getInstance();
        long monthIncome = 0L;
        long monthExpense = 0L;
        HashMap<String, Long> expenseCategories = new HashMap<>();
        JSONArray transactions = readWalletTransactions();
        for (int i = 0; i < transactions.length(); i++) {
            JSONObject tx = transactions.optJSONObject(i);
            if (tx == null) continue;
            long time = tx.optLong("timestamp", 0L);
            Calendar txDate = Calendar.getInstance();
            txDate.setTimeInMillis(time);
            if (txDate.get(Calendar.YEAR) != now.get(Calendar.YEAR)
                    || txDate.get(Calendar.MONTH) != now.get(Calendar.MONTH)) continue;
            String type = tx.optString("type", "");
            long amount = tx.optLong("amount", 0L);
            if ("income".equals(type)) monthIncome += amount;
            else if ("expense".equals(type)) {
                monthExpense += amount;
                String category = tx.optString("category", "Boshqa");
                Long previous = expenseCategories.get(category);
                expenseCategories.put(category, (previous == null ? 0L : previous) + amount);
            }
        }
        String monthName = new SimpleDateFormat("MMMM yyyy", new Locale("uz", "UZ"))
                .format(now.getTime()).toUpperCase(new Locale("uz", "UZ"));
        walletMonthLabel.setText(monthName + "  ·  OYLIK HISOBOT");
        walletMonthIncomeValue.setText("+" + formatMoney(BigDecimal.valueOf(monthIncome)) + " so‘m");
        walletMonthExpenseValue.setText("−" + formatMoney(BigDecimal.valueOf(monthExpense)) + " so‘m");

        ArrayList<Map.Entry<String, Long>> sorted = new ArrayList<>(expenseCategories.entrySet());
        Collections.sort(sorted, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        ArrayList<WalletSlice> slices = new ArrayList<>();
        int[] palette = {0xFF18A85C, 0xFF4D9CD5, 0xFFF1B443, 0xFFE16B72, 0xFF8E77C8, 0xFF56B8AD};
        long otherAmount = 0L;
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Long> entry = sorted.get(i);
            if (i < 5) slices.add(new WalletSlice(entry.getKey(), entry.getValue(), palette[i % palette.length]));
            else otherAmount += entry.getValue();
        }
        if (otherAmount > 0) slices.add(new WalletSlice("Boshqa", otherAmount, 0xFF89978E));
        walletDonutChart.setData(slices, monthExpense);

        walletLegendContainer.removeAllViews();
        if (slices.isEmpty()) {
            TextView empty = text("Hozircha xarajat kiritilmagan.\nXarajat qo‘shilgach, diagramma avtomatik yangilanadi.",
                    8, MUTED, false);
            empty.setLineSpacing(dp(2), 1f);
            walletLegendContainer.addView(empty, matchWrap());
        } else {
            for (int i = 0; i < slices.size(); i++) {
                WalletSlice slice = slices.get(i);
                LinearLayout legendRow = new LinearLayout(this);
                legendRow.setOrientation(LinearLayout.HORIZONTAL);
                legendRow.setGravity(Gravity.CENTER_VERTICAL);
                legendRow.setPadding(0, dp(2), 0, dp(2));
                View dot = new View(this);
                GradientDrawable dotBg = new GradientDrawable();
                dotBg.setShape(GradientDrawable.OVAL);
                dotBg.setColor(slice.color);
                dot.setBackground(dotBg);
                legendRow.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));
                TextView label = text(slice.label + "  " + formatMoney(BigDecimal.valueOf(slice.amount)),
                        8, TEXT, false);
                label.setSingleLine(true);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                labelParams.leftMargin = dp(5);
                legendRow.addView(label, labelParams);
                walletLegendContainer.addView(legendRow, matchWrap());
            }
        }

        walletHistoryContainer.removeAllViews();
        int shown = 0;
        for (int i = 0; i < transactions.length() && shown < 3; i++) {
            JSONObject tx = transactions.optJSONObject(i);
            if (tx == null) continue;
            walletHistoryContainer.addView(buildWalletTransactionRow(tx), matchWrap());
            shown++;
        }
        if (shown == 0) {
            TextView empty = text("Operatsiyalar hozircha yo‘q. Daromad yoki xarajat qo‘shing.", 8, MUTED, false);
            empty.setPadding(0, dp(3), 0, dp(3));
            walletHistoryContainer.addView(empty, matchWrap());
        }
    }

    private View buildWalletTransactionRow(JSONObject tx) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(5), dp(2), dp(5));
        String type = tx.optString("type", "expense");
        String account = tx.optString("account", "cash");
        String category = tx.optString("category", "Boshqa");
        String note = tx.optString("note", "");
        long amount = tx.optLong("amount", 0L);
        TextView icon = text("income".equals(type) ? "↗" : ("transfer".equals(type) ? "⇄" : "↘"),
                15, "income".equals(type) ? DARK_GREEN : ("expense".equals(type)
                        ? Color.rgb(190, 61, 65) : MUTED), true);
        row.addView(icon, new LinearLayout.LayoutParams(dp(25), dp(27)));
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        row.addView(details, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView label = text(note.trim().isEmpty() ? category : note.trim(), 9, TEXT, true);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(label, matchWrap());
        long timestamp = tx.optLong("timestamp", System.currentTimeMillis());
        String date = new SimpleDateFormat("dd.MM  HH:mm", Locale.getDefault()).format(timestamp);
        String sub = "transfer".equals(type)
                ? walletAccountName(account) + " → " + walletAccountName(tx.optString("to_account", "card"))
                : walletAccountName(account) + "  ·  " + date;
        details.addView(text(sub, 7, MUTED, false), matchWrap());
        String prefix = "income".equals(type) ? "+" : ("expense".equals(type) ? "−" : "");
        TextView amountText = text(prefix + formatMoney(BigDecimal.valueOf(amount)), 9,
                "income".equals(type) ? DARK_GREEN : ("expense".equals(type)
                        ? Color.rgb(190, 61, 65) : TEXT), true);
        amountText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(amountText, wrapWrap());
        return row;
    }

    private void saveWalletTransaction(String type, long amount, String account,
                                       String toAccount, String category, String note) {
        if (amount <= 0) {
            Toast.makeText(this, "Summani to‘g‘ri kiriting.", Toast.LENGTH_SHORT).show();
            return;
        }
        JSONArray old = readWalletTransactions();
        JSONArray updated = new JSONArray();
        JSONObject item = new JSONObject();
        try {
            item.put("type", type);
            item.put("amount", amount);
            item.put("account", account);
            item.put("to_account", toAccount == null ? "" : toAccount);
            item.put("category", category == null ? "Boshqa" : category);
            item.put("note", note == null ? "" : note.trim());
            item.put("timestamp", System.currentTimeMillis());
            updated.put(item);
            for (int i = 0; i < old.length(); i++) {
                JSONObject previous = old.optJSONObject(i);
                if (previous != null) updated.put(previous);
            }
        } catch (JSONException error) {
            Toast.makeText(this, "Ma’lumotni saqlashda xatolik.", Toast.LENGTH_SHORT).show();
            return;
        }
        walletPreferences.edit().putString(WALLET_TRANSACTIONS_KEY, updated.toString()).apply();
        renderWalletSummary();
        Toast.makeText(this, "Operatsiya saqlandi.", Toast.LENGTH_SHORT).show();
    }

    private void showWalletEntryDialog(boolean income) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        GlassPanel panel = new GlassPanel(this, false, 20);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(13), dp(16), dp(14));
        panel.addView(text(income ? "＋  DAROMAD QO‘SHISH" : "−  XARAJAT QO‘SHISH",
                12, income ? DARK_GREEN : Color.rgb(190, 61, 65), true),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        panel.addView(text("Summa (so‘m)", 8, MUTED, true), matchWrap());
        EditText amountInput = walletAmountField("Masalan: 150000");
        panel.addView(amountInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(45)));
        addSpace(panel, 8);
        panel.addView(text("Qaysi hamyonga?", 8, MUTED, true), matchWrap());
        LinearLayout accountRow = new LinearLayout(this);
        accountRow.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(accountRow, matchWrap());
        final String[] selectedAccount = {"cash"};
        Button cashButton = calculatorButton("💵 Naqd", 9, false, true);
        Button cardButton = calculatorButton("💳 Karta", 9, false, true);
        accountRow.addView(cashButton, new LinearLayout.LayoutParams(0, dp(38), 1f));
        View accountGap = new View(this);
        accountRow.addView(accountGap, new LinearLayout.LayoutParams(dp(7), 1));
        accountRow.addView(cardButton, new LinearLayout.LayoutParams(0, dp(38), 1f));
        Runnable updateAccounts = () -> {
            styleWalletChoice(cashButton, "cash".equals(selectedAccount[0]));
            styleWalletChoice(cardButton, "card".equals(selectedAccount[0]));
        };
        cashButton.setOnClickListener(v -> { selectedAccount[0] = "cash"; updateAccounts.run(); });
        cardButton.setOnClickListener(v -> { selectedAccount[0] = "card"; updateAccounts.run(); });
        updateAccounts.run();

        addSpace(panel, 8);
        panel.addView(text("Kategoriya", 8, MUTED, true), matchWrap());
        String[] categories = income
                ? new String[]{"Savdo", "Maosh", "Qo‘shimcha daromad", "Qarz qaytishi", "Sovg‘a", "Boshqa"}
                : new String[]{"Oziq-ovqat", "Transport", "Kommunal", "Uy-ro‘zg‘or", "Sog‘liq", "Ta’lim", "Kiyim-kechak", "Ko‘ngilochar", "Biznes", "Boshqa"};
        Spinner categorySpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, categories);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        categorySpinner.setAdapter(adapter);
        panel.addView(categorySpinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        addSpace(panel, 7);
        panel.addView(text("Izoh (ixtiyoriy)", 8, MUTED, true), matchWrap());
        EditText noteInput = walletNoteField("Masalan: mahsulot savdosi");
        panel.addView(noteInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        addSpace(panel, 11);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(buttons, matchWrap());
        Button cancel = calculatorButton("Bekor qilish", 9, false, true);
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(41), 1f));
        cancel.setOnClickListener(v -> dialog.dismiss());
        View buttonGap = new View(this);
        buttons.addView(buttonGap, new LinearLayout.LayoutParams(dp(8), 1));
        Button save = calculatorButton("Saqlash", 9, true, false);
        buttons.addView(save, new LinearLayout.LayoutParams(0, dp(41), 1f));
        save.setOnClickListener(v -> {
            long amount = parseWalletAmount(amountInput);
            if (amount <= 0) {
                Toast.makeText(this, "Summani kiriting.", Toast.LENGTH_SHORT).show();
                return;
            }
            saveWalletTransaction(income ? "income" : "expense", amount,
                    selectedAccount[0], "", String.valueOf(categorySpinner.getSelectedItem()),
                    noteInput.getText().toString());
            dialog.dismiss();
        });
        showWalletDialog(dialog, panel, 0.84f);
    }

    private void styleWalletChoice(Button button, boolean selected) {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                selected ? (darkMode ? new int[]{0xE82BC17A, 0xD80C8050}
                        : new int[]{0xEF22B866, 0xE8078B45})
                        : (darkMode ? new int[]{0xC92D473A, 0xA71D3027}
                        : new int[]{0xEFFFFFFF, 0xCDEAF5EE}));
        background.setCornerRadius(dp(11));
        background.setStroke(dp(1), 0x99FFFFFF);
        button.setBackground(background);
        button.setTextColor(selected ? Color.WHITE : TEXT);
    }

    private void showWalletTransferDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        GlassPanel panel = new GlassPanel(this, false, 20);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(13), dp(16), dp(14));
        panel.addView(text("⇄  HAMYONLAR O‘RTASIDA O‘TKAZMA", 11, DARK_GREEN, true),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        final boolean[] cashToCard = {true};
        Button directionButton = calculatorButton("💵 NAQD  →  💳 KARTA", 10, false, true);
        panel.addView(directionButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        directionButton.setOnClickListener(v -> {
            cashToCard[0] = !cashToCard[0];
            directionButton.setText(cashToCard[0] ? "💵 NAQD  →  💳 KARTA" : "💳 KARTA  →  💵 NAQD");
        });
        addSpace(panel, 8);
        panel.addView(text("O‘tkazma summasi (so‘m)", 8, MUTED, true), matchWrap());
        EditText amountInput = walletAmountField("Masalan: 200000");
        panel.addView(amountInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(45)));
        addSpace(panel, 7);
        EditText noteInput = walletNoteField("Izoh (ixtiyoriy)");
        panel.addView(noteInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        addSpace(panel, 11);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(buttons, matchWrap());
        Button cancel = calculatorButton("Bekor", 9, false, true);
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(41), 1f));
        cancel.setOnClickListener(v -> dialog.dismiss());
        View gap = new View(this);
        buttons.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        Button save = calculatorButton("O‘tkazish", 9, true, false);
        buttons.addView(save, new LinearLayout.LayoutParams(0, dp(41), 1f));
        save.setOnClickListener(v -> {
            long amount = parseWalletAmount(amountInput);
            if (amount <= 0) {
                Toast.makeText(this, "Summani kiriting.", Toast.LENGTH_SHORT).show();
                return;
            }
            String from = cashToCard[0] ? "cash" : "card";
            String to = cashToCard[0] ? "card" : "cash";
            if (amount > walletAccountBalance(from)) {
                Toast.makeText(this, "Tanlangan hamyonda mablag‘ yetarli emas.", Toast.LENGTH_LONG).show();
                return;
            }
            saveWalletTransaction("transfer", amount, from, to, "O‘tkazma", noteInput.getText().toString());
            dialog.dismiss();
        });
        showWalletDialog(dialog, panel, 0.72f);
    }

    private void showWalletOpeningDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        GlassPanel panel = new GlassPanel(this, false, 20);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(13), dp(16), dp(14));
        panel.addView(text("HAMYON BOSHLANG‘ICH QOLDIQLARI", 11, DARK_GREEN, true),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        panel.addView(text("Hozirgi naqd va karta qoldig‘ini kiriting. Saqlanganda ilova oldingi yozuvlarni hisobga olib, balansni aynan shu summaga moslaydi.",
                8, MUTED, false), matchWrap());
        addSpace(panel, 8);
        panel.addView(text("Naqd pul (so‘m)", 8, MUTED, true), matchWrap());
        EditText cashInput = walletAmountField("Naqd qoldiq");
        cashInput.setText(walletAccountBalance("cash") != 0
                ? formatMoney(BigDecimal.valueOf(walletAccountBalance("cash"))) : "");
        panel.addView(cashInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        addSpace(panel, 7);
        panel.addView(text("Bank kartalari (jami, so‘m)", 8, MUTED, true), matchWrap());
        EditText cardInput = walletAmountField("Karta qoldig‘i");
        cardInput.setText(walletAccountBalance("card") != 0
                ? formatMoney(BigDecimal.valueOf(walletAccountBalance("card"))) : "");
        panel.addView(cardInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        addSpace(panel, 11);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(buttons, matchWrap());
        Button cancel = calculatorButton("Bekor", 9, false, true);
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(41), 1f));
        cancel.setOnClickListener(v -> dialog.dismiss());
        View gap = new View(this);
        buttons.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        Button save = calculatorButton("Saqlash", 9, true, false);
        buttons.addView(save, new LinearLayout.LayoutParams(0, dp(41), 1f));
        save.setOnClickListener(v -> {
            long cash = parseWalletAmount(cashInput);
            long card = parseWalletAmount(cardInput);
            long cashNet = walletAccountBalance("cash") - walletOpeningBalance("cash");
            long cardNet = walletAccountBalance("card") - walletOpeningBalance("card");
            walletPreferences.edit().putLong(WALLET_OPENING_CASH_KEY, cash - cashNet)
                    .putLong(WALLET_OPENING_CARD_KEY, card - cardNet).apply();
            renderWalletSummary();
            dialog.dismiss();
            Toast.makeText(this, "Boshlang‘ich qoldiqlar saqlandi.", Toast.LENGTH_SHORT).show();
        });

        addSpace(panel, 9);
        Button clearAll = calculatorButton("🗑  BARCHA HAMYON MA’LUMOTLARINI O‘CHIRISH", 8, false, true);
        clearAll.setTextColor(Color.rgb(190, 55, 62));
        GradientDrawable clearBackground = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                darkMode ? new int[]{0xA94B252A, 0x882C1D22}
                        : new int[]{0xFFFCEBED, 0xFFEFDADD});
        clearBackground.setCornerRadius(dp(12));
        clearBackground.setStroke(dp(1), darkMode ? 0xCCF17A82 : 0xCCA93B45);
        clearAll.setBackground(clearBackground);
        clearAll.setMinHeight(0);
        clearAll.setMinimumHeight(0);
        clearAll.setPadding(dp(8), 0, dp(8), 0);
        panel.addView(clearAll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        clearAll.setOnClickListener(v -> {
            dialog.dismiss();
            showWalletClearConfirmationDialog();
        });

        showWalletDialog(dialog, panel, 0.72f);
    }

    /** A second confirmation prevents accidental deletion of the complete local wallet. */
    private void showWalletClearConfirmationDialog() {
        Dialog confirmDialog = new Dialog(this);
        confirmDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        confirmDialog.setCancelable(true);

        GlassPanel confirmPanel = new GlassPanel(this, false, 20);
        confirmPanel.setOrientation(LinearLayout.VERTICAL);
        confirmPanel.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView heading = text("⚠  HAMYONNI TO‘LIQ TOZALASH", 11,
                Color.rgb(190, 55, 62), true);
        confirmPanel.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        TextView warning = text(
                "Bu amal naqd va karta qoldiqlarini, barcha daromad va xarajat yozuvlarini, o‘tkazmalar tarixini hamda oylik statistikani o‘chiradi. Ma’lumotlarni qayta tiklab bo‘lmaydi.",
                9, TEXT, false);
        warning.setGravity(Gravity.TOP);
        warning.setLineSpacing(dp(3), 1f);
        confirmPanel.addView(warning, matchWrap());
        addSpace(confirmPanel, 12);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        confirmPanel.addView(actions, matchWrap());
        Button cancel = calculatorButton("Yo‘q, saqlash", 9, false, true);
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(42), 1f));
        cancel.setOnClickListener(v -> confirmDialog.dismiss());
        View gap = new View(this);
        actions.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        Button confirm = calculatorButton("Ha, o‘chirish", 9, false, true);
        confirm.setTextColor(Color.WHITE);
        GradientDrawable dangerBackground = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFF06B70, 0xFFB72D39});
        dangerBackground.setCornerRadius(dp(12));
        dangerBackground.setStroke(dp(1), 0xFFFFC5C8);
        confirm.setBackground(dangerBackground);
        actions.addView(confirm, new LinearLayout.LayoutParams(0, dp(42), 1f));
        confirm.setOnClickListener(v -> {
            // WALLET_PREFS is dedicated to the wallet, so clear every wallet key at once.
            walletPreferences.edit().clear().apply();
            renderWalletSummary();
            confirmDialog.dismiss();
            Toast.makeText(this, "Hamyon ma’lumotlari to‘liq o‘chirildi.",
                    Toast.LENGTH_LONG).show();
        });
        showWalletDialog(confirmDialog, confirmPanel, 0.88f);
    }

    private void showWalletDialog(Dialog dialog, View content, float widthFactor) {
        dialog.setContentView(content);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(30),
                    Math.round(getResources().getDisplayMetrics().widthPixels * widthFactor));
            attrs.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            attrs.gravity = Gravity.CENTER;
            attrs.dimAmount = darkMode ? 0.52f : 0.34f;
            window.setAttributes(attrs);
        }
    }

    private void showWalletHistoryDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        GlassPanel panel = new GlassPanel(this, false, 20);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("OPERATSIYALAR TARIXI", 11, DARK_GREEN, true),
                new LinearLayout.LayoutParams(0, dp(34), 1f));
        Button close = calculatorButton("✕", 10, false, true);
        header.addView(close, new LinearLayout.LayoutParams(dp(34), dp(34)));
        close.setOnClickListener(v -> dialog.dismiss());
        panel.addView(header, matchWrap());
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        JSONArray transactions = readWalletTransactions();
        if (transactions.length() == 0) {
            list.addView(text("Hozircha operatsiyalar mavjud emas.", 9, MUTED, false), matchWrap());
        } else {
            for (int i = 0; i < transactions.length(); i++) {
                JSONObject tx = transactions.optJSONObject(i);
                if (tx != null) list.addView(buildWalletTransactionRow(tx), matchWrap());
                View divider = new View(this);
                divider.setBackgroundColor(darkMode ? 0x447FA18D : 0x3385AA95);
                list.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            }
        }
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(430)));
        Button done = calculatorButton("Yopish", 9, true, false);
        LinearLayout.LayoutParams doneParams = matchWrap();
        doneParams.topMargin = dp(8);
        panel.addView(done, doneParams);
        done.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(panel);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(30), dp(380)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setDimAmount(darkMode ? 0.52f : 0.34f);
        }
    }

    private class WalletDonutChart extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF bounds = new android.graphics.RectF();
        private ArrayList<WalletSlice> data = new ArrayList<>();
        private long total = 0L;
        WalletDonutChart(Context context) { super(context); }
        void setData(ArrayList<WalletSlice> slices, long totalAmount) {
            data = new ArrayList<>(slices);
            total = totalAmount;
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float stroke = dp(15);
            float radius = Math.min(getWidth(), getHeight()) / 2f - stroke / 2f - dp(3);
            bounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.BUTT);
            if (data.isEmpty() || total <= 0) {
                paint.setColor(darkMode ? 0xFF344D3E : 0xFFDCEBE1);
                canvas.drawArc(bounds, 0, 360, false, paint);
            } else {
                float start = -90f;
                for (WalletSlice slice : data) {
                    float sweep = (float) (360d * slice.amount / total);
                    paint.setColor(slice.color);
                    canvas.drawArc(bounds, start, Math.max(1f, sweep), false, paint);
                    start += sweep;
                }
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(MUTED);
            paint.setTextSize(dp(7));
            canvas.drawText("OYLIK CHIQIM", cx, cy - dp(3), paint);
            paint.setColor(TEXT);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(dp(9));
            canvas.drawText(compactWalletAmount(total), cx, cy + dp(11), paint);
        }
    }

    private String compactWalletAmount(long amount) {
        long abs = Math.abs(amount);
        if (abs >= 1_000_000_000L) return String.format(Locale.US, "%.1f mlrd", amount / 1_000_000_000d);
        if (abs >= 1_000_000L) return String.format(Locale.US, "%.1f mln", amount / 1_000_000d);
        if (abs >= 1_000L) return String.format(Locale.US, "%.0f ming", amount / 1_000d);
        return formatMoney(BigDecimal.valueOf(amount));
    }

    // ================== END AKSO PERSONAL WALLET ==================

    private String getQuizTodayStamp() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(Calendar.getInstance().getTime());
    }

    private void initializeSellerQuizDay() {
        if (quizPreferences == null) return;
        String today = getQuizTodayStamp();
        String savedDay = quizPreferences.getString(QUIZ_DAY_KEY, "");
        int savedVersion = quizPreferences.getInt(QUIZ_VERSION_KEY, 0);
        String savedIds = quizPreferences.getString(QUIZ_IDS_KEY, "");

        if (!today.equals(savedDay) || savedVersion != QUIZ_BANK_VERSION || savedIds.trim().isEmpty()) {
            // Do not repeat any question from the immediately preceding daily set.
            Set<String> yesterdayIds = parseQuizIds(savedIds);
            ArrayList<SellerQuizQuestion> selected = createDailySellerQuiz(today, yesterdayIds);
            dailySellerQuizQuestions.clear();
            dailySellerQuizQuestions.addAll(selected);
            StringBuilder ids = new StringBuilder();
            for (SellerQuizQuestion question : dailySellerQuizQuestions) {
                if (ids.length() > 0) ids.append('|');
                ids.append(question.id);
            }
            quizPreferences.edit()
                    .putString(QUIZ_DAY_KEY, today)
                    .putString(QUIZ_IDS_KEY, ids.toString())
                    .putInt(QUIZ_VERSION_KEY, QUIZ_BANK_VERSION)
                    .putInt(QUIZ_POSITION_KEY, 0)
                    .putInt(QUIZ_CORRECT_KEY, 0)
                    .apply();
            quizQuestionIndex = 0;
            quizCorrectCount = 0;
        } else {
            dailySellerQuizQuestions.clear();
            dailySellerQuizQuestions.addAll(loadDailySellerQuiz(savedIds, today));
            if (dailySellerQuizQuestions.size() != DAILY_QUIZ_SIZE) {
                dailySellerQuizQuestions.clear();
                dailySellerQuizQuestions.addAll(createDailySellerQuiz(today, new HashSet<String>()));
                StringBuilder ids = new StringBuilder();
                for (SellerQuizQuestion question : dailySellerQuizQuestions) {
                    if (ids.length() > 0) ids.append('|');
                    ids.append(question.id);
                }
                quizPreferences.edit().putString(QUIZ_IDS_KEY, ids.toString())
                        .putInt(QUIZ_POSITION_KEY, 0).putInt(QUIZ_CORRECT_KEY, 0).apply();
            }
            quizQuestionIndex = Math.max(0, Math.min(
                    dailySellerQuizQuestions.size(),
                    quizPreferences.getInt(QUIZ_POSITION_KEY, 0)));
            quizCorrectCount = quizPreferences.getInt(QUIZ_CORRECT_KEY, 0);
        }
    }

    private Set<String> parseQuizIds(String encoded) {
        Set<String> ids = new HashSet<>();
        if (encoded == null || encoded.isEmpty()) return ids;
        for (String id : encoded.split("\\|")) {
            if (!id.trim().isEmpty()) ids.add(id.trim());
        }
        return ids;
    }

    private ArrayList<SellerQuizQuestion> loadDailySellerQuiz(String encodedIds, String day) {
        ArrayList<SellerQuizQuestion> result = new ArrayList<>();
        Map<String, SellerQuizQuestion> bank = new HashMap<>();
        for (SellerQuizQuestion question : buildSellerQuizBank()) bank.put(question.id, question);
        long daySeed = getSellerQuizSeed(day);
        if (encodedIds != null && !encodedIds.isEmpty()) {
            for (String id : encodedIds.split("\\|")) {
                SellerQuizQuestion original = bank.get(id);
                if (original != null) result.add(shuffleQuizOptions(original, daySeed));
            }
        }
        return result;
    }

    private long getSellerQuizSeed(String day) {
        return day.hashCode() * 1103515245L + 12345L;
    }

    private ArrayList<SellerQuizQuestion> createDailySellerQuiz(String day, Set<String> excludedIds) {
        ArrayList<SellerQuizQuestion> bank = buildSellerQuizBank();
        ArrayList<SellerQuizQuestion> available = new ArrayList<>();
        for (SellerQuizQuestion question : bank) {
            if (excludedIds == null || !excludedIds.contains(question.id)) available.add(question);
        }
        // Stable per calendar day: reopening the app does not reshuffle today's test.
        long daySeed = getSellerQuizSeed(day);
        Collections.shuffle(available, new Random(daySeed));
        ArrayList<SellerQuizQuestion> selected = new ArrayList<>();
        for (int i = 0; i < Math.min(DAILY_QUIZ_SIZE, available.size()); i++) {
            selected.add(shuffleQuizOptions(available.get(i), daySeed));
        }
        return selected;
    }

    private SellerQuizQuestion shuffleQuizOptions(SellerQuizQuestion source, long daySeed) {
        ArrayList<Integer> order = new ArrayList<>();
        for (int i = 0; i < source.options.length; i++) order.add(i);
        Collections.shuffle(order, new Random(daySeed ^ source.id.hashCode()));
        String[] shuffledOptions = new String[source.options.length];
        int shuffledCorrect = 0;
        for (int i = 0; i < order.size(); i++) {
            int oldIndex = order.get(i);
            shuffledOptions[i] = source.options[oldIndex];
            if (oldIndex == source.correctIndex) shuffledCorrect = i;
        }
        return new SellerQuizQuestion(source.id, source.question,
                shuffledOptions, shuffledCorrect, source.explanation);
    }

    private ArrayList<SellerQuizQuestion> buildSellerQuizBank() {
        ArrayList<SellerQuizQuestion> bank = new ArrayList<>();
        String[] products = {
                "kir yuvish mashinasi", "muzlatkich", "changyutgich", "televizor",
                "konditsioner", "divan", "gilam", "oshxona mebeli",
                "idish yuvish mashinasi", "mikroto‘lqinli pech", "gaz plitasi",
                "suv isitgich", "yotoqxona mebeli", "kompyuter", "elektr choynak"
        };

        String[][] contexts = {
                {"yangi xonadoniga olayotganini aytdi", "katta oila uchun kerakligini aytdi",
                        "kichik xonaga joylashtirmoqchi", "budjetini oldindan belgilab qo‘ygan",
                        "oldingi mahsulotidan qulayroq model izlayapti", "kun davomida ko‘p foydalanishini aytdi"},
                {"boshqa joyda arzonroq narx ko‘rganini aytdi", "narx budjetidan biroz yuqori ekanini bildirdi",
                        "narx ichiga nimalar kirishini so‘radi", "chegirma bo‘lish-bo‘lmasligini so‘radi",
                        "arzonroq model ham vazifasini bajaradimi deb so‘radi", "narxni oilasi bilan maslahatlashmoqchi"},
                {"ikkala modelning asosiy farqini bilmoqchi", "energiya tejamkorligi muhimligini aytdi",
                        "ko‘proq sig‘im yoki quvvat kerakligini so‘radi", "narx va kafolatni birga solishtirmoqchi",
                        "qaysi model uzoqroq xizmat qilishini so‘radi", "faqat reklamadagi xususiyatlarga qarab tanlayapti"},
                {"bugunoq olib ketmoqchi", "rangini va modelini aniq tanlagan", "yetkazib berish muddatini so‘radi",
                        "saytdagi qoldiq bilan do‘kondagi qoldiqni solishtiryapti", "omborda zaxira borligini bilmoqchi",
                        "oldindan buyurtma berish mumkinligini so‘radi"},
                {"oylik to‘lovni bilmoqchi", "boshlang‘ich to‘lovsiz variantni so‘radi",
                        "6 yoki 12 oylik muddatni solishtiryapti", "jami qancha to‘lashini so‘radi",
                        "to‘lov jadvalini yozib berishni so‘radi", "shartnomadagi qo‘shimcha shartlarni aniqlashtirmoqchi"},
                {"kafolat necha yil ekanini so‘radi", "kafolat nimalarni qamrab olishini bilmoqchi",
                        "servis markazi qayerdaligini so‘radi", "kafolat taloni qanday to‘ldirilishini so‘radi",
                        "kafolatning boshlanish sanasini aniqlamoqchi", "nosozlikda kimga murojaat qilishini so‘radi"},
                {"asosiy funksiyalarini ko‘rib chiqmoqchi", "ishlash shovqinini tekshirmoqchi",
                        "boshqaruv tugmalarini tushunmoqchi", "mahsulot hajmini ko‘z bilan baholamoqchi",
                        "parvarishlash usulini bilmoqchi", "xavfsizlik funksiyasini tekshirmoqchi"},
                {"mahsulot ishlashida muammo borligini aytdi", "yetkazib berilganda shikast sezganini bildirdi",
                        "qurilma kutilganidek ishlamayotganini aytdi", "almashtirish tartibini so‘radi",
                        "ta’mirlash muddati haqida so‘radi", "xarid hujjatini topa olmayotganini aytdi"},
                {"foydali qo‘shimcha aksessuar bor-yo‘qligini so‘radi", "asosiy mahsulot bilan birga kerakli buyumni tanlayapti",
                        "ortiqcha xarajat qilishni istamasligini aytdi", "parvarishlash vositasi kerakmi deb so‘radi",
                        "o‘rnatish uchun qo‘shimcha jihoz zarurligini so‘radi", "faqat zarur narsalarni olmoqchi"},
                {"xaridni rasmiylashtirishga tayyor", "yetkazib berish manzilini tasdiqlamoqchi",
                        "bo‘lib to‘lashga rozilik bildirdi", "sovg‘a sifatida xarid qilmoqchi",
                        "mahsulot rangini oxirgi marta tekshiryapti", "buyurtma tafsilotlarini qayta ko‘rib chiqmoqchi"}
        };

        String[][] options = {
                {"Foydalanish hajmi, joy o‘lchami, zarur funksiyalar va budjetni aniqlab, mos modellarni bir xil mezonlarda solishtirish.",
                        "Avval yuqori sig‘imli modelni tanlab, keyin mijozning joyi va budjetiga moslashtirish.",
                        "Texnik ko‘rsatkichlarni ko‘rsatib, foydalanish sharoiti haqida keyingi bosqichda so‘rash.",
                        "Do‘konda mavjud modelni birinchi tavsiya qilib, boshqa variantlarni faqat so‘ralsa ko‘rsatish."},
                {"E’tiroz sababini aniqlab, teng sharoitdagi xususiyatlarni dalil bilan solishtirish va mos alternativa berish.",
                        "Narxni raqobatchi bilan solishtirish, lekin komplektatsiya va kafolat farqini keyinroq qoldirish.",
                        "Mijozni yo‘qotmaslik uchun vakolatni tekshirmasdan chegirma taklif qilish.",
                        "Texnik afzalliklarni batafsil sanash, ammo mijoz aynan nimani qimmat deb bilayotganini aniqlamaslik."},
                {"Bir xil ehtiyoj bo‘yicha funksiyalar, kafolat, sarf-xarajat, xizmat va yakuniy qiymatni taqqoslash.",
                        "Avval energiya sarfini solishtirib, narx va servis farqini xariddan keyin tushuntirish.",
                        "Brend va reklamadagi asosiy ko‘rsatkichlarga qarab xulosa chiqarish.",
                        "Har bir modelning eng kuchli tomonini aytib, zaif tomonlarini mijoz so‘ragandagina izohlash."},
                {"Aynan model, rang va komplektatsiya qoldig‘ini real tizim yoki ombor orqali tekshirib, keyin muddatni tasdiqlash.",
                        "Tizimdagi umumiy mahsulot sonini ko‘rib, kerakli rang ham bor deb hisoblash.",
                        "Avval buyurtmani rasmiylashtirib, qoldiqni keyin ombor bilan aniqlashtirish.",
                        "So‘nggi yangilangan qoldiqni tekshirib, uning vaqti va joylashuvini tasdiqlamasdan mijozga aytish."},
                {"Boshlang‘ich to‘lovni ajratib, tanlangan muddat shartini qo‘llash va oylik hamda jami to‘lovni birga ko‘rsatish.",
                        "Oylik summani aytib, jami to‘lovni mijoz alohida so‘raganida hisoblash.",
                        "Boshlang‘ich to‘lovni hisobga olmasdan, mahsulotning to‘liq narxiga ustama qo‘shib jadval tuzish.",
                        "Naqd narx bilan oylik summani ko‘rsatish, ammo muddat va ustama miqdorini alohida izohlamaslik."},
                {"Aniq modelni topib, rasmiy kafolat muddati, istisnolar, boshlanish sanasi va servis tartibini hujjatdan tekshirish.",
                        "Shu turdagi mahsulotlarda odatda uchraydigan muddatni aytib, hujjatni xarid paytida ko‘rsatish.",
                        "Kafolat talonidagi muddatni aytish, ammo qamrab olinmaydigan holatlarni mijoz so‘ramaguncha tushuntirmaslik.",
                        "Ishlab chiqaruvchi kafolati va do‘kon xizmatini bitta umumiy kafolat sifatida bayon qilish."},
                {"Mijoz tanlagan funksiyani yo‘riqnoma asosida xavfsiz ko‘rsatib, uning amaliy cheklovlarini ham tushuntirish.",
                        "Barcha funksiyalarni ketma-ket namoyish qilib, mijozga qaysi biri zarurligini keyin tanlatish.",
                        "Mahsulotni tezroq tushuntirish uchun xavfsizlik sozlamalarini vaqtincha chetlab o‘tish.",
                        "Faqat reklama xususiyatlarini ko‘rsatib, amaliy sinovni xariddan keyinga qoldirish."},
                {"Murojaatni xolis tinglab, xarid dalili va holatni qayd etish, keyin rasmiy tekshiruv tartibiga yo‘naltirish.",
                        "Mijozni tinchlantirish uchun tekshiruvdan oldin almashtirish yoki pul qaytarishni va’da qilish.",
                        "Muammoni tez tugatish uchun servis xulosasisiz foydalanish xatosi deb izohlash.",
                        "Mijozga servis markazi raqamini berib, murojaat tafsilotlarini qayd etmaslik."},
                {"Moslik, real foyda va narxni tekshirib, aksessuarni ixtiyoriy qo‘shimcha sifatida taklif qilish.",
                        "Ko‘proq tejamkor ko‘rinishi uchun aksessuarni asosiy mahsulot bilan bir paketda majburiy taklif qilish.",
                        "Moslikni taxminan tekshirib, xariddan keyin almashtirish mumkinligini aytish.",
                        "Aksessuar foydasini tushuntirish, lekin uning alohida narxi va kafolatini umumiy summaga qo‘shib yuborish."},
                {"Model, rang, son, chegirma, to‘lov jadvali, jami summa, yetkazish va kafolatni mijoz bilan qayta tasdiqlash.",
                        "Model va narxni tasdiqlab, qolgan bandlarni ombor buyurtmani qabul qilganda tekshirish.",
                        "Oylik to‘lovni tasdiqlab, boshlang‘ich va jami summani tizimdagi hisobga qoldirish.",
                        "Mijozning og‘zaki roziligini olib, tizimdagi buyurtma tafsilotlarini keyin tuzatish."}
        };

        int[] correct = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        String[] explanations = {
                "Ehtiyojni to‘g‘ri aniqlash mos mahsulot tanlash va qaytarish xavfini kamaytiradi.",
                "Narx e'tiroziga bosim bilan emas, savol, tushunarli dalil va mos alternativa bilan javob bering.",
                "Xolis solishtirish mijozga ongli qaror qabul qilishga yordam beradi.",
                "Qoldiq va muddatni faqat tekshirilgan ma'lumot asosida ayting.",
                "To‘lov shartlarini to‘liq ochiqlash mijoz ishonchi va moliyaviy aniqlikni ta'minlaydi.",
                "Kafolat bo‘yicha faqat rasmiy va tasdiqlangan ma'lumot berilishi kerak.",
                "Amaliy namoyish mahsulotning foydasini tushunishga yordam beradi; xavfsizlikni unutmang.",
                "Shikoyatni xotirjam tinglash va tartib asosida hal qilish ishonchni saqlaydi.",
                "Qo‘shimcha taklif mijoz ehtiyojiga mos bo‘lishi, majburlashga aylanmasligi kerak.",
                "Buyurtma tafsilotlarini yakunda tekshirish xato va tushunmovchiliklarning oldini oladi."
        };

        for (int type = 0; type < contexts.length; type++) {
            for (int productIndex = 0; productIndex < products.length; productIndex++) {
                for (int contextIndex = 0; contextIndex < contexts[type].length; contextIndex++) {
                    String product = products[productIndex];
                    String context = contexts[type][contextIndex];
                    String question;
                    switch (type) {
                        case 0:
                            question = "Mijoz " + product + " xarid qilmoqchi. U " + context
                                    + ". Tavsiya berishdan avval nima qilasiz?";
                            break;
                        case 1:
                            question = "Mijoz " + product + " narxini qimmat deb hisoblayapti. U "
                                    + context + ". Eng to‘g‘ri javob qaysi?";
                            break;
                        case 2:
                            question = "Mijoz " + product + " modellarini solishtiryapti: u "
                                    + context + ". Qanday yordam berasiz?";
                            break;
                        case 3:
                            question = "Mijoz " + product + " bo‘yicha buyurtma bermoqchi va "
                                    + context + ". Qanday javob berish kerak?";
                            break;
                        case 4:
                            question = "Mijoz " + product + " uchun bo‘lib to‘lashni ko‘rib chiqmoqda. U "
                                    + context + ". Qaysi ma'lumotni berasiz?";
                            break;
                        case 5:
                            question = "Mijoz " + product + " kafolati haqida so‘rayapti: u "
                                    + context + ". Qanday yo‘l tutasiz?";
                            break;
                        case 6:
                            question = "Mijoz " + product + "ni do‘konda ko‘rib chiqmoqda. U "
                                    + context + ". Mahsulotni qanday namoyish etasiz?";
                            break;
                        case 7:
                            question = "Xariddan keyin mijoz " + product + " yuzasidan murojaat qildi: u "
                                    + context + ". Birinchi navbatda nima qilasiz?";
                            break;
                        case 8:
                            question = "Mijoz " + product + " bilan birga qo‘shimcha buyum tanlayapti. U "
                                    + context + ". Qanday taklif ma'qul?";
                            break;
                        default:
                            question = "Mijoz " + product + "ni xarid qilish arafasida. U "
                                    + context + ". Buyurtmani yakunlashdan oldin nima qilasiz?";
                            break;
                    }
                    String id = "q" + type + "_" + productIndex + "_" + contextIndex;
                    bank.add(new SellerQuizQuestion(id, question, options[type].clone(),
                            correct[type], explanations[type]));
                }
            }
        }
        return bank;
    }

    private int getQuizCoinBalance() {
        return quizPreferences == null ? 0 : quizPreferences.getInt(QUIZ_COINS_KEY, 0);
    }

    private void updateCoinBalanceLabel() {
        if (coinBalanceLabel == null) return;
        int balance = getQuizCoinBalance();
        coinBalanceLabel.setText("🪙" + balance);
        coinBalanceLabel.setContentDescription("To‘plangan tangalar: " + balance);
    }

    private void addSellerQuizLaunchButton(LinearLayout parent) {
        GlassPanel launchPanel = new GlassPanel(this, false, 17);
        launchPanel.setOrientation(LinearLayout.VERTICAL);
        launchPanel.setPadding(dp(13), dp(12), dp(13), dp(12));
        LinearLayout.LayoutParams panelParams = matchWrap();
        panelParams.topMargin = dp(14);
        parent.addView(launchPanel, panelParams);

        TextView heading = text("🧠  SOTUVCHI BILIMINI SINANG", 10, DARK_GREEN, true);
        heading.setLetterSpacing(0.025f);
        launchPanel.addView(heading, matchWrap());

        TextView description = text(
                "Murakkab savdo vaziyatlari, to‘lov hisob-kitoblari va mijoz bilan ishlash bo‘yicha 100 ta kunlik savol.",
                10, TEXT, false);
        description.setPadding(0, dp(7), 0, dp(9));
        description.setLineSpacing(dp(2), 1f);
        launchPanel.addView(description, matchWrap());

        Button launchButton = calculatorButton("VIKTORINANI BOSHLASH  →", 10, true, false);
        launchPanel.addView(launchButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        launchButton.setOnClickListener(v -> showSellerQuizDialog());
    }

    private void showSellerQuizDialog() {
        if (sellerQuizDialog != null && sellerQuizDialog.isShowing()) return;

        Dialog dialog = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(9), dp(9), dp(9), dp(9));

        GlassPanel panel = new GlassPanel(this, false, 22);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(13), dp(12), dp(13), dp(13));
        root.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("AKSO  /  SOTUVCHI VIKTORINASI", 10, DARK_GREEN, true);
        title.setLetterSpacing(0.035f);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(40), 1f));
        Button closeButton = calculatorButton("✕", 12, false, true);
        header.addView(closeButton, new LinearLayout.LayoutParams(dp(36), dp(36)));
        closeButton.setOnClickListener(v -> dialog.dismiss());
        panel.addView(header, matchWrap());

        ScrollView quizScroll = new ScrollView(this);
        quizScroll.setFillViewport(false);
        quizScroll.setVerticalScrollBarEnabled(false);
        LinearLayout quizContent = new LinearLayout(this);
        quizContent.setOrientation(LinearLayout.VERTICAL);
        quizScroll.addView(quizContent, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(quizScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addSellerQuizSection(quizContent);
        dialog.setContentView(root);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(false);
        sellerQuizDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (sellerQuizDialog == dialog) sellerQuizDialog = null;
        });

        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.48f;
            window.setAttributes(attributes);
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            window.setLayout(Math.min(screenWidth - dp(20), dp(480)),
                    Math.min(screenHeight - dp(48), Math.round(screenHeight * 0.92f)));
            window.setGravity(Gravity.CENTER);
        }
    }

    private void addSellerQuizSection(LinearLayout parent) {
        GlassPanel quizPanel = new GlassPanel(this, false, 18);
        quizPanel.setOrientation(LinearLayout.VERTICAL);
        quizPanel.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams panelParams = matchWrap();
        panelParams.topMargin = dp(13);
        parent.addView(quizPanel, panelParams);

        LinearLayout headingRow = new LinearLayout(this);
        headingRow.setOrientation(LinearLayout.HORIZONTAL);
        headingRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text("🧠  SOTUVCHI BILIMINI SINANG", 10, DARK_GREEN, true);
        heading.setLetterSpacing(0.025f);
        headingRow.addView(heading, new LinearLayout.LayoutParams(0, dp(28), 1f));
        TextView reward = text("+5 🪙", 10, Color.rgb(202, 151, 35), true);
        reward.setGravity(Gravity.CENTER);
        headingRow.addView(reward, wrapWrap());
        quizPanel.addView(headingRow, matchWrap());

        TextView quizDailyNote = text("HAR KUNI 100 TA YANGI SAVOL  ·  HAR TO‘G‘RI JAVOB +5 TANGA",
                7, MUTED, true);
        quizDailyNote.setPadding(0, dp(2), 0, dp(5));
        quizDailyNote.setLetterSpacing(0.015f);
        quizPanel.addView(quizDailyNote, matchWrap());

        quizProgressLabel = text("", 8, MUTED, true);
        quizProgressLabel.setPadding(0, dp(4), 0, dp(4));
        quizPanel.addView(quizProgressLabel, matchWrap());

        quizProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        quizProgressBar.setMax(DAILY_QUIZ_SIZE);
        quizProgressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
        quizProgressBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(darkMode ? 0x664C6A58 : 0x6678A88A));
        quizPanel.addView(quizProgressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5)));

        quizQuestionText = text("", 13, TEXT, true);
        quizQuestionText.setGravity(Gravity.TOP);
        quizQuestionText.setLineSpacing(dp(2), 1f);
        quizQuestionText.setPadding(0, dp(11), 0, dp(8));
        quizPanel.addView(quizQuestionText, matchWrap());

        for (int i = 0; i < quizOptionButtons.length; i++) {
            final int optionIndex = i;
            Button option = calculatorButton("", 10, false, true);
            option.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            option.setPadding(dp(10), dp(5), dp(10), dp(5));
            option.setMinHeight(dp(43));
            option.setMinimumHeight(dp(43));
            option.setSingleLine(false);
            LinearLayout.LayoutParams optionParams = matchWrap();
            optionParams.topMargin = dp(5);
            quizPanel.addView(option, optionParams);
            quizOptionButtons[i] = option;
            option.setOnClickListener(v -> answerSellerQuiz(optionIndex));
        }

        quizFeedbackText = text("", 10, MUTED, true);
        quizFeedbackText.setGravity(Gravity.TOP);
        quizFeedbackText.setLineSpacing(dp(2), 1f);
        quizFeedbackText.setPadding(dp(2), dp(10), dp(2), dp(3));
        quizFeedbackText.setVisibility(View.GONE);
        quizPanel.addView(quizFeedbackText, matchWrap());

        quizNextButton = calculatorButton("Keyingi savol  →", 10, true, false);
        LinearLayout.LayoutParams nextParams = matchWrap();
        nextParams.topMargin = dp(8);
        quizPanel.addView(quizNextButton, nextParams);
        quizNextButton.setVisibility(View.GONE);
        quizNextButton.setOnClickListener(v -> advanceSellerQuiz());

        updateSellerQuizUi();
    }

    private void styleSellerQuizOption(Button button, int state) {
        int[] colors;
        int stroke;
        if (state > 0) {
            colors = darkMode ? new int[]{0xF22FC77B, 0xE80D8050}
                    : new int[]{0xF22BBE70, 0xE8078B45};
            stroke = 0xEEFFFFFF;
            button.setTextColor(Color.WHITE);
        } else if (state < 0) {
            colors = darkMode ? new int[]{0xF2E86C70, 0xE8AF2636}
                    : new int[]{0xF2F06A68, 0xE8CA343D};
            stroke = 0xEEFFFFFF;
            button.setTextColor(Color.WHITE);
        } else {
            colors = darkMode ? new int[]{0xC92D473A, 0xA71D3027}
                    : new int[]{0xEFFFFFFF, 0xCDEAF5EE};
            stroke = 0x99FFFFFF;
            button.setTextColor(TEXT);
        }
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, colors);
        background.setCornerRadius(dp(11));
        background.setStroke(dp(1), stroke);
        button.setBackground(background);
    }

    private void answerSellerQuiz(int selectedIndex) {
        if (quizAnswered || quizCompleted || quizQuestionIndex >= dailySellerQuizQuestions.size()) return;
        quizAnswered = true;
        SellerQuizQuestion currentQuestion = dailySellerQuizQuestions.get(quizQuestionIndex);
        int correctIndex = currentQuestion.correctIndex;
        boolean correct = selectedIndex == correctIndex;
        String rewardKey = "reward_" + getQuizTodayStamp() + "_" + currentQuestion.id;
        boolean firstReward = correct && !quizPreferences.getBoolean(rewardKey, false);

        for (int i = 0; i < quizOptionButtons.length; i++) {
            quizOptionButtons[i].setEnabled(false);
            if (i == correctIndex) styleSellerQuizOption(quizOptionButtons[i], 1);
            else if (i == selectedIndex && !correct) styleSellerQuizOption(quizOptionButtons[i], -1);
            else styleSellerQuizOption(quizOptionButtons[i], 0);
        }

        String explanation = "\nIzoh: " + currentQuestion.explanation;
        if (firstReward) {
            quizCorrectCount++;
            quizPreferences.edit()
                    .putBoolean(rewardKey, true)
                    .putInt(QUIZ_CORRECT_KEY, quizCorrectCount)
                    .putInt(QUIZ_COINS_KEY, getQuizCoinBalance() + 5)
                    .apply();
            updateCoinBalanceLabel();
            quizFeedbackText.setText("✓  To‘g‘ri! Hisobingizga 5 tanga qo‘shildi." + explanation);
            quizFeedbackText.setTextColor(darkMode ? 0xFF72E9A5 : 0xFF138A4C);
        } else if (correct) {
            quizFeedbackText.setText("✓  To‘g‘ri! Bu savol uchun bugun tanga avval berilgan." + explanation);
            quizFeedbackText.setTextColor(darkMode ? 0xFF72E9A5 : 0xFF138A4C);
        } else {
            quizFeedbackText.setText("✕  Noto‘g‘ri. To‘g‘ri javob: "
                    + currentQuestion.options[correctIndex] + "." + explanation);
            quizFeedbackText.setTextColor(darkMode ? 0xFFFF8585 : 0xFFC52D36);
        }
        quizFeedbackText.setVisibility(View.VISIBLE);
        quizNextButton.setText(quizQuestionIndex == DAILY_QUIZ_SIZE - 1
                ? "Natijani ko‘rish  →" : "Keyingi savol  →");
        quizNextButton.setVisibility(View.VISIBLE);
    }

    private void advanceSellerQuiz() {
        if (quizCompleted) {
            quizQuestionIndex = 0;
            quizPreferences.edit().putInt(QUIZ_POSITION_KEY, 0).apply();
            updateSellerQuizUi();
            return;
        }
        quizQuestionIndex++;
        quizPreferences.edit().putInt(QUIZ_POSITION_KEY, quizQuestionIndex).apply();
        updateSellerQuizUi();
    }

    private void updateSellerQuizUi() {
        if (quizProgressLabel == null || quizQuestionText == null) return;
        quizCorrectCount = quizPreferences.getInt(QUIZ_CORRECT_KEY, 0);
        if (quizQuestionIndex >= dailySellerQuizQuestions.size()) {
            quizCompleted = true;
            quizProgressLabel.setText("BUGUNGI TEST YAKUNLANDI  ·  "
                    + dailySellerQuizQuestions.size() + "/" + dailySellerQuizQuestions.size());
            quizProgressBar.setProgress(dailySellerQuizQuestions.size());
            quizQuestionText.setText("Ajoyib! Bugungi 100 ta savolli viktorinani yakunladingiz.");
            for (Button option : quizOptionButtons) option.setVisibility(View.GONE);
            quizFeedbackText.setText("To‘g‘ri javoblar: " + quizCorrectCount + "/"
                    + dailySellerQuizQuestions.size() + "   ·   Bugun olingan tangalar: "
                    + (quizCorrectCount * 5) + " 🪙\nJami balans: " + getQuizCoinBalance() + " 🪙");
            quizFeedbackText.setTextColor(darkMode ? 0xFFBDF5D2 : 0xFF147D48);
            quizFeedbackText.setVisibility(View.VISIBLE);
            quizNextButton.setText("Qayta mashq qilish");
            quizNextButton.setVisibility(View.VISIBLE);
            return;
        }

        quizCompleted = false;
        quizAnswered = false;
        quizProgressLabel.setText("SAVOL " + (quizQuestionIndex + 1) + "/"
                + dailySellerQuizQuestions.size() + "   ·   To‘g‘ri javoblar: " + quizCorrectCount);
        quizProgressBar.setProgress(quizQuestionIndex);
        quizQuestionText.setText(dailySellerQuizQuestions.get(quizQuestionIndex).question);
        quizFeedbackText.setVisibility(View.GONE);
        quizNextButton.setVisibility(View.GONE);

        for (int i = 0; i < quizOptionButtons.length; i++) {
            Button option = quizOptionButtons[i];
            option.setVisibility(View.VISIBLE);
            option.setEnabled(true);
            option.setText((char) ('A' + i) + ".  " + dailySellerQuizQuestions.get(quizQuestionIndex).options[i]);
            styleSellerQuizOption(option, 0);
        }
    }

    private void closeSalesInspirationDrawer() {
        if (drawerOverlay == null) return;
        if (drawerHourlyRefresh != null) drawerHandler.removeCallbacks(drawerHourlyRefresh);
        if (drawerCachePoll != null) drawerHandler.removeCallbacks(drawerCachePoll);
        salesSyncExecutor.shutdownNow();

        final FrameLayout oldOverlay = drawerOverlay;
        final View oldPanel = drawerPanel;
        final View oldScrim = drawerScrim;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mainScrollView != null) {
            mainScrollView.setRenderEffect(null);
        }
        if (oldScrim != null) oldScrim.animate().alpha(0f).setDuration(180).start();
        if (oldPanel != null) {
            oldPanel.animate().translationX(-drawerWidthPx).setDuration(170)
                    .withEndAction(() -> {
                        if (drawerHost != null && oldOverlay.getParent() == drawerHost) {
                            drawerHost.removeView(oldOverlay);
                        }
                    }).start();
        } else if (drawerHost != null && oldOverlay.getParent() == drawerHost) {
            drawerHost.removeView(oldOverlay);
        }

        drawerOverlay = null;
        drawerPanel = null;
        drawerScrim = null;
        drawerHeroImage = null;
        drawerImageFallback = null;
        drawerArticleTitle = null;
        drawerArticleBody = null;
        drawerUpdateLabel = null;
        drawerNextArticleButton = null;
        drawerSelectedArticleIndex = -1;
    }

    private void scheduleDrawerHourlyRefresh() {
        if (drawerHourlyRefresh != null) drawerHandler.removeCallbacks(drawerHourlyRefresh);
        long hourMillis = TimeUnit.HOURS.toMillis(1);
        long delay = hourMillis - (System.currentTimeMillis() % hourMillis) + 500L;
        drawerHourlyRefresh = () -> {
            if (drawerOverlay != null) {
                refreshSalesDrawerCard();
                scheduleDrawerHourlyRefresh();
            }
        };
        drawerHandler.postDelayed(drawerHourlyRefresh, delay);
    }

    private void enqueueSalesContentSyncIfStale() {
        SharedPreferences feedPreferences = getSharedPreferences(SALES_FEED_PREFS, MODE_PRIVATE);
        long lastUpdate = feedPreferences.getLong(SALES_FEED_UPDATED_KEY, 0L);
        long age = System.currentTimeMillis() - lastUpdate;
        if (lastUpdate == 0L || age >= TimeUnit.MINUTES.toMillis(45)) {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build();
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SalesContentWorker.class)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build();
            WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(
                    "akso_sales_inspiration_one_time_sync", ExistingWorkPolicy.KEEP, request);
        }
    }

    /** Refreshes the drawer once background downloads have landed in the local cache. */
    private void scheduleDrawerCachePoll() {
        if (drawerCachePoll != null) drawerHandler.removeCallbacks(drawerCachePoll);
        drawerCachePollAttempts = 0;
        drawerCachePoll = new Runnable() {
            @Override public void run() {
                if (drawerOverlay == null || drawerHeroImage == null) return;
                if (drawerImageFallback != null
                        && drawerImageFallback.getVisibility() == View.GONE) return;
                refreshSalesDrawerCard();
                drawerCachePollAttempts++;
                if (drawerCachePollAttempts < 24 && drawerOverlay != null
                        && drawerImageFallback != null
                        && drawerImageFallback.getVisibility() != View.GONE) {
                    drawerHandler.postDelayed(this, 4000L);
                }
            }
        };
        drawerHandler.postDelayed(drawerCachePoll, 3000L);
    }

    private void refreshSalesDrawerCard() {
        if (drawerArticleTitle == null || drawerArticleBody == null) return;
        SharedPreferences feedPreferences = getSharedPreferences(SALES_FEED_PREFS, MODE_PRIVATE);
        String json = feedPreferences.getString(SALES_FEED_KEY, defaultSalesFeedJson());
        try {
            JSONObject root = new JSONObject(json);
            JSONArray items = root.optJSONArray("items");
            if (items == null || items.length() == 0) {
                root = new JSONObject(defaultSalesFeedJson());
                items = root.optJSONArray("items");
            }
            long hourIndex = System.currentTimeMillis() / TimeUnit.HOURS.toMillis(1);
            if (drawerSelectedArticleIndex < 0 || drawerSelectedArticleIndex >= items.length()) {
                drawerSelectedArticleIndex = (int) (hourIndex % items.length());
            }
            JSONObject item = items.getJSONObject(drawerSelectedArticleIndex);
            String id = item.optString("id", "akso_default").replaceAll("[^a-zA-Z0-9_-]", "_");
            drawerArticleTitle.setText(item.optString("title", "Savdo bo‘yicha maslahat"));
            drawerArticleBody.setText(item.optString("body", ""));

            boolean imageReady = false;
            if (drawerHeroImage != null) {
                File imageFile = new File(new File(getFilesDir(), "akso_sales_feed"), id + ".jpg");
                if (imageFile.isFile() && imageFile.length() > 0) {
                    Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
                    if (bitmap != null) {
                        drawerHeroImage.setImageBitmap(bitmap);
                        imageReady = true;
                    }
                }
                drawerImageFallback.setVisibility(imageReady ? View.GONE : View.VISIBLE);
            }
            long updatedAt = feedPreferences.getLong(SALES_FEED_UPDATED_KEY, 0L);
            String status = updatedAt > 0
                    ? "Kontent yangilangan: " + new SimpleDateFormat("dd.MM.yyyy  HH:mm", Locale.getDefault()).format(updatedAt)
                    : "Boshlang‘ich maqola telefonning ichida saqlangan";
            drawerUpdateLabel.setText("MASLAHAT " + (drawerSelectedArticleIndex + 1) + "/" + items.length()
                    + "  ·  " + status + "  ·  Rasm: " + item.optString("credit", "Unsplash"));
            if (drawerNextArticleButton != null) {
                drawerNextArticleButton.setText("KEYINGI MASLAHAT  →  "
                        + (drawerSelectedArticleIndex + 1 == items.length()
                        ? "(1/" + items.length() + ")" : "(" + (drawerSelectedArticleIndex + 2) + "/" + items.length() + ")"));
            }
        } catch (JSONException error) {
            drawerArticleTitle.setText("Mijozga qiymat taklif qiling");
            drawerArticleBody.setText("Mahsulotning foydasini tushuntiring, mijozni tinglang va to‘lov shartlarini aniq ko‘rsating.");
            if (drawerImageFallback != null) drawerImageFallback.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onBackPressed() {
        if (sellerQuizDialog != null && sellerQuizDialog.isShowing()) {
            sellerQuizDialog.dismiss();
            return;
        }
        if (drawerOverlay != null) {
            closeSalesInspirationDrawer();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isDailySalesDialogVisible() && WasytSecureStore.getValidToken(this) != null
                && salesSourceStatus != null
                && salesSourceStatus.getText().toString().contains("akkaunti ulanmagan")) {
            loadWasytSalesForMainCalendar();
        }
    }

    @Override
    protected void onDestroy() {
        if (sellerQuizDialog != null && sellerQuizDialog.isShowing()) sellerQuizDialog.dismiss();
        if (drawerHourlyRefresh != null) drawerHandler.removeCallbacks(drawerHourlyRefresh);
        if (drawerCachePoll != null) drawerHandler.removeCallbacks(drawerCachePoll);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mainScrollView != null) {
            mainScrollView.setRenderEffect(null);
        }
        super.onDestroy();
    }

    /** Wide, ChatGPT-like left-swipe area; vertical scrolling remains unaffected. */
    private class DrawerHost extends FrameLayout {
        private float downX;
        private float downY;
        private boolean trackingGesture;

        // A rightward horizontal swipe can start anywhere on the home screen.
        // Vertical gestures are still passed to the calculator's ScrollView.
        private int getDrawerGestureStartWidth() {
            return getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        }

        // A shorter activation distance makes the drawer feel immediate.
        private int getDrawerSwipeDistance() {
            return dp(30);
        }

        DrawerHost(Context context) { super(context); }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    trackingGesture = drawerOverlay == null
                            ? downX <= getDrawerGestureStartWidth()
                            : downX <= drawerWidthPx + dp(24);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (trackingGesture) {
                        float dx = event.getX() - downX;
                        float dy = event.getY() - downY;
                        boolean horizontal = Math.abs(dx) > getDrawerSwipeDistance()
                                && Math.abs(dx) > Math.abs(dy) * 1.15f;
                        if (horizontal && ((drawerOverlay == null && dx > 0)
                                || (drawerOverlay != null && dx < 0))) return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    trackingGesture = false;
                    break;
            }
            return super.onInterceptTouchEvent(event);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                boolean horizontalPull = Math.abs(dx) > getDrawerSwipeDistance()
                        && Math.abs(dx) > Math.abs(dy) * 1.15f;
                if (horizontalPull) {
                    if (drawerOverlay == null && dx > 0
                            && downX <= getDrawerGestureStartWidth()) {
                        openSalesInspirationDrawer();
                    } else if (drawerOverlay != null && dx < 0) {
                        closeSalesInspirationDrawer();
                    }
                }
                trackingGesture = false;
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                trackingGesture = false;
                return true;
            }
            return true;
        }
    }

    /** Downloads the current content feed and all related photos into persistent app storage. */
    public static class SalesContentWorker extends Worker {
        private static final int CONNECT_TIMEOUT_MS = 12000;
        private static final int READ_TIMEOUT_MS = 18000;
        private static final int MAX_FEED_BYTES = 512 * 1024;
        private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;

        public SalesContentWorker(Context context, WorkerParameters parameters) {
            super(context, parameters);
        }

        @Override
        public Result doWork() {
            Context context = getApplicationContext();
            SharedPreferences prefs = context.getSharedPreferences(SALES_FEED_PREFS, Context.MODE_PRIVATE);
            String json = downloadText(SALES_FEED_URL, MAX_FEED_BYTES);
            if (json == null || !isValidFeed(json)) json = prefs.getString(SALES_FEED_KEY, defaultSalesFeedJson());
            if (!isValidFeed(json)) json = defaultSalesFeedJson();

            try {
                JSONObject feed = new JSONObject(json);
                JSONArray items = feed.optJSONArray("items");
                if (items == null || items.length() == 0) return Result.retry();
                File cacheDir = new File(context.getFilesDir(), "akso_sales_feed");
                if (!cacheDir.exists() && !cacheDir.mkdirs()) return Result.retry();

                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    String id = item.optString("id", "item_" + i).replaceAll("[^a-zA-Z0-9_-]", "_");
                    String imageUrl = item.optString("imageUrl", "");
                    if (id.isEmpty() || imageUrl.isEmpty()) continue;
                    File imageFile = new File(cacheDir, id + ".jpg");
                    String previousUrl = prefs.getString("image_url_" + id, "");
                    if (!imageFile.isFile() || imageFile.length() < 1024 || !imageUrl.equals(previousUrl)) {
                        if (downloadImage(imageUrl, imageFile)) {
                            prefs.edit().putString("image_url_" + id, imageUrl).apply();
                        }
                    }
                }

                prefs.edit().putString(SALES_FEED_KEY, feed.toString())
                        .putLong(SALES_FEED_UPDATED_KEY, System.currentTimeMillis()).apply();
                return Result.success();
            } catch (Exception exception) {
                return Result.retry();
            }
        }

        private static boolean isValidFeed(String json) {
            try {
                JSONArray items = new JSONObject(json).optJSONArray("items");
                return items != null && items.length() > 0;
            } catch (JSONException exception) { return false; }
        }

        private static String downloadText(String address, int maxBytes) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "AKSO-Android/1.0");
                connection.setInstanceFollowRedirects(true);
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) return null;
                try (InputStream input = new BufferedInputStream(connection.getInputStream());
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read; int total = 0;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > maxBytes) return null;
                        output.write(buffer, 0, read);
                    }
                    return output.toString("UTF-8");
                }
            } catch (Exception ignored) { return null; }
            finally { if (connection != null) connection.disconnect(); }
        }

        private static boolean downloadImage(String address, File target) {
            HttpURLConnection connection = null;
            File temp = new File(target.getParentFile(), target.getName() + ".tmp");
            try {
                connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "AKSO-Android/1.0");
                connection.setInstanceFollowRedirects(true);
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) return false;
                try (InputStream input = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream output = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[8192];
                    int read; int total = 0;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_IMAGE_BYTES) return false;
                        output.write(buffer, 0, read);
                    }
                    output.flush();
                }
                if (!temp.isFile() || temp.length() < 1024) return false;
                if (target.exists() && !target.delete()) return false;
                return temp.renameTo(target);
            } catch (Exception ignored) { return false; }
            finally {
                if (connection != null) connection.disconnect();
                if (temp.exists()) temp.delete();
            }
        }
    }

}
