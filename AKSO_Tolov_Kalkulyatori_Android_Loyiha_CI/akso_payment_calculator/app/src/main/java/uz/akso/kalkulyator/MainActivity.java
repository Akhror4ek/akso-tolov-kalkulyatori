package uz.akso.kalkulyator;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** AKSO installment calculator with a frosted-glass visual system and built-in mini calculator. */
public class MainActivity extends Activity {
    private static final int GREEN = Color.rgb(11, 179, 75);
    private static final int DARK_GREEN = Color.rgb(7, 139, 57);
    private static final int TEXT = Color.rgb(24, 43, 35);
    private static final int MUTED = Color.rgb(105, 127, 116);
    private static final int PAGE = Color.rgb(232, 245, 238);
    private static final int BORDER = Color.rgb(222, 237, 228);

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

    private BigDecimal lastProduct = BigDecimal.ZERO;
    private BigDecimal lastUpfront = BigDecimal.ZERO;
    private BigDecimal lastRemaining = BigDecimal.ZERO;
    private boolean lastValid = true;

    private final int[] terms = {3, 6, 9, 12};
    private final int[] rates = {0, 18, 27, 36};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(232, 245, 238));
        window.setNavigationBarColor(Color.rgb(232, 245, 238));
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(buildScreen());
        productInput.setText("1000000");
        upfrontInput.setText("0");
        recalculate();
    }

    private View buildScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(PAGE);

        GlassBackdropView backdrop = new GlassBackdropView(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
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

        TextView title = text("Bo‘lib to‘lash kalkulyatori", 17, Color.WHITE, true);
        title.setPadding(0, dp(3), 0, 0);
        titleGroup.addView(title, matchWrap());
        header.addView(titleGroup, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView subtitle = text("3  ·  6  ·  9  ·  12 oy", 10, Color.WHITE, true);
        subtitle.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        subtitle.setShadowLayer(dp(5), 0, dp(1), 0x55FFFFFF);
        header.addView(subtitle, wrapWrap());
        return header;
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
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { recalculate(); }
            @Override public void afterTextChanged(Editable s) { }
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

            GradientDrawable rowBackground = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xCFFFFFFF, 0x88FFFFFF});
            rowBackground.setCornerRadius(dp(10));
            rowBackground.setStroke(dp(1), 0xAFFFFFFF);
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

        TextView expression = text("Hisoblashni boshlang", 10, MUTED, false);
        expression.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        displayPanel.addView(expression, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));
        TextView resultDisplay = text("0", 27, DARK_GREEN, true);
        resultDisplay.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        resultDisplay.setSingleLine(true);
        displayPanel.addView(resultDisplay, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        MiniCalculator calculator = new MiniCalculator(expression, resultDisplay);
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
            attributes.dimAmount = 0.28f;
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
            colors = new int[]{0xF20DBD5B, 0xE8078B45};
        } else if (operator) {
            colors = new int[]{0xE7FFFFFF, 0xBDE0F1E6};
        } else {
            colors = new int[]{0xDFFFFFFF, 0xAFFFFFFF};
        }
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, colors);
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), 0xBFFFFFFF);
        button.setBackground(background);
        button.setElevation(dp(primary ? 3 : 1));
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
        private String entry = "0";
        private BigDecimal accumulator;
        private String pendingOperator;
        private boolean startNewEntry = true;
        private boolean error;

        MiniCalculator(TextView expressionView, TextView resultView) {
            this.expressionView = expressionView;
            this.resultView = resultView;
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
            return key.equals("+") || key.equals("−") || key.equals("×") || key.equals("÷");
        }

        private void digit(String digit) {
            if (startNewEntry) {
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
                entry = "0.";
                startNewEntry = false;
            } else if (!entry.contains(".")) {
                entry += ".";
            }
        }

        private void backspace() {
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
            if (entry.startsWith("-")) entry = entry.substring(1);
            else if (!entry.equals("0")) entry = "-" + entry;
            startNewEntry = false;
        }

        private void percent() {
            BigDecimal value = number();
            if (accumulator != null && pendingOperator != null
                    && (pendingOperator.equals("+") || pendingOperator.equals("−"))) {
                value = accumulator.multiply(value).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
            } else {
                value = value.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
            }
            entry = value.stripTrailingZeros().toPlainString();
            startNewEntry = false;
        }

        private void operator(String operator) {
            BigDecimal current = number();
            if (accumulator != null && pendingOperator != null && !startNewEntry) {
                accumulator = calculate(accumulator, pendingOperator, current);
                entry = accumulator.stripTrailingZeros().toPlainString();
            } else if (accumulator == null) {
                accumulator = current;
            }
            pendingOperator = operator;
            startNewEntry = true;
            expressionView.setText(formatCalculatorNumber(accumulator) + " " + operator);
        }

        private void equals() {
            if (accumulator == null || pendingOperator == null) return;
            BigDecimal right = number();
            BigDecimal left = accumulator;
            String op = pendingOperator;
            BigDecimal answer = calculate(left, op, right);
            expressionView.setText(formatCalculatorNumber(left) + " " + op + " "
                    + formatCalculatorNumber(right) + " =");
            entry = answer.stripTrailingZeros().toPlainString();
            accumulator = null;
            pendingOperator = null;
            startNewEntry = true;
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

        private void clear() {
            entry = "0";
            accumulator = null;
            pendingOperator = null;
            startNewEntry = true;
            error = false;
            expressionView.setText("Hisoblashni boshlang");
        }

        private void refresh() {
            if (error) {
                resultView.setText("Xatolik");
            } else {
                resultView.setText(formatCalculatorEntry(entry));
            }
        }

        BigDecimal getValue() {
            if (error) return null;
            try {
                if (accumulator != null && pendingOperator != null) return number();
                return number();
            } catch (NumberFormatException exception) {
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
        String digits = input.getText().toString().replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return BigDecimal.ZERO;
        try { return new BigDecimal(digits); }
        catch (NumberFormatException exception) { return BigDecimal.ZERO; }
    }

    private String formatMoney(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0", new DecimalFormatSymbols(Locale.US));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(amount.setScale(0, RoundingMode.HALF_UP)).replace(',', ' ');
    }

    private EditText amountInput(String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(17);
        input.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        input.setTextColor(DARK_GREEN);
        input.setHintTextColor(0xFF9BAFA4);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setPadding(dp(11), 0, dp(11), 0);
        input.setBackground(amountFieldBackground());

        input.addTextChangedListener(new TextWatcher() {
            private boolean formatting = false;

            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (formatting) return;
                String digits = s.toString().replaceAll("[^0-9]", "");
                if (digits.isEmpty()) return;
                String formatted = formatMoney(new BigDecimal(digits));
                if (formatted.equals(s.toString())) return;

                int cursor = input.getSelectionStart();
                if (cursor < 0) cursor = s.length();
                int digitsBeforeCursor = 0;
                for (int i = 0; i < Math.min(cursor, s.length()); i++) {
                    if (Character.isDigit(s.charAt(i))) digitsBeforeCursor++;
                }

                formatting = true;
                input.setText(formatted);
                int newCursor = 0;
                int digitsSeen = 0;
                while (newCursor < formatted.length() && digitsSeen < digitsBeforeCursor) {
                    if (Character.isDigit(formatted.charAt(newCursor))) digitsSeen++;
                    newCursor++;
                }
                input.setSelection(Math.min(newCursor, formatted.length()));
                formatting = false;
            }

            @Override public void afterTextChanged(Editable s) { }
        });
        return input;
    }

    private GradientDrawable amountFieldBackground() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xCFFFFFFF, 0xAFFFFFFF});
        d.setCornerRadius(dp(13));
        d.setStroke(dp(1), 0xBFFFFFFF);
        return d;
    }

    private GradientDrawable glassTintBackground() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xCDEBFAF0, 0xA8D9F0E2});
        d.setCornerRadius(dp(14));
        d.setStroke(dp(1), 0xDFFFFFFF);
        return d;
    }

    private GradientDrawable glassButtonBackground(boolean primary) {
        int[] colors = primary
                ? new int[]{0xF20DBD5B, 0xE8078B45}
                : new int[]{0xEFFFFFFF, 0xBFEAF5EF};
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        d.setCornerRadius(dp(15));
        d.setStroke(dp(1), primary ? 0xBFFFFFFF : 0xEFFFFFFF);
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

            paint.setShader(new LinearGradient(0, 0, w * 0.35f, h,
                    new int[]{0xFFF3FBF6, 0xFFE7F5ED, 0xFFE2F1E9},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            drawGlow(canvas, w * 0.90f, h * 0.10f, w * 0.72f,
                    new int[]{0x665EE59A, 0x2255C982, 0x0055C982});
            drawGlow(canvas, w * 0.05f, h * 0.46f, w * 0.62f,
                    new int[]{0x55FFFFFF, 0x1FFFFFFF, 0x00FFFFFF});
            drawGlow(canvas, w * 0.83f, h * 0.80f, w * 0.78f,
                    new int[]{0x4438C979, 0x1A8FD1AA, 0x008FD1AA});
            drawGlow(canvas, w * 0.15f, h * 0.95f, w * 0.52f,
                    new int[]{0x33FFFFFF, 0x11FFFFFF, 0x00FFFFFF});
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
                            : new int[]{0x66FFFFFF, 0x24FFFFFF, 0x00FFFFFF},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h * 0.78f, sheen);
            sheen.setShader(null);

            sheen.setShader(new RadialGradient(w * 0.12f, h * 0.04f, w * 0.72f,
                    new int[]{emerald ? 0x38FFFFFF : 0x42FFFFFF, 0x00FFFFFF},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h * 0.55f, sheen);
            sheen.setShader(null);

            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(dp(1));
            rim.setColor(emerald ? 0x88FFFFFF : 0xBFFFFFFF);
            canvas.drawPath(clip, rim);
            rim.setStyle(Paint.Style.FILL);

            canvas.restoreToCount(save);
        }
    }
}
