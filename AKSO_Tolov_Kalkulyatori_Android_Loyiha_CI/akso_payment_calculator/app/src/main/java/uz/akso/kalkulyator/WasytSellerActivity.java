package uz.akso.kalkulyator;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dedicated WASYT seller workflow. The installment calculator, wallet and
 * Firebase synchronization are intentionally not changed by this screen.
 */
public class WasytSellerActivity extends Activity {
    private static final int BG = 0xFF0C2118;
    private static final int CARD = 0xE52B4B3A;
    private static final int GREEN = 0xFF0BB34B;
    private static final int LIGHT_GREEN = 0xFF78E4A8;
    private static final int WHITE = 0xFFF1F8F3;
    private static final int MUTED = 0xFFB4C8BA;
    private static final int RED = 0xFFE36A65;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<WasytBarcodeScanner.ProductInfo> searchResults = new ArrayList<>();
    private final List<CartItem> cartItems = new ArrayList<>();

    private LinearLayout page;
    private ScrollView sellerScroll;
    private ViewTreeObserver.OnGlobalLayoutListener sellerKeyboardLayoutListener;
    private boolean sellerImeVisible = false;
    private int sellerImeBottomInset = 0;
    private int sellerScrollBaseHeight = 0;
    private LinearLayout resultsPanel;
    private LinearLayout cartPanel;
    private EditText productQuery;
    private EditText clientQuery;
    private EditText discountInput;
    private TextView selectedClientView;
    private TextView totalView;
    private TextView statusView;
    private TextView reserveButton;
    private TextView discountModeButton;
    private WasytSellerApi.Client selectedClient;
    private boolean fixedDiscount = false;
    private boolean reserveStock = true;
    private long pendingCartId = -1L;
    private String pendingCartCode = "";

    private Dialog historyDialog;
    private LinearLayout historyFiltersPanel;
    private LinearLayout historyListPanel;
    private TextView historyPageLabel;
    private TextView historySummaryLabel;
    private int historyPage = 1;
    private int historyLastPage = 1;
    private int historyServerLastPage = 1;
    private int historyServerTotal = 0;
    private int historyTotal = 0;
    private boolean historyLoadingAll = false;
    private boolean historyAllLoaded = false;
    private String historyFilter = "all";
    private String historyToken = "";
    private final List<WasytSellerApi.Cart> historyAllCarts = new ArrayList<>();

    private Dialog mySalesDialog;
    private TextView mySalesLoadLabel;
    private TextView mySalesMonthTitle;
    private TextView mySalesMonthlyTotal;
    private TextView mySalesMonthMeta;
    private TextView mySalesSelectedSummary;
    private LinearLayout mySalesCalendarGrid;
    private LinearLayout mySalesDayList;
    private Calendar mySalesDisplayedMonth;
    private Calendar mySalesSelectedDate;
    private String mySalesToken = "";
    private boolean mySalesLoading = false;
    private final List<WasytSellerApi.Cart> myCompletedSales = new ArrayList<>();

    private static final class CartItem {
        final WasytBarcodeScanner.ProductInfo product;
        int quantity;
        CartItem(WasytBarcodeScanner.ProductInfo product) {
            this.product = product;
            this.quantity = 1;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Resize the seller form when the on-screen keyboard opens.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (WasytSecureStore.getValidToken(this) == null
                || WasytSecureStore.getCompanyId(this) <= 0) {
            new AlertDialog.Builder(this)
                    .setTitle("WASYT akkaunti ulanmagan")
                    .setMessage("Sotuvchi modulini ochishdan oldin WASYT akkauntini ulang.")
                    .setPositiveButton("Akkauntni ulash", (dialog, which) ->
                            startActivity(new android.content.Intent(this, WasytAccountActivity.class)))
                    .setNegativeButton("Yopish", (dialog, which) -> finish())
                    .show();
            return;
        }
        buildScreen();
    }

    private void buildScreen() {
        sellerScroll = new ScrollView(this);
        sellerScroll.setFillViewport(true);
        sellerScroll.setClipToPadding(false);
        sellerScroll.setBackgroundColor(BG);

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(15), dp(14), dp(15), dp(24));
        sellerScroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(12), dp(12), dp(12));
        header.setBackground(rounded(0xFF087A43, 0xFF123B2A, 22, 0x99FFFFFF));
        page.addView(header, matchWrap());

        TextView back = button("‹", false);
        back.setTextSize(23);
        header.addView(back, new LinearLayout.LayoutParams(dp(42), dp(42)));
        back.setOnClickListener(v -> finish());

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(10);
        header.addView(titleBox, titleParams);
        titleBox.addView(label("AKSO  /  WASYT", 9, 0xFFD9F8E5, true), matchWrap());
        titleBox.addView(label("Sotuvchi moduli", 15, Color.WHITE, true), matchWrap());

        TextView history = button("SAVATLAR", false);
        history.setTextSize(8);
        header.addView(history, new LinearLayout.LayoutParams(dp(76), dp(40)));
        history.setOnClickListener(v -> showCartHistory());

        TextView mySales = button("SAVDOLARIM", true);
        mySales.setTextSize(8);
        LinearLayout.LayoutParams mySalesParams = new LinearLayout.LayoutParams(dp(86), dp(40));
        mySalesParams.leftMargin = dp(5);
        header.addView(mySales, mySalesParams);
        mySales.setOnClickListener(v -> showMySalesCalendar());

        addSpace(page, 12);
        addSectionTitle("MAHSULOT QIDIRISH");
        LinearLayout searchCard = card();
        page.addView(searchCard, matchWrap());
        productQuery = input("Mahsulot nomi yoki shtrix-kod", InputType.TYPE_CLASS_TEXT);
        searchCard.addView(productQuery, matchWidth(dp(48)));
        addSpace(searchCard, 8);

        LinearLayout searchButtons = new LinearLayout(this);
        searchButtons.setOrientation(LinearLayout.HORIZONTAL);
        searchCard.addView(searchButtons, matchWrap());
        TextView search = button("QIDIRISH", true);
        searchButtons.addView(search, new LinearLayout.LayoutParams(0, dp(44), 1f));
        search.setOnClickListener(v -> searchProducts());
        TextView scan = button("▦  SKANER", false);
        LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        scanParams.leftMargin = dp(8);
        searchButtons.addView(scan, scanParams);
        scan.setOnClickListener(v -> startBarcodeScan());

        resultsPanel = new LinearLayout(this);
        resultsPanel.setOrientation(LinearLayout.VERTICAL);
        addSpace(page, 8);
        page.addView(resultsPanel, matchWrap());

        addSpace(page, 14);
        addSectionTitle("SAVAT");
        cartPanel = new LinearLayout(this);
        cartPanel.setOrientation(LinearLayout.VERTICAL);
        page.addView(cartPanel, matchWrap());
        renderCart();

        addSpace(page, 14);
        addSectionTitle("MIJOZ");
        LinearLayout clientCard = card();
        page.addView(clientCard, matchWrap());
        LinearLayout clientSearchRow = new LinearLayout(this);
        clientSearchRow.setOrientation(LinearLayout.HORIZONTAL);
        clientCard.addView(clientSearchRow, matchWrap());
        clientQuery = input("Mijoz ismi yoki telefoni", InputType.TYPE_CLASS_TEXT);
        clientSearchRow.addView(clientQuery, new LinearLayout.LayoutParams(0, dp(46), 1f));
        TextView findClient = button("TOPISH", false);
        LinearLayout.LayoutParams findParams = new LinearLayout.LayoutParams(dp(86), dp(46));
        findParams.leftMargin = dp(7);
        clientSearchRow.addView(findClient, findParams);
        findClient.setOnClickListener(v -> searchClients());
        TextView newClient = button("+ YANGI MIJOZ", false);
        LinearLayout.LayoutParams newParams = matchWidth(dp(42));
        newParams.topMargin = dp(8);
        clientCard.addView(newClient, newParams);
        newClient.setOnClickListener(v -> showNewClientDialog());

        selectedClientView = label("Mijoz tanlanmagan", 12, MUTED, true);
        selectedClientView.setPadding(dp(3), dp(12), dp(3), dp(3));
        clientCard.addView(selectedClientView, matchWrap());

        addSpace(page, 14);
        addSectionTitle("CHEGIRMA VA BRON");
        LinearLayout options = card();
        page.addView(options, matchWrap());

