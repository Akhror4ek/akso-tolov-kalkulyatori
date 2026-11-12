package uz.akso.kalkulyator;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Google Code Scanner + seller-facing WASYT catalogue search.
 * Read-only GET requests only. No writes to WASYT or Firebase.
 */
final class WasytBarcodeScanner {
    private static final String BASE_URL = "https://api.wasyt.uz";
    private static final String WEB_ORIGIN = "https://app.wasyt.uz";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WasytBarcodeScanner() { }

    interface Listener {
        void onScanStarted();
        void onLookupStarted(String barcode);
        void onProductsFound(String barcode, List<ProductInfo> products);
        void onCancelled();
        void onError(String message);
    }

    static final class ProductInfo {
        final long id;
        final String name;
        final String barcode;
        final long priceAmount;
        final String currency;
        final String stockQuantity;

        ProductInfo(long id, String name, String barcode, long priceAmount,
                    String currency, String stockQuantity) {
            this.id = id;
            this.name = name;
            this.barcode = barcode;
            this.priceAmount = priceAmount;
            this.currency = currency == null ? "" : currency.trim().toLowerCase(Locale.ROOT);
            this.stockQuantity = stockQuantity;
        }

        boolean hasPrice() { return priceAmount > 0; }
        boolean hasUzsPrice() { return hasPrice() && "uzs".equalsIgnoreCase(currency); }
    }

    private static final class ProductAccumulator {
        final long id;
        final String name;
        final String barcode;
        long priceAmount;
        String currency;
        BigDecimal stockTotal = BigDecimal.ZERO;
        boolean hasStock;

        ProductAccumulator(long id, String name, String barcode,
                           long priceAmount, String currency) {
            this.id = id;
            this.name = name;
            this.barcode = barcode;
            this.priceAmount = priceAmount;
            this.currency = currency == null ? "" : currency;
        }
    }

    private static final class PriceValue {
        final long amount;
        final String currency;
        PriceValue(long amount, String currency) {
            this.amount = amount;
            this.currency = currency == null ? "" : currency;
        }
    }

    static void start(Activity activity, Listener listener) {
        if (activity == null || listener == null) return;

        String token = WasytSecureStore.getValidToken(activity);
        if (token == null) {
            listener.onError("WASYT akkaunti ulanmagan yoki token muddati tugagan. Qayta ulang.");
            return;
        }

        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                        Barcode.FORMAT_EAN_13,
                        Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_UPC_A,
                        Barcode.FORMAT_UPC_E,
                        Barcode.FORMAT_CODE_128,
                        Barcode.FORMAT_ITF,
                        Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build();

        GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(activity, options);
        listener.onScanStarted();
        scanner.startScan()
                .addOnSuccessListener(barcode -> {
                    String rawValue = barcode == null ? null : barcode.getRawValue();
                    if (rawValue == null || rawValue.trim().isEmpty()) {
                        listener.onError("Shtrix-kod o‘qilmadi. Qayta skanerlang.");
                        return;
                    }

                    String code = rawValue.trim();
                    listener.onLookupStarted(code);
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    executor.execute(() -> {
                        try {
                            long companyId = WasytSecureStore.getCompanyId(activity);
                            List<ProductInfo> products = searchProducts(token, code, true, companyId);
                            MAIN.post(() -> {
                                if (!activity.isFinishing() && !activity.isDestroyed()) {
                                    listener.onProductsFound(code, products);
                                }
                            });
                        } catch (Exception error) {
                            String message = error.getMessage() == null
                                    ? "WASYT mahsulot qidiruvida xatolik yuz berdi."
                                    : error.getMessage();
                            MAIN.post(() -> {
                                if (!activity.isFinishing() && !activity.isDestroyed()) {
                                    listener.onError(message);
                                }
                            });
                        } finally {
                            executor.shutdown();
                        }
                    });
                })
                .addOnCanceledListener(listener::onCancelled)
                .addOnFailureListener(error -> {
                    String message = error.getMessage() == null
                            ? "Skaner ishga tushmadi. Google Play xizmatlarini tekshiring."
                            : "Skaner ishga tushmadi: " + shortText(error.getMessage());
                    listener.onError(message);
                });
    }

    /**
     * Searches the selected seller warehouse. The catalog-wide endpoint
     * /company/stock/product/catalog returns HTTP 403 for this seller role.
     * The documented seller warehouse endpoint supports filter[stock_id] and
     * filter[common] (product name, barcode or PLU) and includes product/price relations.
     */
    static List<ProductInfo> searchByText(String token, String query, long companyId)
            throws IOException {
        String term = query == null ? "" : query.trim();
        if (term.length() < 2) {
            throw new IOException("Mahsulot nomidan kamida 2 ta belgi yoki to'liq shtrix-kod kiriting.");
        }
        return searchProducts(token, term, false, companyId);
    }

