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
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import java.util.concurrent.TimeUnit;

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

    // Persistent daily sales ledger (separate from theme and sound preferences).
    private SharedPreferences dailySalesPreferences;
    private Calendar salesDisplayedMonth;
    private Calendar selectedSalesDate;
    private TextView salesMonthTitle;
    private TextView salesMonthlyTotal;
    private TextView salesDaysCount;
    private TextView salesSelectedSummary;
    private LinearLayout salesCalendarGrid;
    private EditText salesEntryInput;

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
    private int drawerWidthPx;
    private final Handler drawerHandler = new Handler(Looper.getMainLooper());
    private Runnable drawerHourlyRefresh;
    private Runnable drawerCachePoll;
    private int drawerCachePollAttempts;

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
        dailySalesPreferences = getSharedPreferences("akso_daily_sales", MODE_PRIVATE);
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
        header.setPadding(dp(16), dp(11), dp(15), dp(11));

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

        // Compact daily-sales shortcut: same dimensions and glass style as Settings.
        Button salesButton = calculatorButton("$", 16, false, true);
        salesButton.setContentDescription("Kunlik savdo hisoboti");
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

    /** Opens a monthly sales ledger. Daily totals persist after the app is closed. */
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
        TextView title = text("AKSO  /  KUNLIK SAVDO", 11, DARK_GREEN, true);
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
        summaryText.addView(text("OYLIK SAVDO JAMI", 8, MUTED, true), matchWrap());
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

        salesEntryInput = amountInput("Savdo summasini kiriting");
        LinearLayout.LayoutParams entryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(43));
        entryParams.topMargin = dp(3);
        panel.addView(salesEntryInput, entryParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = matchWrap();
        actionsParams.topMargin = dp(7);
        panel.addView(actions, actionsParams);
        Button addSale = calculatorButton("SAVDOGA QO‘SHISH", 9, true, false);
        actions.addView(addSale, new LinearLayout.LayoutParams(0, dp(42), 1f));
        attachInteractionFeedback(addSale);
        addSale.setOnClickListener(v -> addDailySale());
        Button deleteDay = calculatorButton("KUNNI O‘CHIRISH", 9, false, true);
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        deleteParams.setMargins(dp(6), 0, 0, 0);
        actions.addView(deleteDay, deleteParams);
        attachInteractionFeedback(deleteDay);
        deleteDay.setOnClickListener(v -> confirmDeleteSelectedDay());

        TextView tip = text("Bir kunda bir nechta savdoni ketma-ket qo‘shishingiz mumkin.",
                8, MUTED, false);
        tip.setPadding(dp(2), dp(7), dp(2), 0);
        panel.addView(tip, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Blur only the main calculator while this daily-sales dialog is visible.
        // The dialog itself remains sharp and interactive.
        View activityContent = findViewById(android.R.id.content);
        final boolean[] backdropBlurApplied = {false};
        dialog.setOnDismissListener(d -> {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activityContent != null) {
            try {
                // A strong 22dp blur makes the underlying figures unreadable behind the glass.
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
            // Older Android versions without RenderEffect get a stronger dim fallback.
            attributes.dimAmount = backdropBlurApplied[0]
                    ? (darkMode ? 0.44f : 0.28f)
                    : (darkMode ? 0.62f : 0.48f);
            window.setAttributes(attributes);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        refreshSalesCalendar();
    }

    private void changeSalesMonth(int amount) {
        int selectedDay = selectedSalesDate.get(Calendar.DAY_OF_MONTH);
        salesDisplayedMonth.add(Calendar.MONTH, amount);
        int maxDay = salesDisplayedMonth.getActualMaximum(Calendar.DAY_OF_MONTH);
        selectedSalesDate.set(salesDisplayedMonth.get(Calendar.YEAR),
                salesDisplayedMonth.get(Calendar.MONTH), Math.min(selectedDay, maxDay));
        if (salesEntryInput != null) salesEntryInput.setText("");
        refreshSalesCalendar();
    }

    private void selectSalesDay(int day) {
        selectedSalesDate.set(salesDisplayedMonth.get(Calendar.YEAR),
                salesDisplayedMonth.get(Calendar.MONTH), day);
        salesEntryInput.setText("");
        refreshSalesCalendar();
    }

    private void refreshSalesCalendar() {
        if (salesCalendarGrid == null) return;
        SimpleDateFormat monthFormat = new SimpleDateFormat("LLLL yyyy", new Locale("uz", "UZ"));
        String monthName = monthFormat.format(salesDisplayedMonth.getTime());
        salesMonthTitle.setText(monthName.substring(0, 1).toUpperCase(new Locale("uz", "UZ"))
                + monthName.substring(1));
        salesCalendarGrid.removeAllViews();

        Calendar first = (Calendar) salesDisplayedMonth.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Monday-first calendar.
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
        for (int day = 1; day <= maxDay; day++) {
            Calendar date = (Calendar) salesDisplayedMonth.clone();
            date.set(Calendar.DAY_OF_MONTH, day);
            BigDecimal value = getDailySales(date);
            if (value.signum() > 0) {
                monthTotal = monthTotal.add(value);
                activeDays++;
            }
        }
        salesMonthlyTotal.setText(formatMoney(monthTotal) + " so‘m");
        salesDaysCount.setText("Savdo bo‘lgan kunlar: " + activeDays);
        SimpleDateFormat dateFormat = new SimpleDateFormat("d MMMM yyyy", new Locale("uz", "UZ"));
        salesSelectedSummary.setText(dateFormat.format(selectedSalesDate.getTime())
                + "   ·   Kunlik jami: " + formatMoney(getDailySales(selectedSalesDate)) + " so‘m");
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
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR)
                && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR);
    }

    private String salesDateKey(Calendar date) {
        return String.format(Locale.US, "sale_%04d_%02d_%02d",
                date.get(Calendar.YEAR), date.get(Calendar.MONTH) + 1,
                date.get(Calendar.DAY_OF_MONTH));
    }

    private BigDecimal getDailySales(Calendar date) {
        String stored = dailySalesPreferences.getString(salesDateKey(date), "0");
        try { return new BigDecimal(stored); }
        catch (NumberFormatException exception) { return BigDecimal.ZERO; }
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

    private void addDailySale() {
        BigDecimal addition = readAmount(salesEntryInput);
        if (addition.signum() <= 0) {
            Toast.makeText(this, "Savdo summasini kiriting.", Toast.LENGTH_SHORT).show();
            return;
        }
        BigDecimal newTotal = getDailySales(selectedSalesDate).add(addition);
        dailySalesPreferences.edit()
                .putString(salesDateKey(selectedSalesDate), newTotal.toPlainString()).apply();
        salesEntryInput.setText("");
        refreshSalesCalendar();
        Toast.makeText(this, "Savdo summasi saqlandi.", Toast.LENGTH_SHORT).show();
    }

    private void confirmDeleteSelectedDay() {
        if (getDailySales(selectedSalesDate).signum() <= 0) {
            Toast.makeText(this, "Bu kun uchun savdo kiritilmagan.", Toast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Kunlik savdoni o‘chirish")
                .setMessage("Tanlangan kundagi jami savdo summasini o‘chirasizmi?")
                .setNegativeButton("Bekor qilish", (d, w) -> d.dismiss())
                .setPositiveButton("O‘chirish", (d, w) -> {
                    dailySalesPreferences.edit().remove(salesDateKey(selectedSalesDate)).apply();
                    refreshSalesCalendar();
                    Toast.makeText(this, "Kunlik savdo o‘chirildi.", Toast.LENGTH_SHORT).show();
                }).show();
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
            "https://raw.githubusercontent.com/Akhror4ek/akso-tolov-kalkulyatori/main/akso_sales_feed.json";

    private static String defaultSalesFeedJson() {
        return "{\n  \"version\": 1,\n  \"source\": \"AKSO\",\n  \"items\": [\n    {\n      \"id\": \"customer_first\",\n      \"title\": \"Birinchi taassurot ishonch uyg‘otadi\",\n      \"body\": \"Mijoz do‘konga kirganda samimiy salom bering va unga e’tibor qarating. Darhol mahsulotni tavsiya qilishga shoshilmang. Avval nima izlayotganini so‘rang, ehtiyojini tinglang. O‘zini hurmat qilingan mijoz deb his qilgan odam xarid haqida xotirjamroq qaror qiladi.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1556742049-0cfed4f6a45d?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"value_not_price\",\n      \"title\": \"Faqat narxni emas, foydani tushuntiring\",\n      \"body\": \"Mahsulotning narxini aytish bilan cheklanib qolmang. Uning xaridorga qanday qulaylik berishini, vaqtini yoki xarajatini qanday tejashini sodda misol bilan ko‘rsating. Mijoz mahsulot qiymatini tushunsa, narxni baholashi ham osonlashadi.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1441986300917-64674bd600d8?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"listen_customer\",\n      \"title\": \"Mijozni tinglash — yaxshi savdoning asosi\",\n      \"body\": \"Ochiq savollar bering: “Siz uchun qaysi xususiyat muhim?”, “Qanday foydalanmoqchisiz?”. Javobni bo‘lmasdan tinglang. Shunda keraksiz mahsulotlarni ko‘rsatmay, mijozga mos variantni tezroq topasiz.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1556761175-b413da4baf72?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"alternatives\",\n      \"title\": \"Har doim uchta variant ko‘rsating\",\n      \"body\": \"Mijozga imkon qadar uch xil taklif bering: tejamkor, o‘rtacha va imkoniyati kengroq variant. Farqlarini qisqa tushuntiring. Bu xaridorga tanlash imkonini beradi va suhbatni faqat “qimmat” yoki “arzon” degan savol atrofida aylantirmaydi.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1441986300917-64674bd600d8?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"payment_convenience\",\n      \"title\": \"To‘lov qulayligini aniq tushuntiring\",\n      \"body\": \"Bo‘lib to‘lashni taklif qilganda boshlang‘ich to‘lovni, muddatni va har oy to‘lanadigan summani alohida ayting. Jami to‘lovni ham yashirmang. Raqamlar tushunarli bo‘lsa, mijoz o‘z budjetiga mos qaror qabul qiladi.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1556742049-0cfed4f6a45d?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"after_sale\",\n      \"title\": \"Savdo tugagach ham mijoz bilan aloqa uzilmasin\",\n      \"body\": \"Xariddan keyin mahsulotdan foydalanish bo‘yicha qisqa tavsiya bering va murojaat uchun aloqa yo‘lini eslatib qo‘ying. Muammo bo‘lsa, yordam berishga tayyor ekaningizni bildiring. Yaxshi xizmat takroriy xarid va tavsiyalar uchun mustahkam asos yaratadi.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1497366754035-f200968a6e72?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"clear_display\",\n      \"title\": \"Mahsulot ma’lumotlarini ko‘rinadigan qiling\",\n      \"body\": \"Narx, asosiy xususiyat va to‘lov shartlarini aniq ko‘rsating. Noaniq yoki chalkash yozuvlar mijozda shubha uyg‘otadi. Tartibli vitrinalar va tushunarli narx yorliqlari sotuvchining vaqtini ham tejaydi.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1542838132-92c53300491e?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    },\n    {\n      \"id\": \"trust\",\n      \"title\": \"Va’dani aniq bering, ortiqcha va’da bermang\",\n      \"body\": \"Mahsulot mavjudligi, yetkazib berish muddati, kafolat va to‘lov shartlari haqida faqat tekshirilgan ma’lumotni ayting. Bajarib bo‘lmaydigan va’da qisqa muddatda savdo olib kelishi mumkin, lekin uzoq muddatli ishonchga zarar yetkazadi.\",\n      \"imageUrl\": \"https://images.unsplash.com/photo-1556761175-b413da4baf72?auto=format&fit=crop&fm=jpg&w=1000&q=76\",\n      \"credit\": \"Unsplash\"\n    }\n  ]\n}";
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

        TextView offlineNote = text(
                "Internet bo‘lmasa, telefon xotirasidagi oxirgi rasm va maqola ko‘rsatiladi.",
                8, MUTED, false);
        offlineNote.setPadding(dp(2), dp(8), dp(2), dp(8));
        content.addView(offlineNote, matchWrap());

        drawerOverlay.addView(panel, new FrameLayout.LayoutParams(
                drawerWidthPx, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START | Gravity.TOP));
        drawerHost.addView(drawerOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel.setTranslationX(-drawerWidthPx);
        drawerScrim.animate().alpha(1f).setDuration(220).start();
        panel.animate().translationX(0).setDuration(260)
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

    private void closeSalesInspirationDrawer() {
        if (drawerOverlay == null) return;
        if (drawerHourlyRefresh != null) drawerHandler.removeCallbacks(drawerHourlyRefresh);
        if (drawerCachePoll != null) drawerHandler.removeCallbacks(drawerCachePoll);

        final FrameLayout oldOverlay = drawerOverlay;
        final View oldPanel = drawerPanel;
        final View oldScrim = drawerScrim;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mainScrollView != null) {
            mainScrollView.setRenderEffect(null);
        }
        if (oldScrim != null) oldScrim.animate().alpha(0f).setDuration(180).start();
        if (oldPanel != null) {
            oldPanel.animate().translationX(-drawerWidthPx).setDuration(210)
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
            JSONObject item = items.getJSONObject((int) (hourIndex % items.length()));
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
            drawerUpdateLabel.setText(status + "  ·  Rasm: " + item.optString("credit", "Unsplash"));
        } catch (JSONException error) {
            drawerArticleTitle.setText("Mijozga qiymat taklif qiling");
            drawerArticleBody.setText("Mahsulotning foydasini tushuntiring, mijozni tinglang va to‘lov shartlarini aniq ko‘rsating.");
            if (drawerImageFallback != null) drawerImageFallback.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerOverlay != null) {
            closeSalesInspirationDrawer();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
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

        // Let the user begin the gesture well inside the screen, not just at its edge.
        // The system Back gesture owns the extreme edge on many Android devices, so
        // starting a little inward is more reliable. The active zone scales with the
        // display and is capped to avoid swallowing horizontal gestures across the UI.
        private int getDrawerGestureStartWidth() {
            int screenWidth = getWidth() > 0
                    ? getWidth() : getResources().getDisplayMetrics().widthPixels;
            return Math.min(dp(240), Math.max(dp(140), screenWidth * 2 / 5));
        }

        // A deliberate but comfortable pull distance, similar to a navigation drawer.
        private int getDrawerSwipeDistance() {
            return dp(72);
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
                                && Math.abs(dx) > Math.abs(dy) * 1.25f;
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
                        && Math.abs(dx) > Math.abs(dy) * 1.25f;
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
