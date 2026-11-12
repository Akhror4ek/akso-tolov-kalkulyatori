package uz.akso.kalkulyator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * WASYT seller cart and customer API.
 *
 * Paths and multipart field names are based on successful seller-account
 * requests captured during the AKSO Finance integration investigation.
 * All network calls must run off the Android main thread.
 */
final class WasytSellerApi {
    static final String BASE_URL = "https://api.wasyt.uz";
    static final String WEB_ORIGIN = "https://app.wasyt.uz";

    // Current AKSO Store values captured from the connected seller workflow.
    // Keep these in one place so the active warehouse/source can be changed safely.
    static final long DEFAULT_STOCK_ID = 487L;
    static final long DEFAULT_CLIENT_SOURCE_ID = 8042L;

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 25000;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private WasytSellerApi() { }

    static final class Client {
        final long id;
        final String fullName;
        final String phone;

        Client(long id, String fullName, String phone) {
            this.id = id;
            this.fullName = safe(fullName);
            this.phone = safe(phone);
        }

        @Override public String toString() {
            return fullName + (phone.isEmpty() ? "" : " · " + phone);
        }
    }

    static final class CartLine {
        final long productId;
        final int quantity;
        final long unitAmount;

        CartLine(long productId, int quantity, long unitAmount) {
            if (productId <= 0) throw new IllegalArgumentException("Mahsulot ID noto'g'ri.");
            if (quantity < 1) throw new IllegalArgumentException("Mahsulot miqdori kamida 1 bo'lishi kerak.");
            if (unitAmount < 0) throw new IllegalArgumentException("Mahsulot narxi manfiy bo'lishi mumkin emas.");
            this.productId = productId;
            this.quantity = quantity;
            this.unitAmount = unitAmount;
        }
    }

    static final class Cart {
        final long id;
        final long saleId;
        final int productsCount;
        final String code;
        final String status;
        final String saleStatus;
        final long amount;
        final long discountAmount;
        final long totalAmount;
        final String clientName;
        final String clientPhone;
        final String sellerName;
        final String createdAt;
        final String sentAt;
        final String cancelReason;
        final String cancelComment;

        Cart(long id, long saleId, int productsCount, String code,
             String status, String saleStatus, long amount, long discountAmount,
             long totalAmount, String clientName, String clientPhone,
             String sellerName, String createdAt, String sentAt,
             String cancelReason, String cancelComment) {
            this.id = id;
            this.saleId = saleId;
            this.productsCount = productsCount;
            this.code = safe(code);
            this.status = safe(status);
            this.saleStatus = safe(saleStatus);
            this.amount = amount;
            this.discountAmount = discountAmount;
            this.totalAmount = totalAmount;
            this.clientName = safe(clientName);
            this.clientPhone = safe(clientPhone);
            this.sellerName = safe(sellerName);
            this.createdAt = safe(createdAt);
            this.sentAt = safe(sentAt);
            this.cancelReason = safe(cancelReason);
            this.cancelComment = safe(cancelComment);
        }
    }

    static final class CartPage {
        final List<Cart> carts;
        final int currentPage;
        final int lastPage;
        final int total;

        CartPage(List<Cart> carts, int currentPage, int lastPage, int total) {
            this.carts = carts;
            this.currentPage = currentPage;
            this.lastPage = lastPage;
            this.total = total;
        }
    }

    static final class CartProduct {
        final long id;
        final String name;
        final String barcode;
        final int quantity;
        final long price;
        final long discount;
        final String unit;

        CartProduct(long id, String name, String barcode, int quantity,
                    long price, long discount, String unit) {
            this.id = id;
            this.name = safe(name);
            this.barcode = safe(barcode);
            this.quantity = quantity;
            this.price = price;
            this.discount = discount;
            this.unit = safe(unit);
        }
    }

    static final class CartDetails {
        final Cart cart;
        final String clientPhone;
        final boolean reserved;
        final List<CartProduct> products;

        CartDetails(Cart cart, String clientPhone, boolean reserved,
                    List<CartProduct> products) {
            this.cart = cart;
            this.clientPhone = safe(clientPhone);
            this.reserved = reserved;
            this.products = products;
        }
    }