    private static List<ProductInfo> searchProducts(String token, String query,
                                                     boolean barcodeOnly, long companyId)
            throws IOException {
        if (companyId <= 0) {
            throw new IOException("WASYT kompaniya ID aniqlanmadi. Akkauntni qayta ulang.");
        }

        final int pageSize = 20;
        JSONObject firstResponse = request(
                buildProductSearchPath(query, companyId, pageSize, 1), token);

        JSONArray firstRows = firstResponse.optJSONArray("data");
        if (firstRows == null) {
            throw new IOException("WASYT mahsulot javobida 'data' ro'yxati topilmadi.");
        }

        Map<Long, ProductInfo> products = new LinkedHashMap<>();
        addProductRows(products, firstRows, query, barcodeOnly);

        JSONObject meta = firstResponse.optJSONObject("meta");
        int lastPage = meta == null ? 1 : meta.optInt("last_page", 1);
        if (lastPage < 1) lastPage = 1;
        lastPage = Math.min(lastPage, 5);

        for (int page = 2; page <= lastPage; page++) {
            JSONObject response = request(
                    buildProductSearchPath(query, companyId, pageSize, page), token);
            JSONArray rows = response.optJSONArray("data");
            if (rows == null) {
                throw new IOException("WASYT mahsulot qidiruvining " + page
                        + "-sahifasida 'data' ro'yxati topilmadi.");
            }
            addProductRows(products, rows, query, barcodeOnly);
            if (rows.length() == 0) break;
        }

        if (products.isEmpty()) {
            throw new IOException("WASYT omborida " + query
                    + " bo'yicha mos mahsulot topilmadi.");
        }
        return new ArrayList<>(products.values());
    }

    /**
     * Exact request observed in the official WASYT seller workflow:
     * GET /common/public/records/product
     * The previous /company/stock/product/catalog and /company/stock/product
     * routes are not the seller UI's product-search route.
     */
    private static String buildProductSearchPath(String searchTerm, long companyId,
                                                  int pageSize, int page)
            throws IOException {
        StringBuilder query = new StringBuilder();
        addParam(query, "filter[stock_id]",
                String.valueOf(WasytSellerApi.DEFAULT_STOCK_ID));
        addParam(query, "filter[company_id]", String.valueOf(companyId));
        addParam(query, "filter[has_price]", "1");
        addParam(query, "filter[with_reserved_quantity]", "1");
        addParam(query, "filter[stock_quantity][]", "0.0001");
        addParam(query, "filter[name_or_barcode]", searchTerm);
        addParam(query, "page", String.valueOf(page));
        addParam(query, "page-size", String.valueOf(pageSize));
        return "/common/public/records/product?" + query;
    }

    private static void addParam(StringBuilder query, String key, String value)
            throws IOException {
        if (query.length() > 0) query.append('&');
        query.append(encode(key)).append('=').append(encode(value));
    }

    /**
     * WASYT public product records are flat product objects:
     * id, value, barcode, price{}, stock_product{}.
     * They are NOT wrapped in a nested "product" object.
     */
    private static void addProductRows(Map<Long, ProductInfo> products, JSONArray rows,
                                       String query, boolean barcodeOnly) {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || row.optBoolean("is_deleted", false)) continue;

            String matchedBarcode = findMatchingBarcode(row, query, barcodeOnly);
            if (barcodeOnly && matchedBarcode == null) continue;

            long productId = row.optLong("id", -1L);
            if (productId <= 0) continue;

            String name = row.optString("value", "").trim();
            if (name.isEmpty()) name = row.optString("full_text", "").trim();
            if (name.isEmpty()) name = "Nomsiz mahsulot";

            String barcode = row.optString("barcode", "").trim();
            if (barcode.isEmpty()) barcode = matchedBarcode == null ? "" : matchedBarcode;

            PriceValue price = extractSalePrice(row);
            String stockQuantity = extractAvailableQuantity(row);

