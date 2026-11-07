package com.pos.tablet;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Google 試算表 (Google Apps Script Web App) 雲端同步引擎
 * 嚴格遵守安全紅線：不寫死任何金鑰與憑證，網址由使用者於設定頁輸入並經格式驗證。
 */
public class CloudSyncManager {

    public interface SyncCallback {
        void onResult(boolean success, String message);
    }

    public interface MenuDownloadCallback {
        void onResult(boolean success, String message, List<MainActivity.MenuItem> downloadedMenu);
    }

    public static class PendingQrItem {
        public String name;
        public int qty;
        public int unitPrice;
        public String choice;
        public String sweet;
        public String ice;
        public String mealDrinkName;
        public int mealDrinkDiff;
        public String mealDrinkSweet;
        public String mealDrinkIce;
        public List<MainActivity.AddonOption> addons = new ArrayList<>();
        public String note;
    }

    public static class PendingQrOrder {
        public String orderId;
        public String time;
        public String table; // 如 "1桌".."13桌" 或 "外帶" / "外送"
        public String paymentPref; // 如 "現金", "信用卡", "LINE Pay"
        public String customerNote;
        public int total;
        public List<PendingQrItem> items = new ArrayList<>();
    }

    public interface QrOrdersCallback {
        void onResult(boolean success, String message, List<PendingQrOrder> pendingOrders);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;

    private static boolean isValidWebAppUrl(String url) {
        if (url == null) return false;
        String u = url.trim();
        return u.startsWith("https://script.google.com/") || u.startsWith("https://script.googleusercontent.com/");
    }