    static List<Client> searchClients(String token, String query) throws IOException {
        String term = safe(query).trim();
        if (term.isEmpty()) throw new IOException("Mijoz ismi yoki telefonini kiriting.");

        String path = "/company/client/client?filter%5Bcommon%5D="
                + encode(term) + "&page=1&page-size=20";
        JSONObject response = request("GET", path, token, null);
        JSONArray rows = response.optJSONArray("data");
        ArrayList<Client> clients = new ArrayList<>();
        if (rows == null) return clients;

        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || row.optBoolean("is_deleted", false)) continue;
            long id = row.optLong("id", -1L);
            if (id > 0) {
                clients.add(new Client(id, row.optString("full_name", ""),
                        row.optString("main_phone", "")));
            }
        }
        return clients;
    }

    static Client createClient(String token, String fullName, String phone,
                               String gender, String language) throws IOException {
        String name = safe(fullName).trim();
        String normalizedPhone = digitsOnly(phone);
        if (name.length() < 2) throw new IOException("Mijoz F.I.Sh. kamida 2 ta belgidan iborat bo'lsin.");
        if (normalizedPhone.length() < 9) throw new IOException("Mijoz telefon raqamini to'liq kiriting.");

        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("full_name", name);
        fields.put("gender", "female".equalsIgnoreCase(gender) ? "female" : "male");
        fields.put("main_phone", normalizedPhone);
        fields.put("source_id", String.valueOf(DEFAULT_CLIENT_SOURCE_ID));
        fields.put("socials[telegram]", "");
        fields.put("socials[instagram]", "");
        fields.put("socials[facebook]", "");
        fields.put("socials[other]", "");
        fields.put("languages[]", "ru".equalsIgnoreCase(language) ? "ru" : "uz");
        // No client photo is selected: omit the "image" part because WASYT
        // validates any present image value as an uploaded file.
        // The seller form always submits an empty image_old_keys[] array when
        // creating a client without previous images. Preserve this required field.
        fields.put("image_old_keys", "[]");

        JSONObject response = request("POST", "/company/client/client/", token, fields);
        JSONObject data = unwrapObject(response);
        long id = data.optLong("id", -1L);
        if (id <= 0) throw new IOException("WASYT yangi mijoz ID raqamini qaytarmadi.");
        return new Client(id, data.optString("full_name", name),
                data.optString("main_phone", normalizedPhone));
    }

    static JSONObject createCart(String token, long companyId, long stockId,
                                 boolean reserved, List<CartLine> lines) throws IOException {
        validateCartHeader(companyId, stockId, lines);
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("company_id", String.valueOf(companyId));
        fields.put("stock_id", String.valueOf(stockId));
        fields.put("currency", "uzs");
        fields.put("is_reserved", reserved ? "1" : "0");
        addProducts(fields, lines);
        return request("POST", "/company/trade/seller-cart", token, fields);
    }

    static JSONObject updateCart(String token, long cartId, long companyId, long stockId,
                                 long clientId, boolean reserved, List<CartLine> lines,
                                 boolean fixedDiscount, long discountValue) throws IOException {
        if (cartId <= 0) throw new IOException("Savat ID noto'g'ri.");
        if (clientId <= 0) throw new IOException("Avval mijozni tanlang.");
        validateCartHeader(companyId, stockId, lines);

        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("_method", "PUT");
        fields.put("company_id", String.valueOf(companyId));
        fields.put("stock_id", String.valueOf(stockId));
        fields.put("client_id", String.valueOf(clientId));
        fields.put("currency", "uzs");
        fields.put("is_reserved", reserved ? "1" : "0");
        fields.put("discount_mode", fixedDiscount ? "integer" : "percent");
        fields.put("discount_value", String.valueOf(Math.max(0L, discountValue)));
        addProducts(fields, lines);

        return request("POST", "/company/trade/seller-cart/" + cartId + "?_method=PUT",
                token, fields);
    }

    static JSONObject sendCart(String token, long cartId) throws IOException {
        if (cartId <= 0) throw new IOException("Savat ID noto'g'ri.");
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("_method", "PATCH");
        return request("POST", "/company/trade/seller-cart/" + cartId
                + "/send?_method=PATCH", token, fields);
    }

    static JSONObject cancelCart(String token, long cartId,
                                  String reason, String comment) throws IOException {
        if (cartId <= 0) throw new IOException("Savat ID noto'g'ri.");
        String safeReason = safe(reason).trim();
        if (safeReason.isEmpty()) throw new IOException("Savatni bekor qilish sababini tanlang.");
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("_method", "PATCH");
        // The successful WASYT browser request captured for this endpoint
        // contains only the method override. Keep the reason in the app's
        // local history until WASYT exposes a verified server field for it.
        return request("POST", "/company/trade/seller-cart/" + cartId
                + "/cancel?_method=PATCH", token, fields);
    }

    static CartPage listCartPage(String token, int page) throws IOException {
        int requestedPage = Math.max(1, page);
        String path = "/company/trade/seller-cart?page-size=20&page=" + requestedPage
                + "&with%5B%5D=client&with%5B%5D=creator.profile"
                + "&sort%5B%5D=-created_at";
        JSONObject response = request("GET", path, token, null);
        JSONArray rows = response.optJSONArray("data");
        ArrayList<Cart> carts = new ArrayList<>();
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                JSONObject client = row.optJSONObject("client");
                carts.add(parseCart(row, client));
            }
        }
        JSONObject meta = response.optJSONObject("meta");
        int current = meta == null ? requestedPage : meta.optInt("current_page", requestedPage);
        int last = meta == null ? current : meta.optInt("last_page", current);
        int total = meta == null ? carts.size() : meta.optInt("total", carts.size());
        return new CartPage(carts, Math.max(1, current), Math.max(1, last), Math.max(0, total));
    }

    /** Compatibility wrapper retained for older call sites. */
    static List<Cart> listCarts(String token, int page) throws IOException {
        return listCartPage(token, page).carts;
    }

    static CartDetails getCartDetails(String token, long cartId) throws IOException {
        if (cartId <= 0) throw new IOException("Savat ID noto'g'ri.");
        JSONObject response = request("GET", "/company/trade/seller-cart/" + cartId
                + "?with%5B%5D=client&with%5B%5D=products", token, null);
        JSONObject row = unwrapObject(response);
        JSONObject client = row.optJSONObject("client");
        JSONArray rows = row.optJSONArray("products");
        ArrayList<CartProduct> products = new ArrayList<>();
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject product = rows.optJSONObject(i);
                if (product == null) continue;
                products.add(new CartProduct(
                        product.optLong("id", -1L),
                        product.optString("name", product.optString("value", "Nomsiz mahsulot")),
                        product.optString("barcode", ""),
                        product.optInt("quantity", 0),
                        product.optLong("price", product.optLong("amount", 0L)),
                        product.optLong("discount", 0L),
                        product.optString("unit", "")));
            }
        }
        return new CartDetails(parseCart(row, client),
                client == null ? "" : client.optString("main_phone", ""),
                row.optBoolean("is_reserved", false), products);
    }

    private static Cart parseCart(JSONObject row, JSONObject client) {
        JSONObject creator = row.optJSONObject("creator");
        JSONObject profile = creator == null ? null : creator.optJSONObject("profile");
        return new Cart(
                row.optLong("id", -1L),
                row.optLong("sale_id", -1L),
                row.optInt("products_count", 0),
                row.optString("code", ""),
                row.optString("status", ""),
                row.optString("sale_status", ""),
                row.optLong("amount", 0L),
                row.optLong("discount_amount", 0L),
                row.optLong("total_amount", row.optLong("amount", 0L)),
                client == null ? "" : client.optString("full_name", ""),
                client == null ? "" : client.optString("main_phone", ""),
                profile == null ? "" : profile.optString("full_name", ""),
                row.optString("created_at", ""),
                row.optString("sent_at", ""),
                nullableText(row, "cancel_reason"),
                nullableText(row, "cancel_comment"));
    }

    /** Convert JSON null and the literal text "null" to an empty UI value. */
    private static String nullableText(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return "";
        String value = object.optString(key, "").trim();
        return value.isEmpty() || "null".equalsIgnoreCase(value) ? "" : value;
    }

    private static void validateCartHeader(long companyId, long stockId,
                                           List<CartLine> lines) throws IOException {
        if (companyId <= 0) throw new IOException("WASYT kompaniya ID topilmadi. Akkauntni qayta ulang.");
        if (stockId <= 0) throw new IOException("WASYT ombor ID noto'g'ri.");
        if (lines == null || lines.isEmpty()) throw new IOException("Savat bo'sh.");
    }

    private static void addProducts(Map<String, String> fields, List<CartLine> lines) {
        for (int i = 0; i < lines.size(); i++) {
            CartLine line = lines.get(i);
            fields.put("products[" + i + "][id]", String.valueOf(line.productId));
            fields.put("products[" + i + "][quantity]", String.valueOf(line.quantity));
            // WASYT accepts the per-unit selling amount in this field.
            fields.put("products[" + i + "][amount]", String.valueOf(line.unitAmount));
            fields.put("products[" + i + "][discount]", "0");
        }
    }

    private static JSONObject request(String method, String path, String token,
                                      Map<String, String> fields) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Language", "uz");
            connection.setRequestProperty("Origin", WEB_ORIGIN);
            connection.setRequestProperty("Referer", WEB_ORIGIN + "/");
            connection.setRequestProperty("User-Agent", "AKSO-Finance-Android/1.0");
            if (token == null || token.trim().isEmpty()) {
                throw new IOException("WASYT akkaunti ulanmagan. Avval akkauntni ulang.");
            }
            connection.setRequestProperty("Authorization",
                    token.regionMatches(true, 0, "Bearer ", 0, 7)
                            ? token : "Bearer " + token);

            if (fields != null) {
                String boundary = "----AKSOFinance"
                        + UUID.randomUUID().toString().replace("-", "");
                byte[] body = buildMultipart(fields, boundary);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type",
                        "multipart/form-data; boundary=" + boundary);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                    output.flush();
                }
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readLimited(stream);
            JSONObject json;
            try {
                json = body.isEmpty() ? new JSONObject() : new JSONObject(body);
            } catch (Exception parseError) {
                throw new IOException("WASYT javobini o'qib bo'lmadi (HTTP "
                        + status + ").", parseError);
            }

            if (status == 401) {
                throw new IOException("WASYT tokeni yaroqsiz yoki muddati tugagan. Akkauntni qayta ulang.");
            }
            if (status == 403) {
                throw new IOException("WASYT ushbu amal uchun ruxsat bermadi (HTTP 403): "
                        + apiMessage(json));
            }
            if (status == 404) {
                throw new IOException("WASYT API manzili topilmadi (HTTP 404): " + path);
            }
            if (status == 422) {
                throw new IOException("WASYT ma'lumotlarni qabul qilmadi: " + validationMessage(json));
            }
            if (status == 429) {
                throw new IOException("WASYT so'rovlari limiti oshdi. Birozdan keyin urinib ko'ring.");
            }
            if (status < 200 || status >= 300) {
                throw new IOException("WASYT xatoligi (HTTP " + status + "): " + apiMessage(json));
            }
            return json;
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("WASYT serveriga ulanib bo'lmadi. Internetni tekshiring.", error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] buildMultipart(Map<String, String> fields, String boundary)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(("Content-Disposition: form-data; name=\""
                    + entry.getKey() + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            String value = entry.getValue() == null ? "" : entry.getValue();
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private static JSONObject unwrapObject(JSONObject response) {
        JSONObject data = response.optJSONObject("data");
        return data == null ? response : data;
    }

    private static String validationMessage(JSONObject json) {
        JSONObject errors = json.optJSONObject("errors");
        if (errors != null) {
            JSONArray names = errors.names();
            if (names != null && names.length() > 0) {
                String key = names.optString(0);
                Object value = errors.opt(key);
                if (value instanceof JSONArray) return key + ": " + ((JSONArray) value).optString(0);
                if (value != null) return key + ": " + value;
            }
        }
        return apiMessage(json);
    }

    private static String apiMessage(JSONObject json) {
        String message = json.optString("message", "").trim();
        if (message.isEmpty()) message = json.optString("error", "").trim();
        if (message.isEmpty()) message = "Server so'rovni rad etdi.";
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private static String readLimited(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream input = stream;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            int total = 0;
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
            throw new IOException("So'rov parametrini kodlab bo'lmadi.", error);
        }
    }

    private static String digitsOnly(String value) {
        return safe(value).replaceAll("[^0-9]", "");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}