            ProductInfo candidate = new ProductInfo(productId, name, barcode,
                    price.amount, price.currency, stockQuantity);
            ProductInfo existing = products.get(productId);
            if (existing == null || (!existing.hasPrice() && candidate.hasPrice())) {
                products.put(productId, candidate);
            }
        }
    }

    private static String findMatchingBarcode(JSONObject product, String query,
                                               boolean exactOnly) {
        String main = product.optString("barcode", "").trim();
        if (query.equals(main) || (!exactOnly && main.contains(query))) return main;

        String industrial = product.optString("industrial_barcode", "").trim();
        if (query.equals(industrial) || (!exactOnly && industrial.contains(query))) return industrial;

        JSONArray additional = product.optJSONArray("additional_barcodes");
        if (additional != null) {
            for (int i = 0; i < additional.length(); i++) {
                String additionalCode = additional.optString(i, "").trim();
                if (query.equals(additionalCode) || (!exactOnly && additionalCode.contains(query))) {
                    return additionalCode;
                }
            }
        }
        return null;
    }

    /** Uses the stock quantity less already reserved units, as returned by WASYT. */
    private static String extractAvailableQuantity(JSONObject row) {
        JSONObject stockProduct = row.optJSONObject("stock_product");
        if (stockProduct == null) return "";
        BigDecimal quantity = toDecimal(stockProduct.opt("quantity"));
        if (quantity == null) return "";
        BigDecimal reserved = toDecimal(stockProduct.opt("reserved_quantity"));
        if (reserved == null) reserved = BigDecimal.ZERO;
        BigDecimal available = quantity.subtract(reserved);
        if (available.signum() < 0) available = BigDecimal.ZERO;
        return normalizeQuantity(available);
    }

    /** Reads the seller-facing price object returned by the verified endpoint. */
    private static PriceValue extractSalePrice(JSONObject row) {
        JSONObject price = row.optJSONObject("price");
        if (price == null) return new PriceValue(0L, "");

        String currency = price.optString("currency", "uzs");
        BigDecimal amount = toDecimal(price.opt("price"));
        if (amount == null && "uzs".equalsIgnoreCase(currency)) {
            amount = toDecimal(price.opt("price_uzs"));
        }
        if (amount == null || amount.signum() <= 0) return new PriceValue(0L, currency);
        try {
            long rounded = amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
            return new PriceValue(rounded, currency);
        } catch (ArithmeticException ignored) {
            return new PriceValue(0L, currency);
        }
    }

    private static JSONObject request(String path, String token) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(BASE_URL + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization",
                    token.regionMatches(true, 0, "Bearer ", 0, 7) ? token : "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Language", "uz");
            // Match the browser app's origin context from the verified successful request.
            connection.setRequestProperty("Origin", WEB_ORIGIN);
            connection.setRequestProperty("Referer", WEB_ORIGIN + "/");
            connection.setRequestProperty("User-Agent", "AKSO-Finance-Android/1.0");

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readLimited(stream);

            JSONObject json;
            try {
                json = body.isEmpty() ? new JSONObject() : new JSONObject(body);
            } catch (Exception parseError) {
                throw new IOException("WASYT javobini o‘qib bo‘lmadi (HTTP "
                        + status + ").", parseError);
            }

            if (status == 401) {
                throw new IOException("WASYT tokenining muddati tugagan. WASYT akkauntini qayta ulang.");
            }
            if (status == 403) {
                String detail = json.optString("message", "").trim();
                if (detail.isEmpty()) detail = "Sotuvchi akkauntiga ushbu ombor qidiruvi uchun ruxsat berilmadi.";
                throw new IOException("WASYT katalog so‘rovi rad etildi (HTTP 403): "
                        + shortText(detail) + " [" + path.substring(0, path.indexOf('?')) + "]");
            }
            if (status == 404) {
                throw new IOException("WASYT API ombor mahsulotlari manzilini topmadi (HTTP 404).");
            }
            if (status == 429) {
                throw new IOException("WASYT so‘rovlari limiti oshdi. Birozdan keyin urinib ko‘ring.");
            }
            if (status < 200 || status >= 300) {
                String message = json.optString("message", "").trim();
                if (message.isEmpty()) message = "WASYT server xatoligi (HTTP " + status + ").";
                throw new IOException(shortText(message));
            }
            return json;
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("WASYT serveriga ulanib bo‘lmadi. Internetni tekshiring.", error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readLimited(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream input = stream;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) throw new IOException("WASYT javobi juda katta.");
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }

    private static String encode(String value) throws IOException {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception error) {
            throw new IOException("So‘rov parametrini kodlab bo‘lmadi.", error);
        }
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        try {
            if (value instanceof Number) return new BigDecimal(value.toString());
            String raw = String.valueOf(value).trim().replace(" ", "").replace(",", ".");
            if (raw.isEmpty()) return null;
            return new BigDecimal(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeQuantity(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String shortText(String value) {
        if (value == null) return "Noma’lum xatolik.";
        String text = value.trim();
        return text.length() > 220 ? text.substring(0, 220) : text;
    }
}
