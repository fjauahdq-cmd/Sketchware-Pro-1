package pro.sketchware.utility;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SupabaseClient {
    public static final String SUPABASE_URL = "https://tnzkctlflnnptvqofsmf.supabase.co";
    public static final String SUPABASE_KEY = "sb_publishable_xDYvaBpC_LLloHGroToLZw_1i-uFIfU";
    private static final String PREFS = "sketup_auth";
    private static final String KEY_TOKEN = "access_token";
    private static final String KEY_REFRESH = "refresh_token";
    private static final String KEY_EXPIRES_AT = "expires_at";
    private static final String KEY_USER_ID = "user_id";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient client = new OkHttpClient();
    public interface Callback { void onSuccess(); void onError(String message); }
    public static boolean isLoggedIn(Context context) { return getPrefs(context).getString(KEY_TOKEN, null) != null; }
    public static String getAccessToken(Context context) { return getPrefs(context).getString(KEY_TOKEN, null); }
    public static String getUserId(Context context) { return getPrefs(context).getString(KEY_USER_ID, null); }
    public static void signOut(Context context) { getPrefs(context).edit().clear().apply(); }
    public static void signIn(Context context, String email, String password, Callback callback) {
        JsonObject body = new JsonObject(); body.addProperty("email", email); body.addProperty("password", password);
        post(context, SUPABASE_URL + "/auth/v1/token?grant_type=password", body, true, callback);
    }
    public static void signUp(Context context, String email, String password, Callback callback) {
        JsonObject body = new JsonObject(); body.addProperty("email", email); body.addProperty("password", password);
        post(context, SUPABASE_URL + "/auth/v1/signup", body, false, callback);
    }
    private static void post(Context context, String url, JsonObject body, boolean session, Callback callback) {
        Request request = new Request.Builder().url(url).addHeader("apikey", SUPABASE_KEY)
                .addHeader("Content-Type", "application/json").post(RequestBody.create(JSON, body.toString())).build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, IOException e) { callback.onError("Sem conexão com o servidor"); }
            @Override public void onResponse(okhttp3.Call call, Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    if (session) {
                        try {
                            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
                            if (optString(json, "access_token") != null) { saveSession(context, json); callback.onSuccess(); return; }
                        } catch (Exception ignored) { }
                        callback.onError("Resposta inesperada do servidor");
                    } else callback.onSuccess();
                } else callback.onError(parseError(responseBody, response.code()));
            }
        });
    }
    private static void saveSession(Context context, JsonObject json) {
        long expiresIn = 3600;
        if (json.has("expires_in") && !json.get("expires_in").isJsonNull()) expiresIn = json.get("expires_in").getAsLong();
        String userId = null;
        if (json.has("user") && json.get("user").isJsonObject()) userId = optString(json.getAsJsonObject("user"), "id");
        getPrefs(context).edit().putString(KEY_TOKEN, optString(json, "access_token"))
                .putString(KEY_REFRESH, optString(json, "refresh_token"))
                .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000)
                .putString(KEY_USER_ID, userId).apply();
    }
    public static synchronized boolean ensureFreshToken(Context context) {
        SharedPreferences prefs = getPrefs(context);
        if (System.currentTimeMillis() < prefs.getLong(KEY_EXPIRES_AT, 0) - 60_000) return true;
        String refresh = prefs.getString(KEY_REFRESH, null);
        if (refresh == null) return false;
        try {
            JsonObject body = new JsonObject(); body.addProperty("refresh_token", refresh);
            Request request = new Request.Builder().url(SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token")
                    .addHeader("apikey", SUPABASE_KEY).addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(JSON, body.toString())).build();
            try (Response response = client.newCall(request).execute()) {
                String text = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) return false;
                saveSession(context, JsonParser.parseString(text).getAsJsonObject()); return true;
            }
        } catch (Exception e) { return false; }
    }
    public static String api(Context context, String method, String path, JsonObject body) throws IOException {
        String token = getAccessToken(context);
        Request.Builder builder = new Request.Builder().url(SUPABASE_URL + path)
                .addHeader("apikey", SUPABASE_KEY).addHeader("Content-Type", "application/json");
        if (token != null) builder.addHeader("Authorization", "Bearer " + token);
        if (body != null) builder.method(method, RequestBody.create(JSON, body.toString())); else builder.method(method, null);
        try (Response response = client.newCall(builder.build()).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            return text;
        }
    }
    private static String optString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }
    private static String parseError(String body, int code) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("error_description")) return translate(json.get("error_description").getAsString());
            if (json.has("msg")) return translate(json.get("msg").getAsString());
            if (json.has("message")) return translate(json.get("message").getAsString());
        } catch (Exception ignored) { }
        return "Erro ao autenticar (" + code + ")";
    }
    private static String translate(String message) {
        if (message.contains("Invalid login credentials")) return "E-mail ou senha incorretos";
        if (message.contains("Email not confirmed")) return "Confirme seu e-mail antes de entrar";
        if (message.contains("User already registered")) return "Este e-mail já está cadastrado";
        return message;
    }
    private static SharedPreferences getPrefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}
