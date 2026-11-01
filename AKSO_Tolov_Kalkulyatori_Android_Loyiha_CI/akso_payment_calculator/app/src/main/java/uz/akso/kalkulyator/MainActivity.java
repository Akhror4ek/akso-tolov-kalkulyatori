package uz.akso.kalkulyator;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int GREEN = Color.rgb(11, 179, 75);
    private static final int DARK_GREEN = Color.rgb(7, 139, 57);
    private static final int TEXT = Color.rgb(23, 35, 28);
    private static final int MUTED = Color.rgb(113, 128, 120);
    private static final int PAGE = Color.rgb(244, 247, 245);
    private static final int BORDER = Color.rgb(229, 236, 231);

    private EditText productInput;
    private EditText upfrontInput;
    private TextView remainingValue;
    private TextView validationText;
    private TextView[] monthlyValues = new TextView[4];
    private TextView[] totalValues = new TextView[4];
    private TextView[] grandValues = new TextView[4];
    private Button copyButton;
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
        window.setStatusBarColor(DARK_GREEN);
        window.setNavigationBarColor(PAGE);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        setContentView(buildScreen());
        productInput.setText("1000000");
        upfrontInput.setText("0");
        recalculate();
    }

    private View buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAGE);
        scroll.setClipToPadding(false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        page.addView(buildHeader(), matchWrap());
        addSpace(page, 18);

        TextView inputHeading = text("MAHSULOT MA’LUMOTLARI", 12, MUTED, true);
        inputHeading.setLetterSpacing(0.08f);
        page.addView(inputHeading, matchWrap());
        addSpace(page, 9);

        LinearLayout inputCard = verticalCard();
        inputCard.setPadding(dp(16), dp(15), dp(16), dp(15));
        page.addView(inputCard, matchWrap());

        inputCard.addView(fieldLabel("Mahsulot summasi"), matchWrap());
        productInput = amountInput("Masalan: 1 000 000");
        inputCard.addView(productInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        addSpace(inputCard, 15);
        inputCard.addView(fieldLabel("Oldindan to‘lov"), matchWrap());
        upfrontInput = amountInput("Masalan: 200 000");
        inputCard.addView(upfrontInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        addSpace(inputCard, 13);

        LinearLayout remainingBox = new LinearLayout(this);
        remainingBox.setOrientation(LinearLayout.VERTICAL);
        remainingBox.setPadding(dp(13), dp(11), dp(13), dp(11));
        remainingBox.setBackground(rounded(Color.rgb(237, 249, 241), dp(12), Color.rgb(220, 242, 227), 1));
        inputCard.addView(remainingBox, matchWrap());

        TextView remainingCaption = text("BO‘LIB TO‘LASH UCHUN QOLGAN SUMMA", 10, MUTED, true);
        remainingCaption.setLetterSpacing(0.045f);
        remainingBox.addView(remainingCaption, matchWrap());
        remainingValue = text("0 so‘m", 22, DARK_GREEN, true);
        remainingValue.setPadding(0, dp(3), 0, 0);
        remainingBox.addView(remainingValue, matchWrap());

        validationText = text("", 11, Color.rgb(190, 55, 55), false);
        validationText.setPadding(0, dp(7), 0, 0);
        inputCard.addView(validationText, matchWrap());

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { recalculate(); }
            @Override public void afterTextChanged(Editable s) { }
        };
        productInput.addTextChangedListener(watcher);
        upfrontInput.addTextChangedListener(watcher);

        addSpace(page, 20);
        TextView plansHeading = text("TO‘LOV MUDDATLARI", 12, MUTED, true);
        plansHeading.setLetterSpacing(0.08f);
        page.addView(plansHeading, matchWrap());
        addSpace(page, 9);

        for (int i = 0; i < terms.length; i++) {
            page.addView(buildPlanCard(i), matchWrap());
            if (i != terms.length - 1) addSpace(page, 10);
        }

        addSpace(page, 15);
        TextView note = text("Hisob-kitob oldindan to‘lov ayrilgandan keyingi qoldiq summaga ustama qo‘shish orqali amalga oshiriladi. Oylik summa butun so‘mgacha yaxlitlanadi.",
                11, MUTED, false);
        note.setLineSpacing(dp(3), 1f);
        page.addView(note, matchWrap());

        addSpace(page, 17);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(actions, matchWrap());

        copyButton = new Button(this);
        copyButton.setText("NATIJANI NUSXALASH");
        copyButton.setTextSize(12);
        copyButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copyButton.setTextColor(Color.WHITE);
        copyButton.setAllCaps(false);
        copyButton.setBackground(rounded(GREEN, dp(12), GREEN, 0));
        copyButton.setPadding(dp(8), 0, dp(8), 0);
        actions.addView(copyButton, new LinearLayout.LayoutParams(0, dp(50), 1f));
        copyButton.setOnClickListener(v -> copyResults());

        Button clearButton = new Button(this);
        clearButton.setText("Tozalash");
        clearButton.setTextSize(12);
        clearButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        clearButton.setTextColor(DARK_GREEN);
        clearButton.setAllCaps(false);
        clearButton.setBackground(rounded(Color.WHITE, dp(12), BORDER, 1));
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(dp(112), dp(50));
        clearParams.setMargins(dp(9), 0, 0, 0);
        actions.addView(clearButton, clearParams);
        clearButton.setOnClickListener(v -> clearInputs());

        addSpace(page, 10);
        TextView footer = text("AKSO  •  BO‘LIB TO‘LASH KALKULYATORI", 10, MUTED, true);
        footer.setGravity(Gravity.CENTER);
        footer.setLetterSpacing(0.035f);
        page.addView(footer, matchWrap());

        return scroll;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(19), dp(18), dp(19), dp(20));
        header.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{GREEN, DARK_GREEN}));
        ((GradientDrawable) header.getBackground()).setCornerRadius(dp(20));
        header.setElevation(dp(2));

        TextView brand = text("AKSO", 13, Color.WHITE, true);
        brand.setLetterSpacing(0.16f);
        header.addView(brand, matchWrap());
        addSpace(header, 10);

        TextView title = text("Bo‘lib to‘lash\nkalkulyatori", 25, Color.WHITE, true);
        title.setLineSpacing(dp(1), 1f);
        header.addView(title, matchWrap());
        addSpace(header, 7);

        TextView subtitle = text("Summani kiriting — oylik to‘lovlar avtomatik hisoblanadi.",
                12, Color.rgb(232, 255, 239), false);
        subtitle.setLineSpacing(dp(2), 1f);
        header.addView(subtitle, matchWrap());
        return header;
    }

    private View buildPlanCard(int index) {
        LinearLayout card = verticalCard();
        card.setPadding(dp(15), dp(14), dp(15), dp(13));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(top, matchWrap());

        TextView term = text(terms[index] + " OY", 15, TEXT, true);
        term.setLetterSpacing(0.04f);
        top.addView(term, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView rate = text(rates[index] == 0 ? "0% USTAMA" : "+" + rates[index] + "% USTAMA",
                10, DARK_GREEN, true);
        rate.setGravity(Gravity.CENTER);
        rate.setPadding(dp(10), dp(6), dp(10), dp(6));
        rate.setBackground(rounded(Color.rgb(235, 249, 240), dp(20), Color.rgb(235, 249, 240), 0));
        top.addView(rate, wrapWrap());

        addSpace(card, 12);
        TextView monthlyCaption = text("OYLIK TO‘LOV", 10, MUTED, true);
        monthlyCaption.setLetterSpacing(0.06f);
        card.addView(monthlyCaption, matchWrap());

        monthlyValues[index] = text("—", 24, DARK_GREEN, true);
        monthlyValues[index].setPadding(0, dp(2), 0, dp(3));
        card.addView(monthlyValues[index], matchWrap());

        View separator = new View(this);
        separator.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams sepParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        sepParams.topMargin = dp(4);
        sepParams.bottomMargin = dp(8);
        card.addView(separator, sepParams);

        LinearLayout totalRow = new LinearLayout(this);
        totalRow.setOrientation(LinearLayout.HORIZONTAL);
        totalRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(totalRow, matchWrap());
        TextView totalCaption = text("Muddatli to‘lov jami", 11, MUTED, false);
        totalRow.addView(totalCaption, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        totalValues[index] = text("—", 12, TEXT, true);
        totalRow.addView(totalValues[index], wrapWrap());

        LinearLayout grandRow = new LinearLayout(this);
        grandRow.setOrientation(LinearLayout.HORIZONTAL);
        grandRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams grandParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        grandParams.topMargin = dp(7);
        card.addView(grandRow, grandParams);
        TextView grandCaption = text("Oldindan to‘lov bilan jami", 11, MUTED, false);
        grandRow.addView(grandCaption, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        grandValues[index] = text("—", 12, TEXT, true);
        grandRow.addView(grandValues[index], wrapWrap());

        return card;
    }

    private void recalculate() {
        BigDecimal product = readAmount(productInput);
        BigDecimal upfront = readAmount(upfrontInput);
        lastProduct = product;
        lastUpfront = upfront;

        if (upfront.compareTo(product) > 0) {
            lastValid = false;
            lastRemaining = BigDecimal.ZERO;
            remainingValue.setText("0 so‘m");
            validationText.setText("Oldindan to‘lov mahsulot summasidan oshmasligi kerak.");
            setPlanPlaceholders();
            copyButton.setEnabled(false);
            copyButton.setAlpha(0.5f);
            return;
        }

        lastValid = true;
        lastRemaining = product.subtract(upfront);
        remainingValue.setText(formatMoney(lastRemaining) + " so‘m");
        validationText.setText("");
        copyButton.setEnabled(true);
        copyButton.setAlpha(1f);

        for (int i = 0; i < terms.length; i++) {
            BigDecimal rateMultiplier = BigDecimal.valueOf(100L + rates[i])
                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
            BigDecimal financedTotal = lastRemaining.multiply(rateMultiplier)
                    .setScale(0, RoundingMode.HALF_UP);
            BigDecimal monthly = financedTotal.divide(BigDecimal.valueOf(terms[i]), 0, RoundingMode.HALF_UP);
            BigDecimal grandTotal = financedTotal.add(upfront);

            monthlyValues[i].setText(formatMoney(monthly) + " so‘m");
            totalValues[i].setText(formatMoney(financedTotal) + " so‘m");
            grandValues[i].setText(formatMoney(grandTotal) + " so‘m");
        }
    }

    private void setPlanPlaceholders() {
        for (int i = 0; i < terms.length; i++) {
            monthlyValues[i].setText("—");
            totalValues[i].setText("—");
            grandValues[i].setText("—");
        }
    }

    private void copyResults() {
        if (!lastValid) {
            Toast.makeText(this, "Oldindan to‘lov summasini tekshiring.", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder result = new StringBuilder();
        result.append("AKSO — BO‘LIB TO‘LASH HISOB-KITOBI\n");
        result.append("Mahsulot summasi: ").append(formatMoney(lastProduct)).append(" so‘m\n");
        result.append("Oldindan to‘lov: ").append(formatMoney(lastUpfront)).append(" so‘m\n");
        result.append("Qolgan summa: ").append(formatMoney(lastRemaining)).append(" so‘m\n\n");
        for (int i = 0; i < terms.length; i++) {
            BigDecimal total = lastRemaining.multiply(BigDecimal.valueOf(100L + rates[i]))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
            BigDecimal monthly = total.divide(BigDecimal.valueOf(terms[i]), 0, RoundingMode.HALF_UP);
            result.append(terms[i]).append(" oy (+").append(rates[i]).append("%): ")
                    .append(formatMoney(monthly)).append(" so‘m/oy; jami ")
                    .append(formatMoney(total)).append(" so‘m\n");
        }

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("AKSO hisob-kitobi", result.toString()));
        Toast.makeText(this, "Hisob-kitob nusxalandi.", Toast.LENGTH_SHORT).show();
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
        try {
            return new BigDecimal(digits);
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO;
        }
    }

    private String formatMoney(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        DecimalFormat format = new DecimalFormat("#,##0", symbols);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(amount.setScale(0, RoundingMode.HALF_UP)).replace(',', ' ');
    }

    private EditText amountInput(String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(18);
        input.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        input.setTextColor(TEXT);
        input.setHintTextColor(Color.rgb(164, 175, 168));
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSelectAllOnFocus(false);
        input.setPadding(dp(13), 0, dp(13), 0);
        input.setBackground(rounded(Color.rgb(249, 251, 250), dp(11), BORDER, 1));
        return input;
    }

    private TextView fieldLabel(String value) {
        TextView label = text(value, 12, TEXT, true);
        label.setPadding(0, 0, 0, dp(7));
        return label;
    }

    private LinearLayout verticalCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(Color.WHITE, dp(16), BORDER, 1));
        card.setElevation(dp(1));
        return card;
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
}
