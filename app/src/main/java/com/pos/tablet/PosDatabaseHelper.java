package com.pos.tablet;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 本地 SQLite 資料庫與 CSV 匯出管理（Ponytail 模式：100% 參數化查詢，防止 SQL Injection）
 */
public class PosDatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "evonne_garden_pos.db";
    private static final int DB_VERSION = 1;
    private final Context appContext;

    public PosDatabaseHelper(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        this.appContext = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS app_settings ("
            + "setting_key TEXT PRIMARY KEY, "
            + "setting_value TEXT NOT NULL)");

        db.execSQL("CREATE TABLE IF NOT EXISTS menu_items ("
            + "id INTEGER PRIMARY KEY, "
            + "cat TEXT NOT NULL, "
            + "name TEXT NOT NULL, "
            + "sub TEXT NOT NULL, "
            + "price INTEGER NOT NULL, "
            + "is_drink INTEGER NOT NULL DEFAULT 0, "
            + "has_free_tea INTEGER NOT NULL DEFAULT 0, "
            + "fixed_sweet INTEGER NOT NULL DEFAULT 0, "
            + "cold_only INTEGER NOT NULL DEFAULT 0, "
            + "is_thick_milk INTEGER NOT NULL DEFAULT 0, "
            + "choices_json TEXT NOT NULL DEFAULT '[]', "
            + "enabled INTEGER NOT NULL DEFAULT 1)");

        db.execSQL("CREATE TABLE IF NOT EXISTS orders ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "call_num TEXT NOT NULL, "
            + "order_date TEXT NOT NULL, "
            + "order_time TEXT NOT NULL, "
            + "order_type TEXT NOT NULL, "
            + "items_json TEXT NOT NULL, "
            + "items_summary TEXT NOT NULL, "
            + "order_discount INTEGER NOT NULL DEFAULT 0, "
            + "total INTEGER NOT NULL DEFAULT 0, "
            + "payment_method TEXT NOT NULL, "
            + "cash_received INTEGER NOT NULL DEFAULT 0, "
            + "cash_change INTEGER NOT NULL DEFAULT 0, "
            + "print_receipt INTEGER NOT NULL DEFAULT 1, "
            + "sticker_count INTEGER NOT NULL DEFAULT 0, "
            + "voided INTEGER NOT NULL DEFAULT 0, "
            + "closed INTEGER NOT NULL DEFAULT 0, "
            + "synced INTEGER NOT NULL DEFAULT 0)");

        db.execSQL("CREATE TABLE IF NOT EXISTS daily_closings ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "close_time TEXT NOT NULL, "
            + "valid_count INTEGER NOT NULL DEFAULT 0, "
            + "void_count INTEGER NOT NULL DEFAULT 0, "
            + "net_sales INTEGER NOT NULL DEFAULT 0, "
            + "cash_sales INTEGER NOT NULL DEFAULT 0, "
            + "opening_float INTEGER NOT NULL DEFAULT 2000, "
            + "by_pay_json TEXT NOT NULL DEFAULT '{}', "
            + "item_rank_json TEXT NOT NULL DEFAULT '{}', "
            + "printed_paper INTEGER NOT NULL DEFAULT 0, "
            + "csv_path TEXT NOT NULL DEFAULT '', "
            + "cloud_synced INTEGER NOT NULL DEFAULT 0, "
            + "cloud_status TEXT NOT NULL DEFAULT '')");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onCreate(db);
    }

    // ==================== 參數化設定存取 ====================
    public void setSetting(String key, String value) {
        if (key == null || key.trim().isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("setting_key", key.trim());
        cv.put("setting_value", value != null ? value : "");
        db.insertWithOnConflict("app_settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String getSetting(String key, String defaultVal) {
        if (key == null) return defaultVal;
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT setting_value FROM app_settings WHERE setting_key = ?", new String[]{key});
            if (c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return defaultVal;
    }

    public int getIntSetting(String key, int defaultVal) {
        try {
            return Integer.parseInt(getSetting(key, String.valueOf(defaultVal)));
        } catch (Exception e) {
            return defaultVal;
        }
    }

    // ==================== 菜單持久化與增刪改 ====================
    public void seedMenuIfEmpty(List<MainActivity.MenuItem> defaultMenu) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = null;
        int count = 0;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM menu_items", null);
            if (c.moveToFirst()) count = c.getInt(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (count > 0) return;
        saveMenuList(defaultMenu);
    }

    public void saveMenuList(List<MainActivity.MenuItem> list) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("menu_items", null, null);
            for (MainActivity.MenuItem m : list) {
                upsertMenuItemInternal(db, m);
            }
            db.setTransactionSuccessful();
        } catch (Exception ignored) {
        } finally {
            db.endTransaction();
        }
    }

    public void upsertMenuItem(MainActivity.MenuItem m) {
        SQLiteDatabase db = getWritableDatabase();
        upsertMenuItemInternal(db, m);
    }

    private void upsertMenuItemInternal(SQLiteDatabase db, MainActivity.MenuItem m) {
        ContentValues cv = new ContentValues();
        cv.put("id", m.id);
        cv.put("cat", m.cat);
        cv.put("name", m.name);
        cv.put("sub", m.sub);
        cv.put("price", m.price);
        cv.put("is_drink", m.isDrink ? 1 : 0);
        cv.put("has_free_tea", m.hasFreeTea ? 1 : 0);
        cv.put("fixed_sweet", m.fixedSweet ? 1 : 0);
        cv.put("cold_only", m.coldOnly ? 1 : 0);
        cv.put("is_thick_milk", m.isThickMilk ? 1 : 0);
        JSONArray arr = new JSONArray();
        if (m.choices != null) {
            for (String ch : m.choices) arr.put(ch);
        }
        cv.put("choices_json", arr.toString());
        cv.put("enabled", 1);
        db.insertWithOnConflict("menu_items", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void deleteMenuItem(int id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("menu_items", "id = ?", new String[]{String.valueOf(id)});
    }

    public List<MainActivity.MenuItem> loadMenuItems() {
        List<MainActivity.MenuItem> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT id, cat, name, sub, price, is_drink, has_free_tea, "
                + "fixed_sweet, cold_only, is_thick_milk, choices_json FROM menu_items WHERE enabled = 1 ORDER BY id ASC", null);
            while (c.moveToNext()) {
                JSONArray arr = new JSONArray(c.getString(10));
                String[] choices = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) choices[i] = arr.getString(i);
                list.add(new MainActivity.MenuItem(
                    c.getInt(0),
                    c.getString(1),
                    c.getString(2),
                    c.getString(3),
                    c.getInt(4),
                    c.getInt(5) == 1,
                    c.getInt(6) == 1,
                    c.getInt(7) == 1,
                    c.getInt(8) == 1,
                    c.getInt(9) == 1,
                    choices
                ));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return list;
    }

    // ==================== 訂單儲存與查詢 ====================
    public long insertOrder(MainActivity.OrderRecord rec) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN).format(new Date());
        cv.put("call_num", rec.callNum);
        cv.put("order_date", today);
        cv.put("order_time", rec.time);
        cv.put("order_type", rec.orderType);
        cv.put("items_json", serializeCartItems(rec.items));
        cv.put("items_summary", buildItemsSummary(rec.items));
        cv.put("order_discount", rec.orderDiscount);
        cv.put("total", rec.total);
        cv.put("payment_method", rec.paymentMethod);
        cv.put("cash_received", rec.cashReceived);
        cv.put("cash_change", rec.cashChange);
        cv.put("print_receipt", rec.printReceipt ? 1 : 0);
        cv.put("sticker_count", rec.stickerCount);
        cv.put("voided", rec.voided ? 1 : 0);
        cv.put("closed", 0);
        cv.put("synced", 0);
        long rowId = db.insert("orders", null, cv);
        rec.dbId = rowId;
        return rowId;
    }

    public void markOrderVoided(long dbId, boolean voided) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("voided", voided ? 1 : 0);
        db.update("orders", cv, "id = ?", new String[]{String.valueOf(dbId)});
    }

    public List<MainActivity.OrderRecord> loadActiveOrders(List<MainActivity.MenuItem> menuPool) {
        List<MainActivity.OrderRecord> res = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT id, call_num, order_time, order_type, items_json, "
                + "order_discount, total, payment_method, cash_received, cash_change, "
                + "print_receipt, sticker_count, voided FROM orders WHERE closed = 0 ORDER BY id DESC", null);
            while (c.moveToNext()) {
                MainActivity.OrderRecord r = new MainActivity.OrderRecord();
                r.dbId = c.getLong(0);
                r.callNum = c.getString(1);
                r.time = c.getString(2);
                r.orderType = c.getString(3);
                r.items = deserializeCartItems(c.getString(4), menuPool);
                r.orderDiscount = c.getInt(5);
                r.total = c.getInt(6);
                r.paymentMethod = c.getString(7);
                r.cashReceived = c.getInt(8);
                r.cashChange = c.getInt(9);
                r.printReceipt = c.getInt(10) == 1;
                r.stickerCount = c.getInt(11);
                r.voided = c.getInt(12) == 1;
                res.add(r);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return res;
    }

    private String buildItemsSummary(List<MainActivity.CartItem> items) {
        StringBuilder sb = new StringBuilder();
        for (MainActivity.CartItem ci : items) {
            if (sb.length() > 0) sb.append("；");
            sb.append(ci.menu.name).append("x").append(ci.qty)
              .append("[").append(ci.getModSummary()).append("]");
        }
        return sb.toString();
    }

    public static String serializeCartItems(List<MainActivity.CartItem> items) {
        JSONArray arr = new JSONArray();
        try {
            for (MainActivity.CartItem ci : items) {
                JSONObject o = new JSONObject();
                o.put("menuId", ci.menu.id);
                o.put("cat", ci.menu.cat);
                o.put("name", ci.menu.name);
                o.put("price", ci.menu.price);
                o.put("isDrink", ci.menu.isDrink);
                o.put("hasFreeTea", ci.menu.hasFreeTea);
                o.put("qty", ci.qty);
                o.put("selectedChoice", ci.selectedChoice);
                o.put("drinkSweet", ci.drinkSweet);
                o.put("drinkIce", ci.drinkIce);
                o.put("mealDrinkName", ci.mealDrinkName);
                o.put("mealDrinkDiff", ci.mealDrinkDiff);
                o.put("mealDrinkSweet", ci.mealDrinkSweet);
                o.put("mealDrinkIce", ci.mealDrinkIce);
                o.put("itemDiscount", ci.itemDiscount);
                o.put("note", ci.note);
                o.put("printLabel", ci.printLabel);
                JSONArray adArr = new JSONArray();
                for (MainActivity.AddonOption a : ci.addons) {
                    JSONObject ao = new JSONObject();
                    ao.put("name", a.name);
                    ao.put("price", a.price);
                    adArr.put(ao);
                }
                o.put("addons", adArr);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        return arr.toString();
    }

    public static List<MainActivity.CartItem> deserializeCartItems(String json, List<MainActivity.MenuItem> menuPool) {
        List<MainActivity.CartItem> list = new ArrayList<>();
        if (json == null || json.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int menuId = o.optInt("menuId", 0);
                MainActivity.MenuItem matched = null;
                if (menuPool != null) {
                    for (MainActivity.MenuItem m : menuPool) {
                        if (m.id == menuId) {
                            matched = m;
                            break;
                        }
                    }
                }
                if (matched == null) {
                    matched = new MainActivity.MenuItem(
                        menuId,
                        o.optString("cat", "其他"),
                        o.optString("name", "品項"),
                        "",
                        o.optInt("price", 0),
                        o.optBoolean("isDrink", false),
                        o.optBoolean("hasFreeTea", false),
                        false, false, false, new String[0]
                    );
                }
                MainActivity.CartItem ci = new MainActivity.CartItem();
                ci.menu = matched;
                ci.qty = o.optInt("qty", 1);
                ci.selectedChoice = o.optString("selectedChoice", "");
                ci.drinkSweet = o.optString("drinkSweet", "5分半糖");
                ci.drinkIce = o.optString("drinkIce", "8分少冰");
                ci.mealDrinkName = o.optString("mealDrinkName", "毛尖綠茶");
                ci.mealDrinkDiff = o.optInt("mealDrinkDiff", 0);
                ci.mealDrinkSweet = o.optString("mealDrinkSweet", "無糖");
                ci.mealDrinkIce = o.optString("mealDrinkIce", "3分微冰");
                ci.itemDiscount = o.optInt("itemDiscount", 0);
                ci.note = o.optString("note", "");
                ci.printLabel = o.optBoolean("printLabel", false);
                JSONArray adArr = o.optJSONArray("addons");
                if (adArr != null) {
                    for (int k = 0; k < adArr.length(); k++) {
                        JSONObject ao = adArr.getJSONObject(k);
                        ci.addons.add(new MainActivity.AddonOption(
                            ao.optString("name", ""), ao.optInt("price", 0)));
                    }
                }
                list.add(ci);
            }
        } catch (Exception ignored) {}
        return list;
    }

    // ==================== 每日關帳儲存與 CSV 匯出 ====================
    public long insertDailyClosing(MainActivity.ClosingRecord cr, List<MainActivity.OrderRecord> activeOrders) {
        SQLiteDatabase db = getWritableDatabase();
        long closingId = -1;
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("close_time", cr.time);
            cv.put("valid_count", cr.validCount);
            cv.put("void_count", cr.voidCount);
            cv.put("net_sales", cr.netSales);
            cv.put("cash_sales", cr.cashSales);
            cv.put("opening_float", cr.openingFloat);
            cv.put("by_pay_json", cr.byPayJson != null ? cr.byPayJson : "{}");
            cv.put("item_rank_json", cr.itemRankJson != null ? cr.itemRankJson : "{}");
            cv.put("printed_paper", cr.printedPaper ? 1 : 0);
            cv.put("csv_path", cr.csvPath != null ? cr.csvPath : "");
            cv.put("cloud_synced", cr.cloudSynced ? 1 : 0);
            cv.put("cloud_status", cr.cloudStatus != null ? cr.cloudStatus : "");
            closingId = db.insert("daily_closings", null, cv);
            cr.dbId = closingId;

            // 將當日未關帳訂單標記為 closed = 1，使關帳後新營業日從 #001 乾淨重新計算
            ContentValues cvOrd = new ContentValues();
            cvOrd.put("closed", 1);
            db.update("orders", cvOrd, "closed = ?", new String[]{"0"});

            db.setTransactionSuccessful();
        } catch (Exception ignored) {
        } finally {
            db.endTransaction();
        }
        return closingId;
    }

    public void updateClosingCloudStatus(long closingId, boolean synced, String statusText) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("cloud_synced", synced ? 1 : 0);
        cv.put("cloud_status", statusText);
        db.update("daily_closings", cv, "id = ?", new String[]{String.valueOf(closingId)});
    }

    public List<MainActivity.ClosingRecord> loadClosingHistory() {
        List<MainActivity.ClosingRecord> res = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT id, close_time, valid_count, void_count, net_sales, "
                + "cash_sales, opening_float, by_pay_json, item_rank_json, printed_paper, "
                + "csv_path, cloud_synced, cloud_status FROM daily_closings ORDER BY id DESC LIMIT 1000", null);
            while (c.moveToNext()) {
                MainActivity.ClosingRecord cr = new MainActivity.ClosingRecord();
                cr.dbId = c.getLong(0);
                cr.time = c.getString(1);
                cr.validCount = c.getInt(2);
                cr.voidCount = c.getInt(3);
                cr.netSales = c.getInt(4);
                cr.cashSales = c.getInt(5);
                cr.openingFloat = c.getInt(6);
                cr.byPayJson = c.getString(7);
                cr.itemRankJson = c.getString(8);
                cr.printedPaper = c.getInt(9) == 1;
                cr.csvPath = c.getString(10);
                cr.cloudSynced = c.getInt(11) == 1;
                cr.cloudStatus = c.getString(12);
                res.add(cr);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return res;
    }

    /**
     * 匯出 UTF-8 BOM CSV 關帳報表（Excel 直接雙擊開啟不亂碼）
     */
    public String exportClosingToCsv(MainActivity.ClosingRecord cr,
                                     List<MainActivity.OrderRecord> orders,
                                     Map<String, Integer> byPay,
                                     Map<String, Integer> itemRank) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.TAIWAN).format(new Date());
        String fileName = "EvonnePOS_Closing_" + stamp + ".csv";

        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "POS_Reports");
        if (!dir.exists() && !dir.mkdirs()) {
            dir = new File(appContext.getExternalFilesDir(null), "POS_Reports");
            if (!dir.exists()) dir.mkdirs();
        }

        File outFile = new File(dir, fileName);
        if (!writeCsvInternal(outFile, cr, orders, byPay, itemRank)) {
            File fallbackDir = new File(appContext.getExternalFilesDir(null), "POS_Reports");
            if (!fallbackDir.exists()) fallbackDir.mkdirs();
            outFile = new File(fallbackDir, fileName);
            writeCsvInternal(outFile, cr, orders, byPay, itemRank);
        }
        return outFile.getAbsolutePath();
    }

    private boolean writeCsvInternal(File file, MainActivity.ClosingRecord cr,
                                     List<MainActivity.OrderRecord> orders,
                                     Map<String, Integer> byPay,
                                     Map<String, Integer> itemRank) {
        FileOutputStream fos = null;
        OutputStreamWriter writer = null;
        try {
            fos = new FileOutputStream(file);
            // 寫入 UTF-8 BOM (EF BB BF) 確保 Excel 繁體中文不亂碼
            fos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);

            writer.write("伊凡的花園 Evonne's Garden - 每日關帳日結報表\r\n");
            writer.write("關帳時間," + csvEscape(cr.time) + "\r\n");
            writer.write("有效訂單數," + cr.validCount + "\r\n");
            writer.write("作廢退單數," + cr.voidCount + "\r\n");
            writer.write("今日淨營業額,$" + cr.netSales + "\r\n");
            writer.write("現金實收小計,$" + cr.cashSales + "\r\n");
            writer.write("期初留底零用金,$" + cr.openingFloat + "\r\n");
            writer.write("錢櫃應有總現金,$" + (cr.openingFloat + cr.cashSales) + "\r\n\r\n");

            writer.write("【各付款方式統計】\r\n");
            writer.write("付款方式,金額\r\n");
            for (Map.Entry<String, Integer> e : byPay.entrySet()) {
                writer.write(csvEscape(e.getKey()) + "," + e.getValue() + "\r\n");
            }
            writer.write("\r\n【當日品項銷售數量排行】\r\n");
            writer.write("品項名稱,銷售數量\r\n");
            for (Map.Entry<String, Integer> e : itemRank.entrySet()) {
                writer.write(csvEscape(e.getKey()) + "," + e.getValue() + "\r\n");
            }

            writer.write("\r\n【當日訂單明細】\r\n");
            writer.write("取餐號,時間,用餐類型,狀態,付款方式,整單折扣,總金額,餐點內容與客製明細\r\n");
            for (MainActivity.OrderRecord o : orders) {
                writer.write(csvEscape(o.callNum) + ","
                    + csvEscape(o.time) + ","
                    + csvEscape(o.orderType) + ","
                    + (o.voided ? "已作廢(VOID)" : "有效") + ","
                    + csvEscape(o.paymentMethod) + ","
                    + o.orderDiscount + ","
                    + o.total + ","
                    + csvEscape(buildItemsSummary(o.items)) + "\r\n");
            }
            writer.flush();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }

    private String csvEscape(String s) {
        if (s == null) return "";
        String clean = s.replace("\"", "\"\"");
        if (clean.contains(",") || clean.contains("\"") || clean.contains("\n")) {
            return "\"" + clean + "\"";
        }
        return clean;
    }

    // ==================== 完整系統快照備份 & 雲端一鍵回復 & 斷網補傳佇列 ====================
    public JSONObject exportFullSnapshotJson(List<MainActivity.MenuItem> menu,
                                             List<MainActivity.ClosingRecord> closings) {
        JSONObject root = new JSONObject();
        try {
            root.put("backupVersion", 1);
            root.put("storeName", "伊凡的花園 Evonne's Garden");
            root.put("exportedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.TAIWAN).format(new Date()));

            JSONObject settingsObj = new JSONObject();
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = null;
            try {
                c = db.rawQuery("SELECT setting_key, setting_value FROM app_settings", null);
                while (c.moveToNext()) {
                    String k = c.getString(0);
                    if (!"pending_sync_queue".equals(k)) {
                        settingsObj.put(k, c.getString(1));
                    }
                }
            } finally {
                if (c != null) c.close();
            }
            root.put("settings", settingsObj);

            JSONArray menuArr = new JSONArray();
            if (menu != null) {
                for (MainActivity.MenuItem m : menu) {
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
                    JSONArray ch = new JSONArray();
                    if (m.choices != null) for (String s : m.choices) ch.put(s);
                    o.put("choices", ch);
                    menuArr.put(o);
                }
            }
            root.put("menu", menuArr);

            JSONArray closeArr = new JSONArray();
            if (closings != null) {
                int limit = Math.min(closings.size(), 365);
                for (int i = 0; i < limit; i++) {
                    MainActivity.ClosingRecord cr = closings.get(i);
                    JSONObject co = new JSONObject();
                    co.put("time", cr.time);
                    co.put("validCount", cr.validCount);
                    co.put("voidCount", cr.voidCount);
                    co.put("netSales", cr.netSales);
                    co.put("cashSales", cr.cashSales);
                    co.put("openingFloat", cr.openingFloat);
                    co.put("byPayJson", cr.byPayJson != null ? cr.byPayJson : "{}");
                    co.put("itemRankJson", cr.itemRankJson != null ? cr.itemRankJson : "{}");
                    co.put("printedPaper", cr.printedPaper);
                    co.put("cloudSynced", cr.cloudSynced);
                    co.put("cloudStatus", cr.cloudStatus != null ? cr.cloudStatus : "");
                    closeArr.put(co);
                }
            }
            root.put("closings", closeArr);
        } catch (Exception ignored) {}
        return root;
    }

    public String restoreFromSnapshotJson(JSONObject snap) throws Exception {
        if (snap == null) throw new IllegalArgumentException("雲端備份資料為空");
        SQLiteDatabase db = getWritableDatabase();
        int restoredSettings = 0, restoredMenu = 0, restoredClosings = 0;
        db.beginTransaction();
        try {
            JSONObject settingsObj = snap.optJSONObject("settings");
            if (settingsObj != null) {
                java.util.Iterator<String> keys = settingsObj.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    String val = settingsObj.optString(k, "");
                    if ("google_sheet_url".equals(k) && val.isEmpty()) continue;
                    ContentValues cv = new ContentValues();
                    cv.put("setting_key", k);
                    cv.put("setting_value", val);
                    db.insertWithOnConflict("app_settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                    restoredSettings++;
                }
            }

            JSONArray menuArr = snap.optJSONArray("menu");
            if (menuArr != null && menuArr.length() > 0) {
                db.delete("menu_items", null, null);
                for (int i = 0; i < menuArr.length(); i++) {
                    JSONObject o = menuArr.getJSONObject(i);
                    JSONArray ch = o.optJSONArray("choices");
                    String[] choices = new String[ch != null ? ch.length() : 0];
                    if (ch != null) for (int k = 0; k < ch.length(); k++) choices[k] = ch.optString(k, "");
                    MainActivity.MenuItem m = new MainActivity.MenuItem(
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
                    );
                    upsertMenuItemInternal(db, m);
                    restoredMenu++;
                }
            }

            JSONArray closeArr = snap.optJSONArray("closings");
            if (closeArr != null && closeArr.length() > 0) {
                db.delete("daily_closings", null, null);
                for (int i = closeArr.length() - 1; i >= 0; i--) {
                    JSONObject co = closeArr.getJSONObject(i);
                    ContentValues cv = new ContentValues();
                    cv.put("close_time", co.optString("time", ""));
                    cv.put("valid_count", co.optInt("validCount", 0));
                    cv.put("void_count", co.optInt("voidCount", 0));
                    cv.put("net_sales", co.optInt("netSales", 0));
                    cv.put("cash_sales", co.optInt("cashSales", 0));
                    cv.put("opening_float", co.optInt("openingFloat", 2000));
                    cv.put("by_pay_json", co.optString("byPayJson", "{}"));
                    cv.put("item_rank_json", co.optString("itemRankJson", "{}"));
                    cv.put("printed_paper", co.optBoolean("printedPaper", false) ? 1 : 0);
                    cv.put("csv_path", "");
                    cv.put("cloud_synced", co.optBoolean("cloudSynced", true) ? 1 : 0);
                    cv.put("cloud_status", co.optString("cloudStatus", "✅ 已由雲端備份還原"));
                    db.insert("daily_closings", null, cv);
                    restoredClosings++;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return "設定 " + restoredSettings + " 項｜菜單 " + restoredMenu + " 道｜關帳歷史 " + restoredClosings + " 筆";
    }

    public synchronized void enqueuePendingSync(String jsonPayload) {
        if (jsonPayload == null || jsonPayload.isEmpty()) return;
        try {
            JSONArray arr = getPendingSyncArray();
            arr.put(jsonPayload);
            setSetting("pending_sync_queue", arr.toString());
        } catch (Exception ignored) {}
    }

    public synchronized JSONArray getPendingSyncArray() {
        String raw = getSetting("pending_sync_queue", "[]");
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public synchronized void savePendingSyncArray(JSONArray arr) {
        setSetting("pending_sync_queue", arr != null ? arr.toString() : "[]");
    }
}
