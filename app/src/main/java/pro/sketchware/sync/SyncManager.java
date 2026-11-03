package pro.sketchware.sync;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.widget.Toast;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import pro.sketchware.utility.SupabaseClient;

/** Sincronização SKET-UP v3.4. */
public class SyncManager {
    private static final long AUTO_SYNC_INTERVAL_MS = 60 * 1000;
    private static final byte[] SK_KEY = "sketchwaresecure".getBytes(StandardCharsets.UTF_8);
    private static final Set<String> PLAIN_FILES = new HashSet<>(Arrays.asList("data/permission", "data/project_config", "data/import"));
    private static long lastAutoSync = 0;
    private static boolean syncing = false;
    public interface Listener { void onResult(String message); }
    public static void maybeAutoSync(final Context context) { maybeAutoSync(context, null); }
    public static void maybeAutoSync(final Context context, final Runnable onDownloaded) {
        if (!SupabaseClient.isLoggedIn(context)) return;
        long now = System.currentTimeMillis();
        if (now - lastAutoSync < AUTO_SYNC_INTERVAL_MS || syncing) return;
        lastAutoSync = now;
        syncAll(context, message -> {
            boolean nothingChanged = message.contains("↑0 ") && message.contains("↓0 ");
            if (nothingChanged) return;
            Toast.makeText(context.getApplicationContext(), "SKET-UP sync: " + message, Toast.LENGTH_LONG).show();
            boolean downloadedSomething = !message.contains("↓0 ");
            if (downloadedSomething && onDownloaded != null) onDownloaded.run();
        });
    }
    public static void syncAll(Context context, Listener listener) {
        if (syncing) { if (listener != null) listener.onResult("Sincronização já em andamento"); return; }
        syncing = true;
        new Thread(() -> {
            String result;
            try { result = doSync(context.getApplicationContext()); }
            catch (Exception e) { result = "Erro na sincronização: " + e.getMessage(); }
            finally { syncing = false; }
            if (listener != null) { String finalResult = result; new Handler(Looper.getMainLooper()).post(() -> listener.onResult(finalResult)); }
        }, "sketup-sync").start();
    }
    private static String doSync(Context context) throws Exception {
        if (!SupabaseClient.ensureFreshToken(context)) return "Sessão expirada — faça login novamente";
        File listRoot = new File(Environment.getExternalStorageDirectory(), ".sketchware/mysc/list");
        File dataRoot = new File(Environment.getExternalStorageDirectory(), ".sketchware/data");
        File sharedRoot = new File(Environment.getExternalStorageDirectory(), ".sketchware");
        String remoteJson = SupabaseClient.api(context, "GET", "/rest/v1/projects?select=id,name,sc_id,data,updated_at", null);
        JsonArray remote = JsonParser.parseString(remoteJson).getAsJsonArray();
        Map<String, JsonObject> remoteByScId = new HashMap<>();
        for (JsonElement el : remote) {
            JsonObject row = el.getAsJsonObject();
            if (row.has("sc_id") && row.get("sc_id").isJsonPrimitive()) remoteByScId.put(row.get("sc_id").getAsString(), row);
        }
        int uploaded = 0, downloaded = 0, failed = 0;
        Set<String> localScIds = new HashSet<>();
        File[] dirs = listRoot.listFiles();
        if (dirs != null) for (File dir : dirs) {
            if (!dir.isDirectory() || !isValidProjectDir(dir)) continue;
            String scId = dir.getName(); localScIds.add(scId);
            File dataDir = new File(dataRoot, scId);
            try {
                long localMtime = Math.max(maxMtime(dir), maxMtime(dataDir));
                JsonObject remoteRow = remoteByScId.get(scId);
                long remoteTime = remoteRow != null ? parseRemoteTime(remoteRow) : 0;
                if (remoteRow == null || localMtime > remoteTime + 2000) { uploadProject(context, scId, dir, dataDir, remoteRow); uploaded++; }
                else if (remoteTime > localMtime + 2000) { downloadProject(dir, dataDir, sharedRoot, remoteRow); downloaded++; }
            } catch (Exception e) { failed++; }
        }
        for (Map.Entry<String, JsonObject> entry : remoteByScId.entrySet()) {
            if (localScIds.contains(entry.getKey())) continue;
            try { downloadProject(new File(listRoot, entry.getKey()), new File(dataRoot, entry.getKey()), sharedRoot, entry.getValue()); downloaded++; }
            catch (Exception e) { failed++; }
        }
        String result = "Sincronizado: ↑" + uploaded + " enviados, ↓" + downloaded + " recebidos";
        if (failed > 0) result += " (" + failed + " com erro)";
        return result;
    }
    private static boolean isValidProjectDir(File dir) { File projectFile = new File(dir, "project"); return projectFile.exists() && projectFile.length() > 2; }
    private static void uploadProject(Context context, String scId, File listDir, File dataDir, JsonObject remoteRow) throws Exception {
        JsonObject files = new JsonObject(); collectFiles(listDir, "list/", files); collectFiles(dataDir, "data/", files);
        byte[] rawProject = Files.readAllBytes(new File(listDir, "project").toPath());
        String plainProject = decryptBytes(rawProject);
        JsonObject config;
        try { config = JsonParser.parseString(plainProject != null ? plainProject : new String(rawProject, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (Exception e) { throw new IOException("arquivo project ilegível em " + scId); }
        String name = config.has("my_app_name") && !config.get("my_app_name").isJsonNull() ? config.get("my_app_name").getAsString() : "Projeto " + scId;
        JsonObject data = new JsonObject(); data.addProperty("sc_id", scId); data.add("config", config); data.add("files", files);
        JsonObject payload = new JsonObject(); payload.addProperty("name", name); payload.add("data", data); payload.addProperty("sc_id", scId);
        if (remoteRow != null) SupabaseClient.api(context, "PATCH", "/rest/v1/projects?id=eq." + remoteRow.get("id").getAsString(), payload);
        else { payload.addProperty("user_id", SupabaseClient.getUserId(context)); SupabaseClient.api(context, "POST", "/rest/v1/projects", payload); }
    }
    private static void downloadProject(File listDir, File dataDir, File sharedRoot, JsonObject row) throws IOException {
        JsonObject data = row.getAsJsonObject("data");
        if (data == null || !data.has("files")) return;
        JsonObject files = data.getAsJsonObject("files");
        for (Map.Entry<String, JsonElement> entry : files.entrySet()) {
            String key = entry.getKey(); File out;
            if (key.startsWith("list/")) out = new File(listDir, key.substring(5));
            else if (key.startsWith("data/")) out = new File(dataDir, key.substring(5));
            else if (key.startsWith("shared/")) out = new File(sharedRoot, key.substring(7));
            else continue;
            if (out.getParentFile() != null) out.getParentFile().mkdirs();
            JsonElement value = entry.getValue(); byte[] bytes;
            if (value.isJsonObject() && value.getAsJsonObject().has("__b64")) bytes = Base64.decode(value.getAsJsonObject().get("__b64").getAsString(), Base64.DEFAULT);
            else bytes = value.getAsString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(out)) { fos.write(bytes); }
        }
        touch(listDir); touch(dataDir);
    }
    private static String decryptBytes(byte[] raw) {
        try { Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding"); cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(SK_KEY, "AES"), new IvParameterSpec(SK_KEY)); return new String(cipher.doFinal(raw), StandardCharsets.UTF_8); }
        catch (Exception e) { return null; }
    }
    private static void collectFiles(File base, String prefix, JsonObject out) throws IOException {
        File[] list = base.listFiles(); if (list == null) return;
        for (File file : list) {
            String rel = prefix + file.getName();
            if (file.isDirectory()) { collectFiles(file, rel + "/", out); continue; }
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (PLAIN_FILES.contains(rel)) { out.addProperty(rel, new String(bytes, StandardCharsets.UTF_8)); continue; }
            JsonObject b64 = new JsonObject(); b64.addProperty("__b64", Base64.encodeToString(bytes, Base64.NO_WRAP)); out.add(rel, b64);
        }
    }
    private static long maxMtime(File dir) {
        long max = 0; File[] list = dir.listFiles(); if (list == null) return 0;
        for (File f : list) max = Math.max(max, f.isDirectory() ? maxMtime(f) : f.lastModified());
        return max;
    }
    private static void touch(File dir) {
        long now = System.currentTimeMillis(); File[] list = dir.listFiles(); if (list == null) return;
        for (File f : list) { if (f.isDirectory()) touch(f); else f.setLastModified(now); }
    }
    private static long parseRemoteTime(JsonObject row) {
        try { return OffsetDateTime.parse(row.get("updated_at").getAsString()).toInstant().toEpochMilli(); }
        catch (Exception e) { return 0; }
    }
}
