package uz.akso.kalkulyator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Minimal, read-only WASYT authentication/profile client for AKSO Finance. */
final class WasytApiClient {
    private static final String BASE_URL = "https://api.wasyt.uz";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private WasytApiClient() { }

    static final class AccountInfo {
        final String username;
        final String displayName;
        final String companyName;
        final long companyId;

        AccountInfo(String username, String displayName, String companyName, long companyId) {
            this.username = username;
            this.displayName = displayName;
            this.companyName = companyName;
            this.companyId = companyId;
        }
    }

    static final class LoginResult {
        final String token;
        final AccountInfo account;

        LoginResult(String token, AccountInfo account) {
            this.token = token;
            this.account = account;
        }
    }

    static LoginResult loginAndReadProfile(String username, String password) throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("username", username);
            payload.put("password", password);
        } catch (Exception error) {
            throw new IOException("Kirish ma’lumotlarini tayyorlab bo‘lmadi.", error);
        }

        JSONObject login = request("POST", "/common/auth/login", null, payload);
        String token = login.optString("token", "").trim();
        if (token.isEmpty()) {
            throw new IOException("WASYT token qaytarmadi. Login javobini tekshirish kerak.");
        }
        // WASYT qaytaradigan token odatda allaqachon "Bearer " prefiksiga ega.
        if (!token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = "Bearer " + token;
        }

        JSONObject rawProfile = request("GET", "/common/user/profile", token, null);
        JSONObject profile = unwrap(rawProfile);
        long companyId = profile.optLong("company_id", -1L);
        JSONObject company = profile.optJSONObject("company");
        if (companyId <= 0 && company != null) {
            companyId = company.optLong("id", -1L);
        }
        if (companyId <= 0) {
            throw new IOException("Bu WASYT akkaunti kompaniya xodimi yoki direktori sifatida bog‘lanmagan. WASYT administratoriga murojaat qiling.");
        }

        JSONObject profileBlock = profile.optJSONObject("profile");
        String firstName = profileBlock == null ? "" : profileBlock.optString("first_name", "");
        String lastName = profileBlock == null ? "" : profileBlock.optString("last_name", "");
        String displayName = (firstName + " " + lastName).trim();
        if (displayName.isEmpty()) {
            displayName = profile.optString("name", username);
        }
        String companyName = company == null ? "" : company.optString("name", "");
        if (companyName.isEmpty()) {
            companyName = profile.optString("company_name", "Store Akso");
        }

        AccountInfo account = new AccountInfo(username, displayName, companyName, companyId);
        return new LoginResult(token, account);
    }

    private static JSONObject unwrap(JSONObject response) {
        JSONObject data = response.optJSONObject("data");
        return data == null ? response : data;
    }

    private static JSONObject request(String method, String path, String token,
                                      JSONObject body) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(BASE_URL + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Language", "uz");
            connection.setRequestProperty("User-Agent", "AKSO-Finance-Android/1.0");
            if (token != null && !token.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", token);
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                    output.flush();
                }
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String responseText = readLimited(stream);
            JSONObject json;
            try {
                json = responseText.isEmpty() ? new JSONObject() : new JSONObject(responseText);
            } catch (Exception parseError) {
                if (status < 200 || status >= 300) {
                    throw new IOException("WASYT serveri xato qaytardi (HTTP " + status + ").");
                }
                throw new IOException("WASYT javobini o‘qib bo‘lmadi.", parseError);
            }

            if (status < 200 || status >= 300) {
                throw new IOException(readApiError(json, status));
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
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("WASYT javobi juda katta.");
                }
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        }
    }

    private static String readApiError(JSONObject json, int status) {
        String message = json.optString("message", "").trim();
        if (message.isEmpty()) {
            JSONObject errors = json.optJSONObject("errors");
            if (errors != null) {
                JSONArray names = errors.names();
                if (names != null && names.length() > 0) {
                    Object value = errors.opt(names.optString(0));
                    if (value instanceof JSONArray) {
                        message = ((JSONArray) value).optString(0, "");
                    } else if (value != null) {
                        message = String.valueOf(value);
                    }
                }
            }
        }
        if (status == 401) return "WASYT login yoki parol noto‘g‘ri, yoxud kirish tokeni yaroqsiz.";
        if (status == 403) return "WASYT akkauntida API orqali kirish huquqi yo‘q.";
        if (status == 429) return "WASYT so‘rovlari limiti oshdi. Birozdan keyin urinib ko‘ring.";
        if (message.isEmpty()) return "WASYT xatoligi (HTTP " + status + ").";
        return message.length() > 220 ? message.substring(0, 220) : message;
    }
}