    // ==================== 1. 上傳每日關帳總表與訂單明細 ====================
    public static void uploadClosingAndOrdersAsync(final String webAppUrl,
                                                   final MainActivity.ClosingRecord cr,
                                                   final List<MainActivity.OrderRecord> orders,
                                                   final Map<String, Integer> byPay,
                                                   final Map<String, Integer> itemRank,
                                                   final SyncCallback callback) {
        if (!isValidWebAppUrl(webAppUrl)) {
            postResult(callback, false, "尚未設定有效的 Google 試算表網址 (已先完成本地 SQLite 與 CSV 備份)");
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject root = new JSONObject();
                    root.put("action", "upload_closing");
                    root.put("storeName", "伊凡的花園 Evonne's Garden");
                    root.put("closeTime", cr.time);
                    root.put("validCount", cr.validCount);
                    root.put("voidCount", cr.voidCount);
                    root.put("netSales", cr.netSales);
                    root.put("cashSales", cr.cashSales);
                    root.put("openingFloat", cr.openingFloat);
                    root.put("expectedDrawerCash", cr.openingFloat + cr.cashSales);
                    root.put("byPayment", new JSONObject(byPay));
                    root.put("itemRanking", new JSONObject(itemRank));

                    JSONArray ordArr = new JSONArray();
                    if (orders != null) {
                        for (MainActivity.OrderRecord o : orders) {
                            JSONObject jo = new JSONObject();
                            jo.put("callNum", o.callNum);
                            jo.put("time", o.time);
                            jo.put("orderType", o.orderType);
                            jo.put("status", o.voided ? "VOID" : "VALID");
                            jo.put("paymentMethod", o.paymentMethod);
                            jo.put("orderDiscount", o.orderDiscount);
                            jo.put("total", o.total);
                            StringBuilder sb = new StringBuilder();
                            for (MainActivity.CartItem ci : o.items) {
                                if (sb.length() > 0) sb.append("；");
                                sb.append(ci.menu.name).append("x").append(ci.qty)
                                  .append("(").append(ci.getModSummary()).append(")");
                            }
                            jo.put("itemsSummary", sb.toString());
                            ordArr.put(jo);
                        }
                    }
                    root.put("orders", ordArr);

                    String resp = postJsonFollowRedirects(webAppUrl.trim(), root.toString());
                    postResult(callback, true, "✅ 已成功上傳至 Google 試算表！(" + resp + ")");
                } catch (Exception e) {
                    postResult(callback, false, "⚠️ 雲端暫時無法連線 (已存入待補傳佇列)：" + e.getMessage());
                }
            }
        });
    }

    // ==================== 2. 上傳平板菜單備份至 Google 試算表 ====================
    public static void uploadMenuAsync(final String webAppUrl,
                                       final List<MainActivity.MenuItem> menuList,
                                       final SyncCallback callback) {
        if (!isValidWebAppUrl(webAppUrl)) {
            postResult(callback, false, "請先填寫並儲存有效的 https://script.google.com/... 網址");
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject root = new JSONObject();
                    root.put("action", "upload_menu");
                    JSONArray arr = new JSONArray();
                    for (MainActivity.MenuItem m : menuList) {
                        JSONObject o = new JSONObject();
                        o.put("id", m.id);
                        o.put("cat", m.cat);
                        o.put("name", m.name);
                        o.put("sub", m.sub);
                        o.put("price", m.price);
                        o.put("isDrink", m.isDrink);
                        o.put("hasFreeTea", m.hasFreeTea);
                        o.put("fixedSweet", m.fixedSweet);
                        o.put("coldOnly", m.coldOnly);
                        o.put("isThickMilk", m.isThickMilk);
                        JSONArray chArr = new JSONArray();
                        if (m.choices != null) {
                            for (String c : m.choices) chArr.put(c);
                        }
                        o.put("choices", chArr);
                        arr.put(o);
                    }
                    root.put("menu", arr);
                    String resp = postJsonFollowRedirects(webAppUrl.trim(), root.toString());
                    postResult(callback, true, "✅ 菜單已上傳備份至 Google 試算表 (" + menuList.size() + " 項)！" + resp);
                } catch (Exception e) {
                    postResult(callback, false, "❌ 菜單上傳失敗：" + e.getMessage());
                }
            }
        });
    }

    // ==================== 3. 從 Google 試算表下載更新菜單 ====================
    public static void downloadMenuAsync(final String webAppUrl,
                                         final MenuDownloadCallback callback) {
        if (!isValidWebAppUrl(webAppUrl)) {
            MAIN_HANDLER.post(new Runnable() {
                @Override public void run() {
                    if (callback != null) callback.onResult(false, "請先設定有效的 Google 試算表網址", null);
                }
            });
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject req = new JSONObject();
                    req.put("action", "download_menu");
                    String resp = postJsonFollowRedirects(webAppUrl.trim(), req.toString());
                    JSONObject json = new JSONObject(resp);
                    JSONArray arr = json.optJSONArray("menu");
                    if (arr == null || arr.length() == 0) {
                        throw new IllegalStateException("雲端試算表尚無菜單資料，請先點擊「上傳菜單備份」");
                    }
                    final List<MainActivity.MenuItem> list = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        JSONArray chArr = o.optJSONArray("choices");
                        String[] choices = new String[chArr != null ? chArr.length() : 0];
                        if (chArr != null) {
                            for (int k = 0; k < chArr.length(); k++) choices[k] = chArr.getString(k);
                        }
                        list.add(new MainActivity.MenuItem(
                            o.optInt("id", i + 1),
                            o.optString("cat", "其他"),
                            o.optString("name", "未命名"),
                            o.optString("sub", ""),
                            Math.max(0, o.optInt("price", 0)),
                            o.optBoolean("isDrink", false),
                            o.optBoolean("hasFreeTea", false),
                            o.optBoolean("fixedSweet", false),
                            o.optBoolean("coldOnly", false),
                            o.optBoolean("isThickMilk", false),
                            choices
                        ));
                    }
                    MAIN_HANDLER.post(new Runnable() {
                        @Override public void run() {
                            if (callback != null) {
                                callback.onResult(true, "✅ 已從 Google 試算表同步更新 " + list.size() + " 項菜單！", list);
                            }
                        }
                    });
                } catch (final Exception e) {
                    MAIN_HANDLER.post(new Runnable() {
                        @Override public void run() {
                            if (callback != null) callback.onResult(false, "❌ 下載菜單失敗：" + e.getMessage(), null);
                        }
                    });
                }
            }
        });
    }

    // ==================== 4. 拉取雲端「📱 客人掃碼點餐」待接訂單佇列 ====================
    public static void fetchPendingQrOrdersAsync(final String webAppUrl,
                                                 final QrOrdersCallback callback) {
        if (!isValidWebAppUrl(webAppUrl)) {
            MAIN_HANDLER.post(new Runnable() {
                @Override public void run() {
                    if (callback != null) callback.onResult(false, "請先於設定頁儲存有效的 Google 試算表網址", null);
                }
            });
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject req = new JSONObject();
                    req.put("action", "fetch_pending_qr_orders");
                    String resp = postJsonFollowRedirects(webAppUrl.trim(), req.toString());
                    JSONObject json = new JSONObject(resp);
                    JSONArray arr = json.optJSONArray("orders");
                    final List<PendingQrOrder> list = new ArrayList<>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            PendingQrOrder po = new PendingQrOrder();
                            po.orderId = o.optString("orderId", "QR-" + (i + 1));
                            po.time = o.optString("time", "");
                            po.table = o.optString("table", "1桌");
                            po.paymentPref = o.optString("paymentPref", "現金");
                            po.customerNote = o.optString("customerNote", "");
                            po.total = o.optInt("total", 0);

                            JSONArray itemsArr = o.optJSONArray("items");
                            if (itemsArr != null) {
                                for (int k = 0; k < itemsArr.length(); k++) {
                                    JSONObject it = itemsArr.getJSONObject(k);
                                    PendingQrItem pi = new PendingQrItem();
                                    pi.name = it.optString("name", "");
                                    pi.qty = Math.max(1, it.optInt("qty", 1));
                                    pi.unitPrice = Math.max(0, it.optInt("unitPrice", 0));
                                    pi.choice = it.optString("choice", "");
                                    pi.sweet = it.optString("sweet", "5分半糖");
                                    pi.ice = it.optString("ice", "8分少冰");
                                    pi.mealDrinkName = it.optString("mealDrinkName", "毛尖綠茶");
                                    pi.mealDrinkDiff = it.optInt("mealDrinkDiff", 0);
                                    pi.mealDrinkSweet = it.optString("mealDrinkSweet", "無糖");
                                    pi.mealDrinkIce = it.optString("mealDrinkIce", "3分微冰");
                                    pi.note = it.optString("note", "");

                                    JSONArray addArr = it.optJSONArray("addons");
                                    if (addArr != null) {
                                        for (int a = 0; a < addArr.length(); a++) {
                                            JSONObject ao = addArr.getJSONObject(a);
                                            pi.addons.add(new MainActivity.AddonOption(
                                                ao.optString("name", ""), ao.optInt("price", 0)));
                                        }
                                    }
                                    po.items.add(pi);
                                }
                            }
                            list.add(po);
                        }
                    }
                    MAIN_HANDLER.post(new Runnable() {
                        @Override public void run() {
                            if (callback != null) {
                                callback.onResult(true, "✅ 已同步雲端掃碼訂單 (共 " + list.size() + " 筆待處理)", list);
                            }
                        }
                    });
                } catch (final Exception e) {
                    MAIN_HANDLER.post(new Runnable() {
                        @Override public void run() {
                            if (callback != null) callback.onResult(false, "⚠️ 無法取得線上訂單：" + e.getMessage(), null);
                        }
                    });
                }
            }
        });
    }

    // ==================== 5. 通知雲端將掃碼訂單標記為「已接單 (ACCEPTED)」或「已取消」 ====================
    public static void ackQrOrderAsync(final String webAppUrl,
                                       final String orderId,
                                       final String newStatus,
                                       final SyncCallback callback) {
        if (!isValidWebAppUrl(webAppUrl)) return;
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject req = new JSONObject();
                    req.put("action", "ack_qr_order");
                    req.put("orderId", orderId);
                    req.put("status", newStatus);
                    String resp = postJsonFollowRedirects(webAppUrl.trim(), req.toString());
                    postResult(callback, true, resp);
                } catch (Exception e) {
                    postResult(callback, false, e.getMessage());
                }
            }
        });
    }

    // ==================== HTTP POST + Google Apps Script 302 轉址處理 ====================
    private static String postJsonFollowRedirects(String urlStr, String jsonBody) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) throw new RuntimeException("空白轉址");
                return getResponseFromRedirectUrl(loc);
            }
            return readStream(conn.getInputStream());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String getResponseFromRedirectUrl(String redirectUrl) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(redirectUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            return readStream(conn.getInputStream());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readStream(InputStream is) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        br.close();
        return sb.toString();
    }

    private static void postResult(final SyncCallback cb, final boolean ok, final String msg) {
        MAIN_HANDLER.post(new Runnable() {
            @Override public void run() {
                if (cb != null) cb.onResult(ok, msg);
            }
        });
    }
}