        LinearLayout discountHeader = new LinearLayout(this);
        discountHeader.setGravity(Gravity.CENTER_VERTICAL);
        options.addView(discountHeader, matchWrap());
        discountModeButton = button("FOIZ %", false);
        discountHeader.addView(discountModeButton, new LinearLayout.LayoutParams(0, dp(42), 1f));
        discountModeButton.setOnClickListener(v -> {
            fixedDiscount = !fixedDiscount;
            discountModeButton.setText(fixedDiscount ? "ANIQ SUMMA" : "FOIZ %");
            discountInput.setHint(fixedDiscount ? "Yakuniy summa (so'm)" : "Chegirma foizi");
            // A value entered in the previous mode has a different meaning.
            discountInput.setText("");
            updateTotal();
        });
        discountInput = input("Chegirma foizi", InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout.LayoutParams discountParams = matchWidth(dp(46));
        discountParams.topMargin = dp(8);
        options.addView(discountInput, discountParams);
        // Explicitly bring the discount field above the keyboard on small screens.
        discountInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) ensureDiscountFieldVisible();
        });
        discountInput.setOnClickListener(view -> ensureDiscountFieldVisible());
        discountInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateTotal(); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });

        reserveButton = button("✓  OMBORDAN BRON QILISH  ·  YOQILGAN", false);
        LinearLayout.LayoutParams reserveParams = matchWidth(dp(44));
        reserveParams.topMargin = dp(8);
        options.addView(reserveButton, reserveParams);
        reserveButton.setOnClickListener(v -> {
            reserveStock = !reserveStock;
            updateReserveLabel();
        });

        addSpace(page, 14);
        LinearLayout totalCard = card();
        page.addView(totalCard, matchWrap());
        totalView = label("JAMI: 0 so'm", 21, LIGHT_GREEN, true);
        totalView.setGravity(Gravity.END);
        totalCard.addView(totalView, matchWrap());

        statusView = label("", 10, MUTED, false);
        statusView.setPadding(dp(3), dp(8), dp(3), dp(3));
        page.addView(statusView, matchWrap());

        TextView checkout = button("KASSAGA YUBORISH  ›", true);
        checkout.setTextSize(14);
        LinearLayout.LayoutParams checkoutParams = matchWidth(dp(54));
        checkoutParams.topMargin = dp(10);
        page.addView(checkout, checkoutParams);
        checkout.setOnClickListener(v -> confirmCheckout());

        setContentView(sellerScroll);
        installSellerKeyboardHandling();
        updateReserveLabel();
        updateTotal();
    }

    /**
     * Android 15 (targetSdk 35) keeps edge-to-edge content behind the IME.
     * adjustResize alone is not reliable there, so calculate the actually
     * visible window bottom and keep the focused discount editor above it.
     */
    private void installSellerKeyboardHandling() {
        if (sellerScroll == null) return;

        sellerScroll.setOnApplyWindowInsetsListener((view, insets) -> {
            sellerImeVisible = false;
            sellerImeBottomInset = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                sellerImeVisible = insets.isVisible(WindowInsets.Type.ime());
                if (sellerImeVisible) {
                    sellerImeBottomInset = insets.getInsets(WindowInsets.Type.ime()).bottom;
                }
            }
            updateSellerKeyboardPadding();
            if (sellerImeVisible) ensureDiscountFieldVisible();
            return insets;
        });

        sellerKeyboardLayoutListener = () -> {
            if (!sellerImeVisible && sellerScroll != null) {
                sellerScrollBaseHeight = sellerScroll.getHeight();
            }
            if (discountInput != null && discountInput.hasFocus()) {
                updateSellerKeyboardPadding();
                scrollDiscountEditorAboveKeyboard();
            }
        };
        sellerScroll.getViewTreeObserver()
                .addOnGlobalLayoutListener(sellerKeyboardLayoutListener);
        sellerScroll.requestApplyInsets();
    }

    private void updateSellerKeyboardPadding() {
        if (sellerScroll == null) return;
        Rect visible = new Rect();
        sellerScroll.getWindowVisibleDisplayFrame(visible);
        int[] location = new int[2];
        sellerScroll.getLocationOnScreen(location);
        int scrollBottom = location[1] + sellerScroll.getHeight();
        int visibleBottom = getSellerKeyboardVisibleBottom(visible, location, scrollBottom);
        // Padding is only the portion actually covered by IME. If Android has
        // already resized the activity, visibleBottom equals scrollBottom.
        int covered = Math.max(0, scrollBottom - visibleBottom);
        int wanted = sellerImeVisible ? covered : 0;
        if (sellerScroll.getPaddingBottom() != wanted) {
            sellerScroll.setPadding(sellerScroll.getPaddingLeft(),
                    sellerScroll.getPaddingTop(), sellerScroll.getPaddingRight(), wanted);
        }
    }

    private int getSellerKeyboardVisibleBottom(Rect visible, int[] rootLocation,
                                               int rootBottom) {
        int visibleBottom = visible.bottom;
        if (Build.VERSION.SDK_INT >= 30 && sellerImeVisible
                && sellerImeBottomInset > dp(80)) {
            boolean decorAlreadyResized = sellerScrollBaseHeight > 0
                    && sellerScrollBaseHeight - sellerScroll.getHeight() > dp(100);
            if (!decorAlreadyResized) {
                int imeTop = rootBottom - sellerImeBottomInset;
                visibleBottom = Math.min(visibleBottom, imeTop);
            }
        }
        return visibleBottom;
    }

    private void scrollDiscountEditorAboveKeyboard() {
        if (sellerScroll == null || discountInput == null
                || !discountInput.hasFocus()) return;
        Rect visible = new Rect();
        sellerScroll.getWindowVisibleDisplayFrame(visible);

        int[] fieldLocation = new int[2];
        discountInput.getLocationOnScreen(fieldLocation);
        int[] rootLocation = new int[2];
        sellerScroll.getLocationOnScreen(rootLocation);
        int rootBottom = rootLocation[1] + sellerScroll.getHeight();
        int visibleBottom = getSellerKeyboardVisibleBottom(visible, rootLocation, rootBottom);
        int fieldTop = fieldLocation[1];
        int fieldBottom = fieldTop + discountInput.getHeight();
        int safeTop = visible.top + dp(12);
        int safeBottom = visibleBottom - dp(28);

        if (fieldBottom > safeBottom) {
            sellerScroll.scrollBy(0, fieldBottom - safeBottom);
        } else if (fieldTop < safeTop) {
            sellerScroll.scrollBy(0, fieldTop - safeTop);
        }
    }

    private void ensureDiscountFieldVisible() {
        if (sellerScroll == null || discountInput == null) return;
        // IME animation can take several frames; re-check after it settles.
        int[] delays = {80, 180, 320, 520};
        for (int delay : delays) {
            discountInput.postDelayed(() -> {
                if (sellerScroll == null || discountInput == null
                        || !discountInput.hasFocus()) return;
                updateSellerKeyboardPadding();
                scrollDiscountEditorAboveKeyboard();
                Rect rect = new Rect(0, 0,
                        discountInput.getWidth(), discountInput.getHeight());
                discountInput.requestRectangleOnScreen(rect, true);
            }, delay);
        }
    }

    private void addSectionTitle(String value) {
        TextView title = label(value, 10, MUTED, true);
        title.setLetterSpacing(0.08f);
        title.setPadding(dp(2), 0, 0, dp(6));
        page.addView(title, matchWrap());
    }

    private void searchProducts() {
        String query = productQuery.getText().toString().trim();
        if (query.length() < 2) {
            showStatus("Kamida 2 ta belgi kiriting.", RED);
            return;
        }
        String token = WasytSecureStore.getValidToken(this);
        showStatus("WASYT katalogi qidirilmoqda…", MUTED);
        runNetwork("Mahsulot qidirilmoqda…", () -> {
            List<WasytBarcodeScanner.ProductInfo> found =
                    WasytBarcodeScanner.searchByText(token, query, WasytSecureStore.getCompanyId(this));
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                searchResults.clear();
                searchResults.addAll(found);
                renderSearchResults();
                showStatus(found.size() + " ta mahsulot topildi.", LIGHT_GREEN);
            });
        });
    }

    private void startBarcodeScan() {
        WasytBarcodeScanner.start(this, new WasytBarcodeScanner.Listener() {
            @Override public void onScanStarted() {
                Toast.makeText(WasytSellerActivity.this,
                        "Kamerani mahsulot shtrix-kodiga qarating.", Toast.LENGTH_SHORT).show();
            }
            @Override public void onLookupStarted(String barcode) {
                showStatus("Shtrix-kod qidirilmoqda: " + barcode, MUTED);
            }
            @Override public void onProductsFound(String barcode,
                    List<WasytBarcodeScanner.ProductInfo> products) {
                searchResults.clear();
                searchResults.addAll(products);
                renderSearchResults();
                showStatus(products.size() + " ta mos mahsulot topildi.", LIGHT_GREEN);
            }
            @Override public void onCancelled() { }
            @Override public void onError(String message) { showError("WASYT skaneri", message); }
        });
    }

    private void renderSearchResults() {
        resultsPanel.removeAllViews();
        if (searchResults.isEmpty()) return;

        // Keep the catalog results compact so the cart, client and discount
        // sections remain close by even when a search returns many products.
        LinearLayout panel = card();
        resultsPanel.addView(panel, matchWrap());

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(2), 0, dp(2), dp(7));
        panel.addView(heading, matchWrap());
        heading.addView(label("TOPILGAN MAHSULOTLAR", 10, WHITE, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        heading.addView(label(searchResults.size() + " ta", 10, LIGHT_GREEN, true),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView resultsScroll = new ScrollView(this);
        resultsScroll.setFillViewport(false);
        resultsScroll.setVerticalScrollBarEnabled(true);
        resultsScroll.setSmoothScrollingEnabled(true);
        resultsScroll.setNestedScrollingEnabled(true);
        resultsScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        resultsScroll.setClipToPadding(false);

        // Show about 3 compact rows at once. Extra matches scroll inside this
        // viewport instead of pushing SAVAT / MIJOZ / CHEGIRMA down the page.
        int viewportHeight = Math.min(dp(232), dp(Math.max(1, searchResults.size()) * 66));
        panel.addView(resultsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, viewportHeight));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        resultsScroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < searchResults.size(); i++) {
            WasytBarcodeScanner.ProductInfo product = searchResults.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(56));
            row.setPadding(0, dp(4), 0, dp(4));
            list.addView(row, matchWrap());

            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            row.addView(details, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView name = label(product.name, 11, WHITE, true);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            details.addView(name, matchWrap());

            String info = (product.barcode.isEmpty() ? "Shtrix-kod yo'q" : product.barcode)
                    + (product.stockQuantity == null || product.stockQuantity.isEmpty()
                    ? "" : "  ·  Qoldiq: " + product.stockQuantity);
            TextView metadata = label(info, 9, MUTED, false);
            metadata.setMaxLines(1);
            metadata.setEllipsize(android.text.TextUtils.TruncateAt.END);
            details.addView(metadata, matchWrap());
            details.addView(label(formatMoney(product.priceAmount) + " so'm", 11,
                    LIGHT_GREEN, true), matchWrap());

            TextView add = button("+", true);
            row.addView(add, new LinearLayout.LayoutParams(dp(38), dp(38)));
            add.setOnClickListener(v -> addToCart(product));

            if (i < searchResults.size() - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(0x33FFFFFF);
                list.addView(divider, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            }
        }
    }

    private void addToCart(WasytBarcodeScanner.ProductInfo product) {
        if (product == null || product.id <= 0) return;
        if (!product.hasUzsPrice()) {
            showError("Mahsulot narxi", "Mahsulotning UZS sotuv narxi ko'rsatilmagan. Uni savatga qo'shib bo'lmaydi.");
            return;
        }
        for (CartItem item : cartItems) {
            if (item.product.id == product.id) {
                item.quantity++;
                renderCart();
                showStatus("Mahsulot miqdori oshirildi.", LIGHT_GREEN);
                return;
            }
        }
        cartItems.add(new CartItem(product));
        renderCart();
        showStatus("Mahsulot savatga qo'shildi.", LIGHT_GREEN);
    }

    private void renderCart() {
        if (cartPanel == null) return;
        cartPanel.removeAllViews();
        if (cartItems.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(label("Savat hozircha bo'sh. Mahsulotni nomi yoki shtrix-kod bilan qidiring.",
                    11, MUTED, false), matchWrap());
            cartPanel.addView(empty, matchWrap());
            updateTotal();
            return;
        }
        LinearLayout box = card();
        cartPanel.addView(box, matchWrap());
        for (int i = 0; i < cartItems.size(); i++) {
            final CartItem item = cartItems.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(5), 0, dp(6));
            box.addView(row, matchWrap());

            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            row.addView(details, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            details.addView(label(item.product.name, 11, WHITE, true), matchWrap());
            details.addView(label(formatMoney(item.product.priceAmount) + " × "
                    + item.quantity + " = "
                    + formatMoney(safeMultiply(item.product.priceAmount, item.quantity))
                    + " so'm", 10, LIGHT_GREEN, true), matchWrap());

            TextView minus = button("−", false);
            row.addView(minus, new LinearLayout.LayoutParams(dp(36), dp(36)));
            minus.setOnClickListener(v -> {
                if (item.quantity > 1) item.quantity--;
                else cartItems.remove(item);
                renderCart();
            });
            TextView quantity = label(String.valueOf(item.quantity), 13, WHITE, true);
            quantity.setGravity(Gravity.CENTER);
            row.addView(quantity, new LinearLayout.LayoutParams(dp(30), dp(36)));
            TextView plus = button("+", false);
            row.addView(plus, new LinearLayout.LayoutParams(dp(36), dp(36)));
            plus.setOnClickListener(v -> {
                if (item.quantity < 999) item.quantity++;
                renderCart();
            });
        }
        TextView clear = button("SAVATNI TOZALASH", false);
        LinearLayout.LayoutParams clearParams = matchWidth(dp(38));
        clearParams.topMargin = dp(6);
        box.addView(clear, clearParams);
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Savat tozalansinmi?")
                .setMessage("Savatdagi barcha mahsulotlar olib tashlanadi.")
                .setNegativeButton("Yo'q", null)
                .setPositiveButton("Tozalash", (dialog, which) -> {
                    cartItems.clear();
                    pendingCartId = -1L;
                    pendingCartCode = "";
                    renderCart();
                }).show());
        updateTotal();
    }

    private void searchClients() {
        String query = clientQuery.getText().toString().trim();
        if (query.isEmpty()) {
            showStatus("Mijoz ismi yoki telefonini kiriting.", RED);
            return;
        }
        String token = WasytSecureStore.getValidToken(this);
        runNetwork("Mijoz qidirilmoqda…", () -> {
            List<WasytSellerApi.Client> clients = WasytSellerApi.searchClients(token, query);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (clients.isEmpty()) {
                    showStatus("Mijoz topilmadi. Yangi mijoz yaratishingiz mumkin.", MUTED);
                    return;
                }
                String[] labels = new String[clients.size()];
                for (int i = 0; i < clients.size(); i++) labels[i] = clients.get(i).toString();
                new AlertDialog.Builder(this)
                        .setTitle("Mijozni tanlang")
                        .setItems(labels, (dialog, which) -> selectClient(clients.get(which)))
                        .setNegativeButton("Yopish", null)
                        .show();
            });
        });
    }

    private void selectClient(WasytSellerApi.Client client) {
        selectedClient = client;
        selectedClientView.setText("✓  " + client.fullName + "  ·  " + client.phone);
        selectedClientView.setTextColor(LIGHT_GREEN);
        showStatus("Mijoz tanlandi.", LIGHT_GREEN);
    }

    private void showNewClientDialog() {
        Dialog dialog = new Dialog(this);
        LinearLayout sheet = card();
        sheet.setPadding(dp(17), dp(16), dp(17), dp(15));
        sheet.setBackground(rounded(0xFF214A36, 0xFF10291E, 22, 0xBFFFFFFF));

        TextView title = label("YANGI MIJOZ", 17, WHITE, true);
        title.setLetterSpacing(0.04f);
        sheet.addView(title, matchWrap());
        TextView subtitle = label("Mijoz ma'lumotlarini kiriting", 10, MUTED, false);
        LinearLayout.LayoutParams subtitleParams = matchWidth(dp(26));
        subtitleParams.topMargin = dp(3);
        sheet.addView(subtitle, subtitleParams);

        EditText name = input("Mijoz F.I.Sh.", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        LinearLayout.LayoutParams nameParams = matchWidth(dp(50));
        nameParams.topMargin = dp(12);
        sheet.addView(name, nameParams);

        EditText phone = input("Telefon raqami: 998...", InputType.TYPE_CLASS_PHONE);
        LinearLayout.LayoutParams phoneParams = matchWidth(dp(50));
        phoneParams.topMargin = dp(9);
        sheet.addView(phone, phoneParams);

        TextView genderTitle = label("JINSI", 9, MUTED, true);
        LinearLayout.LayoutParams genderTitleParams = matchWidth(dp(25));
        genderTitleParams.topMargin = dp(8);
        sheet.addView(genderTitle, genderTitleParams);

        LinearLayout genderRow = new LinearLayout(this);
        genderRow.setOrientation(LinearLayout.HORIZONTAL);
        sheet.addView(genderRow, matchWidth(dp(42)));
        TextView male = button("ERKAK", true);
        TextView female = button("AYOL", false);
        genderRow.addView(male, new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams femaleParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        femaleParams.leftMargin = dp(8);
        genderRow.addView(female, femaleParams);
        final boolean[] femaleSelected = {false};
        Runnable refreshGender = () -> {
            male.setBackground(femaleSelected[0]
                    ? rounded(0xB83A5C48, 0xA91C3328, 14, 0x88FFFFFF)
                    : rounded(0xFF1CCB7A, 0xFF078747, 14, 0xAAE8FFF1));
            female.setBackground(femaleSelected[0]
                    ? rounded(0xFF1CCB7A, 0xFF078747, 14, 0xAAE8FFF1)
                    : rounded(0xB83A5C48, 0xA91C3328, 14, 0x88FFFFFF));
        };
        male.setOnClickListener(v -> { femaleSelected[0] = false; refreshGender.run(); });
        female.setOnClickListener(v -> { femaleSelected[0] = true; refreshGender.run(); });

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = matchWidth(dp(46));
        actionsParams.topMargin = dp(14);
        sheet.addView(actions, actionsParams);
        TextView cancel = button("BEKOR QILISH", false);
        TextView save = button("SAQLASH", true);
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        saveParams.leftMargin = dp(8);
        actions.addView(save, saveParams);

        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            String fullName = name.getText().toString().trim();
            String mobile = phone.getText().toString().trim();
            if (fullName.length() < 2) {
                name.setError("Mijoz F.I.Sh. kiriting.");
                name.requestFocus();
                return;
            }
            if (mobile.replaceAll("[^0-9]", "").length() < 9) {
                phone.setError("Telefon raqamini to'liq kiriting.");
                phone.requestFocus();
                return;
            }
            String gender = femaleSelected[0] ? "female" : "male";
            String token = WasytSecureStore.getValidToken(this);
            dialog.dismiss();
            runNetwork("Mijoz yaratilmoqda…", () -> {
                WasytSellerApi.Client client = WasytSellerApi.createClient(
                        token, fullName, mobile, gender, "uz");
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    selectClient(client);
                    clientQuery.setText(client.fullName);
                    Toast.makeText(this, "Mijoz WASYT bazasiga qo'shildi.",
                            Toast.LENGTH_SHORT).show();
                });
            });
        });

        dialog.setContentView(sheet);
        dialog.setCancelable(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = getResources().getDisplayMetrics().widthPixels - dp(42);
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
    }

    private void confirmCheckout() {
        if (cartItems.isEmpty()) {
            showStatus("Savat bo'sh.", RED);
            return;
        }
        if (selectedClient == null) {
            showStatus("Kassaga yuborishdan oldin mijozni tanlang.", RED);
            return;
        }
        try {
            calculateDiscountAmount(); // validates the entered value in the selected mode
        } catch (Exception error) {
            showStatus(error.getMessage(), RED);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Savat kassaga yuborilsinmi?")
                .setMessage("Mijoz: " + selectedClient.fullName
                        + "\nMahsulotlar: " + cartItems.size()
                        + "\nJami: " + formatMoney(calculateTotalAfterDiscount()) + " so'm"
                        + "\nOmbordan bron: " + (reserveStock ? "Ha" : "Yo'q"))
                .setNegativeButton("Tekshirish", null)
                .setPositiveButton("Kassaga yuborish", (dialog, which) -> checkout())
                .show();
    }

    private void checkout() {
        final String token = WasytSecureStore.getValidToken(this);
        final long companyId = WasytSecureStore.getCompanyId(this);
        final long stockId = WasytSellerApi.DEFAULT_STOCK_ID;
        final long clientId = selectedClient.id;
        final long discount;
        final String checkoutClientName = selectedClient.fullName;
        try {
            // WASYT expects the discount amount. In fixed mode the seller enters
            // the final payable total, so convert it to subtotal - final total.
            discount = calculateDiscountAmount();
        } catch (Exception error) {
            showStatus(error.getMessage(), RED);
            return;
        }

        final ArrayList<WasytSellerApi.CartLine> lines = new ArrayList<>();
        for (CartItem item : cartItems) {
            lines.add(new WasytSellerApi.CartLine(item.product.id,
                    item.quantity, item.product.priceAmount));
        }
        runNetwork("WASYT savati kassaga yuborilmoqda…", () -> {
            long cartId = pendingCartId;
            String code = pendingCartCode;
            if (cartId <= 0) {
                JSONObject created = WasytSellerApi.createCart(token, companyId,
                        stockId, reserveStock, lines);
                JSONObject data = created.optJSONObject("data");
                if (data == null) data = created;
                cartId = data.optLong("id", -1L);
                code = data.optString("code", "");
                if (cartId <= 0) {
                    throw new java.io.IOException("WASYT savat yaratdi, ammo savat ID qaytmadi.");
                }
                final long savedId = cartId;
                final String savedCode = code;
                main.post(() -> {
                    pendingCartId = savedId;
                    pendingCartCode = savedCode;
                    showStatus("Savat WASYTda yaratildi. Kassaga yuborish davom etmoqda…", MUTED);
                });
            }

            WasytSellerApi.updateCart(token, cartId, companyId, stockId,
                    clientId, reserveStock, lines, fixedDiscount, discount);
            JSONObject sent = WasytSellerApi.sendCart(token, cartId);
            JSONObject sentData = sent.optJSONObject("data");
            if (sentData == null) sentData = sent;
            String sentCode = sentData.optString("code", code);
            String finalCode = sentCode;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                cartItems.clear();
                selectedClient = null;
                selectedClientView.setText("Mijoz tanlanmagan");
                selectedClientView.setTextColor(MUTED);
                clientQuery.setText("");
                discountInput.setText("");
                pendingCartId = -1L;
                pendingCartCode = "";
                renderCart();
                showStatus("✓ Savat " + (finalCode.isEmpty() ? "" : "#" + finalCode + " ")
                        + "kassaga yuborildi.", LIGHT_GREEN);
                new AlertDialog.Builder(this)
                        .setTitle("Kassaga yuborildi")
                        .setMessage("Mijozni kassaga yo'llang va navbat raqamini ayting.\n\n"
                                + "Savat: " + (finalCode.isEmpty() ? "tayyor" : finalCode)
                                + "\nMijoz: " + checkoutClientName)
                        .setPositiveButton("Yangi savat", null)
                        .show();
            });
        });
    }

    private String selectedClientNameSafe(long clientId) {
        return "Mijoz ID: " + clientId;
    }

    private boolean isCompletedSaleCart(WasytSellerApi.Cart cart) {
        return WasytSalesRepository.isCompletedSale(this, cart);
    }

    private void showMySalesCalendar() {
        Calendar now = Calendar.getInstance();
        mySalesDisplayedMonth = (Calendar) now.clone();
        mySalesDisplayedMonth.set(Calendar.DAY_OF_MONTH, 1);
        mySalesSelectedDate = (Calendar) now.clone();
        mySalesToken = WasytSecureStore.getValidToken(this);
        myCompletedSales.clear();
        mySalesLoading = false;

        mySalesDialog = new Dialog(this);
        LinearLayout shell = card();
        shell.setPadding(dp(13), dp(12), dp(13), dp(11));
        shell.setBackground(rounded(0xFF214A36, 0xFF0C2118, 22, 0xBFFFFFFF));
        shell.setOrientation(LinearLayout.VERTICAL);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        shell.addView(heading, matchWidth(dp(42)));
        LinearLayout headingText = new LinearLayout(this);
        headingText.setOrientation(LinearLayout.VERTICAL);
        heading.addView(headingText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        headingText.addView(label("AKSO  /  WASYT", 8, MUTED, true), matchWrap());
        headingText.addView(label("SAVDOLARIM", 15, WHITE, true), matchWrap());

        TextView refresh = button("YANGILASH", false);
        refresh.setTextSize(8);
        heading.addView(refresh, new LinearLayout.LayoutParams(dp(78), dp(36)));
        refresh.setOnClickListener(v -> loadMySalesData());
        TextView close = button("YOPISH", false);
        close.setTextSize(8);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(62), dp(36));
        closeParams.leftMargin = dp(5);
        heading.addView(close, closeParams);
        close.setOnClickListener(v -> mySalesDialog.dismiss());

        mySalesLoadLabel = label("WASYT savdolari yuklanmoqda…", 9, LIGHT_GREEN, true);
        LinearLayout.LayoutParams loadParams = matchWidth(dp(22));
        loadParams.topMargin = dp(4);
        shell.addView(mySalesLoadLabel, loadParams);

        LinearLayout monthNav = new LinearLayout(this);
        monthNav.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams navParams = matchWidth(dp(38));
        navParams.topMargin = dp(3);
        shell.addView(monthNav, navParams);
        TextView previous = button("‹", false);
        previous.setTextSize(18);
        monthNav.addView(previous, new LinearLayout.LayoutParams(dp(38), dp(34)));
        mySalesMonthTitle = label("", 12, WHITE, true);
        mySalesMonthTitle.setGravity(Gravity.CENTER);
        monthNav.addView(mySalesMonthTitle, new LinearLayout.LayoutParams(0, dp(34), 1f));
        TextView next = button("›", false);
        next.setTextSize(18);
        monthNav.addView(next, new LinearLayout.LayoutParams(dp(38), dp(34)));
        previous.setOnClickListener(v -> changeMySalesMonth(-1));
        next.setOnClickListener(v -> changeMySalesMonth(1));

        LinearLayout summary = card();
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams summaryParams = matchWidth(dp(58));
        summaryParams.topMargin = dp(5);
        shell.addView(summary, summaryParams);
        LinearLayout summaryText = new LinearLayout(this);
        summaryText.setOrientation(LinearLayout.VERTICAL);
        summary.addView(summaryText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        summaryText.addView(label("OYLIK YAKUNLANGAN SAVDOLAR", 8, MUTED, true), matchWrap());
        mySalesMonthlyTotal = label("0 so'm", 16, LIGHT_GREEN, true);
        summaryText.addView(mySalesMonthlyTotal, matchWrap());
        mySalesMonthMeta = label("0 ta savdo · 0 kun", 8, WHITE, false);
        mySalesMonthMeta.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        summary.addView(mySalesMonthMeta, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout weekdays = new LinearLayout(this);
        weekdays.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams weekdayParams = matchWidth(dp(20));
        weekdayParams.topMargin = dp(5);
        shell.addView(weekdays, weekdayParams);
        String[] dayNames = {"Du", "Se", "Ch", "Pa", "Ju", "Sh", "Ya"};
        for (String dayName : dayNames) {
            TextView day = label(dayName, 8, MUTED, true);
            day.setGravity(Gravity.CENTER);
            weekdays.addView(day, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }

        mySalesCalendarGrid = new LinearLayout(this);
        mySalesCalendarGrid.setOrientation(LinearLayout.VERTICAL);
        shell.addView(mySalesCalendarGrid, matchWrap());

        mySalesSelectedSummary = label("", 9, LIGHT_GREEN, true);
        mySalesSelectedSummary.setPadding(dp(2), dp(7), dp(2), dp(5));
        shell.addView(mySalesSelectedSummary, matchWrap());

        ScrollView listScroll = new ScrollView(this);
        listScroll.setFillViewport(false);
        listScroll.setVerticalScrollBarEnabled(true);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        shell.addView(listScroll, listParams);
        mySalesDayList = new LinearLayout(this);
        mySalesDayList.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(mySalesDayList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mySalesDialog.setContentView(shell);
        mySalesDialog.setCancelable(true);
        mySalesDialog.show();
        Window window = mySalesDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = getResources().getDisplayMetrics().widthPixels - dp(28);
            int height = Math.min(getResources().getDisplayMetrics().heightPixels - dp(90), dp(760));
            window.setLayout(width, height);
        }
        refreshMySalesCalendar();
        loadMySalesData();
    }

    private void loadMySalesData() {
        if (mySalesDialog == null || !mySalesDialog.isShowing()) return;
        if (mySalesLoading) {
            Toast.makeText(this, "Savdolar yangilanmoqda.", Toast.LENGTH_SHORT).show();
            return;
        }
        mySalesLoading = true;
        mySalesLoadLabel.setText("WASYT yakunlangan savdolari yuklanmoqda…");
        refreshMySalesCalendar();

        final String token = mySalesToken;
        network.execute(() -> {
            try {
                List<WasytSellerApi.Cart> completed =
                        WasytSalesRepository.loadCompletedSales(this, token);
                ArrayList<WasytSellerApi.Cart> result = new ArrayList<>(completed);
                main.post(() -> {
                    mySalesLoading = false;
                    if (!isMySalesDialogVisible()) return;
                    myCompletedSales.clear();
                    myCompletedSales.addAll(result);
                    mySalesLoading = false;
                    mySalesLoadLabel.setText("Yuklandi: " + result.size()
                            + " ta yakunlangan savdo");
                    refreshMySalesCalendar();
                });
            } catch (Exception error) {
                String message = error.getMessage() == null
                        ? "WASYT savdolarini yuklab bo'lmadi." : error.getMessage();
                main.post(() -> {
                    mySalesLoading = false;
                    if (!isMySalesDialogVisible()) return;
                    mySalesLoadLabel.setText("Savdolar yuklanmadi. YANGILASH tugmasini bosing.");
                    refreshMySalesCalendar();
                    showError("Savdolarim", message);
                });
            }
        });
    }

    private boolean isMySalesDialogVisible() {
        return !isFinishing() && !isDestroyed()
                && mySalesDialog != null && mySalesDialog.isShowing();
    }

    private void changeMySalesMonth(int amount) {
        int selectedDay = mySalesSelectedDate.get(Calendar.DAY_OF_MONTH);
        mySalesDisplayedMonth.add(Calendar.MONTH, amount);
        int maxDay = mySalesDisplayedMonth.getActualMaximum(Calendar.DAY_OF_MONTH);
        mySalesSelectedDate.set(mySalesDisplayedMonth.get(Calendar.YEAR),
                mySalesDisplayedMonth.get(Calendar.MONTH), Math.min(selectedDay, maxDay));
        refreshMySalesCalendar();
    }

    private void selectMySalesDay(int day) {
        mySalesSelectedDate.set(mySalesDisplayedMonth.get(Calendar.YEAR),
                mySalesDisplayedMonth.get(Calendar.MONTH), day);
        refreshMySalesCalendar();
    }

    private Calendar getCompletedSaleDate(WasytSellerApi.Cart cart) {
        return WasytSalesRepository.getSaleDate(cart);
    }

    private boolean isSameMySalesDate(Calendar first, Calendar second) {
        return WasytSalesRepository.isSameDate(first, second);
    }

    private BigDecimal getMySalesForDate(Calendar date) {
        return WasytSalesRepository.getDailyTotal(myCompletedSales, date);
    }

    private int getMySalesCountForDate(Calendar date) {
        return WasytSalesRepository.getDailyCount(myCompletedSales, date);
    }

    private String compactMySalesAmount(BigDecimal amount) {
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
        return amount.stripTrailingZeros().toPlainString();
    }

    private void refreshMySalesCalendar() {
        if (mySalesCalendarGrid == null || mySalesDisplayedMonth == null) return;
        Locale uz = new Locale("uz", "UZ");
        SimpleDateFormat monthFormat = new SimpleDateFormat("LLLL yyyy", uz);
        String month = monthFormat.format(mySalesDisplayedMonth.getTime());
        mySalesMonthTitle.setText(month.substring(0, 1).toUpperCase(uz) + month.substring(1));
        mySalesCalendarGrid.removeAllViews();

        Calendar first = (Calendar) mySalesDisplayedMonth.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        int maxDay = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        for (int week = 0; week < 6; week++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = matchWrap();
            if (week > 0) rowParams.topMargin = dp(3);
            mySalesCalendarGrid.addView(row, rowParams);
            for (int column = 0; column < 7; column++) {
                int day = week * 7 + column - offset + 1;
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
                if (column > 0) cellParams.leftMargin = dp(2);
                if (day < 1 || day > maxDay) {
                    row.addView(new View(this), cellParams);
                } else {
                    Calendar date = (Calendar) mySalesDisplayedMonth.clone();
                    date.set(Calendar.DAY_OF_MONTH, day);
                    row.addView(buildMySalesDayCell(day, getMySalesForDate(date),
                            isSameMySalesDate(date, mySalesSelectedDate)), cellParams);
                }
            }
        }

        BigDecimal monthTotal = BigDecimal.ZERO;
        int monthSalesCount = 0;
        int activeDays = 0;
        for (int day = 1; day <= maxDay; day++) {
            Calendar date = (Calendar) mySalesDisplayedMonth.clone();
            date.set(Calendar.DAY_OF_MONTH, day);
            BigDecimal amount = getMySalesForDate(date);
            if (amount.signum() > 0) activeDays++;
            monthTotal = monthTotal.add(amount);
            monthSalesCount += getMySalesCountForDate(date);
        }
        mySalesMonthlyTotal.setText(formatMoney(monthTotal.longValue()) + " so'm");
        mySalesMonthMeta.setText(monthSalesCount + " ta savdo · " + activeDays + " kun");

        SimpleDateFormat dateFormat = new SimpleDateFormat("d MMMM yyyy", uz);
        int dayCount = getMySalesCountForDate(mySalesSelectedDate);
        mySalesSelectedSummary.setText(dateFormat.format(mySalesSelectedDate.getTime())
                + "  ·  " + dayCount + " ta savdo  ·  Kunlik jami: "
                + formatMoney(getMySalesForDate(mySalesSelectedDate).longValue()) + " so'm");
        renderMySalesDayList();
    }

    private View buildMySalesDayCell(int day, BigDecimal amount, boolean selected) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(1), dp(2), dp(1), dp(2));
        int bg1, bg2, stroke, dayColor, amountColor;
        if (selected) {
            bg1 = 0xE52BC27B;
            bg2 = 0xD70E8053;
            stroke = 0xEEFFFFFF;
            dayColor = Color.WHITE;
            amountColor = Color.WHITE;
        } else if (amount.signum() > 0) {
            bg1 = 0xC83B7657;
            bg2 = 0xA82A5942;
            stroke = 0x7769D9A0;
            dayColor = WHITE;
            amountColor = LIGHT_GREEN;
        } else {
            bg1 = 0x55334B3D;
            bg2 = 0x33273B30;
            stroke = 0x446F927D;
            dayColor = MUTED;
            amountColor = MUTED;
        }
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{bg1, bg2});
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), stroke);
        cell.setBackground(background);
        TextView dayNumber = label(String.valueOf(day), 9, dayColor, true);
        dayNumber.setGravity(Gravity.CENTER);
        cell.addView(dayNumber, matchWrap());
        TextView amountText = label(amount.signum() > 0
                ? compactMySalesAmount(amount) : "·", 7, amountColor, true);
        amountText.setGravity(Gravity.CENTER);
        amountText.setSingleLine(true);
        cell.addView(amountText, matchWrap());
        cell.setOnClickListener(v -> selectMySalesDay(day));
        return cell;
    }

    private void renderMySalesDayList() {
        if (mySalesDayList == null || mySalesSelectedDate == null) return;
        mySalesDayList.removeAllViews();
        int count = 0;
        for (WasytSellerApi.Cart cart : myCompletedSales) {
            Calendar date = getCompletedSaleDate(cart);
            if (date == null || !isSameMySalesDate(date, mySalesSelectedDate)) continue;
            addMySalesEntry(cart, date);
            count++;
        }
        if (count == 0) {
            LinearLayout empty = card();
            empty.addView(label("Bu kunda yakunlangan savdolar yo'q.", 10, MUTED, false), matchWrap());
            mySalesDayList.addView(empty, matchWrap());
        }
    }

    private void addMySalesEntry(WasytSellerApi.Cart cart, Calendar saleDate) {
        LinearLayout item = card();
        LinearLayout.LayoutParams itemParams = matchWrap();
        itemParams.bottomMargin = dp(7);
        mySalesDayList.addView(item, itemParams);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        item.addView(top, matchWrap());
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        top.addView(info, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        info.addView(label("SAVDO  #" + (cart.saleId > 0 ? cart.saleId : cart.code),
                10, WHITE, true), matchWrap());
        info.addView(label(cart.clientName.isEmpty() ? "Mijoz tanlanmagan" : cart.clientName,
                9, MUTED, false), matchWrap());

        TextView amount = label(formatMoney(cart.totalAmount) + " so'm", 13, LIGHT_GREEN, true);
        amount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        top.addView(amount, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.US);
        String meta = time.format(saleDate.getTime()) + "  ·  "
                + cart.productsCount + " ta mahsulot";
        if (cart.saleId > 0) meta += "  ·  Kassa #" + cart.saleId;
        item.addView(label(meta, 8, MUTED, false), matchWrap());
        item.setOnClickListener(v -> showCartDetails(cart));
    }

    private void showCartHistory() {
        historyPage = 1;
        historyFilter = "all";
        historyToken = WasytSecureStore.getValidToken(this);
        historyAllCarts.clear();
        historyAllLoaded = false;
        historyLoadingAll = false;

        historyDialog = new Dialog(this);
        LinearLayout shell = card();
        shell.setPadding(dp(14), dp(13), dp(14), dp(12));
        shell.setBackground(rounded(0xFF214A36, 0xFF0C2118, 22, 0xBFFFFFFF));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        shell.addView(heading, matchWidth(dp(42)));
        LinearLayout headingText = new LinearLayout(this);
        headingText.setOrientation(LinearLayout.VERTICAL);
        heading.addView(headingText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        headingText.addView(label("AKSO  /  WASYT", 8, MUTED, true), matchWrap());
        headingText.addView(label("MENING SAVATLARIM", 14, WHITE, true), matchWrap());

        TextView refresh = button("YANGILASH", false);
        refresh.setTextSize(8);
        heading.addView(refresh, new LinearLayout.LayoutParams(dp(82), dp(36)));
        refresh.setOnClickListener(v -> {
            if (historyLoadingAll) {
                Toast.makeText(this, "Tarix hozir yangilanmoqda.", Toast.LENGTH_SHORT).show();
                return;
            }
            historyPage = 1;
            loadHistoryPage();
        });
        TextView close = button("YOPISH", false);
        close.setTextSize(8);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(65), dp(36));
        closeParams.leftMargin = dp(5);
        heading.addView(close, closeParams);
        close.setOnClickListener(v -> historyDialog.dismiss());

        historySummaryLabel = label("Savatlar tarixi yuklanmoqda…", 10, LIGHT_GREEN, true);
        LinearLayout.LayoutParams summaryParams = matchWidth(dp(28));
        summaryParams.topMargin = dp(7);
        shell.addView(historySummaryLabel, summaryParams);

        HorizontalScrollView filterScroll = new HorizontalScrollView(this);
        filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams filterScrollParams = matchWidth(dp(42));
        filterScrollParams.topMargin = dp(4);
        shell.addView(filterScroll, filterScrollParams);
        historyFiltersPanel = new LinearLayout(this);
        historyFiltersPanel.setOrientation(LinearLayout.HORIZONTAL);
        filterScroll.addView(historyFiltersPanel, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView listScroll = new ScrollView(this);
        listScroll.setFillViewport(false);
        LinearLayout.LayoutParams listScrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listScrollParams.topMargin = dp(8);
        listScrollParams.bottomMargin = dp(8);
        shell.addView(listScroll, listScrollParams);
        historyListPanel = new LinearLayout(this);
        historyListPanel.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(historyListPanel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout pager = new LinearLayout(this);
        pager.setGravity(Gravity.CENTER_VERTICAL);
        shell.addView(pager, matchWidth(dp(42)));
        TextView previous = button("‹  OLDINGI", false);
        TextView next = button("KEYINGI  ›", false);
        pager.addView(previous, new LinearLayout.LayoutParams(0, dp(40), 1f));
        LinearLayout.LayoutParams pageParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        pageParams.leftMargin = dp(7);
        pager.addView(next, pageParams);
        previous.setOnClickListener(v -> {
            if (historyPage > 1) {
                historyPage--;
                renderHistoryPage();
            }
        });
        next.setOnClickListener(v -> {
            if (historyPage < historyLastPage) {
                historyPage++;
                renderHistoryPage();
            }
        });

        historyPageLabel = label("", 9, MUTED, false);
        historyPageLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pageLabelParams = matchWidth(dp(20));
        pageLabelParams.topMargin = dp(3);
        shell.addView(historyPageLabel, pageLabelParams);

        historyDialog.setContentView(shell);
        historyDialog.setCancelable(true);
        historyDialog.show();
        Window window = historyDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = getResources().getDisplayMetrics().widthPixels - dp(30);
            int height = Math.min(getResources().getDisplayMetrics().heightPixels - dp(110), dp(720));
            window.setLayout(width, height);
        }
        loadHistoryPage();
    }

    /**
     * Load every server page sequentially. The visible status filters and local
     * pagination then operate across the complete history, not just the current
     * API page. Requests stay sequential to respect WASYT rate limits.
     */
    private void loadHistoryPage() {
        if (historyDialog == null || !historyDialog.isShowing() || historyLoadingAll) return;
        historyLoadingAll = true;
        historyAllLoaded = false;
        historyPage = 1;
        historyAllCarts.clear();
        historyServerTotal = 0;
        historyServerLastPage = 1;
        historyListPanel.removeAllViews();
        historyListPanel.addView(label("Savatlar tarixi serverdan olinmoqda…", 11, MUTED, false), matchWrap());
        historySummaryLabel.setText("WASYT tarixi yuklanmoqda…");

        final String token = historyToken;
        network.execute(() -> {
            try {
                WasytSellerApi.CartPage first = WasytSellerApi.listCartPage(token, 1);
                ArrayList<WasytSellerApi.Cart> accumulated = new ArrayList<>(first.carts);
                final int lastPage = Math.max(1, first.lastPage);
                final int serverTotal = first.total;
                ArrayList<WasytSellerApi.Cart> firstSnapshot = new ArrayList<>(accumulated);
                main.post(() -> {
                    if (!isHistoryDialogVisible()) return;
                    historyServerLastPage = lastPage;
                    historyServerTotal = serverTotal;
                    historyAllCarts.clear();
                    historyAllCarts.addAll(firstSnapshot);
                    renderHistoryPage();
                });

                for (int pageNo = 2; pageNo <= lastPage; pageNo++) {
                    WasytSellerApi.CartPage next = WasytSellerApi.listCartPage(token, pageNo);
                    if (next.carts != null) accumulated.addAll(next.carts);

                    if (pageNo == lastPage || pageNo % 3 == 0) {
                        ArrayList<WasytSellerApi.Cart> snapshot = new ArrayList<>(accumulated);
                        final int completedPages = pageNo;
                        main.post(() -> {
                            if (!isHistoryDialogVisible()) return;
                            historyAllCarts.clear();
                            historyAllCarts.addAll(snapshot);
                            historySummaryLabel.setText("Savatlar yuklanmoqda: "
                                    + completedPages + " / " + lastPage + " sahifa  ·  "
                                    + snapshot.size() + " / " + serverTotal + " ta");
                            renderHistoryPage();
                        });
                    }
                }

                ArrayList<WasytSellerApi.Cart> complete = new ArrayList<>(accumulated);
                main.post(() -> {
                    if (!isHistoryDialogVisible()) return;
                    historyAllCarts.clear();
                    historyAllCarts.addAll(complete);
                    historyAllLoaded = true;
                    historyLoadingAll = false;
                    historySummaryLabel.setText("Yuklandi: " + complete.size()
                            + " / " + serverTotal + " ta savat");
                    renderHistoryPage();
                });
            } catch (Exception error) {
                String message = error.getMessage() == null
                        ? "Savatlar tarixini yuklab bo'lmadi." : error.getMessage();
                main.post(() -> {
                    if (!isHistoryDialogVisible()) return;
                    historyLoadingAll = false;
                    historySummaryLabel.setText("Tarix to'liq yuklanmadi. Qayta yangilang.");
                    renderHistoryPage();
                    showError("Savatlar tarixi", message);
                });
            }
        });
    }

    private static final String PREF_CANCEL_REASONS = "wasyt_seller_cancel_reasons";

    private void saveLocalCancellationMetadata(long cartId, String reason, String comment) {
        if (cartId <= 0) return;
        SharedPreferences preferences = getSharedPreferences(PREF_CANCEL_REASONS, MODE_PRIVATE);
        preferences.edit()
                .putBoolean("cancelled_" + cartId, true)
                .putString("reason_" + cartId, reason == null ? "" : reason)
                .putString("comment_" + cartId, comment == null ? "" : comment)
                .apply();
    }

    private boolean isCartCancelled(WasytSellerApi.Cart cart) {
        if (cart == null) return false;
        String status = safeLower(cart.status);
        String sale = safeLower(cart.saleStatus);
        String combined = status + " " + sale;
        if (combined.contains("cancel") || combined.contains("reject")) return true;
        if (cart.id <= 0) return false;
        return getSharedPreferences(PREF_CANCEL_REASONS, MODE_PRIVATE)
                .getBoolean("cancelled_" + cart.id, false);
    }

    private String getDisplayedCancelReason(WasytSellerApi.Cart cart) {
        if (cart != null && !cart.cancelReason.isEmpty()) return cart.cancelReason;
        if (cart == null || cart.id <= 0) return "";
        return getSharedPreferences(PREF_CANCEL_REASONS, MODE_PRIVATE)
                .getString("reason_" + cart.id, "");
    }

    private String getDisplayedCancelComment(WasytSellerApi.Cart cart) {
        if (cart != null && !cart.cancelComment.isEmpty()) return cart.cancelComment;
        if (cart == null || cart.id <= 0) return "";
        return getSharedPreferences(PREF_CANCEL_REASONS, MODE_PRIVATE)
                .getString("comment_" + cart.id, "");
    }

    private boolean isHistoryDialogVisible() {
        return !isFinishing() && !isDestroyed()
                && historyDialog != null && historyDialog.isShowing();
    }

    private List<WasytSellerApi.Cart> filteredHistoryCarts() {
        ArrayList<WasytSellerApi.Cart> filtered = new ArrayList<>();
        for (WasytSellerApi.Cart cart : historyAllCarts) {
            if (matchesHistoryFilter(cart)) filtered.add(cart);
        }
        return filtered;
    }

    private void renderHistoryPage() {
        if (historyListPanel == null || historyFiltersPanel == null || historyPageLabel == null) return;
        historyFiltersPanel.removeAllViews();
        addHistoryFilter("all", "HAMMASI");
        addHistoryFilter("picking", "TANLOVDA");
        addHistoryFilter("sent", "KASSADA");
        addHistoryFilter("completed", "YAKUNLANGAN");
        addHistoryFilter("cancelled", "BEKOR QILINGAN");

        List<WasytSellerApi.Cart> filtered = filteredHistoryCarts();
        historyTotal = filtered.size();
        historyLastPage = Math.max(1, (historyTotal + 19) / 20);
        if (historyPage > historyLastPage) historyPage = historyLastPage;
        int start = (historyPage - 1) * 20;
        int end = Math.min(start + 20, historyTotal);

        historyListPanel.removeAllViews();
        for (int i = start; i < end; i++) addHistoryCartCard(filtered.get(i));

        if (start == end) {
            LinearLayout empty = card();
            String message;
            if (historyLoadingAll) {
                message = "Qolgan sahifalar yuklanmoqda. Natijalar avtomatik yangilanadi.";
            } else if (historyTotal == 0 && historyAllLoaded) {
                message = "Tanlangan holatda savatlar topilmadi.";
            } else if (historyTotal == 0) {
                message = "Savatlar tarixini yuklash uchun YANGILASH tugmasini bosing.";
            } else {
                message = "Bu sahifada savatlar yo'q.";
            }
            empty.addView(label(message, 10, MUTED, false), matchWrap());
            historyListPanel.addView(empty, matchWrap());
        }

        String progress = historyLoadingAll
                ? "  ·  yuklanmoqda " + historyAllCarts.size() + "/" + historyServerTotal
                : "";
        historyPageLabel.setText("Jami " + historyTotal + " ta  ·  "
                + historyPage + " / " + historyLastPage + "-sahifa" + progress);
    }

    private void addHistoryFilter(String key, String text) {
        boolean selected = historyFilter.equals(key);
        TextView chip = label(text, 9, selected ? WHITE : MUTED, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        chip.setBackground(rounded(selected ? 0xFF087A43 : 0x803A5C48,
                selected ? 0xFF0BB34B : 0x801C3328, 13,
                selected ? 0xAAE8FFF1 : 0x6688A997));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        params.rightMargin = dp(6);
        historyFiltersPanel.addView(chip, params);
        chip.setOnClickListener(v -> {
            historyFilter = key;
            historyPage = 1;
            renderHistoryPage();
        });
    }

    private boolean matchesHistoryFilter(WasytSellerApi.Cart cart) {
        if ("all".equals(historyFilter)) return true;
        String status = safeLower(cart.status);
        String sale = safeLower(cart.saleStatus);
        String combined = status + " " + sale;
        if ("cancelled".equals(historyFilter)) return isCartCancelled(cart);
        if ("completed".equals(historyFilter)) return isCompletedSaleCart(cart);
        if ("sent".equals(historyFilter)) {
            return (status.equals("sent") || status.equals("cashier") || status.equals("cash"))
                    && !isCartCancelled(cart)
                    && !isCompletedSaleCart(cart);
        }
        if ("picking".equals(historyFilter)) {
            return (status.equals("picking") || status.equals("reserved"))
                    && !isCartCancelled(cart);
        }
        return true;
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private String cartStatusLabel(WasytSellerApi.Cart cart) {
        String status = safeLower(cart.status);
        String sale = safeLower(cart.saleStatus);
        String combined = status + " " + sale;
        if (combined.contains("partial") && combined.contains("cancel")) return "QISMAN BEKOR";
        if (combined.contains("partial") && combined.contains("return")) return "QISMAN QAYTARILGAN";
        if (combined.contains("return")) return "QAYTARILGAN";
        if (isCartCancelled(cart)) return "BEKOR QILINGAN";
        if (status.equals("picking") || status.equals("reserved")) return "TANLOVDA";
        if (isCompletedSaleCart(cart)) return "YAKUNLANGAN";
        if (status.equals("sent") || status.equals("cashier") || status.equals("cash")) {
            return "KASSADA";
        }
        return cart.status.isEmpty() ? "HOLAT NOMA'LUM" : cart.status.toUpperCase(Locale.ROOT);
    }

    private void addHistoryCartCard(WasytSellerApi.Cart cart) {
        LinearLayout item = card();
        LinearLayout.LayoutParams itemParams = matchWrap();
        itemParams.bottomMargin = dp(8);
        historyListPanel.addView(item, itemParams);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        item.addView(top, matchWrap());
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        top.addView(heading, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        heading.addView(label("SAVAT  #" + cart.code, 11, WHITE, true), matchWrap());
        heading.addView(label(cart.clientName.isEmpty() ? "Mijoz tanlanmagan" : cart.clientName,
                10, MUTED, false), matchWrap());

        TextView state = label(cartStatusLabel(cart), 8, LIGHT_GREEN, true);
        state.setGravity(Gravity.CENTER);
        state.setPadding(dp(8), dp(6), dp(8), dp(6));
        state.setBackground(rounded(0x5537B978, 0x3320A45F, 10, 0x6678E4A8));
        top.addView(state, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)));

        TextView amount = label(formatMoney(cart.totalAmount) + " so'm", 15, LIGHT_GREEN, true);
        LinearLayout.LayoutParams amountParams = matchWidth(dp(27));
        amountParams.topMargin = dp(7);
        item.addView(amount, amountParams);
        String sub = cart.productsCount + " ta mahsulot  ·  " + cart.createdAt;
        if (!cart.sentAt.isEmpty()) sub += "  ·  Kassaga: " + cart.sentAt;
        item.addView(label(sub, 8, MUTED, false), matchWrap());
        if (!cart.sellerName.isEmpty()) {
            item.addView(label("Sotuvchi: " + cart.sellerName, 8, MUTED, false), matchWrap());
        }
        if (cart.saleId > 0) {
            item.addView(label("Kassa savdosi #" + cart.saleId, 8, LIGHT_GREEN, false), matchWrap());
        }
        String localReason = getDisplayedCancelReason(cart);
        if (!localReason.isEmpty()) {
            item.addView(label("Bekor qilish sababi: " + localReason, 8, RED, true), matchWrap());
        }
        item.setOnClickListener(v -> showCartDetails(cart));
    }

    private void showCartDetails(WasytSellerApi.Cart summary) {
        runNetwork("Savat tafsilotlari olinmoqda…", () -> {
            WasytSellerApi.CartDetails details = WasytSellerApi.getCartDetails(
                    WasytSecureStore.getValidToken(this), summary.id);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                updateHistoryCart(details.cart);
                renderCartDetails(details);
            });
        });
    }

    private void updateHistoryCart(WasytSellerApi.Cart updated) {
        if (updated == null || updated.id <= 0) return;
        for (int i = 0; i < historyAllCarts.size(); i++) {
            if (historyAllCarts.get(i).id == updated.id) {
                historyAllCarts.set(i, updated);
                if (isHistoryDialogVisible()) renderHistoryPage();
                return;
            }
        }
    }

    private String saleLifecycleLabel(WasytSellerApi.Cart cart) {
        String sale = safeLower(cart.saleStatus);
        if (sale.equals("completed") || sale.equals("done")
                || sale.equals("finished") || sale.equals("paid")
                || sale.equals("closed")) return "YAKUNLANGAN";
        if (sale.equals("pending") || sale.equals("processing")
                || sale.equals("in_progress") || sale.equals("in-progress")) {
            return "JARAYONDA";
        }
        if (sale.contains("cancel") || sale.contains("reject")) return "BEKOR QILINGAN";
        if (sale.contains("return") || sale.contains("refund")) return "QAYTARILGAN";
        if (cart.saleId > 0) return "KASSA SAVDOSI BOG'LANGAN";
        String status = safeLower(cart.status);
        if (status.equals("sent") || status.equals("cashier") || status.equals("cash")) {
            return "KASSAGA YUBORILGAN";
        }
        return "KASSA SAVDOSI HALI YO'Q";
    }

    private void renderCartDetails(WasytSellerApi.CartDetails details) {
        WasytSellerApi.Cart cart = details.cart;
        Dialog dialog = new Dialog(this);
        LinearLayout shell = card();
        shell.setPadding(dp(15), dp(14), dp(15), dp(13));
        shell.setBackground(rounded(0xFF214A36, 0xFF0C2118, 20, 0xBFFFFFFF));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        shell.addView(heading, matchWidth(dp(38)));
        heading.addView(label("SAVAT  #" + cart.code, 14, WHITE, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = button("YOPISH", false);
        heading.addView(close, new LinearLayout.LayoutParams(dp(70), dp(34)));
        close.setOnClickListener(v -> dialog.dismiss());

        TextView state = label(cartStatusLabel(cart), 10, LIGHT_GREEN, true);
        LinearLayout.LayoutParams stateParams = matchWidth(dp(25));
        stateParams.topMargin = dp(3);
        shell.addView(state, stateParams);
        addDetailLine(shell, "Mijoz", cart.clientName);
        if (!details.clientPhone.isEmpty()) addDetailLine(shell, "Telefon", details.clientPhone);
        if (!cart.sellerName.isEmpty()) addDetailLine(shell, "Sotuvchi", cart.sellerName);
        addDetailLine(shell, "Yaratilgan", cart.createdAt);
        if (!cart.sentAt.isEmpty()) addDetailLine(shell, "Kassaga yuborilgan", cart.sentAt);
        addDetailLine(shell, "Savat holati", cartStatusLabel(cart));
        addDetailLine(shell, "Savdo holati", saleLifecycleLabel(cart));
        if (cart.saleId > 0) addDetailLine(shell, "Kassa savdosi ID", String.valueOf(cart.saleId));
        if (!cart.saleStatus.isEmpty()) addDetailLine(shell, "WASYT savdo kodi", cart.saleStatus);
        addDetailLine(shell, "Ombordan bron", details.reserved ? "Ha" : "Yo'q");

        TextView productsTitle = label("MAHSULOTLAR  (" + details.products.size() + ")", 10, MUTED, true);
        LinearLayout.LayoutParams productsTitleParams = matchWidth(dp(28));
        productsTitleParams.topMargin = dp(7);
        shell.addView(productsTitle, productsTitleParams);
        ScrollView productsScroll = new ScrollView(this);
        shell.addView(productsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));
        LinearLayout products = new LinearLayout(this);
        products.setOrientation(LinearLayout.VERTICAL);
        productsScroll.addView(products, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (details.products.isEmpty()) {
            products.addView(label("Mahsulot tafsilotlari serverdan qaytmadi.", 10, MUTED, false), matchWrap());
        } else {
            for (WasytSellerApi.CartProduct product : details.products) {
                LinearLayout line = new LinearLayout(this);
                line.setOrientation(LinearLayout.VERTICAL);
                line.setPadding(0, dp(5), 0, dp(5));
                products.addView(line, matchWrap());
                line.addView(label(product.name, 10, WHITE, true), matchWrap());
                String meta = product.quantity + " " + product.unit + " × "
                        + formatMoney(product.price) + " so'm";
                if (!product.barcode.isEmpty()) meta += "  ·  " + product.barcode;
                line.addView(label(meta, 9, MUTED, false), matchWrap());
                if (product.discount > 0) {
                    line.addView(label("Mahsulot chegirmasi: -" + formatMoney(product.discount)
                            + " so'm", 9, RED, false), matchWrap());
                }
                View divider = new View(this);
                divider.setBackgroundColor(0x33FFFFFF);
                products.addView(divider, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            }
        }

        addDetailLine(shell, "Mahsulotlar summasi", formatMoney(cart.amount) + " so'm");
        addDetailLine(shell, "Chegirma", "-" + formatMoney(cart.discountAmount) + " so'm");
        TextView total = label("JAMI: " + formatMoney(cart.totalAmount) + " so'm",
                17, LIGHT_GREEN, true);
        total.setGravity(Gravity.END);
        LinearLayout.LayoutParams totalParams = matchWidth(dp(35));
        totalParams.topMargin = dp(5);
        shell.addView(total, totalParams);
        String cancelReason = getDisplayedCancelReason(cart);
        String cancelComment = getDisplayedCancelComment(cart);
        if (!cancelReason.isEmpty()) {
            addDetailLine(shell, "Bekor qilish sababi",
                    cancelReason + (cart.cancelReason.isEmpty() ? " (shu qurilmada)" : ""));
            if (!cancelComment.isEmpty()) addDetailLine(shell, "Izoh", cancelComment);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams actionsParams = matchWidth(dp(43));
        actionsParams.topMargin = dp(8);
        shell.addView(actions, actionsParams);
        TextView refresh = button("HOLATNI YANGILASH", false);
        actions.addView(refresh, new LinearLayout.LayoutParams(
                isCartCancelable(cart) ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42), isCartCancelable(cart) ? 1f : 0f));
        refresh.setOnClickListener(v -> {
            dialog.dismiss();
            showCartDetails(cart);
        });
        if (isCartCancelable(cart)) {
            TextView cancel = button("SAVATNI BEKOR QILISH", false);
            cancel.setTextColor(RED);
            cancel.setBackground(rounded(0x663D211F, 0x66251515, 13, 0x99E36A65));
            LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
            cancelParams.leftMargin = dp(7);
            actions.addView(cancel, cancelParams);
            cancel.setOnClickListener(v -> {
                dialog.dismiss();
                confirmCancelCart(cart);
            });
        }

        dialog.setContentView(shell);
        dialog.setCancelable(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = getResources().getDisplayMetrics().widthPixels - dp(34);
            int height = Math.min(getResources().getDisplayMetrics().heightPixels - dp(110), dp(700));
            window.setLayout(width, height);
        }
    }

    private void addDetailLine(LinearLayout parent, String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        parent.addView(row, matchWrap());
        row.addView(label(key, 9, MUTED, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView valueView = label(value == null ? "" : value, 9, WHITE, true);
        valueView.setGravity(Gravity.END);
        row.addView(valueView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f));
    }

    private boolean isCartCancelable(WasytSellerApi.Cart cart) {
        String status = safeLower(cart.status);
        String sale = safeLower(cart.saleStatus);
        return (status.equals("picking") || status.equals("reserved") || status.equals("sent"))
                && !sale.equals("completed") && !sale.equals("done")
                && !status.contains("cancel");
    }

    private void confirmCancelCart(WasytSellerApi.Cart cart) {
        Dialog dialog = new Dialog(this);
        LinearLayout shell = card();
        shell.setPadding(dp(16), dp(15), dp(16), dp(14));
        shell.setBackground(rounded(0xFF214A36, 0xFF0C2118, 20, 0xBFFFFFFF));
        shell.addView(label("SAVATNI BEKOR QILISH", 14, WHITE, true), matchWrap());
        LinearLayout.LayoutParams messageParams = matchWidth(dp(38));
        messageParams.topMargin = dp(4);
        shell.addView(label("#" + cart.code + " savati bekor qilinadi. Sababini tanlang.",
                9, MUTED, false), messageParams);

        String[] reasons = {
                "Takroriy / xato yaratilgan",
                "Mijoz voz kechdi",
                "Eskirgan",
                "Boshqa"
        };
        RadioGroup reasonGroup = new RadioGroup(this);
        reasonGroup.setOrientation(RadioGroup.VERTICAL);
        RadioButton[] choices = new RadioButton[reasons.length];
        for (int i = 0; i < reasons.length; i++) {
            RadioButton choice = new RadioButton(this);
            choice.setId(View.generateViewId());
            choice.setText(reasons[i]);
            choice.setTextColor(WHITE);
            choice.setButtonTintList(android.content.res.ColorStateList.valueOf(GREEN));
            reasonGroup.addView(choice, matchWrap());
            choices[i] = choice;
        }
        choices[1].setChecked(true);
        reasonGroup.check(choices[1].getId());
        shell.addView(reasonGroup, matchWrap());

        EditText comment = input("Qo'shimcha izoh (ixtiyoriy)", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        comment.setSingleLine(false);
        comment.setGravity(Gravity.TOP | Gravity.START);
        comment.setMinLines(2);
        comment.setPadding(dp(11), dp(9), dp(11), dp(9));
        LinearLayout.LayoutParams commentParams = matchWidth(dp(64));
        commentParams.topMargin = dp(4);
        shell.addView(comment, commentParams);
        comment.setVisibility(View.GONE);
        reasonGroup.setOnCheckedChangeListener((group, checkedId) -> {
            comment.setVisibility(checkedId == choices[3].getId() ? View.VISIBLE : View.GONE);
        });

        LinearLayout actions = new LinearLayout(this);
        LinearLayout.LayoutParams actionsParams = matchWidth(dp(44));
        actionsParams.topMargin = dp(10);
        shell.addView(actions, actionsParams);
        TextView back = button("ORTGA", false);
        TextView confirm = button("BEKOR QILISH", true);
        confirm.setBackground(rounded(0xFFE65F5A, 0xFFC83D3D, 13, 0xAAFFD9D7));
        actions.addView(back, new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        confirmParams.leftMargin = dp(8);
        actions.addView(confirm, confirmParams);
        back.setOnClickListener(v -> dialog.dismiss());
        confirm.setOnClickListener(v -> {
            int selected = reasonGroup.getCheckedRadioButtonId();
            RadioButton checked = selected == -1 ? null : reasonGroup.findViewById(selected);
            if (checked == null) {
                showError("Bekor qilish sababi", "Bekor qilish sababini tanlang.");
                return;
            }
            String reason = checked.getText().toString();
            String note = comment.getText().toString().trim();
            if ("Boshqa".equals(reason) && note.isEmpty()) {
                comment.setError("Sababni izohlang.");
                comment.setVisibility(View.VISIBLE);
                comment.requestFocus();
                return;
            }
            dialog.dismiss();
            String token = WasytSecureStore.getValidToken(this);
            runNetwork("Savat bekor qilinmoqda…", () -> {
                WasytSellerApi.cancelCart(token, cart.id, reason, note);
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    saveLocalCancellationMetadata(cart.id, reason, note);
                    showStatus("Savat #" + cart.code + " bekor qilindi. Sabab ushbu qurilmada saqlandi.", LIGHT_GREEN);
                    loadHistoryPage();
                });
            });
        });

        dialog.setContentView(shell);
        dialog.setCancelable(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(getResources().getDisplayMetrics().widthPixels - dp(38),
                    WindowManager.LayoutParams.WRAP_CONTENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void runNetwork(String message, NetworkTask task) {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setTitle("WASYT");
        progress.setMessage(message);
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        if (!isFinishing() && !isDestroyed()) progress.show();

        network.execute(() -> {
            try {
                task.run();
            } catch (Exception error) {
                String detail = error.getMessage() == null
                        ? "WASYT bilan ishlashda xatolik yuz berdi." : error.getMessage();
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    showError("WASYT sotuvchi moduli", detail);
                });
            } finally {
                main.post(() -> {
                    if (progress.isShowing()) {
                        try { progress.dismiss(); } catch (RuntimeException ignored) { }
                    }
                });
            }
        });
    }

    private interface NetworkTask { void run() throws Exception; }

    /** Value typed by the seller: final payable total in fixed mode, percent otherwise. */
    private long parseDiscountInput() throws Exception {
        String raw = discountInput == null ? "" : discountInput.getText().toString().trim();
        if (raw.isEmpty()) return 0L;
        BigDecimal value = new BigDecimal(raw.replace(" ", "").replace(",", "."));
        if (value.signum() < 0) throw new Exception("Qiymat manfiy bo'lmasligi kerak.");
        return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private long calculateSubtotal() {
        long sum = 0L;
        for (CartItem item : cartItems) {
            sum = safeAdd(sum, safeMultiply(item.product.priceAmount, item.quantity));
        }
        return sum;
    }

    /**
     * WASYT API field discount_value is the actual discount amount even when
     * discount_mode=integer. The UI's fixed-sum mode is intentionally different:
     * the seller enters the final amount the customer will pay.
     */
    private long calculateDiscountAmount() throws Exception {
        long subtotal = calculateSubtotal();
        boolean hasInput = discountInput != null
                && !discountInput.getText().toString().trim().isEmpty();
        long entered = parseDiscountInput();
        if (fixedDiscount) {
            // An untouched fixed-total field means no discount, not a free sale.
            if (!hasInput) return 0L;
            if (entered > subtotal) {
                throw new Exception("Yakuniy summa mahsulotlar summasidan katta bo'lmasligi kerak.");
            }
            return Math.max(0L, subtotal - entered);
        }
        if (entered > 100L) {
            throw new Exception("Foizli chegirma 100% dan oshmasligi kerak.");
        }
        return BigDecimal.valueOf(subtotal)
                .multiply(BigDecimal.valueOf(entered))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValue();
    }

    private long calculateTotalAfterDiscount() {
        long subtotal = calculateSubtotal();
        try {
            if (fixedDiscount) {
                if (discountInput == null
                        || discountInput.getText().toString().trim().isEmpty()) {
                    return subtotal;
                }
                long enteredFinalTotal = parseDiscountInput();
                // Keep the preview within the available subtotal while typing;
                // checkout validates and rejects values greater than subtotal.
                return Math.max(0L, Math.min(enteredFinalTotal, subtotal));
            }
            return Math.max(0L, subtotal - calculateDiscountAmount());
        } catch (Exception ignored) {
            return subtotal;
        }
    }

    private void updateTotal() {
        if (totalView != null) totalView.setText("JAMI: "
                + formatMoney(calculateTotalAfterDiscount()) + " so'm");
    }

    private void updateReserveLabel() {
        if (reserveButton != null) {
            reserveButton.setText((reserveStock ? "✓" : "○")
                    + "  OMBORDAN BRON QILISH  ·  "
                    + (reserveStock ? "YOQILGAN" : "O'CHIRILGAN"));
        }
    }

    private void showStatus(String message, int color) {
        if (statusView != null) {
            statusView.setText(message == null ? "" : message);
            statusView.setTextColor(color);
        }
    }

    private void showError(String title, String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton("Tushunarli", null).show();
    }

    private String formatMoney(long value) {
        return String.format(Locale.US, "%,d", Math.max(0L, value)).replace(',', ' ');
    }

    private static long safeMultiply(long a, long b) {
        try { return Math.multiplyExact(a, b); }
        catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }

    private static long safeAdd(long a, long b) {
        try { return Math.addExact(a, b); }
        catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }

    private LinearLayout card() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(11), dp(12), dp(11));
        panel.setBackground(rounded(CARD, 0xB91E3428, 17, 0x66FFFFFF));
        return panel;
    }

    private GradientDrawable rounded(int top, int bottom, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{top, bottom});
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private EditText input(String hint, int type) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setHint(hint);
        edit.setInputType(type);
        edit.setTextSize(12);
        edit.setTextColor(WHITE);
        edit.setHintTextColor(0xFF9BB2A2);
        edit.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xAA12281D);
        bg.setCornerRadius(dp(13));
        bg.setStroke(dp(1), 0x88FFFFFF);
        edit.setBackground(bg);
        return edit;
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setIncludeFontPadding(true);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private TextView button(String value, boolean primary) {
        TextView view = label(value, 11, WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(8), dp(8), dp(8));
        view.setClickable(true);
        view.setFocusable(true);
        view.setBackground(primary
                ? rounded(0xFF1CCB7A, 0xFF078747, 14, 0xAAE8FFF1)
                : rounded(0xB83A5C48, 0xA91C3328, 14, 0x88FFFFFF));
        return view;
    }

    private void addSpace(LinearLayout parent, int heightDp) {
        View space = new View(this);
        parent.addView(space, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWidth(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        if (sellerScroll != null && sellerKeyboardLayoutListener != null) {
            try {
                sellerScroll.getViewTreeObserver()
                        .removeOnGlobalLayoutListener(sellerKeyboardLayoutListener);
            } catch (RuntimeException ignored) { }
        }
        main.removeCallbacksAndMessages(null);
        network.shutdownNow();
        if (historyDialog != null && historyDialog.isShowing()) {
            try { historyDialog.dismiss(); } catch (RuntimeException ignored) { }
        }
        if (mySalesDialog != null && mySalesDialog.isShowing()) {
            try { mySalesDialog.dismiss(); } catch (RuntimeException ignored) { }
        }
        super.onDestroy();
    }
}