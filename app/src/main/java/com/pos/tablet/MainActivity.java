package com.pos.tablet;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    // ==================== DATA MODELS ====================
    public static class AddonOption {
        public String name;
        public int price;
        public AddonOption(String n, int p) { this.name = n; this.price = p; }
    }

    public static class DrinkUpgradeOption {
        public String group;
        public String name;
        public int diffPrice; // 折抵附餐冷壓萃茶 $30 後的補差價
        public boolean fixedSweet;
        public boolean coldOnly;
        public boolean isThickMilk;

        public DrinkUpgradeOption(String group, String name, int diffPrice,
                                  boolean fixedSweet, boolean coldOnly, boolean isThickMilk) {
            this.group = group;
            this.name = name;
            this.diffPrice = diffPrice;
            this.fixedSweet = fixedSweet;
            this.coldOnly = coldOnly;
            this.isThickMilk = isThickMilk;
        }
    }

    public static class MenuItem {
        public int id;
        public String cat;
        public String name;
        public String sub;
        public int price;
        public boolean isDrink;
        public boolean hasFreeTea;
        public boolean fixedSweet;
        public boolean coldOnly;
        public boolean isThickMilk;
        public String[] choices;

        public MenuItem(int id, String cat, String name, String sub, int price,
                        boolean isDrink, boolean hasFreeTea, boolean fixedSweet,
                        boolean coldOnly, boolean isThickMilk, String[] choices) {
            this.id = id;
            this.cat = cat;
            this.name = name;
            this.sub = sub;
            this.price = price;
            this.isDrink = isDrink;
            this.hasFreeTea = hasFreeTea;
            this.fixedSweet = fixedSweet;
            this.coldOnly = coldOnly;
            this.isThickMilk = isThickMilk;
            this.choices = choices != null ? choices : new String[0];
        }
    }

    public static class CartItem {
        public MenuItem menu;
        public int qty = 1;
        public String selectedChoice = ""; // 素別 / 湯頭 (🌶️麻辣燙 or 🌿藥膳燙) / 炸物調味
        public String drinkSweet = "5分半糖";
        public String drinkIce = "8分少冰";

        // 主餐附贈/換購飲品欄位
        public String mealDrinkName = "毛尖綠茶";
        public int mealDrinkDiff = 0;
        public String mealDrinkSweet = "無糖";
        public String mealDrinkIce = "3分微冰";

        public List<AddonOption> addons = new ArrayList<>();
        public int itemDiscount = 0;
        public String note = "";
        public boolean printLabel;
        public boolean manualLabelOverride = false;
        // 隨餐附贈/換購之飲料是否列印專屬飲料杯貼紙 (預設一律列印，解決餐點附贈飲料未印貼紙問題)
        public boolean printDrinkLabel = true;
        public boolean manualDrinkLabelOverride = false;

        public boolean hasAttachedDrink() {
            return menu != null && menu.hasFreeTea
                && mealDrinkName != null && !mealDrinkName.isEmpty()
                && !"不需附茶".equals(mealDrinkName);
        }

        public String getAttachedDrinkModSummary() {
            List<String> dp = new ArrayList<>();
            dp.add(mealDrinkSweet != null && !mealDrinkSweet.isEmpty() ? mealDrinkSweet : "無糖");
            dp.add(mealDrinkIce != null && !mealDrinkIce.isEmpty() ? mealDrinkIce : "3分微冰");
            for (AddonOption a : addons) {
                if (a.name.contains("愛玉") || a.name.contains("椰果") || a.name.contains("奶")) {
                    dp.add("+" + a.name + "($" + a.price + ")");
                }
            }
            dp.add("隨餐:" + (menu != null ? menu.name : ""));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < dp.size(); i++) {
                if (i > 0) sb.append(" / ");
                sb.append(dp.get(i));
            }
            return sb.toString();
        }

        public int getItemStickerCount() {
            int c = 0;
            if (printLabel) c += qty;
            if (hasAttachedDrink() && printDrinkLabel) c += qty;
            return c;
        }

        public int getAddonSum() {
            int s = 0;
            for (AddonOption a : addons) s += a.price;
            return s;
        }

        public int getUnitPrice() {
            return menu.price + (menu.hasFreeTea ? mealDrinkDiff : 0) + getAddonSum();
        }

        public int getSubTotal() {
            int raw = getUnitPrice() * qty;
            return Math.max(0, raw - itemDiscount);
        }

        public String getModSummary() {
            List<String> parts = new ArrayList<>();
            if (menu.isDrink) {
                parts.add(drinkSweet);
                parts.add(drinkIce);
            } else {
                if (selectedChoice != null && !selectedChoice.isEmpty()) {
                    parts.add(selectedChoice);
                }
                if (menu.hasFreeTea) {
                    if ("不需附茶".equals(mealDrinkName)) {
                        parts.add("不需附茶");
                    } else {
                        String tag = "附:" + mealDrinkName + "(" + mealDrinkSweet + "/" + mealDrinkIce + ")";
                        if (mealDrinkDiff > 0) tag += "(換購+$" + mealDrinkDiff + ")";
                        parts.add(tag);
                    }
                }
            }
            for (AddonOption a : addons) {
                parts.add("+" + a.name + "($" + a.price + ")");
            }
            if (note != null && !note.isEmpty()) parts.add("備註:" + note);
            if (itemDiscount > 0) parts.add("折-$" + itemDiscount);
            if (parts.isEmpty()) return "標準";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) sb.append(" / ");
                sb.append(parts.get(i));
            }
            return sb.toString();
        }

        public boolean isSameConfigAs(CartItem other) {
            if (other == null || this.menu.id != other.menu.id) return false;
            if (this.itemDiscount != other.itemDiscount) return false;
            return this.getModSummary().equals(other.getModSummary());
        }
    }

    public static class OrderRecord {
        public long dbId = -1;
        public String callNum;
        public String time;
        public String orderType;
        public List<CartItem> items = new ArrayList<>();
        public int orderDiscount;
        public int total;
        public String paymentMethod;
        public int cashReceived;
        public int cashChange;
        public boolean printReceipt;
        public int stickerCount;
        public boolean voided = false;
        public String staffName = "";
        public String invoiceNo = "";
        public String invoicePeriod = "";
        public String invoiceRandom = "";
        public String buyerTaxId = "";
        public String carrierType = "PAPER";
        public String carrierCode = "";
        public String invoiceStatus = "NONE";
        public String invoiceQrLeft = "";
        public String invoiceQrRight = "";
        public String paymentTxId = "";
        public String paymentProvider = "";
        public transient boolean triggerCashDrawer = false;
    }

    public static class StaffMember {
        public long id = -1;
        public String name;
        public String role = "店員"; // "店長" or "店員"
        public String pin = "";
        public boolean enabled = true;
    }

    public static class ShiftHandoverRecord {
        public long id = -1;
        public String shiftStart;
        public String shiftEnd;
        public String staffName;
        public int openingCash;
        public int cashSales;
        public int nonCashSales;
        public int expectedCash;
        public int countedCash;
        public int diffCash;
        public int orderCount;
        public int voidCount;
        public String note = "";
    }

    public static class AuditLogRecord {
        public long id = -1;
        public String createdAt;
        public String staffName;
        public String actionType;
        public String targetRef;
        public String detail;
    }

    public static class ClosingRecord {
        public long dbId = -1;
        public String time;
        public int validCount;
        public int voidCount;
        public int netSales;
        public int cashSales;
        public int openingFloat = 2000;
        public String byPayJson = "{}";
        public String itemRankJson = "{}";
        public boolean printedPaper;
        public String csvPath = "";
        public boolean cloudSynced = false;
        public String cloudStatus;
    }

    // ==================== STATE & SQLITE HELPER ====================
    private PosDatabaseHelper dbHelper;
    private int callSequence = 1;
    private String currentOrderType = "內用";
    private String currentTable = "1桌";
    private String currentCategory = "義大利麵&飯食";
    private String defaultBroth = "🌶️麻辣燙"; // 麻辣燙/藥膳燙 預設湯頭快速切換
    private int orderDiscount = 0;
    private boolean globalPrintReceipt = true;
    private boolean globalPrintLabels = true;
    private int openingFloat = 2000;
    private String receiptIp = "192.168.0.200";
    private int receiptPort = 9100;
    private int receiptWidthMm = 80; // 80mm or 58mm
    private String labelIp = "192.168.0.201";
    private int labelPort = 9100;
    private String labelProtocol = "EZPL"; // EZPL (GoDEX原生) or TSPL
    private String googleSheetUrl = "";
    private static final String DEFAULT_OTA_MANIFEST_URL =
        "https://raw.githubusercontent.com/cch0919542639-gif/evonne-garden-pos/main/version.json";
    private String otaManifestUrl = DEFAULT_OTA_MANIFEST_URL;
    private String managerPin = "";

    // 彈性員工權限 / 交班盤點 / 稽核日誌設定（可讓小店極簡免切換，也可讓多員工分權稽核）
    private boolean staffModeEnabled = false;
    private boolean shiftHandoverEnabled = false;
    private boolean auditLogEnabled = false;
    private String currentStaffName = "店長";
    private String currentStaffRole = "店長";
    private String shiftStartTime = "";
    private long lastHandoverOrderDbId = 0L;
    private final List<StaffMember> staffMembers = new ArrayList<>();
    private LicenseManager.LicenseState licenseState = null;
    private static final int REQ_BLUETOOTH_CONNECT = 9101;
    private boolean pendingBtPickerForReceipt = true;

    private final List<MenuItem> menuItems = new ArrayList<>();
    private final List<DrinkUpgradeOption> mealDrinkPool = new ArrayList<>();
    private final List<CartItem> cart = new ArrayList<>();
    private final List<OrderRecord> ordersHistory = new ArrayList<>();
    private final List<ClosingRecord> closingHistory = new ArrayList<>();

    private static final String[] SWEET_OPTIONS = {
        "無糖", "1分糖", "3分微糖", "5分半糖", "8分少糖", "10分正常", "▲甜度固定"
    };
    private static final String[] ICE_OPTIONS = {
        "去冰", "3分微冰", "5分半冰", "8分少冰", "10分正常冰", "常溫", "熱飲(Hot)"
    };
    private static final String[] COLD_ONLY_ICE_OPTIONS = {
        "去冰", "3分微冰", "5分半冰", "8分少冰", "10分正常冰", "常溫"
    };

    private static final AddonOption[] ADDONS_PASTA_PIZZA = {
        new AddonOption("升級焗烤", 50),
        new AddonOption("升級松露", 50),
        new AddonOption("升級全素起司", 80)
    };
    private static final AddonOption[] ADDONS_SET_MEAL = {
        new AddonOption("加爌肉", 40),
        new AddonOption("加植蛋", 40),
        new AddonOption("加叉燒", 50),
        new AddonOption("加棒腿", 60),
        new AddonOption("加滷排", 70),
        new AddonOption("加和牛排", 80)
    };
    // 飲品加料（含濃奶/厚奶+$20、鮮奶+$30、寒天愛玉+$15、鳳梨椰果+$10）
    private static final AddonOption[] ADDONS_DRINK_COMMON = {
        new AddonOption("寒天愛玉", 15),
        new AddonOption("鳳梨椰果", 10),
        new AddonOption("厚奶(濃奶)", 20),
        new AddonOption("鮮奶", 30)
    };
    private static final AddonOption[] ADDONS_THICK_MILK = {
        new AddonOption("厚奶升級鮮奶", 10),
        new AddonOption("寒天愛玉", 15),
        new AddonOption("鳳梨椰果", 10),
        new AddonOption("厚奶(濃奶)", 20),
        new AddonOption("鮮奶", 30)
    };

    // ==================== UI REFERENCES ====================
    private TextView tvBrand, tvTopCallNum, tvPrinterStatusBadge, tvStaffBadge;
    private Button btnTabPos, btnTabQrOrders, btnTabOrders, btnTabClosing, btnTabSettings;
    private LinearLayout viewPos, viewOrders, viewClosing, viewSettings;

    // POS Tab views
    private Button btnTypeDineIn, btnTypeTakeout, btnTypeDelivery, btnTypeUber, btnTypePanda;
    private Button btnSelectTable;
    private LinearLayout llCategoryBar;
    private LinearLayout llBrothQuickBar;
    private GridLayout glMenuGrid;
    private TextView tvCartHeaderType, tvCartRuleHint;
    private LinearLayout llCartList;
    private CheckBox cbGlobalReceipt, cbGlobalLabels;
    private TextView tvCartSummary, tvCartGrandTotal;

    // 視覺化餐廳桌位平面圖 (5欄 x 4列 = 20格，預設配置 1~13 桌 + 吧台，可自由移位與增減桌號)
    private static final int FLOOR_COLS = 5;
    private static final int FLOOR_ROWS = 4;
    private static final int FLOOR_TOTAL_SLOTS = FLOOR_COLS * FLOOR_ROWS; // 20 格
    private final String[] tableFloorSlots = new String[FLOOR_TOTAL_SLOTS];

    // 雲端「📱 客人掃碼線上點餐」待接訂單佇列
    private final List<CloudSyncManager.PendingQrOrder> pendingQrOrders = new ArrayList<>();
    private final android.os.Handler qrPollHandler = new android.os.Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        dbHelper = new PosDatabaseHelper(this);
        PosPrintEngine.init(this);
        boolean isFirstFreshInstall = loadDatabaseState();
        buildRootUI();
        updateBrandAndStaffHeader();
        updateOrdersBadge();
        refreshAllPosViews();
        handleIncomingConfigIntent();
        startQrOrderAutoPoller();
        refreshPrinterStatusBadge();
        if (googleSheetUrl != null && !googleSheetUrl.trim().isEmpty()) {
            CloudSyncManager.flushPendingSyncQueueAsync(googleSheetUrl, dbHelper, new CloudSyncManager.SyncCallback() {
                @Override public void onResult(boolean ok, String msg) {
                    if (ok) Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                }
            });
        }
        if (isFirstFreshInstall && (getIntent() == null || !getIntent().hasExtra("set_sheet_url"))) {
            showStoreOnboardingDialog();
        }
    }

    private void startQrOrderAutoPoller() {
        qrPollHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (googleSheetUrl != null && !googleSheetUrl.trim().isEmpty()) {
                    CloudSyncManager.fetchPendingQrOrdersAsync(googleSheetUrl, new CloudSyncManager.QrOrdersCallback() {
                        @Override
                        public void onResult(boolean ok, String msg, List<CloudSyncManager.PendingQrOrder> list) {
                            if (ok && list != null) {
                                int prevSize = pendingQrOrders.size();
                                pendingQrOrders.clear();
                                pendingQrOrders.addAll(list);
                                updateQrOrdersBadge();
                                if (list.size() > prevSize) {
                                    try {
                                        android.media.ToneGenerator tg = new android.media.ToneGenerator(
                                            android.media.AudioManager.STREAM_NOTIFICATION, 100);
                                        tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 400);
                                    } catch (Exception ignored) {}
                                    Toast.makeText(MainActivity.this,
                                        "🔔 收到新的客人手機掃碼點餐訂單！(共 " + list.size() + " 筆待接單)",
                                        Toast.LENGTH_LONG).show();
                                    showQrOrdersAndStandeeDialog();
                                }
                            }
                        }
                    });
                }
                qrPollHandler.postDelayed(this, 12000);
            }
        }, 4000);
    }

    private void updateQrOrdersBadge() {
        if (btnTabQrOrders != null) {
            int cnt = pendingQrOrders.size();
            btnTabQrOrders.setText(cnt > 0 ? "🔔 掃碼待接(" + cnt + ")" : "📱 掃碼點餐/桌牌");
            if (cnt > 0) {
                btnTabQrOrders.setTextColor(Color.BLACK);
                btnTabQrOrders.setBackground(roundBg(Color.parseColor("#F59E0B"), Color.WHITE, 2, 8));
            } else {
                styleTopTabBtn(btnTabQrOrders, false);
            }
        }
    }

    private void initDefaultTableFloorSlots() {
        for (int i = 0; i < FLOOR_TOTAL_SLOTS; i++) tableFloorSlots[i] = "";
        // 預設配置 1桌 ~ 13桌 + 吧台，留出走道格位供店家自由視覺化排桌
        tableFloorSlots[0] = "1桌";
        tableFloorSlots[1] = "2桌";
        tableFloorSlots[2] = "3桌";
        tableFloorSlots[3] = "4桌";
        tableFloorSlots[4] = "5桌";
        tableFloorSlots[5] = "6桌";
        tableFloorSlots[6] = "7桌";
        tableFloorSlots[8] = "8桌";
        tableFloorSlots[9] = "9桌";
        tableFloorSlots[10] = "10桌";
        tableFloorSlots[11] = "11桌";
        tableFloorSlots[13] = "12桌";
        tableFloorSlots[14] = "13桌";
        tableFloorSlots[19] = "吧台";
    }

    private void loadTableFloorSlotsFromDb() {
        String raw = dbHelper.getSetting("table_floor_slots", "");
        if (raw == null || raw.trim().isEmpty()) {
            initDefaultTableFloorSlots();
            saveTableFloorSlotsToDb();
            return;
        }
        try {
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            for (int i = 0; i < FLOOR_TOTAL_SLOTS; i++) {
                tableFloorSlots[i] = (i < arr.length()) ? arr.optString(i, "") : "";
            }
        } catch (Exception e) {
            initDefaultTableFloorSlots();
        }
    }

    private void saveTableFloorSlotsToDb() {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (int i = 0; i < FLOOR_TOTAL_SLOTS; i++) {
            arr.put(tableFloorSlots[i] != null ? tableFloorSlots[i] : "");
        }
        dbHelper.setSetting("table_floor_slots", arr.toString());
    }

    private List<String> getActiveTableNames() {
        List<String> list = new ArrayList<>();
        for (String s : tableFloorSlots) {
            if (s != null && !s.trim().isEmpty()) list.add(s.trim());
        }
        if (list.isEmpty()) list.add("1桌");
        return list;
    }

    private void handleIncomingConfigIntent() {
        if (getIntent() == null) return;
        String incomingUrl = getIntent().getStringExtra("set_sheet_url");
        if (incomingUrl != null && !incomingUrl.trim().isEmpty()) {
            googleSheetUrl = incomingUrl.trim();
            dbHelper.setSetting("google_sheet_url", googleSheetUrl);
            switchMainTab(3);
            if (getIntent().getBooleanExtra("auto_upload_menu", false)) {
                Toast.makeText(this, "⏳ 正在將 66 道菜單同步備份至 Google 試算表...", Toast.LENGTH_SHORT).show();
                CloudSyncManager.uploadMenuAsync(googleSheetUrl, menuItems, new CloudSyncManager.SyncCallback() {
                    @Override
                    public void onResult(boolean ok, String msg) {
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle(ok ? "✅ 雲端菜單同步完成" : "⚠️ 雲端同步提示")
                            .setMessage(msg + "\n\n💡 您可以直接點下方【📱 開啟各桌掃碼點餐 QR Code 條碼 & 印桌牌中心】查看或列印各桌條碼！")
                            .setPositiveButton("📱 立即查看各桌 QR Code", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    showQrOrdersAndStandeeDialog();
                                }
                            })
                            .setNegativeButton("稍後", null)
                            .show();
                    }
                });
            }
        }
    }

    private boolean loadDatabaseState() {
        boolean isFirstFreshInstall = false;
        String initializedFlag = dbHelper.getSetting("store_initialized", "");
        if (initializedFlag == null || initializedFlag.isEmpty()) {
            boolean hasLegacy = dbHelper.hasAnyMenuOrHistory()
                || !dbHelper.getSetting("google_sheet_url", "").isEmpty()
                || !dbHelper.getSetting("receipt_ip", "").isEmpty();
            if (hasLegacy) {
                // 舊版升級相容：使用中性預設門市檔（實機既有設定可透過資料庫或設定頁維護）
                dbHelper.setSetting("store_code", "LEGACY-001");
                dbHelper.setSetting("store_name", "我的餐飲門市");
                dbHelper.setSetting("store_short_name", "我的門市");
                dbHelper.setSetting("store_subtitle", "");
                dbHelper.setSetting("store_initialized", "1");
            } else {
                isFirstFreshInstall = true;
                dbHelper.setSetting("store_code", "STORE-001");
                dbHelper.setSetting("store_name", "我的餐飲門市");
                dbHelper.setSetting("store_short_name", "我的門市");
                dbHelper.setSetting("store_subtitle", "");
            }
        }

        PosPrintEngine.storeCode = dbHelper.getSetting("store_code", "STORE-001");
        PosPrintEngine.storeName = dbHelper.getSetting("store_name", "我的餐飲門市");
        PosPrintEngine.storeShortName = dbHelper.getSetting("store_short_name", "我的門市");
        PosPrintEngine.storeSubtitle = dbHelper.getSetting("store_subtitle", "");
        PosPrintEngine.storeReceiptFooter = dbHelper.getSetting("store_receipt_footer", "");

        List<MenuItem> storedMenu = dbHelper.loadMenuItems();
        menuItems.clear();
        menuItems.addAll(storedMenu);
        rebuildMealDrinkPool();

        callSequence = dbHelper.getIntSetting("call_sequence", 1);
        openingFloat = dbHelper.getIntSetting("opening_float", 2000);
        receiptIp = dbHelper.getSetting("receipt_ip", "192.168.0.200");
        receiptPort = dbHelper.getIntSetting("receipt_port", 9100);
        receiptWidthMm = dbHelper.getIntSetting("receipt_width_mm", 80);
        PosPrintEngine.receiptFontScalePercent = dbHelper.getIntSetting("receipt_font_scale", 145);
        PosPrintEngine.receiptPrintableDots = dbHelper.getIntSetting("receipt_printable_dots", 512);
        PosPrintEngine.receiptLeftMarginDots = dbHelper.getIntSetting("receipt_left_margin", 24);
        PosPrintEngine.receiptRightMarginDots = dbHelper.getIntSetting("receipt_right_margin", 28);

        labelIp = dbHelper.getSetting("label_ip", "192.168.0.201");
        labelPort = dbHelper.getIntSetting("label_port", 9100);
        labelProtocol = dbHelper.getSetting("label_protocol", "EZPL");
        PosPrintEngine.labelFontScalePercent = dbHelper.getIntSetting("label_font_scale", 115);
        PosPrintEngine.labelMarginDots = dbHelper.getIntSetting("label_margin_dots", 12);
        PosPrintEngine.receiptConnMode = dbHelper.getSetting("receipt_conn_mode", PosPrintEngine.CONN_WIFI);
        PosPrintEngine.receiptBtMac = dbHelper.getSetting("receipt_bt_mac", "");
        PosPrintEngine.receiptUsbId = dbHelper.getSetting("receipt_usb_id", "");
        PosPrintEngine.labelConnMode = dbHelper.getSetting("label_conn_mode", PosPrintEngine.CONN_WIFI);
        PosPrintEngine.labelBtMac = dbHelper.getSetting("label_bt_mac", "");
        PosPrintEngine.labelUsbId = dbHelper.getSetting("label_usb_id", "");
        PosPrintEngine.autoOpenCashDrawerOnCash = "1".equals(dbHelper.getSetting("auto_open_cash_drawer", "0"));
        googleSheetUrl = dbHelper.getSetting("google_sheet_url", "");
        otaManifestUrl = dbHelper.getSetting("ota_manifest_url", DEFAULT_OTA_MANIFEST_URL);
        managerPin = dbHelper.getSetting("manager_pin", "");

        staffModeEnabled = "1".equals(dbHelper.getSetting("staff_mode_enabled", "0"));
        shiftHandoverEnabled = "1".equals(dbHelper.getSetting("shift_handover_enabled", "0"));
        auditLogEnabled = "1".equals(dbHelper.getSetting("audit_log_enabled", "0"));
        currentStaffName = dbHelper.getSetting("current_staff_name", "店長");
        currentStaffRole = dbHelper.getSetting("current_staff_role", "店長");
        shiftStartTime = dbHelper.getSetting("shift_start_time", "");
        if (shiftStartTime.isEmpty()) {
            shiftStartTime = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TAIWAN).format(new Date());
            dbHelper.setSetting("shift_start_time", shiftStartTime);
        }
        try {
            lastHandoverOrderDbId = Long.parseLong(dbHelper.getSetting("last_handover_order_id", "0"));
        } catch (Exception e) {
            lastHandoverOrderDbId = 0L;
        }
        if (staffModeEnabled) {
            dbHelper.seedDefaultStaffIfEmpty(managerPin);
        }
        staffMembers.clear();
        staffMembers.addAll(dbHelper.loadStaffMembers());

        loadTableFloorSlotsFromDb();

        ordersHistory.clear();
        ordersHistory.addAll(dbHelper.loadActiveOrders(menuItems));

        closingHistory.clear();
        closingHistory.addAll(dbHelper.loadClosingHistory());
        TaiwanCommerceGateway.loadFromDb(dbHelper);
        refreshLicenseState();
        return isFirstFreshInstall;
    }

    private void refreshLicenseState() {
        licenseState = LicenseManager.evaluate(this, dbHelper, PosPrintEngine.storeCode);
        dbHelper.setSetting("plan_type", licenseState.planCode);
        dbHelper.setSetting("enabled_features", licenseState.enabledFeatures);
    }

    private boolean ensureFeatureAllowed(String featureKey, String featureLabel) {
        if (licenseState == null) refreshLicenseState();
        if (!licenseState.canCheckout) {
            showLicenseActivationDialog("⚠️ " + featureLabel + " 需要有效授權（目前狀態：" + licenseState.statusSummary + "）");
            return false;
        }
        if (featureKey != null && !licenseState.hasFeature(featureKey)) {
            showLicenseActivationDialog("🔒 「" + featureLabel + "」尚未包含在目前方案 (" + licenseState.planLabel + ") 中。\n可聯繫供應商升級解鎖此模組，或繼續正常營業核心結帳功能。");
            return false;
        }
        return true;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private GradientDrawable roundBg(int fillColor, int strokeColor, int strokeDp, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) gd.setStroke(dp(strokeDp), strokeColor);
        return gd;
    }

    private String formatCallNum(int n) {
        return String.format(Locale.US, "#%03d", n);
    }

    private boolean shouldPrintLabelByDefault(boolean isDrink, String orderType) {
        if (isDrink) return true;
        return !"內用".equals(orderType);
    }

    private void rebuildMealDrinkPool() {
        mealDrinkPool.clear();
        // 建立主餐隨餐附飲 & 補差價換購池（原附飲折抵 $30）
        for (MenuItem m : menuItems) {
            if (m.isDrink) {
                int diff = Math.max(0, m.price - 30);
                mealDrinkPool.add(new DrinkUpgradeOption(m.cat, m.name, diff, m.fixedSweet, m.coldOnly, m.isThickMilk));
            }
        }
        mealDrinkPool.add(new DrinkUpgradeOption("冷壓萃茶", "不需附茶", 0, true, true, false));
    }

    private void initMenuData() {
        menuItems.clear();
        mealDrinkPool.clear();

        // 1. 義大利麵&飯食 ($200，附冷壓萃茶)
        menuItems.add(new MenuItem(101, "義大利麵&飯食", "白醬起司麵", "奶素/純素｜附冷壓萃茶", 200, false, true, false, false, false, new String[]{"奶素", "純素"}));
        menuItems.add(new MenuItem(102, "義大利麵&飯食", "白醬燉飯", "奶素/純素｜附冷壓萃茶", 200, false, true, false, false, false, new String[]{"奶素", "純素"}));
        menuItems.add(new MenuItem(103, "義大利麵&飯食", "手作青醬麵", "奶素/純素｜附冷壓萃茶", 200, false, true, false, false, false, new String[]{"奶素", "純素"}));
        menuItems.add(new MenuItem(104, "義大利麵&飯食", "青醬燉飯", "奶素/純素｜附冷壓萃茶", 200, false, true, false, false, false, new String[]{"奶素", "純素"}));
        menuItems.add(new MenuItem(105, "義大利麵&飯食", "義式紅醬麵", "純素｜附冷壓萃茶", 200, false, true, false, false, false, new String[]{"純素"}));
        menuItems.add(new MenuItem(106, "義大利麵&飯食", "🌶️黑胡椒豆腐飯", "純素｜附冷壓萃茶", 200, false, true, false, false, false, new String[]{"純素", "小辣", "中辣"}));
        menuItems.add(new MenuItem(107, "義大利麵&飯食", "蕃茄肉醬麵", "純素｜附冷壓萃茶", 200, false, true, false, false, false, new String[]{"純素"}));
        menuItems.add(new MenuItem(108, "義大利麵&飯食", "🌶️XO 金沙燉飯", "蛋素｜附冷壓萃茶", 200, false, true, false, false, false, new String[]{"蛋素", "小辣", "中辣"}));

        // 2. 冰花煎餃x10顆 (附冷壓萃茶)
        menuItems.add(new MenuItem(201, "冰花煎餃x10顆", "玉米鮮蔬餃", "純素｜附冷壓萃茶", 120, false, true, false, false, false, new String[]{"純素"}));
        menuItems.add(new MenuItem(202, "冰花煎餃x10顆", "海苔鮮蔬餃", "純素｜附冷壓萃茶", 130, false, true, false, false, false, new String[]{"純素"}));
        menuItems.add(new MenuItem(203, "冰花煎餃x10顆", "姑姑鮮蔬餃", "純素｜附冷壓萃茶", 130, false, true, false, false, false, new String[]{"純素"}));
        menuItems.add(new MenuItem(204, "冰花煎餃x10顆", "椰奶咖哩餃", "奶素｜附冷壓萃茶", 130, false, true, false, false, false, new String[]{"奶素"}));
        menuItems.add(new MenuItem(205, "冰花煎餃x10顆", "塔香煙燻餃", "純素｜附冷壓萃茶", 130, false, true, false, false, false, new String[]{"純素"}));
        menuItems.add(new MenuItem(206, "冰花煎餃x10顆", "青江粉絲餃", "蛋素｜附冷壓萃茶", 130, false, true, false, false, false, new String[]{"蛋素"}));
        menuItems.add(new MenuItem(207, "冰花煎餃x10顆", "香菜堅果餃", "純素｜附冷壓萃茶", 130, false, true, false, false, false, new String[]{"純素"}));

        // 3. 簡餐(五穀飯) (附冷壓萃茶)
        menuItems.add(new MenuItem(301, "簡餐(五穀飯)", "港式植叉燒飯", "純素｜附冷壓萃茶", 180, false, true, false, false, false, new String[]{"純素", "飯正常", "飯少"}));
        menuItems.add(new MenuItem(302, "簡餐(五穀飯)", "甘蔗棒棒腿飯", "純素｜附冷壓萃茶", 190, false, true, false, false, false, new String[]{"純素", "飯正常", "飯少"}));
        menuItems.add(new MenuItem(303, "簡餐(五穀飯)", "爌肉植物蛋飯", "純素｜附冷壓萃茶", 200, false, true, false, false, false, new String[]{"純素", "飯正常", "飯少"}));
        menuItems.add(new MenuItem(304, "簡餐(五穀飯)", "古早味滷排飯", "蛋素｜附冷壓萃茶", 200, false, true, false, false, false, new String[]{"蛋素", "飯正常", "飯少"}));
        menuItems.add(new MenuItem(305, "簡餐(五穀飯)", "植物和牛排飯", "純素｜附冷壓萃茶", 210, false, true, false, false, false, new String[]{"純素", "飯正常", "飯少"}));

        // 4. 個人比薩7.5吋 (附冷壓萃茶)
        menuItems.add(new MenuItem(401, "個人比薩7.5吋", "青醬野菇披薩", "奶素｜附冷壓萃茶", 190, false, true, false, false, false, new String[]{"奶素"}));
        menuItems.add(new MenuItem(402, "個人比薩7.5吋", "瑪格麗特披薩", "奶素｜附冷壓萃茶", 190, false, true, false, false, false, new String[]{"奶素"}));
        menuItems.add(new MenuItem(403, "個人比薩7.5吋", "🌶️XO 薯條披薩", "蛋奶素｜附冷壓萃茶", 190, false, true, false, false, false, new String[]{"蛋奶素"}));
        menuItems.add(new MenuItem(404, "個人比薩7.5吋", "夏威夷披薩", "奶素｜附冷壓萃茶", 190, false, true, false, false, false, new String[]{"奶素"}));
        menuItems.add(new MenuItem(405, "個人比薩7.5吋", "泡菜臭豆腐披薩", "奶素｜附冷壓萃茶", 190, false, true, false, false, false, new String[]{"奶素"}));
        menuItems.add(new MenuItem(406, "個人比薩7.5吋", "🌶️黑胡椒植牛披薩", "奶素｜附冷壓萃茶", 220, false, true, false, false, false, new String[]{"奶素"}));
        menuItems.add(new MenuItem(407, "個人比薩7.5吋", "塔香姑姑披薩", "奶素｜附冷壓萃茶", 220, false, true, false, false, false, new String[]{"奶素"}));
        menuItems.add(new MenuItem(408, "個人比薩7.5吋", "鮪魚玉米披薩", "奶素｜附冷壓萃茶", 220, false, true, false, false, false, new String[]{"奶素"}));
        menuItems.add(new MenuItem(409, "個人比薩7.5吋", "三杯姑姑雞披薩", "純素｜附冷壓萃茶", 220, false, true, false, false, false, new String[]{"純素"}));

        // 5. 酥脆炸物
        menuItems.add(new MenuItem(501, "酥脆炸物", "炸杏鮑菇", "純素炸物", 40, false, false, false, false, false, new String[]{"胡椒鹽", "加辣粉", "不灑粉"}));
        menuItems.add(new MenuItem(502, "酥脆炸物", "炸金針菇", "純素炸物", 40, false, false, false, false, false, new String[]{"胡椒鹽", "加辣粉", "不灑粉"}));
        menuItems.add(new MenuItem(503, "酥脆炸物", "波浪薯條", "純素炸物", 50, false, false, false, false, false, new String[]{"胡椒鹽", "蕃茄醬", "不灑粉"}));
        menuItems.add(new MenuItem(504, "酥脆炸物", "酥炸黑輪", "純素炸物", 50, false, false, false, false, false, new String[]{"胡椒鹽", "加辣粉", "不灑粉"}));
        menuItems.add(new MenuItem(505, "酥脆炸物", "蚵仔酥", "純素炸物", 50, false, false, false, false, false, new String[]{"胡椒鹽", "加辣粉", "不灑粉"}));
        menuItems.add(new MenuItem(506, "酥脆炸物", "魚柳條", "純素炸物", 60, false, false, false, false, false, new String[]{"胡椒鹽", "加辣粉", "不灑粉"}));
        menuItems.add(new MenuItem(507, "酥脆炸物", "塔香雞丁", "純素炸物", 60, false, false, false, false, false, new String[]{"胡椒鹽", "加辣粉", "不灑粉"}));
        menuItems.add(new MenuItem(508, "酥脆炸物", "什豆天貝排", "純素炸物", 70, false, false, false, false, false, new String[]{"胡椒鹽", "加辣粉", "不灑粉"}));
        menuItems.add(new MenuItem(509, "酥脆炸物", "臭豆腐排", "純素炸物", 80, false, false, false, false, false, new String[]{"胡椒鹽", "加辣", "不辣"}));
        menuItems.add(new MenuItem(510, "酥脆炸物", "酥炸臭豆腐", "純素炸物", 100, false, false, false, false, false, new String[]{"不辣", "加辣", "泡菜分裝"}));
        menuItems.add(new MenuItem(511, "酥脆炸物", "臭豆腐薯條", "純素炸物", 150, false, false, false, false, false, new String[]{"不辣", "加辣"}));

        // 6. 麻辣燙/藥膳燙
        String[] brothChoices = {"🌶️麻辣燙", "🌿藥膳燙"};
        menuItems.add(new MenuItem(601, "麻辣燙/藥膳燙", "板豆腐", "必選湯底", 40, false, false, false, false, false, brothChoices));
        menuItems.add(new MenuItem(602, "麻辣燙/藥膳燙", "高麗菜", "必選湯底", 40, false, false, false, false, false, brothChoices));
        menuItems.add(new MenuItem(603, "麻辣燙/藥膳燙", "時蔬", "必選湯底", 40, false, false, false, false, false, brothChoices));
        menuItems.add(new MenuItem(604, "麻辣燙/藥膳燙", "杏鮑菇(燙)", "必選湯底", 40, false, false, false, false, false, brothChoices));
        menuItems.add(new MenuItem(605, "麻辣燙/藥膳燙", "金針菇(燙)", "必選湯底", 40, false, false, false, false, false, brothChoices));
        menuItems.add(new MenuItem(606, "麻辣燙/藥膳燙", "豆皮", "必選湯底", 50, false, false, false, false, false, brothChoices));
        menuItems.add(new MenuItem(607, "麻辣燙/藥膳燙", "毛豆", "必選湯底", 50, false, false, false, false, false, brothChoices));
        menuItems.add(new MenuItem(608, "麻辣燙/藥膳燙", "黑輪(燙)", "必選湯底", 50, false, false, false, false, false, brothChoices));
        menuItems.add(new MenuItem(609, "麻辣燙/藥膳燙", "魚蛋", "必選湯底", 50, false, false, false, false, false, brothChoices));
        menuItems.add(new MenuItem(610, "麻辣燙/藥膳燙", "植牛肉", "必選湯底", 50, false, false, false, false, false, brothChoices));
        menuItems.add(new MenuItem(611, "麻辣燙/藥膳燙", "冬粉", "必選湯底", 20, false, false, false, false, false, brothChoices));
        menuItems.add(new MenuItem(612, "麻辣燙/藥膳燙", "王子麵", "必選湯底", 20, false, false, false, false, false, brothChoices));

        // 7. 小點&湯
        menuItems.add(new MenuItem(701, "小點&湯", "溏心蛋", "蛋素", 40, false, false, false, false, false, new String[]{"蛋素"}));
        menuItems.add(new MenuItem(702, "小點&湯", "植物蛋", "純素", 40, false, false, false, false, false, new String[]{"純素"}));
        menuItems.add(new MenuItem(703, "小點&湯", "玉米濃湯", "奶素", 60, false, false, false, false, false, new String[]{"奶素", "加胡椒", "不加胡椒"}));
        menuItems.add(new MenuItem(704, "小點&湯", "南瓜濃湯", "奶素", 60, false, false, false, false, false, new String[]{"奶素"}));
        menuItems.add(new MenuItem(705, "小點&湯", "蕃茄濃湯", "純素", 60, false, false, false, false, false, new String[]{"純素"}));

        // 8. 冷壓萃茶 (僅供冷飲)
        menuItems.add(new MenuItem(801, "冷壓萃茶", "毛尖綠茶", "僅供冷飲", 30, true, false, false, true, false, null));
        menuItems.add(new MenuItem(802, "冷壓萃茶", "甘露青茶", "僅供冷飲", 30, true, false, false, true, false, null));
        menuItems.add(new MenuItem(803, "冷壓萃茶", "麥香紅玉", "僅供冷飲", 30, true, false, false, true, false, null));
        menuItems.add(new MenuItem(804, "冷壓萃茶", "👍凍頂烏龍", "僅供冷飲", 30, true, false, false, true, false, null));
        menuItems.add(new MenuItem(805, "冷壓萃茶", "英式伯爵", "僅供冷飲", 30, true, false, false, true, false, null));

        // 9. 纖果茶
        menuItems.add(new MenuItem(901, "纖果茶", "▲手熬冬瓜", "▲甜度固定", 30, true, false, true, false, false, null));
        menuItems.add(new MenuItem(902, "纖果茶", "▲龍眼蜂蜜", "▲甜度固定", 30, true, false, true, false, false, null));
        menuItems.add(new MenuItem(903, "纖果茶", "鮮榨檸汁", "可調甜度", 40, true, false, false, false, false, null));
        menuItems.add(new MenuItem(904, "纖果茶", "▲蜂蜜葡萄柚", "▲甜度固定", 50, true, false, true, false, false, null));
        menuItems.add(new MenuItem(905, "纖果茶", "▲檸檬冬瓜", "▲甜度固定(冬瓜檸檬)", 50, true, false, true, false, false, null));
        menuItems.add(new MenuItem(906, "纖果茶", "金桔檸檬", "可調甜度", 50, true, false, false, false, false, null));

        // 10. 現萃鮮茶
        menuItems.add(new MenuItem(1001, "現萃鮮茶", "貴妃荔枝紅", "現泡可熱飲", 45, true, false, false, false, false, null));
        menuItems.add(new MenuItem(1002, "現萃鮮茶", "玫瑰鐵紅", "現泡可熱飲", 45, true, false, false, false, false, null));
        menuItems.add(new MenuItem(1003, "現萃鮮茶", "👍璞玉金萱", "現泡可熱飲", 45, true, false, false, false, false, null));
        menuItems.add(new MenuItem(1004, "現萃鮮茶", "普洱茶", "現泡可熱飲", 45, true, false, false, false, false, null));

        // 11. 人氣搭配
        menuItems.add(new MenuItem(1101, "人氣搭配", "▲冬瓜青茶", "▲甜度固定", 40, true, false, true, false, false, null));
        menuItems.add(new MenuItem(1102, "人氣搭配", "梅子鮮綠茶", "可調甜度", 40, true, false, false, false, false, null));
        menuItems.add(new MenuItem(1103, "人氣搭配", "檸檬鮮綠茶", "可調甜度", 45, true, false, false, false, false, null));
        menuItems.add(new MenuItem(1104, "人氣搭配", "金桔鮮綠茶", "可調甜度", 45, true, false, false, false, false, null));
        menuItems.add(new MenuItem(1105, "人氣搭配", "葡萄柚綠茶", "可調甜度", 45, true, false, false, false, false, null));
        menuItems.add(new MenuItem(1106, "人氣搭配", "▲蜜香烏龍", "▲甜度固定", 45, true, false, true, false, false, null));
        menuItems.add(new MenuItem(1107, "人氣搭配", "▲蜜香青茶", "▲甜度固定", 45, true, false, true, false, false, null));
        menuItems.add(new MenuItem(1108, "人氣搭配", "▲蜜香綠茶", "▲甜度固定", 45, true, false, true, false, false, null));

        // 12. 獨家厚奶
        menuItems.add(new MenuItem(1201, "獨家厚奶", "紅玉厚奶", "可+$10升級鮮奶", 50, true, false, false, false, true, null));
        menuItems.add(new MenuItem(1202, "獨家厚奶", "毛綠厚奶", "可+$10升級鮮奶", 50, true, false, false, false, true, null));
        menuItems.add(new MenuItem(1203, "獨家厚奶", "甘青厚奶", "可+$10升級鮮奶", 50, true, false, false, false, true, null));
        menuItems.add(new MenuItem(1204, "獨家厚奶", "烏龍厚奶", "可+$10升級鮮奶", 50, true, false, false, false, true, null));
        menuItems.add(new MenuItem(1205, "獨家厚奶", "👍伯爵厚奶", "可+$10升級鮮奶", 50, true, false, false, false, true, null));
        menuItems.add(new MenuItem(1206, "獨家厚奶", "▲黑糖濃奶", "▲甜度固定｜可+$10鮮奶", 50, true, false, true, false, true, null));
        menuItems.add(new MenuItem(1207, "獨家厚奶", "▲冬瓜濃奶", "▲甜度固定｜可+$10鮮奶", 50, true, false, true, false, true, null));
        menuItems.add(new MenuItem(1208, "獨家厚奶", "▲蜂蜜濃奶", "▲甜度固定｜可+$10鮮奶", 50, true, false, true, false, true, null));

        rebuildMealDrinkPool();
    }

    // ==================== ROOT UI CONSTRUCTION ====================
    private void buildRootUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F172A"));

        // 1. Top Navigation Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Color.parseColor("#062419"));
        topBar.setPadding(dp(12), dp(6), dp(12), dp(6));

        tvBrand = new TextView(this);
        tvBrand.setText("🏪 " + PosPrintEngine.getEffectiveShortName() + " POS");
        tvBrand.setTextColor(Color.parseColor("#A7F3D0"));
        tvBrand.setTextSize(17);
        tvBrand.setTypeface(null, Typeface.BOLD);
        topBar.addView(tvBrand);

        tvTopCallNum = new TextView(this);
        tvTopCallNum.setText("取餐號 #001");
        tvTopCallNum.setTextColor(Color.BLACK);
        tvTopCallNum.setTextSize(14);
        tvTopCallNum.setTypeface(null, Typeface.BOLD);
        tvTopCallNum.setBackground(roundBg(Color.parseColor("#F59E0B"), 0, 0, 6));
        tvTopCallNum.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams lpCall = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpCall.leftMargin = dp(10);
        topBar.addView(tvTopCallNum, lpCall);

        tvPrinterStatusBadge = new TextView(this);
        tvPrinterStatusBadge.setText("⏳雙機偵測中");
        tvPrinterStatusBadge.setTextColor(Color.WHITE);
        tvPrinterStatusBadge.setTextSize(11);
        tvPrinterStatusBadge.setBackground(roundBg(Color.parseColor("#1E293B"), Color.parseColor("#475569"), 1, 6));
        tvPrinterStatusBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
        tvPrinterStatusBadge.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPrinterDiscoveryDialog(); }
        });
        LinearLayout.LayoutParams lpPrn = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpPrn.leftMargin = dp(6);
        topBar.addView(tvPrinterStatusBadge, lpPrn);

        tvStaffBadge = new TextView(this);
        tvStaffBadge.setTextColor(Color.parseColor("#E0F2FE"));
        tvStaffBadge.setTextSize(11);
        tvStaffBadge.setTypeface(null, Typeface.BOLD);
        tvStaffBadge.setBackground(roundBg(Color.parseColor("#0369A1"), Color.parseColor("#38BDF8"), 1, 6));
        tvStaffBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
        tvStaffBadge.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSwitchStaffDialog(); }
        });
        LinearLayout.LayoutParams lpStaff = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpStaff.leftMargin = dp(6);
        topBar.addView(tvStaffBadge, lpStaff);

        View spacer = new View(this);
        topBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        btnTabPos = makeTopTabBtn("🍔 點餐結帳", true, new View.OnClickListener() {
            @Override public void onClick(View v) { switchMainTab(0); }
        });
        btnTabQrOrders = makeTopTabBtn("📱 掃碼點餐/桌牌", false, new View.OnClickListener() {
            @Override public void onClick(View v) { showQrOrdersAndStandeeDialog(); }
        });
        btnTabOrders = makeTopTabBtn("📋 當日訂單 (0)", false, new View.OnClickListener() {
            @Override public void onClick(View v) { switchMainTab(1); }
        });
        btnTabClosing = makeTopTabBtn("📊 每日關帳", false, new View.OnClickListener() {
            @Override public void onClick(View v) { switchMainTab(2); }
        });
        btnTabSettings = makeTopTabBtn("⚙️ 雙機與設定", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                authorizeAction("進入雙機與系統設定", true, new Runnable() {
                    @Override public void run() { switchMainTab(3); }
                });
            }
        });

        topBar.addView(btnTabPos);
        topBar.addView(btnTabQrOrders);
        topBar.addView(btnTabOrders);
        topBar.addView(btnTabClosing);
        topBar.addView(btnTabSettings);

        root.addView(topBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        // 2. Main Content Views
        viewPos = buildPosTab();
        viewOrders = new LinearLayout(this);
        viewOrders.setOrientation(LinearLayout.VERTICAL);
        viewOrders.setVisibility(View.GONE);

        viewClosing = new LinearLayout(this);
        viewClosing.setOrientation(LinearLayout.VERTICAL);
        viewClosing.setVisibility(View.GONE);

        viewSettings = new LinearLayout(this);
        viewSettings.setOrientation(LinearLayout.VERTICAL);
        viewSettings.setVisibility(View.GONE);

        LinearLayout.LayoutParams lpFill = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(viewPos, lpFill);
        root.addView(viewOrders, lpFill);
        root.addView(viewClosing, lpFill);
        root.addView(viewSettings, lpFill);

        setContentView(root);
    }

    private void updateBrandAndStaffHeader() {
        if (tvBrand != null) {
            tvBrand.setText("🏪 " + PosPrintEngine.getEffectiveShortName() + " POS");
        }
        if (tvStaffBadge != null) {
            if (staffModeEnabled) {
                tvStaffBadge.setVisibility(View.VISIBLE);
                tvStaffBadge.setText("👤 " + currentStaffName + " (" + currentStaffRole + ") ▾");
            } else {
                tvStaffBadge.setVisibility(View.GONE);
            }
        }
    }

    private Button makeTopTabBtn(String text, boolean active, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTypeface(null, Typeface.BOLD);
        b.setPadding(dp(12), dp(4), dp(12), dp(4));
        styleTopTabBtn(b, active);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private void styleTopTabBtn(Button b, boolean active) {
        if (active) {
            b.setTextColor(Color.parseColor("#052E16"));
            b.setBackground(roundBg(Color.parseColor("#10B981"), Color.parseColor("#34D399"), 1, 8));
        } else {
            b.setTextColor(Color.parseColor("#CBD5E1"));
            b.setBackground(roundBg(Color.parseColor("#1E293B"), Color.parseColor("#475569"), 1, 8));
        }
    }

    private void switchMainTab(int idx) {
        viewPos.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        viewOrders.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        viewClosing.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        viewSettings.setVisibility(idx == 3 ? View.VISIBLE : View.GONE);

        styleTopTabBtn(btnTabPos, idx == 0);
        styleTopTabBtn(btnTabOrders, idx == 1);
        styleTopTabBtn(btnTabClosing, idx == 2);
        styleTopTabBtn(btnTabSettings, idx == 3);

        if (idx == 1) rebuildOrdersTab();
        if (idx == 2) rebuildClosingTab();
        if (idx == 3) rebuildSettingsTab();
    }

    // ==================== TAB 0: POS ORDERING & CHECKOUT ====================
    private LinearLayout buildPosTab() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);

        // LEFT PANE (60% width)
        LinearLayout leftPane = new LinearLayout(this);
        leftPane.setOrientation(LinearLayout.VERTICAL);
        leftPane.setPadding(dp(8), dp(8), dp(8), dp(8));

        // Order Type Bar
        LinearLayout typeBar = new LinearLayout(this);
        typeBar.setOrientation(LinearLayout.HORIZONTAL);
        typeBar.setGravity(Gravity.CENTER_VERTICAL);

        btnTypeDineIn = makeOrderTypeBtn("🏠 內用", "內用");
        btnTypeTakeout = makeOrderTypeBtn("🛍️ 外帶", "外帶");
        btnTypeDelivery = makeOrderTypeBtn("🛵 外送", "外送");
        btnTypeUber = makeOrderTypeBtn("🛵 Uber", "UberEats");
        btnTypePanda = makeOrderTypeBtn("🐼 熊貓", "Foodpanda");

        btnSelectTable = new Button(this);
        btnSelectTable.setText("🗺️ " + currentTable + " ▾");
        btnSelectTable.setAllCaps(false);
        btnSelectTable.setTextSize(12);
        btnSelectTable.setTypeface(null, Typeface.BOLD);
        btnSelectTable.setTextColor(Color.parseColor("#FCD34D"));
        btnSelectTable.setPadding(dp(4), dp(2), dp(4), dp(2));
        btnSelectTable.setBackground(roundBg(Color.parseColor("#0F172A"), Color.parseColor("#38BDF8"), 2, 7));
        btnSelectTable.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showVisualTableMapDialog();
            }
        });

        LinearLayout.LayoutParams lpTypeBtn = new LinearLayout.LayoutParams(0, dp(38), 1f);
        lpTypeBtn.rightMargin = dp(4);
        typeBar.addView(btnTypeDineIn, lpTypeBtn);

        LinearLayout.LayoutParams lpTableBtn = new LinearLayout.LayoutParams(dp(92), dp(38));
        lpTableBtn.rightMargin = dp(4);
        typeBar.addView(btnSelectTable, lpTableBtn);

        typeBar.addView(btnTypeTakeout, lpTypeBtn);
        typeBar.addView(btnTypeDelivery, lpTypeBtn);
        typeBar.addView(btnTypeUber, lpTypeBtn);
        LinearLayout.LayoutParams lpLastType = new LinearLayout.LayoutParams(0, dp(38), 1f);
        typeBar.addView(btnTypePanda, lpLastType);

        leftPane.addView(typeBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Category Bar (Horizontal Scroll)
        HorizontalScrollView hsvCats = new HorizontalScrollView(this);
        hsvCats.setHorizontalScrollBarEnabled(false);
        llCategoryBar = new LinearLayout(this);
        llCategoryBar.setOrientation(LinearLayout.HORIZONTAL);
        llCategoryBar.setPadding(0, dp(6), 0, dp(6));
        hsvCats.addView(llCategoryBar);
        leftPane.addView(hsvCats, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Broth Quick Selector Bar (Shown when "麻辣燙/藥膳燙" is active)
        llBrothQuickBar = new LinearLayout(this);
        llBrothQuickBar.setOrientation(LinearLayout.HORIZONTAL);
        llBrothQuickBar.setGravity(Gravity.CENTER_VERTICAL);
        llBrothQuickBar.setPadding(dp(8), dp(4), dp(8), dp(4));
        llBrothQuickBar.setBackground(roundBg(Color.parseColor("#1E293B"), Color.parseColor("#F59E0B"), 1, 8));
        llBrothQuickBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams lpBrothBar = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpBrothBar.bottomMargin = dp(6);
        leftPane.addView(llBrothQuickBar, lpBrothBar);

        // Menu Grid (ScrollView + GridLayout 3 columns)
        ScrollView svMenu = new ScrollView(this);
        glMenuGrid = new GridLayout(this);
        glMenuGrid.setColumnCount(3);
        svMenu.addView(glMenuGrid);
        leftPane.addView(svMenu, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // RIGHT PANE (40% width - Cart & Checkout)
        LinearLayout rightPane = new LinearLayout(this);
        rightPane.setOrientation(LinearLayout.VERTICAL);
        rightPane.setBackgroundColor(Color.parseColor("#1E293B"));
        rightPane.setPadding(dp(8), dp(8), dp(8), dp(8));

        // Cart Header
        LinearLayout cartHeader = new LinearLayout(this);
        cartHeader.setOrientation(LinearLayout.HORIZONTAL);
        cartHeader.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout cartTitles = new LinearLayout(this);
        cartTitles.setOrientation(LinearLayout.VERTICAL);
        tvCartHeaderType = new TextView(this);
        tvCartHeaderType.setText("🏠 內用 (1桌)");
        tvCartHeaderType.setTextColor(Color.WHITE);
        tvCartHeaderType.setTextSize(15);
        tvCartHeaderType.setTypeface(null, Typeface.BOLD);

        tvCartRuleHint = new TextView(this);
        tvCartRuleHint.setText("預設：飲料印貼紙、餐點不印");
        tvCartRuleHint.setTextColor(Color.parseColor("#94A3B8"));
        tvCartRuleHint.setTextSize(11);

        cartTitles.addView(tvCartHeaderType);
        cartTitles.addView(tvCartRuleHint);
        cartHeader.addView(cartTitles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnClearCart = new Button(this);
        btnClearCart.setText("清空");
        btnClearCart.setAllCaps(false);
        btnClearCart.setTextSize(12);
        btnClearCart.setTextColor(Color.WHITE);
        btnClearCart.setBackground(roundBg(Color.parseColor("#475569"), 0, 0, 6));
        btnClearCart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cart.clear();
                orderDiscount = 0;
                refreshCartUI();
            }
        });
        cartHeader.addView(btnClearCart, new LinearLayout.LayoutParams(dp(64), dp(34)));

        rightPane.addView(cartHeader, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Cart Items ScrollView
        ScrollView svCart = new ScrollView(this);
        llCartList = new LinearLayout(this);
        llCartList.setOrientation(LinearLayout.VERTICAL);
        llCartList.setPadding(0, dp(6), 0, dp(6));
        svCart.addView(llCartList);
        rightPane.addView(svCart, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Checkout Footer Box
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setBackground(roundBg(Color.parseColor("#0F172A"), Color.parseColor("#334155"), 1, 8));
        footer.setPadding(dp(8), dp(6), dp(8), dp(6));

        // Print Toggles Row
        LinearLayout printRow = new LinearLayout(this);
        printRow.setOrientation(LinearLayout.HORIZONTAL);
        cbGlobalReceipt = new CheckBox(this);
        cbGlobalReceipt.setText("🧾 印收據(TP805L)");
        cbGlobalReceipt.setTextColor(Color.WHITE);
        cbGlobalReceipt.setTextSize(12);
        cbGlobalReceipt.setChecked(true);
        cbGlobalReceipt.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                globalPrintReceipt = isChecked;
            }
        });

        cbGlobalLabels = new CheckBox(this);
        cbGlobalLabels.setText("🏷️ 印貼紙(0張)");
        cbGlobalLabels.setTextColor(Color.WHITE);
        cbGlobalLabels.setTextSize(12);
        cbGlobalLabels.setChecked(true);
        cbGlobalLabels.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                globalPrintLabels = isChecked;
                refreshCartUI();
            }
        });

        printRow.addView(cbGlobalReceipt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        printRow.addView(cbGlobalLabels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        footer.addView(printRow);

        // Summary + Discount Row
        LinearLayout discRow = new LinearLayout(this);
        discRow.setOrientation(LinearLayout.HORIZONTAL);
        discRow.setGravity(Gravity.CENTER_VERTICAL);
        tvCartSummary = new TextView(this);
        tvCartSummary.setText("共 0 項｜整單折讓 -$0");
        tvCartSummary.setTextColor(Color.parseColor("#CBD5E1"));
        tvCartSummary.setTextSize(12);
        discRow.addView(tvCartSummary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnDiscount = new Button(this);
        btnDiscount.setText("✂️ 整單折扣/招待");
        btnDiscount.setAllCaps(false);
        btnDiscount.setTextSize(11);
        btnDiscount.setTextColor(Color.WHITE);
        btnDiscount.setBackground(roundBg(Color.parseColor("#334155"), Color.parseColor("#475569"), 1, 6));
        btnDiscount.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showOrderDiscountDialog(); }
        });
        discRow.addView(btnDiscount, new LinearLayout.LayoutParams(dp(108), dp(32)));
        footer.addView(discRow);

        // Total & Pay Button Row
        LinearLayout payRow = new LinearLayout(this);
        payRow.setOrientation(LinearLayout.HORIZONTAL);
        payRow.setGravity(Gravity.CENTER_VERTICAL);
        payRow.setPadding(0, dp(4), 0, 0);

        tvCartGrandTotal = new TextView(this);
        tvCartGrandTotal.setText("應收 $0");
        tvCartGrandTotal.setTextColor(Color.parseColor("#FCD34D"));
        tvCartGrandTotal.setTextSize(20);
        tvCartGrandTotal.setTypeface(null, Typeface.BOLD);
        payRow.addView(tvCartGrandTotal, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnCheckout = new Button(this);
        btnCheckout.setText("💰 結帳收款 & 出單");
        btnCheckout.setAllCaps(false);
        btnCheckout.setTextSize(15);
        btnCheckout.setTypeface(null, Typeface.BOLD);
        btnCheckout.setTextColor(Color.parseColor("#052E16"));
        btnCheckout.setBackground(roundBg(Color.parseColor("#10B981"), Color.parseColor("#34D399"), 1, 8));
        btnCheckout.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCheckoutDialog(); }
        });
        payRow.addView(btnCheckout, new LinearLayout.LayoutParams(dp(175), dp(44)));

        footer.addView(payRow);
        rightPane.addView(footer, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        container.addView(leftPane, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.60f));
        container.addView(rightPane, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.40f));
        return container;
    }

    private Button makeOrderTypeBtn(String text, final String typeVal) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTypeface(null, Typeface.BOLD);
        b.setPadding(dp(4), dp(2), dp(4), dp(2));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                currentOrderType = typeVal;
                for (CartItem ci : cart) {
                    if (!ci.manualLabelOverride) {
                        ci.printLabel = shouldPrintLabelByDefault(ci.menu.isDrink, currentOrderType);
                    }
                }
                refreshOrderTypeButtons();
                refreshCartUI();
            }
        });
        return b;
    }

    private void refreshAllPosViews() {
        refreshOrderTypeButtons();
        refreshCategoryBar();
        refreshBrothQuickBar();
        refreshMenuGrid();
        refreshCartUI();
    }

    private void refreshOrderTypeButtons() {
        if (btnSelectTable != null) {
            btnSelectTable.setVisibility("內用".equals(currentOrderType) ? View.VISIBLE : View.GONE);
            btnSelectTable.setText("🗺️ " + currentTable + " ▾");
        }
        styleTypeBtn(btnTypeDineIn, "內用".equals(currentOrderType), "#38BDF8");
        styleTypeBtn(btnTypeTakeout, "外帶".equals(currentOrderType), "#10B981");
        styleTypeBtn(btnTypeDelivery, "外送".equals(currentOrderType), "#F59E0B");
        styleTypeBtn(btnTypeUber, "UberEats".equals(currentOrderType), "#22C55E");
        styleTypeBtn(btnTypePanda, "Foodpanda".equals(currentOrderType), "#EC4899");
    }

    private void styleTypeBtn(Button b, boolean active, String hexColor) {
        if (active) {
            b.setTextColor(Color.WHITE);
            b.setBackground(roundBg(Color.parseColor(hexColor), Color.WHITE, 1, 7));
        } else {
            b.setTextColor(Color.parseColor("#CBD5E1"));
            b.setBackground(roundBg(Color.parseColor("#1E293B"), Color.parseColor("#475569"), 1, 7));
        }
    }

    private void refreshCategoryBar() {
        llCategoryBar.removeAllViews();
        List<String> cats = new ArrayList<>();
        for (MenuItem m : menuItems) {
            if (!cats.contains(m.cat)) cats.add(m.cat);
        }
        if (!cats.isEmpty() && !cats.contains(currentCategory)) {
            currentCategory = cats.get(0);
        }
        for (final String c : cats) {
            Button b = new Button(this);
            b.setText(c);
            b.setAllCaps(false);
            b.setTextSize(13);
            b.setTypeface(null, Typeface.BOLD);
            b.setPadding(dp(12), dp(4), dp(12), dp(4));
            boolean active = c.equals(currentCategory);
            if (active) {
                b.setTextColor(Color.parseColor("#064E3B"));
                b.setBackground(roundBg(Color.parseColor("#A7F3D0"), Color.parseColor("#10B981"), 1, 7));
            } else {
                b.setTextColor(Color.parseColor("#CBD5E1"));
                b.setBackground(roundBg(Color.parseColor("#1E293B"), Color.parseColor("#475569"), 1, 7));
            }
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    currentCategory = c;
                    refreshCategoryBar();
                    refreshBrothQuickBar();
                    refreshMenuGrid();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            lp.rightMargin = dp(6);
            llCategoryBar.addView(b, lp);
        }
    }

    private void refreshBrothQuickBar() {
        if (!"麻辣燙/藥膳燙".equals(currentCategory)) {
            llBrothQuickBar.setVisibility(View.GONE);
            return;
        }
        llBrothQuickBar.setVisibility(View.VISIBLE);
        llBrothQuickBar.removeAllViews();

        TextView tvLabel = new TextView(this);
        tvLabel.setText("🍲 點選前先選湯頭：");
        tvLabel.setTextColor(Color.parseColor("#FCD34D"));
        tvLabel.setTextSize(13);
        tvLabel.setTypeface(null, Typeface.BOLD);
        llBrothQuickBar.addView(tvLabel);

        final String[] broths = {"🌶️麻辣燙", "🌿藥膳燙"};
        for (final String br : broths) {
            Button b = new Button(this);
            boolean active = br.equals(defaultBroth);
            b.setText(active ? "✔ " + br : br);
            b.setAllCaps(false);
            b.setTextSize(13);
            b.setTypeface(null, Typeface.BOLD);
            b.setTextColor(active ? Color.parseColor("#052E16") : Color.WHITE);
            b.setBackground(roundBg(
                active ? Color.parseColor("#10B981") : Color.parseColor("#334155"),
                Color.parseColor("#6EE7B7"), active ? 2 : 0, 6));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    defaultBroth = br;
                    refreshBrothQuickBar();
                    refreshMenuGrid();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(115), dp(34));
            lp.leftMargin = dp(6);
            llBrothQuickBar.addView(b, lp);
        }

        Button btnBatchBroth = new Button(this);
        btnBatchBroth.setText("🔄 購物車燙類全改" + defaultBroth);
        btnBatchBroth.setAllCaps(false);
        btnBatchBroth.setTextSize(11);
        btnBatchBroth.setTextColor(Color.BLACK);
        btnBatchBroth.setBackground(roundBg(Color.parseColor("#F59E0B"), 0, 0, 6));
        btnBatchBroth.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int changed = 0;
                for (CartItem ci : cart) {
                    if ("麻辣燙/藥膳燙".equals(ci.menu.cat)) {
                        ci.selectedChoice = defaultBroth;
                        changed++;
                    }
                }
                mergeIdenticalCartItems();
                refreshCartUI();
                Toast.makeText(MainActivity.this, "已將 " + changed + " 項燙類改為 " + defaultBroth, Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams lpBatch = new LinearLayout.LayoutParams(
            0, dp(34), 1f);
        lpBatch.leftMargin = dp(8);
        llBrothQuickBar.addView(btnBatchBroth, lpBatch);
    }

    private void refreshMenuGrid() {
        glMenuGrid.removeAllViews();
        if (menuItems.isEmpty()) {
            TextView tvEmptyMenu = new TextView(this);
            tvEmptyMenu.setText("📋 目前尚無菜單品項。\n請點右上角「⚙️ 雙機與設定」新增品項、匯入示範菜單，或從雲端/本機快照回復資料。");
            tvEmptyMenu.setTextColor(Color.parseColor("#CBD5E1"));
            tvEmptyMenu.setTextSize(14);
            tvEmptyMenu.setPadding(dp(16), dp(24), dp(16), dp(24));
            glMenuGrid.addView(tvEmptyMenu);
            return;
        }
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int leftW = (int) (screenW * 0.60f) - dp(24);
        int cardW = (leftW / 3) - dp(6);

        for (final MenuItem item : menuItems) {
            if (!item.cat.equals(currentCategory)) continue;

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(roundBg(Color.parseColor("#334155"), Color.parseColor("#475569"), 1, 9));
            card.setPadding(dp(8), dp(8), dp(8), dp(8));

            TextView tvTitle = new TextView(this);
            tvTitle.setText(item.name);
            tvTitle.setTextColor(Color.WHITE);
            tvTitle.setTextSize(14);
            tvTitle.setTypeface(null, Typeface.BOLD);
            card.addView(tvTitle);

            TextView tvSub = new TextView(this);
            if ("麻辣燙/藥膳燙".equals(item.cat)) {
                tvSub.setText("目前預設：" + defaultBroth);
                tvSub.setTextColor(Color.parseColor("#FCD34D"));
            } else {
                tvSub.setText(item.sub);
                tvSub.setTextColor(Color.parseColor("#CBD5E1"));
            }
            tvSub.setTextSize(10);
            card.addView(tvSub);

            LinearLayout bottom = new LinearLayout(this);
            bottom.setOrientation(LinearLayout.HORIZONTAL);
            bottom.setGravity(Gravity.CENTER_VERTICAL);
            bottom.setPadding(0, dp(6), 0, 0);

            TextView tvTag = new TextView(this);
            tvTag.setText(item.isDrink ? "☕點擊選甜度/加料" : (item.hasFreeTea ? "🍵點擊選附茶/換購" : "🌿外帶貼紙"));
            tvTag.setTextSize(10);
            tvTag.setTextColor(item.isDrink ? Color.parseColor("#93C5FD") : Color.parseColor("#A7F3D0"));
            bottom.addView(tvTag, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvPrice = new TextView(this);
            tvPrice.setText("$" + item.price);
            tvPrice.setTextColor(Color.parseColor("#FCD34D"));
            tvPrice.setTextSize(15);
            tvPrice.setTypeface(null, Typeface.BOLD);
            bottom.addView(tvPrice);

            card.addView(bottom);

            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { addItemToCart(item); }
            });

            GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            glp.width = cardW;
            glp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            glp.setMargins(dp(3), dp(3), dp(3), dp(3));
            glMenuGrid.addView(card, glp);
        }
    }

    private void addItemToCart(MenuItem m) {
        CartItem ci = new CartItem();
        ci.menu = m;
        ci.qty = 1;
        ci.printLabel = shouldPrintLabelByDefault(m.isDrink, currentOrderType);
        if (m.isDrink) {
            ci.drinkSweet = m.fixedSweet ? "▲甜度固定" : "5分半糖";
            ci.drinkIce = "8分少冰";
        } else if ("麻辣燙/藥膳燙".equals(m.cat)) {
            ci.selectedChoice = defaultBroth;
        } else {
            if (m.choices.length > 0) ci.selectedChoice = m.choices[0];
            if (m.hasFreeTea) {
                ci.mealDrinkName = "毛尖綠茶";
                ci.mealDrinkDiff = 0;
                ci.mealDrinkSweet = "無糖";
                ci.mealDrinkIce = "3分微冰";
            }
        }

        // 若是麻辣燙/藥膳燙或一般炸物/小點，相同品項與相同湯頭自動合併數量 +1
        if (!m.isDrink && !m.hasFreeTea) {
            for (CartItem ex : cart) {
                if (ex.isSameConfigAs(ci)) {
                    ex.qty++;
                    refreshCartUI();
                    return;
                }
            }
        }

        cart.add(ci);
        refreshCartUI();

        // 點擊「主餐(附茶/換購)」或「飲品(選甜度冰塊/加濃奶鮮奶)」時，自動彈出客製化與加價視窗
        if (m.hasFreeTea || m.isDrink) {
            showItemModifierDialog(cart.size() - 1);
        }
    }

    private void mergeIdenticalCartItems() {
        for (int i = 0; i < cart.size(); i++) {
            CartItem a = cart.get(i);
            for (int j = i + 1; j < cart.size(); j++) {
                CartItem b = cart.get(j);
                if (a.isSameConfigAs(b)) {
                    a.qty += b.qty;
                    cart.remove(j);
                    j--;
                }
            }
        }
    }

    private int getTotalStickersCount() {
        if (!globalPrintLabels) return 0;
        int c = 0;
        for (CartItem ci : cart) {
            c += ci.getItemStickerCount();
        }
        return c;
    }

    private int getCartGrandTotal() {
        int s = 0;
        for (CartItem ci : cart) s += ci.getSubTotal();
        return Math.max(0, s - orderDiscount);
    }

    private void refreshCartUI() {
        String typeDisplay = "內用".equals(currentOrderType)
            ? "🏠 內用 (" + currentTable + ")"
            : ("外送".equals(currentOrderType) ? "🛵 外送" : "🛍️ " + currentOrderType);
        tvCartHeaderType.setText(typeDisplay);
        tvCartRuleHint.setText("內用".equals(currentOrderType)
            ? "預設：單點飲料與隨餐附飲印貼紙、內用餐盒不印"
            : "預設：外帶/外送全品項(含附餐飲品)皆印貼紙");

        llCartList.removeAllViews();
        if (cart.isEmpty()) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("👆 點選左側【" + PosPrintEngine.getEffectiveShortName() + "】菜單品項\n\n• 支援主餐隨附飲品或加價換購、甜度/冰量/加料客製\n• 點選右側可即時調整數量、單品折讓、整單折扣\n• 支援斷網離線照常點餐與印單，恢復網路自動同步");
            tvEmpty.setTextColor(Color.parseColor("#94A3B8"));
            tvEmpty.setTextSize(12);
            tvEmpty.setGravity(Gravity.CENTER);
            tvEmpty.setPadding(dp(12), dp(30), dp(12), dp(12));
            llCartList.addView(tvEmpty);
        } else {
            for (int i = 0; i < cart.size(); i++) {
                final int idx = i;
                final CartItem ci = cart.get(i);

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setBackground(roundBg(Color.parseColor("#0F172A"), Color.parseColor("#334155"), 1, 8));
                row.setPadding(dp(8), dp(6), dp(8), dp(6));
                LinearLayout.LayoutParams lpRow = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lpRow.bottomMargin = dp(6);
                row.setLayoutParams(lpRow);

                // Top line: Name + Subtotal
                LinearLayout topLine = new LinearLayout(this);
                topLine.setOrientation(LinearLayout.HORIZONTAL);
                topLine.setGravity(Gravity.CENTER_VERTICAL);

                TextView tvName = new TextView(this);
                tvName.setText((idx + 1) + ". " + ci.menu.name);
                tvName.setTextColor(Color.WHITE);
                tvName.setTextSize(14);
                tvName.setTypeface(null, Typeface.BOLD);
                tvName.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { showItemModifierDialog(idx); }
                });
                topLine.addView(tvName, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                // 若為麻辣燙/藥膳燙，提供「一鍵秒換湯頭」按鈕
                if ("麻辣燙/藥膳燙".equals(ci.menu.cat)) {
                    Button btnBrothToggle = new Button(this);
                    btnBrothToggle.setText("🍲換湯頭:" + ci.selectedChoice);
                    btnBrothToggle.setAllCaps(false);
                    btnBrothToggle.setTextSize(11);
                    btnBrothToggle.setPadding(dp(6), dp(2), dp(6), dp(2));
                    boolean isMala = "🌶️麻辣燙".equals(ci.selectedChoice);
                    btnBrothToggle.setTextColor(Color.WHITE);
                    btnBrothToggle.setBackground(roundBg(
                        isMala ? Color.parseColor("#DC2626") : Color.parseColor("#059669"), 0, 0, 6));
                    btnBrothToggle.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            ci.selectedChoice = "🌶️麻辣燙".equals(ci.selectedChoice) ? "🌿藥膳燙" : "🌶️麻辣燙";
                            refreshCartUI();
                        }
                    });
                    LinearLayout.LayoutParams lpBt = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
                    lpBt.rightMargin = dp(6);
                    topLine.addView(btnBrothToggle, lpBt);
                }

                Button btnEdit = new Button(this);
                btnEdit.setText("✏️客製/加價");
                btnEdit.setAllCaps(false);
                btnEdit.setTextSize(11);
                btnEdit.setPadding(dp(6), dp(2), dp(6), dp(2));
                btnEdit.setTextColor(Color.parseColor("#052E16"));
                btnEdit.setBackground(roundBg(Color.parseColor("#38BDF8"), 0, 0, 6));
                btnEdit.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { showItemModifierDialog(idx); }
                });
                LinearLayout.LayoutParams lpEd = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
                lpEd.rightMargin = dp(6);
                topLine.addView(btnEdit, lpEd);

                TextView tvSubTotal = new TextView(this);
                tvSubTotal.setText("$" + ci.getSubTotal());
                tvSubTotal.setTextColor(Color.parseColor("#FCD34D"));
                tvSubTotal.setTextSize(15);
                tvSubTotal.setTypeface(null, Typeface.BOLD);
                topLine.addView(tvSubTotal);
                row.addView(topLine);

                // Mod summary
                TextView tvMods = new TextView(this);
                tvMods.setText(ci.getModSummary());
                tvMods.setTextColor(Color.parseColor("#6EE7B7"));
                tvMods.setTextSize(12);
                tvMods.setPadding(0, dp(2), 0, dp(2));
                tvMods.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { showItemModifierDialog(idx); }
                });
                row.addView(tvMods);

                // Controls: Sticker Checkbox(es) + Qty - / +
                LinearLayout ctrlLine = new LinearLayout(this);
                ctrlLine.setOrientation(LinearLayout.HORIZONTAL);
                ctrlLine.setGravity(Gravity.CENTER_VERTICAL);
                ctrlLine.setPadding(0, dp(2), 0, 0);

                CheckBox cbSticker = new CheckBox(this);
                if (ci.hasAttachedDrink()) {
                    cbSticker.setText(ci.printLabel ? "🏷️餐盒(" + ci.qty + ")" : "🏷️餐盒不印");
                } else {
                    cbSticker.setText(ci.printLabel ? "🏷️ 印貼紙(" + ci.qty + "張)" : "🏷️ 不印貼紙");
                }
                cbSticker.setTextColor(ci.printLabel ? Color.parseColor("#6EE7B7") : Color.parseColor("#94A3B8"));
                cbSticker.setTextSize(11);
                cbSticker.setChecked(ci.printLabel);
                cbSticker.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        ci.printLabel = isChecked;
                        ci.manualLabelOverride = true;
                        refreshCartUI();
                    }
                });

                if (ci.hasAttachedDrink()) {
                    ctrlLine.addView(cbSticker, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    CheckBox cbDrinkSticker = new CheckBox(this);
                    cbDrinkSticker.setText(ci.printDrinkLabel ? "🥤附飲(" + ci.qty + "張)" : "🥤附飲不印");
                    cbDrinkSticker.setTextColor(ci.printDrinkLabel ? Color.parseColor("#38BDF8") : Color.parseColor("#94A3B8"));
                    cbDrinkSticker.setTextSize(11);
                    cbDrinkSticker.setChecked(ci.printDrinkLabel);
                    cbDrinkSticker.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                        @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                            ci.printDrinkLabel = isChecked;
                            ci.manualDrinkLabelOverride = true;
                            refreshCartUI();
                        }
                    });
                    ctrlLine.addView(cbDrinkSticker, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                } else {
                    ctrlLine.addView(cbSticker, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                }

                Button btnMinus = new Button(this);
                btnMinus.setText("−");
                btnMinus.setTextColor(Color.WHITE);
                btnMinus.setTextSize(15);
                btnMinus.setPadding(0, 0, 0, 0);
                btnMinus.setBackground(roundBg(Color.parseColor("#334155"), 0, 0, 6));
                btnMinus.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        ci.qty--;
                        if (ci.qty <= 0) cart.remove(idx);
                        refreshCartUI();
                    }
                });
                ctrlLine.addView(btnMinus, new LinearLayout.LayoutParams(dp(32), dp(30)));

                TextView tvQty = new TextView(this);
                tvQty.setText(String.valueOf(ci.qty));
                tvQty.setTextColor(Color.WHITE);
                tvQty.setTextSize(15);
                tvQty.setTypeface(null, Typeface.BOLD);
                tvQty.setGravity(Gravity.CENTER);
                ctrlLine.addView(tvQty, new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT));

                Button btnPlus = new Button(this);
                btnPlus.setText("＋");
                btnPlus.setTextColor(Color.WHITE);
                btnPlus.setTextSize(15);
                btnPlus.setPadding(0, 0, 0, 0);
                btnPlus.setBackground(roundBg(Color.parseColor("#334155"), 0, 0, 6));
                btnPlus.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        ci.qty++;
                        refreshCartUI();
                    }
                });
                ctrlLine.addView(btnPlus, new LinearLayout.LayoutParams(dp(32), dp(30)));

                row.addView(ctrlLine);
                llCartList.addView(row);
            }
        }

        int totalQty = 0;
        for (CartItem ci : cart) totalQty += ci.qty;
        int stickers = getTotalStickersCount();
        cbGlobalLabels.setText("🏷️ 印貼紙(" + stickers + "張)");
        tvCartSummary.setText("共 " + totalQty + " 項｜整單折讓 -$" + orderDiscount);
        tvCartGrandTotal.setText("應收 $" + getCartGrandTotal());
    }

    // ==================== MODIFIER & UPGRADE DIALOG (全展開網格 + 隨餐換購全飲品 + 即時試算) ====================
    private void showItemModifierDialog(final int cartIdx) {
        if (cartIdx < 0 || cartIdx >= cart.size()) return;
        final CartItem ci = cart.get(cartIdx);
        final MenuItem m = ci.menu;

        final String[] selChoice = {
            (ci.selectedChoice != null && !ci.selectedChoice.isEmpty())
                ? ci.selectedChoice
                : (m.choices.length > 0 ? m.choices[0] : "")
        };
        final String[] selDrinkSweet = { ci.drinkSweet };
        final String[] selDrinkIce = { ci.drinkIce };

        final String[] selMealDrinkName = { ci.mealDrinkName };
        final int[] selMealDrinkDiff = { ci.mealDrinkDiff };
        final String[] selMealDrinkSweet = { ci.mealDrinkSweet };
        final String[] selMealDrinkIce = { ci.mealDrinkIce };
        final String[] activeMealDrinkGroup = { findDrinkGroup(ci.mealDrinkName) };

        final List<AddonOption> tempAddons = new ArrayList<>(ci.addons);

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        final int dialogW = (int) (screenW * 0.92f);
        final int dialogH = (int) (screenH * 0.90f);

        // 使用原生 Dialog (NO_TITLE) 取代 AlertDialog，消除系統內建邊框吃掉底部與右側空間的問題
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Dialog 外層容器（固定頂部標題 + 中間 weight=1 可捲動區 + 固定底部超大確認按鈕）
        LinearLayout dialogRoot = new LinearLayout(this);
        dialogRoot.setOrientation(LinearLayout.VERTICAL);
        dialogRoot.setBackground(roundBg(Color.parseColor("#0F172A"), Color.parseColor("#334155"), 2, 10));

        // 1. 固定頂部列（同時放置標題與右上角快速確認/取消按鈕）
        LinearLayout headerBar = new LinearLayout(this);
        headerBar.setOrientation(LinearLayout.HORIZONTAL);
        headerBar.setGravity(Gravity.CENTER_VERTICAL);
        headerBar.setBackgroundColor(Color.parseColor("#062419"));
        headerBar.setPadding(dp(12), dp(6), dp(12), dp(6));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("🌿 客製與加價：" + m.name + " (原價 $" + m.price + ")");
        tvTitle.setTextColor(Color.parseColor("#A7F3D0"));
        tvTitle.setTextSize(15);
        tvTitle.setTypeface(null, Typeface.BOLD);
        headerBar.addView(tvTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnTopConfirm = new Button(this);
        btnTopConfirm.setText("✅ 確認套用");
        btnTopConfirm.setAllCaps(false);
        btnTopConfirm.setTextSize(13);
        btnTopConfirm.setTypeface(null, Typeface.BOLD);
        btnTopConfirm.setTextColor(Color.parseColor("#052E16"));
        btnTopConfirm.setBackground(roundBg(Color.parseColor("#10B981"), Color.parseColor("#34D399"), 1, 6));
        headerBar.addView(btnTopConfirm, new LinearLayout.LayoutParams(dp(115), dp(34)));

        dialogRoot.addView(headerBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        // 2. 中間捲動內容區（使用 layout_weight = 1f 自動撐滿頂部與底部之間，並加足底部留白確保滑到底完整顯示）
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(8), dp(14), dp(28));
        sv.addView(body, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialogRoot.addView(sv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 3. 固定底部確認列（含即時價格預覽）
        LinearLayout footerBar = new LinearLayout(this);
        footerBar.setOrientation(LinearLayout.HORIZONTAL);
        footerBar.setGravity(Gravity.CENTER_VERTICAL);
        footerBar.setBackgroundColor(Color.parseColor("#1E293B"));
        footerBar.setPadding(dp(12), dp(6), dp(12), dp(6));

        Button btnCancel = new Button(this);
        btnCancel.setText("❌ 取消返回");
        btnCancel.setAllCaps(false);
        btnCancel.setTextSize(13);
        btnCancel.setTextColor(Color.WHITE);
        btnCancel.setBackground(roundBg(Color.parseColor("#475569"), 0, 0, 8));

        final Button btnConfirm = new Button(this);
        btnConfirm.setAllCaps(false);
        btnConfirm.setTextSize(14);
        btnConfirm.setTypeface(null, Typeface.BOLD);
        btnConfirm.setTextColor(Color.parseColor("#052E16"));
        btnConfirm.setBackground(roundBg(Color.parseColor("#10B981"), Color.parseColor("#34D399"), 1, 8));

        final Runnable updatePricePreview = new Runnable() {
            @Override public void run() {
                int addonSum = 0;
                for (AddonOption a : tempAddons) addonSum += a.price;
                int unit = m.price + (m.hasFreeTea ? selMealDrinkDiff[0] : 0) + addonSum;
                btnConfirm.setText("✅ 確認套用 (單價 $" + unit + " ｜ 小計 $" + (unit * ci.qty) + ")");
            }
        };
        updatePricePreview.run();

        // --- A. 素別 / 麻辣燙湯頭 / 炸物調味 ---
        if (!m.isDrink && m.choices.length > 0) {
            addSectionHeader(body, "麻辣燙/藥膳燙".equals(m.cat)
                ? "🍲 1. 選擇湯頭（點擊直接切換 🌶️麻辣燙 / 🌿藥膳燙）"
                : "🌿 1. 素別 / 客製選項");
            addGridSingleSelect(body, m.choices, selChoice, 3, 0, updatePricePreview);
        }

        // --- B. 主餐升級加價 (義大利麵/燉飯/比薩/簡餐) ---
        if ("義大利麵&飯食".equals(m.cat) || "個人比薩7.5吋".equals(m.cat)) {
            addSectionHeader(body, "🧀 主餐升級加價（可複選：焗烤+$50 / 松露+$50 / 全素起司+$80）");
            addGridMultiSelectAddons(body, ADDONS_PASTA_PIZZA, tempAddons, 3, 0, updatePricePreview);
        } else if ("簡餐(五穀飯)".equals(m.cat)) {
            addSectionHeader(body, "🍱 簡餐單點加菜（可複選：爌肉/植蛋/叉燒/棒腿/滷排/和牛排）");
            addGridMultiSelectAddons(body, ADDONS_SET_MEAL, tempAddons, 3, 0, updatePricePreview);
        }

        // --- C. 主餐隨餐附贈冷壓萃茶 & 加價換購全店飲品 (含冬瓜檸檬等果茶/鮮茶/厚奶) ---
        if (m.hasFreeTea) {
            addSectionHeader(body, "🍵 隨餐附飲（免費附冷壓萃茶 $30，點下方分類可補差價換購冬瓜檸檬等全店飲品）");

            final LinearLayout groupTabRow = new LinearLayout(this);
            groupTabRow.setOrientation(LinearLayout.HORIZONTAL);
            body.addView(groupTabRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            final LinearLayout drinkItemsContainer = new LinearLayout(this);
            drinkItemsContainer.setOrientation(LinearLayout.VERTICAL);
            body.addView(drinkItemsContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            final LinearLayout mealDrinkSweetContainer = new LinearLayout(this);
            mealDrinkSweetContainer.setOrientation(LinearLayout.VERTICAL);

            final String[] drinkGroups = {"冷壓萃茶", "纖果茶", "現萃鮮茶", "人氣搭配", "獨家厚奶"};
            final String[] drinkGroupLabels = {
                "免費冷壓萃茶(+$0)", "換購:纖果茶", "換購:現萃鮮茶", "換購:人氣搭配", "換購:獨家厚奶"
            };

            final Runnable rebuildMealDrinkSelector = new Runnable() {
                @Override public void run() {
                    groupTabRow.removeAllViews();
                    for (int g = 0; g < drinkGroups.length; g++) {
                        final String grp = drinkGroups[g];
                        Button bGrp = new Button(MainActivity.this);
                        bGrp.setText(drinkGroupLabels[g]);
                        bGrp.setAllCaps(false);
                        bGrp.setTextSize(11);
                        bGrp.setTypeface(null, Typeface.BOLD);
                        bGrp.setPadding(dp(2), dp(2), dp(2), dp(2));
                        boolean isAct = grp.equals(activeMealDrinkGroup[0]);
                        bGrp.setTextColor(isAct ? Color.parseColor("#052E16") : Color.WHITE);
                        bGrp.setBackground(roundBg(
                            isAct ? Color.parseColor("#38BDF8") : Color.parseColor("#334155"), 0, 0, 6));
                        bGrp.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                activeMealDrinkGroup[0] = grp;
                                run();
                            }
                        });
                        LinearLayout.LayoutParams lpG = new LinearLayout.LayoutParams(0, dp(34), 1f);
                        lpG.setMargins(dp(2), dp(2), dp(2), dp(2));
                        groupTabRow.addView(bGrp, lpG);
                    }

                    drinkItemsContainer.removeAllViews();
                    List<DrinkUpgradeOption> groupOpts = new ArrayList<>();
                    for (DrinkUpgradeOption opt : mealDrinkPool) {
                        if (opt.group.equals(activeMealDrinkGroup[0])) {
                            groupOpts.add(opt);
                        }
                    }

                    int cols = 3;
                    for (int i = 0; i < groupOpts.size(); i += cols) {
                        LinearLayout row = new LinearLayout(MainActivity.this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        for (int c = 0; c < cols; c++) {
                            int idx = i + c;
                            if (idx < groupOpts.size()) {
                                final DrinkUpgradeOption opt = groupOpts.get(idx);
                                Button bOpt = new Button(MainActivity.this);
                                String priceLabel = opt.diffPrice == 0 ? "(+$0)" : "(+$" + opt.diffPrice + ")";
                                boolean isSel = opt.name.equals(selMealDrinkName[0]);
                                bOpt.setText((isSel ? "✔ " : "") + opt.name + priceLabel);
                                bOpt.setAllCaps(false);
                                bOpt.setTextSize(12);
                                bOpt.setTypeface(null, Typeface.BOLD);
                                bOpt.setPadding(dp(4), dp(2), dp(4), dp(2));
                                bOpt.setTextColor(isSel ? Color.parseColor("#052E16") : Color.WHITE);
                                bOpt.setBackground(roundBg(
                                    isSel ? Color.parseColor("#10B981") : Color.parseColor("#334155"),
                                    Color.parseColor("#6EE7B7"), isSel ? 2 : 0, 7));
                                bOpt.setOnClickListener(new View.OnClickListener() {
                                    @Override public void onClick(View v) {
                                        selMealDrinkName[0] = opt.name;
                                        selMealDrinkDiff[0] = opt.diffPrice;
                                        if (opt.fixedSweet) {
                                            selMealDrinkSweet[0] = "▲甜度固定";
                                        } else if ("▲甜度固定".equals(selMealDrinkSweet[0])) {
                                            selMealDrinkSweet[0] = "無糖";
                                        }
                                        updatePricePreview.run();
                                        run();
                                        mealDrinkSweetContainer.removeAllViews();
                                        addGridSingleSelect(mealDrinkSweetContainer, SWEET_OPTIONS, selMealDrinkSweet, 4, 0, updatePricePreview);
                                    }
                                });
                                LinearLayout.LayoutParams lpBtn = new LinearLayout.LayoutParams(0, dp(36), 1f);
                                lpBtn.setMargins(dp(3), dp(3), dp(3), dp(3));
                                row.addView(bOpt, lpBtn);
                            } else {
                                View spacer = new View(MainActivity.this);
                                LinearLayout.LayoutParams lpSp = new LinearLayout.LayoutParams(0, dp(36), 1f);
                                lpSp.setMargins(dp(3), dp(3), dp(3), dp(3));
                                row.addView(spacer, lpSp);
                            }
                        }
                        drinkItemsContainer.addView(row, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    }
                }
            };
            rebuildMealDrinkSelector.run();

            addSectionHeader(body, "🍬 附餐飲品甜度（若選 ▲冬瓜/蜂蜜/濃奶 為固定甜度）");
            addGridSingleSelect(mealDrinkSweetContainer, SWEET_OPTIONS, selMealDrinkSweet, 4, 0, updatePricePreview);
            body.addView(mealDrinkSweetContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            addSectionHeader(body, "🧊 附餐飲品冰量 / 溫度");
            addGridSingleSelect(body, ICE_OPTIONS, selMealDrinkIce, 4, 0, updatePricePreview);

            addSectionHeader(body, "🧋 附餐飲品加料（可複選：寒天愛玉+$15 / 鳳梨椰果+$10 / 厚奶(濃奶)+$20 / 鮮奶+$30）");
            addGridMultiSelectAddons(body, ADDONS_THICK_MILK, tempAddons, 3, 0, updatePricePreview);
        }

        // --- D. 單點飲品：甜度、冰塊與加料 (含厚奶/濃奶+$20、鮮奶+$30、寒天愛玉+$15、鳳梨椰果+$10) ---
        if (m.isDrink) {
            addSectionHeader(body, m.fixedSweet ? "🍬 1. 甜度選擇（此品項預設 ▲甜度固定）" : "🍬 1. 甜度選擇");
            addGridSingleSelect(body, SWEET_OPTIONS, selDrinkSweet, 4, 0, updatePricePreview);

            addSectionHeader(body, m.coldOnly ? "🧊 2. 冰量選擇（冷壓萃茶僅供冷飲）" : "🧊 2. 冰量 / 溫度選擇");
            addGridSingleSelect(body, m.coldOnly ? COLD_ONLY_ICE_OPTIONS : ICE_OPTIONS, selDrinkIce, 4, 0, updatePricePreview);

            addSectionHeader(body, "🧋 3. 飲品加料（點擊直接加價：寒天愛玉+$15 / 鳳梨椰果+$10 / 厚奶(濃奶)+$20 / 鮮奶+$30）");
            AddonOption[] drinkAddonPool = m.isThickMilk ? ADDONS_THICK_MILK : ADDONS_DRINK_COMMON;
            addGridMultiSelectAddons(body, drinkAddonPool, tempAddons, 3, 0, updatePricePreview);
        }

        // --- E. 手寫備註 & 單品招待/折讓 ---
        addSectionHeader(body, "📝 手寫備註 & 單品折讓 / 招待");
        LinearLayout noteDiscRow = new LinearLayout(this);
        noteDiscRow.setOrientation(LinearLayout.HORIZONTAL);
        noteDiscRow.setGravity(Gravity.CENTER_VERTICAL);
        noteDiscRow.setBackground(roundBg(Color.parseColor("#1E293B"), Color.parseColor("#475569"), 1, 8));
        noteDiscRow.setPadding(dp(10), dp(6), dp(10), dp(6));

        final EditText etNote = new EditText(this);
        etNote.setHint("備註(如:醬少/先出)");
        etNote.setText(ci.note);
        etNote.setTextColor(Color.WHITE);
        etNote.setHintTextColor(Color.parseColor("#94A3B8"));
        etNote.setTextSize(13);
        noteDiscRow.addView(etNote, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));

        final EditText etDisc = new EditText(this);
        etDisc.setInputType(InputType.TYPE_CLASS_NUMBER);
        etDisc.setHint("折讓$");
        etDisc.setText(ci.itemDiscount > 0 ? String.valueOf(ci.itemDiscount) : "");
        etDisc.setTextColor(Color.parseColor("#FCD34D"));
        etDisc.setHintTextColor(Color.parseColor("#94A3B8"));
        etDisc.setTextSize(13);
        LinearLayout.LayoutParams lpDisc = new LinearLayout.LayoutParams(dp(95), ViewGroup.LayoutParams.WRAP_CONTENT);
        lpDisc.leftMargin = dp(8);
        noteDiscRow.addView(etDisc, lpDisc);

        Button btnFree = new Button(this);
        btnFree.setText("🎁 單品招待($0)");
        btnFree.setAllCaps(false);
        btnFree.setTextSize(12);
        btnFree.setTextColor(Color.WHITE);
        btnFree.setBackground(roundBg(Color.parseColor("#7C3AED"), 0, 0, 6));
        btnFree.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int addSum = 0;
                for (AddonOption a : tempAddons) addSum += a.price;
                int unit = m.price + (m.hasFreeTea ? selMealDrinkDiff[0] : 0) + addSum;
                etDisc.setText(String.valueOf(unit * ci.qty));
            }
        });
        LinearLayout.LayoutParams lpFree = new LinearLayout.LayoutParams(dp(125), dp(36));
        lpFree.leftMargin = dp(8);
        noteDiscRow.addView(btnFree, lpFree);

        body.addView(noteDiscRow, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lpCancel = new LinearLayout.LayoutParams(dp(130), dp(40));
        lpCancel.rightMargin = dp(10);
        footerBar.addView(btnCancel, lpCancel);
        footerBar.addView(btnConfirm, new LinearLayout.LayoutParams(0, dp(40), 1f));
        dialogRoot.addView(footerBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        dialog.setContentView(dialogRoot, new ViewGroup.LayoutParams(dialogW, dialogH));

        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });

        final View.OnClickListener applyListener = new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (m.isDrink) {
                    ci.drinkSweet = selDrinkSweet[0];
                    ci.drinkIce = selDrinkIce[0];
                } else {
                    ci.selectedChoice = selChoice[0];
                    if ("麻辣燙/藥膳燙".equals(m.cat)) {
                        defaultBroth = selChoice[0];
                        refreshBrothQuickBar();
                        refreshMenuGrid();
                    }
                    if (m.hasFreeTea) {
                        ci.mealDrinkName = selMealDrinkName[0];
                        ci.mealDrinkDiff = selMealDrinkDiff[0];
                        ci.mealDrinkSweet = selMealDrinkSweet[0];
                        ci.mealDrinkIce = selMealDrinkIce[0];
                    }
                }
                ci.addons.clear();
                ci.addons.addAll(tempAddons);
                ci.note = etNote.getText().toString().trim();
                try {
                    String ds = etDisc.getText().toString().trim();
                    ci.itemDiscount = ds.isEmpty() ? 0 : Math.max(0, Integer.parseInt(ds));
                } catch (Exception ignored) {
                    ci.itemDiscount = 0;
                }
                mergeIdenticalCartItems();
                refreshCartUI();
                dialog.dismiss();
            }
        };

        btnConfirm.setOnClickListener(applyListener);
        btnTopConfirm.setOnClickListener(applyListener);

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            dialog.getWindow().setLayout(dialogW, dialogH);
        }
    }

    private String findDrinkGroup(String drinkName) {
        for (DrinkUpgradeOption o : mealDrinkPool) {
            if (o.name.equals(drinkName)) return o.group;
        }
        return "冷壓萃茶";
    }

    private void addSectionHeader(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#A7F3D0"));
        tv.setTextSize(13);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(6), 0, dp(3));
        parent.addView(tv);
    }

    // 多列自動換行單選網格（使用 LinearLayout weight=1f 等分欄寬，100% 絕不裁切右邊按鈕）
    private void addGridSingleSelect(LinearLayout parent, final String[] options,
                                     final String[] holder, int cols, int unusedW,
                                     final Runnable onChange) {
        final List<Button> btns = new ArrayList<>();

        for (int i = 0; i < options.length; i += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < cols; c++) {
                int idx = i + c;
                if (idx < options.length) {
                    final String opt = options[idx];
                    final Button b = new Button(this);
                    b.setTag(opt);
                    b.setAllCaps(false);
                    b.setTextSize(12);
                    b.setTypeface(null, Typeface.BOLD);
                    b.setPadding(dp(4), dp(2), dp(4), dp(2));

                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
                    lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                    row.addView(b, lp);
                    btns.add(b);

                    b.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            holder[0] = opt;
                            styleSingleSelectButtons(btns, holder[0]);
                            if (onChange != null) onChange.run();
                        }
                    });
                } else {
                    View spacer = new View(this);
                    LinearLayout.LayoutParams lpSp = new LinearLayout.LayoutParams(0, dp(36), 1f);
                    lpSp.setMargins(dp(3), dp(3), dp(3), dp(3));
                    row.addView(spacer, lpSp);
                }
            }
            parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        styleSingleSelectButtons(btns, holder[0]);
    }

    private void styleSingleSelectButtons(List<Button> btns, String currentVal) {
        for (Button x : btns) {
            String opt = (String) x.getTag();
            boolean sel = opt.equals(currentVal);
            x.setText((sel ? "✔ " : "") + opt);
            x.setTextColor(sel ? Color.parseColor("#052E16") : Color.WHITE);
            x.setBackground(roundBg(
                sel ? Color.parseColor("#10B981") : Color.parseColor("#334155"),
                Color.parseColor("#6EE7B7"), sel ? 2 : 0, 7));
        }
    }

    // 多列自動換行複選加料網格（使用 LinearLayout weight=1f 等分欄寬，全素起司+$80 等第3欄完整顯示）
    private void addGridMultiSelectAddons(LinearLayout parent, final AddonOption[] pool,
                                          final List<AddonOption> selected, int cols,
                                          int unusedW, final Runnable onChange) {
        for (int i = 0; i < pool.length; i += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < cols; c++) {
                int idx = i + c;
                if (idx < pool.length) {
                    final AddonOption addon = pool[idx];
                    final Button b = new Button(this);
                    b.setAllCaps(false);
                    b.setTextSize(12);
                    b.setTypeface(null, Typeface.BOLD);
                    b.setPadding(dp(4), dp(2), dp(4), dp(2));

                    styleAddonButton(b, addon, isAddonSelected(selected, addon.name));

                    b.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            AddonOption found = null;
                            for (AddonOption ex : selected) {
                                if (ex.name.equals(addon.name)) {
                                    found = ex;
                                    break;
                                }
                            }
                            if (found != null) {
                                selected.remove(found);
                                styleAddonButton(b, addon, false);
                            } else {
                                selected.add(addon);
                                styleAddonButton(b, addon, true);
                            }
                            if (onChange != null) onChange.run();
                        }
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
                    lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                    row.addView(b, lp);
                } else {
                    View spacer = new View(this);
                    LinearLayout.LayoutParams lpSp = new LinearLayout.LayoutParams(0, dp(36), 1f);
                    lpSp.setMargins(dp(3), dp(3), dp(3), dp(3));
                    row.addView(spacer, lpSp);
                }
            }
            parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private boolean isAddonSelected(List<AddonOption> list, String name) {
        for (AddonOption a : list) {
            if (a.name.equals(name)) return true;
        }
        return false;
    }

    private void styleAddonButton(Button b, AddonOption addon, boolean isSel) {
        b.setText((isSel ? "✔ +" : "+") + addon.name + " ($" + addon.price + ")");
        b.setTextColor(isSel ? Color.parseColor("#052E16") : Color.WHITE);
        b.setBackground(roundBg(
            isSel ? Color.parseColor("#F59E0B") : Color.parseColor("#334155"),
            Color.parseColor("#FCD34D"), isSel ? 2 : 0, 7));
    }

    // ==================== ORDER DISCOUNT DIALOG ====================
    private void showOrderDiscountDialog() {
        if (cart.isEmpty()) {
            Toast.makeText(this, "請先點選餐點加入購物車", Toast.LENGTH_SHORT).show();
            return;
        }
        int rawSum = 0;
        for (CartItem ci : cart) rawSum += ci.getSubTotal();
        final int totalBefore = rawSum;

        final String[] opts = {
            "9 折 (折讓 $" + Math.round(totalBefore * 0.1f) + ")",
            "85 折 (折讓 $" + Math.round(totalBefore * 0.15f) + ")",
            "折讓 $10",
            "折讓 $30",
            "折讓 $50",
            "🎁 整單招待 (折讓 $" + totalBefore + ")",
            "清除整單折扣 ($0)"
        };
        new AlertDialog.Builder(this)
            .setTitle("✂️ 選擇整單折扣 / 招待")
            .setItems(opts, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) orderDiscount = Math.round(totalBefore * 0.1f);
                    else if (which == 1) orderDiscount = Math.round(totalBefore * 0.15f);
                    else if (which == 2) orderDiscount = 10;
                    else if (which == 3) orderDiscount = 30;
                    else if (which == 4) orderDiscount = 50;
                    else if (which == 5) orderDiscount = totalBefore;
                    else orderDiscount = 0;
                    if (orderDiscount > 0 && (auditLogEnabled || staffModeEnabled)) {
                        dbHelper.insertAuditLog(
                            staffModeEnabled ? currentStaffName : "店長",
                            "ORDER_DISCOUNT", "購物車折扣", "折讓 -$" + orderDiscount);
                    }
                    refreshCartUI();
                }
            })
            .show();
    }

    // ==================== 視覺化餐廳桌位平面圖點選 & 自由排桌設定系統 (1~13桌 + 自訂排桌) ====================
    private void showVisualTableMapDialog() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        final int dialogW = (int) (screenW * 0.92f);
        final int dialogH = (int) (screenH * 0.90f);

        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundBg(Color.parseColor("#0F172A"), Color.parseColor("#38BDF8"), 2, 10));

        // 頂部標題與「自由排桌編輯模式」切換列
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Color.parseColor("#062419"));
        header.setPadding(dp(12), dp(6), dp(12), dp(6));

        final TextView tvTitle = new TextView(this);
        tvTitle.setText("🗺️ " + PosPrintEngine.getEffectiveShortName() + " — 視覺化桌位點選 (點擊桌位立即帶入)");
        tvTitle.setTextColor(Color.parseColor("#A7F3D0"));
        tvTitle.setTextSize(15);
        tvTitle.setTypeface(null, Typeface.BOLD);
        header.addView(tvTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final boolean[] editMode = {false};
        final int[] movingSlotIdx = {-1};

        final Button btnToggleEdit = new Button(this);
        btnToggleEdit.setText("✏️ 自由排桌 / 增減桌號");
        btnToggleEdit.setAllCaps(false);
        btnToggleEdit.setTextSize(12);
        btnToggleEdit.setTypeface(null, Typeface.BOLD);
        btnToggleEdit.setTextColor(Color.WHITE);
        btnToggleEdit.setBackground(roundBg(Color.parseColor("#0284C7"), 0, 0, 6));
        header.addView(btnToggleEdit, new LinearLayout.LayoutParams(dp(155), dp(34)));

        Button btnClose = new Button(this);
        btnClose.setText("✖ 關閉");
        btnClose.setAllCaps(false);
        btnClose.setTextSize(12);
        btnClose.setTextColor(Color.WHITE);
        btnClose.setBackground(roundBg(Color.parseColor("#475569"), 0, 0, 6));
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        LinearLayout.LayoutParams lpClose = new LinearLayout.LayoutParams(dp(75), dp(34));
        lpClose.leftMargin = dp(6);
        header.addView(btnClose, lpClose);

        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        // 編輯工具列 (僅在編輯排桌模式開啟時顯示)
        final LinearLayout editToolbar = new LinearLayout(this);
        editToolbar.setOrientation(LinearLayout.HORIZONTAL);
        editToolbar.setGravity(Gravity.CENTER_VERTICAL);
        editToolbar.setBackgroundColor(Color.parseColor("#1E293B"));
        editToolbar.setPadding(dp(12), dp(6), dp(12), dp(6));
        editToolbar.setVisibility(View.GONE);

        final TextView tvEditHint = new TextView(this);
        tvEditHint.setText("💡 排桌操作：先點選任一桌(黃框)，再點選空位或另一桌即可移位/對調！");
        tvEditHint.setTextColor(Color.parseColor("#FCD34D"));
        tvEditHint.setTextSize(12);
        tvEditHint.setTypeface(null, Typeface.BOLD);
        editToolbar.addView(tvEditHint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnAddTable = makeMiniTuneBtn("➕ 新增桌號", null);
        btnAddTable.setBackground(roundBg(Color.parseColor("#10B981"), 0, 0, 6));
        btnAddTable.setTextColor(Color.parseColor("#052E16"));

        Button btnRenameTable = makeMiniTuneBtn("✏️ 修改桌名", null);
        Button btnDeleteTable = makeMiniTuneBtn("🗑️ 刪除選取桌", null);
        btnDeleteTable.setBackground(roundBg(Color.parseColor("#EF4444"), 0, 0, 6));

        Button btnResetTables = makeMiniTuneBtn("🔄 重設預設1~13桌", null);

        LinearLayout.LayoutParams lpTool = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        lpTool.leftMargin = dp(6);
        editToolbar.addView(btnAddTable, lpTool);
        editToolbar.addView(btnRenameTable, lpTool);
        editToolbar.addView(btnDeleteTable, lpTool);
        editToolbar.addView(btnResetTables, lpTool);
        root.addView(editToolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 統計今日各桌內用未作廢訂單金額 (供平面圖顯示「用餐中」狀態)
        final Map<String, Integer> tableActiveAmount = new LinkedHashMap<>();
        for (OrderRecord o : ordersHistory) {
            if (!o.voided && o.orderType != null && o.orderType.startsWith("內用(") && o.orderType.endsWith(")")) {
                String tName = o.orderType.substring(3, o.orderType.length() - 1);
                int prev = tableActiveAmount.containsKey(tName) ? tableActiveAmount.get(tName) : 0;
                tableActiveAmount.put(tName, prev + o.total);
            }
        }

        // 中間 5x4 餐廳平面圖網格區
        final LinearLayout mapGridContainer = new LinearLayout(this);
        mapGridContainer.setOrientation(LinearLayout.VERTICAL);
        mapGridContainer.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(mapGridContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final Runnable rebuildFloorMap = new Runnable() {
            @Override
            public void run() {
                mapGridContainer.removeAllViews();
                for (int r = 0; r < FLOOR_ROWS; r++) {
                    LinearLayout row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams lpRow = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
                    lpRow.bottomMargin = dp(6);
                    row.setLayoutParams(lpRow);

                    for (int c = 0; c < FLOOR_COLS; c++) {
                        final int slotIdx = r * FLOOR_COLS + c;
                        final String tName = tableFloorSlots[slotIdx] != null ? tableFloorSlots[slotIdx].trim() : "";
                        final boolean isEmptySlot = tName.isEmpty();

                        Button cell = new Button(MainActivity.this);
                        cell.setAllCaps(false);
                        cell.setPadding(dp(4), dp(4), dp(4), dp(4));

                        LinearLayout.LayoutParams lpCell = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                        lpCell.setMargins(dp(4), dp(2), dp(4), dp(2));

                        if (editMode[0]) {
                            boolean isSelectedForMove = (movingSlotIdx[0] == slotIdx);
                            if (isEmptySlot) {
                                cell.setText("＋ 空位格 (" + (slotIdx + 1) + ")\n(可移至此處)");
                                cell.setTextSize(11);
                                cell.setTextColor(Color.parseColor("#64748B"));
                                cell.setBackground(roundBg(
                                    Color.parseColor("#0F172A"),
                                    isSelectedForMove ? Color.parseColor("#FCD34D") : Color.parseColor("#334155"),
                                    isSelectedForMove ? 3 : 1, 8));
                            } else {
                                cell.setText("🪑 " + tName + "\n" + (isSelectedForMove ? "⚡ 請點目標位置" : "(點我移動/編輯)"));
                                cell.setTextSize(13);
                                cell.setTypeface(null, Typeface.BOLD);
                                cell.setTextColor(isSelectedForMove ? Color.BLACK : Color.WHITE);
                                cell.setBackground(roundBg(
                                    isSelectedForMove ? Color.parseColor("#FCD34D") : Color.parseColor("#1E293B"),
                                    isSelectedForMove ? Color.WHITE : Color.parseColor("#38BDF8"),
                                    isSelectedForMove ? 3 : 2, 10));
                            }
                            cell.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    if (movingSlotIdx[0] == -1) {
                                        if (!isEmptySlot) {
                                            movingSlotIdx[0] = slotIdx;
                                            tvEditHint.setText("⚡ 已選取【" + tName + "】➔ 請點選平面圖任一空位或另一桌進行移動/互換！");
                                            run();
                                        }
                                    } else if (movingSlotIdx[0] == slotIdx) {
                                        movingSlotIdx[0] = -1;
                                        tvEditHint.setText("💡 排桌操作：先點選任一桌(黃框)，再點選空位或另一桌即可移位/對調！");
                                        run();
                                    } else {
                                        // 對調位置並儲存至 SQLite
                                        String srcTable = tableFloorSlots[movingSlotIdx[0]];
                                        tableFloorSlots[movingSlotIdx[0]] = tableFloorSlots[slotIdx];
                                        tableFloorSlots[slotIdx] = srcTable;
                                        movingSlotIdx[0] = -1;
                                        saveTableFloorSlotsToDb();
                                        tvEditHint.setText("✅ 已移動【" + srcTable + "】並儲存桌位排列！");
                                        run();
                                    }
                                }
                            });
                        } else {
                            // 一般點餐選桌模式
                            if (isEmptySlot) {
                                cell.setText("走道 / 空位");
                                cell.setTextSize(10);
                                cell.setTextColor(Color.parseColor("#334155"));
                                cell.setBackground(roundBg(Color.parseColor("#0B1324"), Color.parseColor("#1E293B"), 1, 8));
                                cell.setEnabled(false);
                            } else {
                                boolean isCurrent = tName.equals(currentTable);
                                int activeAmt = tableActiveAmount.containsKey(tName) ? tableActiveAmount.get(tName) : 0;
                                String statusLine = isCurrent
                                    ? "✔ 目前選取中"
                                    : (activeAmt > 0 ? "🍽️ 今日消費 $" + activeAmt : "🟢 點擊選此桌");
                                cell.setText("🪑 " + tName + "\n" + statusLine);
                                cell.setTextSize(14);
                                cell.setTypeface(null, Typeface.BOLD);
                                if (isCurrent) {
                                    cell.setTextColor(Color.parseColor("#052E16"));
                                    cell.setBackground(roundBg(Color.parseColor("#10B981"), Color.parseColor("#6EE7B7"), 3, 10));
                                } else if (activeAmt > 0) {
                                    cell.setTextColor(Color.WHITE);
                                    cell.setBackground(roundBg(Color.parseColor("#B45309"), Color.parseColor("#FCD34D"), 2, 10));
                                } else {
                                    cell.setTextColor(Color.WHITE);
                                    cell.setBackground(roundBg(Color.parseColor("#1E293B"), Color.parseColor("#475569"), 1, 10));
                                }
                                cell.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        currentOrderType = "內用";
                                        currentTable = tName;
                                        for (CartItem ci : cart) {
                                            if (!ci.manualLabelOverride) {
                                                ci.printLabel = shouldPrintLabelByDefault(ci.menu.isDrink, currentOrderType);
                                            }
                                        }
                                        refreshOrderTypeButtons();
                                        refreshCartUI();
                                        dialog.dismiss();
                                    }
                                });
                            }
                        }
                        row.addView(cell, lpCell);
                    }
                    mapGridContainer.addView(row);
                }
            }
        };

        btnToggleEdit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editMode[0] = !editMode[0];
                movingSlotIdx[0] = -1;
                editToolbar.setVisibility(editMode[0] ? View.VISIBLE : View.GONE);
                btnToggleEdit.setText(editMode[0] ? "✅ 完成排桌返回點選" : "✏️ 自由排桌 / 增減桌號");
                btnToggleEdit.setBackground(roundBg(
                    editMode[0] ? Color.parseColor("#10B981") : Color.parseColor("#0284C7"), 0, 0, 6));
                btnToggleEdit.setTextColor(editMode[0] ? Color.parseColor("#052E16") : Color.WHITE);
                tvTitle.setText(editMode[0]
                    ? "✏️ 餐廳桌位排列編輯模式 (點選桌號再點空位即可自由排列位置)"
                    : "🗺️ " + PosPrintEngine.getEffectiveShortName() + " — 視覺化桌位點選 (點擊桌位立即帶入)");
                rebuildFloorMap.run();
            }
        });

        btnAddTable.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int emptyIdx = -1;
                for (int i = 0; i < FLOOR_TOTAL_SLOTS; i++) {
                    if (tableFloorSlots[i] == null || tableFloorSlots[i].trim().isEmpty()) {
                        emptyIdx = i;
                        break;
                    }
                }
                if (emptyIdx == -1) {
                    Toast.makeText(MainActivity.this, "20 個平面圖格位已滿，請先刪除不需要的桌號", Toast.LENGTH_SHORT).show();
                    return;
                }
                final int targetSlot = emptyIdx;
                final EditText et = new EditText(MainActivity.this);
                int nextNum = getActiveTableNames().size() + 1;
                et.setText(nextNum + "桌");
                et.setTextColor(Color.BLACK);
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("➕ 新增桌號")
                    .setMessage("請輸入新桌號名稱（例如：14桌、VIP桌、戶外1）：")
                    .setView(et)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("新增", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            String name = et.getText().toString().trim();
                            if (!name.isEmpty()) {
                                tableFloorSlots[targetSlot] = name;
                                saveTableFloorSlotsToDb();
                                rebuildFloorMap.run();
                            }
                        }
                    })
                    .show();
            }
        });

        btnRenameTable.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (movingSlotIdx[0] < 0 || movingSlotIdx[0] >= FLOOR_TOTAL_SLOTS
                    || tableFloorSlots[movingSlotIdx[0]].trim().isEmpty()) {
                    Toast.makeText(MainActivity.this, "請先在下方點選要改名的桌號(黃框)！", Toast.LENGTH_SHORT).show();
                    return;
                }
                final int targetIdx = movingSlotIdx[0];
                final EditText et = new EditText(MainActivity.this);
                et.setText(tableFloorSlots[targetIdx]);
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("✏️ 修改桌號名稱")
                    .setView(et)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("儲存", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            String n = et.getText().toString().trim();
                            if (!n.isEmpty()) {
                                tableFloorSlots[targetIdx] = n;
                                currentTable = n;
                                movingSlotIdx[0] = -1;
                                saveTableFloorSlotsToDb();
                                refreshOrderTypeButtons();
                                rebuildFloorMap.run();
                            }
                        }
                    })
                    .show();
            }
        });

        btnDeleteTable.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (movingSlotIdx[0] < 0 || movingSlotIdx[0] >= FLOOR_TOTAL_SLOTS
                    || tableFloorSlots[movingSlotIdx[0]].trim().isEmpty()) {
                    Toast.makeText(MainActivity.this, "請先在下方點選要刪除的桌號(黃框)！", Toast.LENGTH_SHORT).show();
                    return;
                }
                String removed = tableFloorSlots[movingSlotIdx[0]];
                tableFloorSlots[movingSlotIdx[0]] = "";
                movingSlotIdx[0] = -1;
                saveTableFloorSlotsToDb();
                Toast.makeText(MainActivity.this, "已移除【" + removed + "】", Toast.LENGTH_SHORT).show();
                rebuildFloorMap.run();
            }
        });

        btnResetTables.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                initDefaultTableFloorSlots();
                movingSlotIdx[0] = -1;
                saveTableFloorSlotsToDb();
                Toast.makeText(MainActivity.this, "✅ 已重設為 1桌 ~ 13桌 + 吧台 預設排列", Toast.LENGTH_SHORT).show();
                rebuildFloorMap.run();
            }
        });

        rebuildFloorMap.run();
        dialog.setContentView(root, new ViewGroup.LayoutParams(dialogW, dialogH));
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(dialogW, dialogH);
        }
    }

    // ==================== 📱 掃碼線上點餐待接單中心 & 各桌 QR Code 立牌列印系統 ====================
    private void showQrOrdersAndStandeeDialog() {
        if (!ensureFeatureAllowed(LicenseManager.FEATURE_QR, "手機掃碼點餐 & 桌牌中心")) return;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        final int dialogW = (int) (screenW * 0.94f);
        final int dialogH = (int) (screenH * 0.90f);

        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundBg(Color.parseColor("#0F172A"), Color.parseColor("#10B981"), 2, 10));

        // 頂部標題列
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Color.parseColor("#062419"));
        header.setPadding(dp(12), dp(6), dp(12), dp(6));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("📱 客人掃碼線上點餐接單中心 & 各桌 QR Code 立牌列印");
        tvTitle.setTextColor(Color.parseColor("#A7F3D0"));
        tvTitle.setTextSize(15);
        tvTitle.setTypeface(null, Typeface.BOLD);
        header.addView(tvTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Button btnRefreshQr = new Button(this);
        btnRefreshQr.setText("🔄 立即刷新雲端掃碼單");
        btnRefreshQr.setAllCaps(false);
        btnRefreshQr.setTextSize(12);
        btnRefreshQr.setTypeface(null, Typeface.BOLD);
        btnRefreshQr.setTextColor(Color.parseColor("#052E16"));
        btnRefreshQr.setBackground(roundBg(Color.parseColor("#38BDF8"), 0, 0, 6));
        header.addView(btnRefreshQr, new LinearLayout.LayoutParams(dp(165), dp(34)));

        Button btnClose = new Button(this);
        btnClose.setText("✖ 關閉");
        btnClose.setAllCaps(false);
        btnClose.setTextSize(12);
        btnClose.setTextColor(Color.WHITE);
        btnClose.setBackground(roundBg(Color.parseColor("#475569"), 0, 0, 6));
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        LinearLayout.LayoutParams lpClose = new LinearLayout.LayoutParams(dp(75), dp(34));
        lpClose.leftMargin = dp(8);
        header.addView(btnClose, lpClose);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        // 左右雙欄配置：左欄 = 🔔 待接線上掃碼訂單佇列 (55%)，右欄 = 🖨️ 各桌掃碼點餐 QR Code 立牌產生與列印 (45%)
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setPadding(dp(10), dp(8), dp(10), dp(10));
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // --- 左欄：待接線上掃碼訂單 ---
        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setBackground(roundBg(Color.parseColor("#1E293B"), Color.parseColor("#334155"), 1, 8));
        leftCol.setPadding(dp(10), dp(8), dp(10), dp(8));

        final TextView tvQueueHeader = new TextView(this);
        tvQueueHeader.setText("🔔 客人手機掃碼待接訂單 (" + pendingQrOrders.size() + " 筆)");
        tvQueueHeader.setTextColor(Color.parseColor("#FCD34D"));
        tvQueueHeader.setTextSize(14);
        tvQueueHeader.setTypeface(null, Typeface.BOLD);
        leftCol.addView(tvQueueHeader);

        ScrollView svQueue = new ScrollView(this);
        final LinearLayout llQueueList = new LinearLayout(this);
        llQueueList.setOrientation(LinearLayout.VERTICAL);
        llQueueList.setPadding(0, dp(6), 0, dp(6));
        svQueue.addView(llQueueList);
        leftCol.addView(svQueue, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final Runnable rebuildQrQueueUI = new Runnable() {
            @Override
            public void run() {
                tvQueueHeader.setText("🔔 客人手機掃碼待接訂單 (" + pendingQrOrders.size() + " 筆)");
                llQueueList.removeAllViews();
                if (pendingQrOrders.isEmpty()) {
                    TextView tvEmpty = new TextView(MainActivity.this);
                    tvEmpty.setText("目前尚無待處理的手機掃碼訂單。\n\n"
                        + "💡 使用方式：\n"
                        + "1. 請確認已更新 Google 試算表 Apps Script 並部署新版本。\n"
                        + "2. 點擊右側【🖨️ 用出單機印出本桌 QR Code 立牌】貼於 1~13 號餐桌。\n"
                        + "3. 客人用手機相機或 LINE 掃描後即可直接點餐，送出後會自動出現在此處！");
                    tvEmpty.setTextColor(Color.parseColor("#94A3B8"));
                    tvEmpty.setTextSize(12);
                    tvEmpty.setPadding(dp(6), dp(16), dp(6), dp(16));
                    llQueueList.addView(tvEmpty);
                    return;
                }
                for (int i = 0; i < pendingQrOrders.size(); i++) {
                    final CloudSyncManager.PendingQrOrder po = pendingQrOrders.get(i);
                    LinearLayout card = new LinearLayout(MainActivity.this);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setBackground(roundBg(Color.parseColor("#0F172A"), Color.parseColor("#F59E0B"), 1, 8));
                    card.setPadding(dp(10), dp(8), dp(10), dp(8));
                    LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lpC.bottomMargin = dp(8);
                    card.setLayoutParams(lpC);

                    TextView tvTop = new TextView(MainActivity.this);
                    tvTop.setText("🪑 【" + po.table + "】 單號:" + po.orderId + " ｜ 時間:" + po.time
                        + " ｜ 預計付款:" + po.paymentPref + " ｜ 應收 $" + po.total);
                    tvTop.setTextColor(Color.parseColor("#FCD34D"));
                    tvTop.setTextSize(13);
                    tvTop.setTypeface(null, Typeface.BOLD);
                    card.addView(tvTop);

                    StringBuilder itemsSb = new StringBuilder();
                    for (CloudSyncManager.PendingQrItem pi : po.items) {
                        itemsSb.append("• ").append(pi.name).append(" x").append(pi.qty);
                        if (pi.choice != null && !pi.choice.isEmpty()) itemsSb.append(" [").append(pi.choice).append("]");
                        if (pi.mealDrinkName != null && !pi.mealDrinkName.isEmpty() && !"不需附茶".equals(pi.mealDrinkName)) {
                            itemsSb.append(" +附飲:").append(pi.mealDrinkName)
                                   .append("(").append(pi.mealDrinkSweet).append("/").append(pi.mealDrinkIce).append(")");
                        } else if (pi.sweet != null && !pi.sweet.isEmpty()) {
                            itemsSb.append(" (").append(pi.sweet).append("/").append(pi.ice).append(")");
                        }
                        for (AddonOption ao : pi.addons) {
                            itemsSb.append(" +").append(ao.name);
                        }
                        itemsSb.append("\n");
                    }
                    if (po.customerNote != null && !po.customerNote.isEmpty()) {
                        itemsSb.append("📝 客人備註：").append(po.customerNote).append("\n");
                    }

                    TextView tvItems = new TextView(MainActivity.this);
                    tvItems.setText(itemsSb.toString().trim());
                    tvItems.setTextColor(Color.WHITE);
                    tvItems.setTextSize(12);
                    tvItems.setPadding(0, dp(4), 0, dp(6));
                    card.addView(tvItems);

                    LinearLayout btnRow = new LinearLayout(MainActivity.this);
                    btnRow.setOrientation(LinearLayout.HORIZONTAL);

                    Button btnLoadToCart = makeMiniTuneBtn("📥 載入至購物車(櫃檯收款)", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            loadPendingQrOrderIntoCart(po, false);
                            pendingQrOrders.remove(po);
                            updateQrOrdersBadge();
                            CloudSyncManager.ackQrOrderAsync(googleSheetUrl, po.orderId, "ACCEPTED", null);
                            dialog.dismiss();
                        }
                    });
                    btnLoadToCart.setBackground(roundBg(Color.parseColor("#0284C7"), 0, 0, 6));

                    Button btnDirectPrint = makeMiniTuneBtn("⚡ 直接結帳 & 雙機出單", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            pendingQrOrders.remove(po);
                            updateQrOrdersBadge();
                            CloudSyncManager.ackQrOrderAsync(googleSheetUrl, po.orderId, "ACCEPTED", null);
                            dialog.dismiss();
                            loadPendingQrOrderIntoCart(po, true);
                        }
                    });
                    btnDirectPrint.setBackground(roundBg(Color.parseColor("#10B981"), 0, 0, 6));
                    btnDirectPrint.setTextColor(Color.parseColor("#052E16"));

                    Button btnReject = makeMiniTuneBtn("🗑️ 取消", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            pendingQrOrders.remove(po);
                            updateQrOrdersBadge();
                            CloudSyncManager.ackQrOrderAsync(googleSheetUrl, po.orderId, "CANCELLED", null);
                            run();
                        }
                    });
                    btnReject.setBackground(roundBg(Color.parseColor("#EF4444"), 0, 0, 6));

                    LinearLayout.LayoutParams lpB1 = new LinearLayout.LayoutParams(0, dp(34), 1.1f);
                    lpB1.rightMargin = dp(4);
                    btnRow.addView(btnLoadToCart, lpB1);
                    btnRow.addView(btnDirectPrint, lpB1);
                    btnRow.addView(btnReject, new LinearLayout.LayoutParams(dp(64), dp(34)));

                    card.addView(btnRow);
                    llQueueList.addView(card);
                }
            }
        };

        btnRefreshQr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "⏳ 正在向 Google 試算表查詢最新掃碼訂單...", Toast.LENGTH_SHORT).show();
                CloudSyncManager.fetchPendingQrOrdersAsync(googleSheetUrl, new CloudSyncManager.QrOrdersCallback() {
                    @Override
                    public void onResult(boolean ok, String msg, List<CloudSyncManager.PendingQrOrder> list) {
                        if (ok && list != null) {
                            pendingQrOrders.clear();
                            pendingQrOrders.addAll(list);
                            updateQrOrdersBadge();
                            rebuildQrQueueUI.run();
                        }
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        // --- 右欄：各桌專屬 QR Code 產生與 HPRT TP805L 立牌列印 ---
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setGravity(Gravity.CENTER_HORIZONTAL);
        rightCol.setBackground(roundBg(Color.parseColor("#1E293B"), Color.parseColor("#334155"), 1, 8));
        rightCol.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView tvQrHeader = new TextView(this);
        tvQrHeader.setText("🖨️ 各桌掃碼點餐 QR Code 產生與印桌牌");
        tvQrHeader.setTextColor(Color.parseColor("#A7F3D0"));
        tvQrHeader.setTextSize(14);
        tvQrHeader.setTypeface(null, Typeface.BOLD);
        rightCol.addView(tvQrHeader);

        final List<String> qrTargets = new ArrayList<>(getActiveTableNames());
        qrTargets.add("外帶");
        qrTargets.add("外送");
        final String[] selectedQrTable = {currentTable};

        HorizontalScrollView hsvTables = new HorizontalScrollView(this);
        final LinearLayout llQrTableBtns = new LinearLayout(this);
        llQrTableBtns.setOrientation(LinearLayout.HORIZONTAL);
        llQrTableBtns.setPadding(0, dp(6), 0, dp(6));
        hsvTables.addView(llQrTableBtns);
        rightCol.addView(hsvTables, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final android.widget.ImageView ivQrPreview = new android.widget.ImageView(this);
        ivQrPreview.setAdjustViewBounds(true);
        LinearLayout.LayoutParams lpQrImg = new LinearLayout.LayoutParams(dp(200), dp(200));
        lpQrImg.topMargin = dp(4);
        rightCol.addView(ivQrPreview, lpQrImg);

        final TextView tvQrUrlHint = new TextView(this);
        tvQrUrlHint.setTextColor(Color.parseColor("#CBD5E1"));
        tvQrUrlHint.setTextSize(11);
        tvQrUrlHint.setGravity(Gravity.CENTER);
        tvQrUrlHint.setPadding(dp(4), dp(4), dp(4), dp(6));
        rightCol.addView(tvQrUrlHint);

        final Runnable updateQrPreview = new Runnable() {
            @Override
            public void run() {
                llQrTableBtns.removeAllViews();
                for (final String tbl : qrTargets) {
                    Button b = makeMiniTuneBtn(tbl, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            selectedQrTable[0] = tbl;
                            run();
                        }
                    });
                    boolean act = tbl.equals(selectedQrTable[0]);
                    b.setBackground(roundBg(
                        act ? Color.parseColor("#10B981") : Color.parseColor("#334155"), 0, 0, 6));
                    b.setTextColor(act ? Color.parseColor("#052E16") : Color.WHITE);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
                    lp.rightMargin = dp(4);
                    llQrTableBtns.addView(b, lp);
                }

                String baseUrl = (googleSheetUrl != null && !googleSheetUrl.trim().isEmpty())
                    ? googleSheetUrl.trim()
                    : "https://script.google.com/macros/s/請先設定試算表網址/exec";
                String fullUrl = buildTableOrderUrl(baseUrl, selectedQrTable[0]);
                android.graphics.Bitmap qrBmp = QrCodeGenerator.generateQrBitmap(fullUrl, 240);
                ivQrPreview.setImageBitmap(qrBmp);
                tvQrUrlHint.setText("目前顯示：【" + selectedQrTable[0] + "】專屬掃碼點餐條碼\n(客人掃描後自動帶入桌號 " + selectedQrTable[0] + ")");
            }
        };

        Button btnPrintSingleStandee = new Button(this);
        btnPrintSingleStandee.setText("🖨️ 用出單機(TP805L)印出本桌掃碼立牌");
        btnPrintSingleStandee.setAllCaps(false);
        btnPrintSingleStandee.setTextSize(12);
        btnPrintSingleStandee.setTypeface(null, Typeface.BOLD);
        btnPrintSingleStandee.setTextColor(Color.parseColor("#052E16"));
        btnPrintSingleStandee.setBackground(roundBg(Color.parseColor("#10B981"), 0, 0, 8));
        btnPrintSingleStandee.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (googleSheetUrl == null || googleSheetUrl.trim().isEmpty()) {
                    Toast.makeText(MainActivity.this, "請先在設定頁儲存 Google 試算表網址！", Toast.LENGTH_LONG).show();
                    return;
                }
                String url = buildTableOrderUrl(googleSheetUrl.trim(), selectedQrTable[0]);
                Toast.makeText(MainActivity.this, "⏳ 正在從 HPRT TP805L 印出【" + selectedQrTable[0] + "】掃碼點餐立牌...", Toast.LENGTH_SHORT).show();
                PosPrintEngine.printTableQrStandeeAsync(
                    selectedQrTable[0], url, receiptIp, receiptPort,
                    new PosPrintEngine.PrintCallback() {
                        @Override
                        public void onComplete(boolean ok, String msg) {
                            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                        }
                    });
            }
        });
        rightCol.addView(btnPrintSingleStandee, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.56f);
        lpLeft.rightMargin = dp(8);
        content.addView(leftCol, lpLeft);
        content.addView(rightCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.44f));

        rebuildQrQueueUI.run();
        updateQrPreview.run();

        dialog.setContentView(root, new ViewGroup.LayoutParams(dialogW, dialogH));
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(dialogW, dialogH);
        }
    }

    private String buildTableOrderUrl(String baseUrl, String tableName) {
        try {
            String enc = java.net.URLEncoder.encode(tableName, "UTF-8");
            return baseUrl + (baseUrl.contains("?") ? "&" : "?") + "page=order&table=" + enc;
        } catch (Exception e) {
            return baseUrl + "?page=order&table=" + tableName;
        }
    }

    private void loadPendingQrOrderIntoCart(CloudSyncManager.PendingQrOrder po, boolean autoCheckoutAndPrint) {
        cart.clear();
        orderDiscount = 0;
        if ("外帶".equals(po.table) || "外送".equals(po.table)) {
            currentOrderType = po.table;
        } else {
            currentOrderType = "內用";
            currentTable = po.table;
        }

        for (CloudSyncManager.PendingQrItem pi : po.items) {
            MenuItem matched = null;
            for (MenuItem m : menuItems) {
                if (m.name.equals(pi.name)) {
                    matched = m;
                    break;
                }
            }
            if (matched == null) {
                matched = new MenuItem(9999, "線上點餐", pi.name, "", pi.unitPrice, false, false, false, false, false, null);
            }
            CartItem ci = new CartItem();
            ci.menu = matched;
            ci.qty = Math.max(1, pi.qty);
            ci.selectedChoice = pi.choice != null ? pi.choice : "";
            ci.drinkSweet = pi.sweet != null ? pi.sweet : "5分半糖";
            ci.drinkIce = pi.ice != null ? pi.ice : "8分少冰";
            ci.mealDrinkName = pi.mealDrinkName != null ? pi.mealDrinkName : "毛尖綠茶";
            ci.mealDrinkDiff = pi.mealDrinkDiff;
            ci.mealDrinkSweet = pi.mealDrinkSweet != null ? pi.mealDrinkSweet : "無糖";
            ci.mealDrinkIce = pi.mealDrinkIce != null ? pi.mealDrinkIce : "3分微冰";
            ci.addons.addAll(pi.addons);
            ci.note = pi.note != null ? pi.note : "";
            if (po.customerNote != null && !po.customerNote.isEmpty() && ci.note.isEmpty()) {
                ci.note = po.customerNote;
            }
            ci.printLabel = shouldPrintLabelByDefault(matched.isDrink, currentOrderType);
            ci.printDrinkLabel = true;
            cart.add(ci);
        }

        refreshOrderTypeButtons();
        refreshCartUI();
        switchMainTab(0);

        if (autoCheckoutAndPrint) {
            if (!ensureFeatureAllowed(LicenseManager.FEATURE_CORE, "點餐結帳")) return;
            int due = getCartGrandTotal();
            OrderRecord rec = new OrderRecord();
            rec.callNum = formatCallNum(callSequence);
            rec.time = new SimpleDateFormat("HH:mm", Locale.TAIWAN).format(new Date());
            rec.orderType = "內用".equals(currentOrderType) ? "內用(" + currentTable + ")" : currentOrderType;
            for (CartItem ci : cart) rec.items.add(ci);
            rec.orderDiscount = 0;
            rec.total = due;
            rec.paymentMethod = (po.paymentPref != null && !po.paymentPref.isEmpty()) ? po.paymentPref : "現金";
            rec.cashReceived = due;
            rec.cashChange = 0;
            rec.printReceipt = globalPrintReceipt;
            rec.stickerCount = getTotalStickersCount();
            rec.staffName = staffModeEnabled ? currentStaffName : "";
            rec.triggerCashDrawer = PosPrintEngine.autoOpenCashDrawerOnCash
                && "現金".equals(rec.paymentMethod)
                && (licenseState == null || licenseState.hasFeature(LicenseManager.FEATURE_CASH_DRAWER));

            dbHelper.insertOrder(rec);
            ordersHistory.add(0, rec);
            callSequence++;
            dbHelper.setSetting("call_sequence", String.valueOf(callSequence));
            tvTopCallNum.setText("取餐號 " + formatCallNum(callSequence));
            updateOrdersBadge();

            cart.clear();
            refreshCartUI();
            showPrintPreviewAndDispatch(rec, true);
        } else {
            Toast.makeText(this, "📥 已將【" + po.table + "】掃碼訂單載入購物車，請點右下角結帳出單！", Toast.LENGTH_LONG).show();
        }
    }

    // ==================== CHECKOUT & PRINT PREVIEW DIALOG ====================
    private void showCheckoutDialog() {
        if (!ensureFeatureAllowed(LicenseManager.FEATURE_CORE, "點餐結帳")) return;
        if (cart.isEmpty()) {
            Toast.makeText(this, "購物車是空的，請先點選餐點！", Toast.LENGTH_SHORT).show();
            return;
        }
        final int due = getCartGrandTotal();
        final String[] payMethods = {"現金", "信用卡", "LINE Pay", "街口支付", "TWQR 掃碼", "UberEats", "Foodpanda"};
        final String[] selPay = {
            ("UberEats".equals(currentOrderType) || "Foodpanda".equals(currentOrderType)) ? currentOrderType : "現金"
        };

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.parseColor("#1E293B"));
        box.setPadding(dp(16), dp(12), dp(16), dp(12));

        int chkGridW = (int) (getResources().getDisplayMetrics().widthPixels * 0.72f) - dp(40);
        addSectionHeader(box, "1. 選擇付款方式：");
        addGridSingleSelect(box, payMethods, selPay, 4, chkGridW, null);

        addSectionHeader(box, "2. 應收金額：$" + due + "  (若收現金可點快速面額算找零)");
        final EditText etCash = new EditText(this);
        etCash.setInputType(InputType.TYPE_CLASS_NUMBER);
        etCash.setText(String.valueOf(due));
        etCash.setTextColor(Color.parseColor("#FCD34D"));
        etCash.setTextSize(18);
        box.addView(etCash);

        final TextView tvChange = new TextView(this);
        tvChange.setText("找零：$0");
        tvChange.setTextColor(Color.parseColor("#6EE7B7"));
        tvChange.setTextSize(16);
        tvChange.setTypeface(null, Typeface.BOLD);
        box.addView(tvChange);

        LinearLayout quickCashRow = new LinearLayout(this);
        quickCashRow.setOrientation(LinearLayout.HORIZONTAL);
        int[] bills = {due, 100, 200, 500, 1000};
        for (final int bVal : bills) {
            Button qb = new Button(this);
            qb.setText(bVal == due ? "剛好($" + due + ")" : "$" + bVal);
            qb.setTextSize(12);
            qb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    etCash.setText(String.valueOf(bVal));
                    int chg = Math.max(0, bVal - due);
                    tvChange.setText("找零：$" + chg);
                }
            });
            quickCashRow.addView(qb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        box.addView(quickCashRow);

        // 3. 台灣電子發票選項（僅於設定頁啟用電子發票時顯示）
        final boolean invEnabled = TaiwanCommerceGateway.invoiceCfg.enabled
            && (licenseState == null || licenseState.hasFeature(LicenseManager.FEATURE_EINVOICE));
        final String[] selCarrierType = {TaiwanCommerceGateway.CARRIER_PAPER};
        final EditText etBuyerTaxId = new EditText(this);
        final EditText etCarrierCode = new EditText(this);
        if (invEnabled) {
            addSectionHeader(box, "3. 台灣電子發票 ("
                + (TaiwanCommerceGateway.INV_MODE_WEBHOOK.equals(TaiwanCommerceGateway.invoiceCfg.mode) ? "正式中繼" : "測試模擬")
                + ")：");
            LinearLayout invOptRow = new LinearLayout(this);
            invOptRow.setOrientation(LinearLayout.HORIZONTAL);
            invOptRow.setGravity(Gravity.CENTER_VERTICAL);

            final Button btnCarrierMode = new Button(this);
            btnCarrierMode.setText("🧾 紙本證明聯");
            btnCarrierMode.setAllCaps(false);
            btnCarrierMode.setTextSize(12);
            btnCarrierMode.setTextColor(Color.WHITE);
            btnCarrierMode.setBackground(roundBg(Color.parseColor("#0F766E"), 0, 0, 6));

            etCarrierCode.setHint("載具 / 愛心碼");
            etCarrierCode.setTextColor(Color.WHITE);
            etCarrierCode.setHintTextColor(Color.parseColor("#94A3B8"));
            etCarrierCode.setTextSize(12);
            etCarrierCode.setVisibility(View.GONE);

            btnCarrierMode.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (TaiwanCommerceGateway.CARRIER_PAPER.equals(selCarrierType[0])) {
                        selCarrierType[0] = TaiwanCommerceGateway.CARRIER_MOBILE;
                        btnCarrierMode.setText("📱 手機條碼");
                        etCarrierCode.setHint("輸入 /XXXXXXX (8碼)");
                        etCarrierCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
                        etCarrierCode.setVisibility(View.VISIBLE);
                    } else if (TaiwanCommerceGateway.CARRIER_MOBILE.equals(selCarrierType[0])) {
                        selCarrierType[0] = TaiwanCommerceGateway.CARRIER_DONATE;
                        btnCarrierMode.setText("❤️ 捐贈愛心碼");
                        etCarrierCode.setHint("輸入 3~7 碼愛心碼");
                        etCarrierCode.setInputType(InputType.TYPE_CLASS_NUMBER);
                        etCarrierCode.setVisibility(View.VISIBLE);
                    } else {
                        selCarrierType[0] = TaiwanCommerceGateway.CARRIER_PAPER;
                        btnCarrierMode.setText("🧾 紙本證明聯");
                        etCarrierCode.setText("");
                        etCarrierCode.setVisibility(View.GONE);
                    }
                }
            });
            invOptRow.addView(btnCarrierMode, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
            invOptRow.addView(etCarrierCode, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f));

            etBuyerTaxId.setHint("買方統編(8碼選填)");
            etBuyerTaxId.setInputType(InputType.TYPE_CLASS_NUMBER);
            etBuyerTaxId.setTextColor(Color.WHITE);
            etBuyerTaxId.setHintTextColor(Color.parseColor("#94A3B8"));
            etBuyerTaxId.setTextSize(12);
            invOptRow.addView(etBuyerTaxId, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            box.addView(invOptRow);
        }

        int sCount = getTotalStickersCount();
        TextView tvPrintInfo = new TextView(this);
        tvPrintInfo.setText("🖨️ 出單設定："
            + (globalPrintReceipt ? "✅ 印收據" : "❌ 不印收據")
            + (invEnabled ? " + 電子發票" : "")
            + " ｜ "
            + (sCount > 0 ? "✅ 印貼紙共 " + sCount + " 張" : "❌ 不印貼紙"));
        tvPrintInfo.setTextColor(Color.parseColor("#93C5FD"));
        tvPrintInfo.setTextSize(13);
        tvPrintInfo.setPadding(0, dp(8), dp(0), dp(8));
        box.addView(tvPrintInfo);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnBack = new Button(this);
        btnBack.setText("❌ 返回修改");
        btnBack.setAllCaps(false);
        btnBack.setTextColor(Color.WHITE);
        btnBack.setBackground(roundBg(Color.parseColor("#475569"), 0, 0, 8));

        Button btnFinish = new Button(this);
        btnFinish.setText("✅ 完成結帳 & 立即出單 ($" + due + ")");
        btnFinish.setAllCaps(false);
        btnFinish.setTextSize(15);
        btnFinish.setTypeface(null, Typeface.BOLD);
        btnFinish.setTextColor(Color.parseColor("#052E16"));
        btnFinish.setBackground(roundBg(Color.parseColor("#10B981"), Color.parseColor("#34D399"), 1, 8));

        LinearLayout.LayoutParams lpBack = new LinearLayout.LayoutParams(dp(130), dp(44));
        lpBack.rightMargin = dp(10);
        actionRow.addView(btnBack, lpBack);
        actionRow.addView(btnFinish, new LinearLayout.LayoutParams(0, dp(44), 1f));
        box.addView(actionRow);

        ScrollView svBox = new ScrollView(this);
        svBox.addView(box);

        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("💰 結帳收款｜取餐號 " + formatCallNum(callSequence))
            .setView(svBox)
            .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }

        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });

        btnFinish.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int cashRec = due;
                try { cashRec = Integer.parseInt(etCash.getText().toString().trim()); } catch (Exception ignored) {}
                final String buyerTax = invEnabled ? etBuyerTaxId.getText().toString().trim() : "";
                final String cType = invEnabled ? selCarrierType[0] : TaiwanCommerceGateway.CARRIER_PAPER;
                final String cCode = invEnabled ? etCarrierCode.getText().toString().trim().toUpperCase(Locale.US) : "";

                if (invEnabled) {
                    if (!buyerTax.isEmpty() && !TaiwanCommerceGateway.isValidTaiwanTaxId(buyerTax)) {
                        Toast.makeText(MainActivity.this, "❌ 買方統一編號檢查碼錯誤 (須為正確 8 碼)", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (TaiwanCommerceGateway.CARRIER_MOBILE.equals(cType) && !TaiwanCommerceGateway.isValidMobileBarcode(cCode)) {
                        Toast.makeText(MainActivity.this, "❌ 手機條碼格式錯誤 (範例 /AB12+34)", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (TaiwanCommerceGateway.CARRIER_DONATE.equals(cType) && !TaiwanCommerceGateway.isValidDonateCode(cCode)) {
                        Toast.makeText(MainActivity.this, "❌ 捐贈愛心碼須為 3~7 碼數字", Toast.LENGTH_LONG).show();
                        return;
                    }
                }

                final String payMethod = selPay[0];
                final int finalCashRec = cashRec;
                dialog.dismiss();

                if (TaiwanCommerceGateway.isDigitalPaymentConfigured(payMethod)
                    && ensureFeatureAllowed(LicenseManager.FEATURE_MULTI_PAY, "多元電子支付 API 收款")) {
                    showDigitalPaymentCollectDialog(payMethod, due, formatCallNum(callSequence),
                        new TaiwanCommerceGateway.PaymentCallback() {
                            @Override public void onResult(TaiwanCommerceGateway.PaymentResult res) {
                                if (res.success) {
                                    Toast.makeText(MainActivity.this, res.message, Toast.LENGTH_SHORT).show();
                                    finalizeOrderCheckout(due, payMethod, finalCashRec, buyerTax, cType, cCode, res.txId);
                                }
                            }
                        });
                } else {
                    finalizeOrderCheckout(due, payMethod, finalCashRec, buyerTax, cType, cCode, "");
                }
            }
        });

        dialog.show();
    }

    private void showDigitalPaymentCollectDialog(final String method, final int amount, final String callNum,
                                                 final TaiwanCommerceGateway.PaymentCallback onPaid) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.parseColor("#0F172A"));
        box.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView tvAmt = new TextView(this);
        tvAmt.setText("💳 " + method + " 待收金額：$" + amount + " (" + callNum + ")");
        tvAmt.setTextColor(Color.parseColor("#FCD34D"));
        tvAmt.setTextSize(17);
        tvAmt.setTypeface(null, Typeface.BOLD);
        tvAmt.setPadding(0, 0, 0, dp(8));
        box.addView(tvAmt);

        if ("TWQR 掃碼".equals(method)) {
            String twqrStr = TaiwanCommerceGateway.buildTwqrScanString(amount, callNum);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(QrCodeGenerator.generateQrBitmap(twqrStr, 340));
            LinearLayout.LayoutParams lpIv = new LinearLayout.LayoutParams(dp(240), dp(240));
            lpIv.gravity = Gravity.CENTER_HORIZONTAL;
            box.addView(iv, lpIv);

            TextView tvHint = new TextView(this);
            tvHint.setText("請顧客開啟任一台灣行動支付 / 網銀 App 掃描上方 TWQR 收款碼後，點擊「確認已入帳」。");
            tvHint.setTextColor(Color.parseColor("#CBD5E1"));
            tvHint.setTextSize(12);
            tvHint.setPadding(0, dp(8), 0, 0);
            box.addView(tvHint);

            new AlertDialog.Builder(this)
                .setTitle("📱 TWQR 共通掃碼收款")
                .setView(box)
                .setNegativeButton("取消收款", null)
                .setPositiveButton("✅ 已確認收到款項 ($" + amount + ")", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        TaiwanCommerceGateway.processDigitalPaymentAsync(method, amount, callNum, "", onPaid);
                    }
                })
                .show();
            return;
        }

        final EditText etBarcode = new EditText(this);
        etBarcode.setHint("請用掃碼槍掃入顧客 " + method + " 條碼 (支援 USB/藍牙條碼槍)");
        etBarcode.setTextColor(Color.WHITE);
        etBarcode.setHintTextColor(Color.parseColor("#94A3B8"));
        etBarcode.setSingleLine(true);
        etBarcode.setImeOptions(EditorInfo.IME_ACTION_DONE);
        box.addView(etBarcode);

        final TextView tvStatus = new TextView(this);
        tvStatus.setText("等待掃描付款碼...");
        tvStatus.setTextColor(Color.parseColor("#93C5FD"));
        tvStatus.setTextSize(12);
        tvStatus.setPadding(0, dp(6), 0, dp(6));
        box.addView(tvStatus);

        final AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle("📱 " + method + " 掃碼收款")
            .setView(box)
            .setNegativeButton("取消", null)
            .setNeutralButton("🧪 模擬測試掃碼", null)
            .setPositiveButton("⚡ 送出扣款", null)
            .create();

        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                final Runnable submitPay = new Runnable() {
                    @Override public void run() {
                        String code = etBarcode.getText().toString().trim();
                        tvStatus.setText("⏳ 正在呼叫 " + method + " API 授權中...");
                        TaiwanCommerceGateway.processDigitalPaymentAsync(method, amount, callNum, code,
                            new TaiwanCommerceGateway.PaymentCallback() {
                                @Override public void onResult(TaiwanCommerceGateway.PaymentResult res) {
                                    if (res.success) {
                                        dlg.dismiss();
                                        onPaid.onResult(res);
                                    } else {
                                        tvStatus.setText(res.message);
                                        tvStatus.setTextColor(Color.parseColor("#FCA5A5"));
                                    }
                                }
                            });
                    }
                };
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { submitPay.run(); }
                });
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        etBarcode.setText("9900" + (10000000000000L + (System.currentTimeMillis() % 89999999999999L)));
                        submitPay.run();
                    }
                });
                etBarcode.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
                    @Override public boolean onEditorAction(android.widget.TextView tv, int actionId, android.view.KeyEvent event) {
                        submitPay.run();
                        return true;
                    }
                });
            }
        });
        dlg.show();
    }

    private void finalizeOrderCheckout(int due, String payMethod, int cashRec,
                                       String buyerTaxId, String carrierType, String carrierCode,
                                       String paymentTxId) {
        final OrderRecord rec = new OrderRecord();
        rec.callNum = formatCallNum(callSequence);
        rec.time = new SimpleDateFormat("HH:mm", Locale.TAIWAN).format(new Date());
        rec.orderType = "內用".equals(currentOrderType) ? "內用(" + currentTable + ")" : currentOrderType;
        for (CartItem ci : cart) rec.items.add(ci);
        rec.orderDiscount = orderDiscount;
        rec.total = due;
        rec.paymentMethod = payMethod;
        rec.cashReceived = cashRec;
        rec.cashChange = Math.max(0, cashRec - due);
        rec.printReceipt = globalPrintReceipt;
        rec.stickerCount = getTotalStickersCount();
        rec.staffName = staffModeEnabled ? currentStaffName : "";
        rec.buyerTaxId = buyerTaxId != null ? buyerTaxId : "";
        rec.carrierType = carrierType != null ? carrierType : TaiwanCommerceGateway.CARRIER_PAPER;
        rec.carrierCode = carrierCode != null ? carrierCode : "";
        rec.paymentTxId = paymentTxId != null ? paymentTxId : "";
        rec.paymentProvider = (paymentTxId != null && !paymentTxId.isEmpty()) ? payMethod : "";
        rec.triggerCashDrawer = PosPrintEngine.autoOpenCashDrawerOnCash
            && "現金".equals(rec.paymentMethod)
            && (licenseState == null || licenseState.hasFeature(LicenseManager.FEATURE_CASH_DRAWER));

        dbHelper.insertOrder(rec);
        ordersHistory.add(0, rec);
        callSequence++;
        dbHelper.setSetting("call_sequence", String.valueOf(callSequence));
        tvTopCallNum.setText("取餐號 " + formatCallNum(callSequence));
        updateOrdersBadge();

        cart.clear();
        orderDiscount = 0;
        refreshCartUI();

        if (TaiwanCommerceGateway.invoiceCfg.enabled
            && (licenseState == null || licenseState.hasFeature(LicenseManager.FEATURE_EINVOICE))) {
            Toast.makeText(this, "🧾 正在開立電子發票並準備出單...", Toast.LENGTH_SHORT).show();
            TaiwanCommerceGateway.issueEinvoiceAsync(dbHelper, rec, new TaiwanCommerceGateway.InvoiceCallback() {
                @Override public void onResult(TaiwanCommerceGateway.InvoiceResult res) {
                    if (res.success) {
                        rec.invoiceNo = res.invoiceNo;
                        rec.invoicePeriod = res.period;
                        rec.invoiceRandom = res.randomCode;
                        rec.invoiceQrLeft = res.qrLeft;
                        rec.invoiceQrRight = res.qrRight;
                        rec.invoiceStatus = res.status;
                    } else {
                        rec.invoiceStatus = res.status;
                        Toast.makeText(MainActivity.this, res.message, Toast.LENGTH_LONG).show();
                    }
                    dbHelper.updateOrderInvoice(rec);
                    showPrintPreviewAndDispatch(rec, true);
                }
            });
        } else {
            showPrintPreviewAndDispatch(rec, true);
        }
    }

    private void updateOrdersBadge() {
        int valid = 0;
        for (OrderRecord r : ordersHistory) if (!r.voided) valid++;
        if (btnTabOrders != null) {
            btnTabOrders.setText("📋 當日訂單 (" + valid + ")");
        }
    }

    private void showPrintPreviewAndDispatch(final OrderRecord order, boolean autoPrintHardware) {
        StringBuilder sb = new StringBuilder();
        if (order.printReceipt) {
            sb.append("========== [HPRT TP805L 收據/廚房總單 (").append(receiptIp).append(":").append(receiptPort).append(")] ==========\n");
            sb.append("        ").append(PosPrintEngine.storeName).append("\n");
            if (PosPrintEngine.storeSubtitle != null && !PosPrintEngine.storeSubtitle.trim().isEmpty()) {
                sb.append("   ").append(PosPrintEngine.storeSubtitle.trim()).append("\n");
            }
            sb.append("   取餐號：").append(order.callNum).append("  [").append(order.orderType).append("]  ").append(order.time);
            if (order.staffName != null && !order.staffName.isEmpty()) {
                sb.append("  經手:").append(order.staffName);
            }
            sb.append("\n");
            sb.append("------------------------------------------------\n");
            for (CartItem ci : order.items) {
                sb.append("• ").append(ci.menu.name).append(" x").append(ci.qty)
                  .append("   $").append(ci.getSubTotal()).append("\n");
                sb.append("  └ ").append(ci.getModSummary()).append("\n");
            }
            sb.append("------------------------------------------------\n");
            if (order.orderDiscount > 0) {
                sb.append("整單折扣：-$").append(order.orderDiscount).append("\n");
            }
            sb.append("應收總計 (").append(order.paymentMethod).append(")：$").append(order.total).append("\n");
            if (order.paymentTxId != null && !order.paymentTxId.trim().isEmpty()) {
                sb.append("支付交易序號：").append(order.paymentTxId).append("\n");
            }
            if (order.invoiceNo != null && !order.invoiceNo.trim().isEmpty()) {
                sb.append("電子發票：").append(order.invoiceNo)
                  .append(" (").append(order.invoicePeriod).append(" / 隨機碼:").append(order.invoiceRandom).append(")")
                  .append(" [").append(order.invoiceStatus).append("]\n");
                if (order.buyerTaxId != null && !order.buyerTaxId.isEmpty()) {
                    sb.append("買方統編：").append(order.buyerTaxId).append("\n");
                }
                if (order.carrierCode != null && !order.carrierCode.isEmpty()) {
                    sb.append("載具/愛心碼：").append(order.carrierCode).append("\n");
                }
            }
            if ("現金".equals(order.paymentMethod)) {
                sb.append("實收：$").append(order.cashReceived).append("  找零：$").append(order.cashChange).append("\n");
            }
            if (PosPrintEngine.storeReceiptFooter != null && !PosPrintEngine.storeReceiptFooter.trim().isEmpty()) {
                sb.append(PosPrintEngine.storeReceiptFooter.trim()).append("\n");
            }
            sb.append("\n");
        }

        if (order.stickerCount > 0) {
            sb.append("========== [GoDEX DT2x 40×30mm 標籤貼紙 (").append(labelIp).append(":").append(labelPort).append(")] ==========\n");
            int cur = 1;
            String shortBrand = PosPrintEngine.getEffectiveShortName();
            for (CartItem ci : order.items) {
                if (ci.printLabel) {
                    for (int k = 0; k < ci.qty; k++) {
                        sb.append("┌──────────────────────────────┐\n");
                        sb.append("│ ").append(order.callNum).append(" [").append(order.orderType).append("]          (")
                          .append(cur).append("/").append(order.stickerCount).append(") │\n");
                        sb.append("│ ").append(ci.menu.name).append("\n");
                        sb.append("│ ").append(ci.getModSummary()).append("\n");
                        sb.append("│ ").append(shortBrand).append(" ").append(order.time).append("        $")
                          .append(ci.getSubTotal() / Math.max(1, ci.qty)).append(" │\n");
                        sb.append("└──────────────────────────────┘\n");
                        cur++;
                    }
                }
                if (ci.hasAttachedDrink() && ci.printDrinkLabel) {
                    for (int k = 0; k < ci.qty; k++) {
                        sb.append("┌──────────────────────────────┐\n");
                        sb.append("│ ").append(order.callNum).append(" [").append(order.orderType).append("]          (")
                          .append(cur).append("/").append(order.stickerCount).append(") │\n");
                        sb.append("│ [附飲] ").append(ci.mealDrinkName).append("\n");
                        sb.append("│ ").append(ci.getAttachedDrinkModSummary()).append("\n");
                        sb.append("│ ").append(shortBrand).append(" ").append(order.time).append("        $")
                          .append(Math.max(0, ci.mealDrinkDiff)).append(" │\n");
                        sb.append("└──────────────────────────────┘\n");
                        cur++;
                    }
                }
            }
        }

        if (!order.printReceipt && order.stickerCount == 0) {
            sb.append("✅ 訂單 ").append(order.callNum).append(" 已寫入 SQLite 資料庫（本次未勾選列印收據與貼紙）");
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.parseColor("#0F172A"));
        box.setPadding(dp(14), dp(10), dp(14), dp(10));

        final TextView tvHwStatus = new TextView(this);
        tvHwStatus.setText(autoPrintHardware && (order.printReceipt || order.stickerCount > 0)
            ? "⏳ 正在透過 Wi-Fi (Port 9100) 連線雙印表機出單中..."
            : "💾 已儲存於平板 SQLite 資料庫");
        tvHwStatus.setTextColor(Color.parseColor("#FCD34D"));
        tvHwStatus.setTextSize(13);
        tvHwStatus.setTypeface(null, Typeface.BOLD);
        tvHwStatus.setPadding(0, 0, 0, dp(6));
        box.addView(tvHwStatus);

        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextColor(Color.WHITE);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(13);
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        tv.setBackgroundColor(Color.parseColor("#1E293B"));
        sv.addView(tv);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));

        new AlertDialog.Builder(this)
            .setTitle("🖨️ 出單狀態與單據預覽｜取餐號 " + order.callNum)
            .setView(box)
            .setPositiveButton("確認 (下一單)", null)
            .show();

        if (autoPrintHardware && (order.printReceipt || order.stickerCount > 0)) {
            PosPrintEngine.printOrderAsync(
                order,
                receiptIp, receiptPort, receiptWidthMm,
                labelIp, labelPort, labelProtocol,
                new PosPrintEngine.PrintCallback() {
                    @Override
                    public void onComplete(boolean allSuccess, String message) {
                        tvHwStatus.setText(message);
                        tvHwStatus.setTextColor(allSuccess ? Color.parseColor("#6EE7B7") : Color.parseColor("#FCA5A5"));
                    }
                });
        }
    }

    // ==================== TAB 1: ORDERS HISTORY & VOID/REPRINT ====================
    private void rebuildOrdersTab() {
        viewOrders.removeAllViews();
        viewOrders.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("📋 當日未關帳訂單紀錄（自動保存於本地 SQLite，可單獨補印收據/貼紙或作廢退單）");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(15);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, dp(8));
        viewOrders.addView(tvTitle);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        viewOrders.addView(sv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (ordersHistory.isEmpty()) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("今日尚無訂單紀錄");
            tvEmpty.setTextColor(Color.parseColor("#94A3B8"));
            tvEmpty.setTextSize(14);
            list.addView(tvEmpty);
            return;
        }

        for (int i = 0; i < ordersHistory.size(); i++) {
            final OrderRecord o = ordersHistory.get(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(roundBg(Color.parseColor("#1E293B"), Color.parseColor("#475569"), 1, 8));
            card.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            card.setLayoutParams(lp);

            StringBuilder desc = new StringBuilder();
            for (CartItem ci : o.items) {
                if (desc.length() > 0) desc.append("、");
                desc.append(ci.menu.name).append("x").append(ci.qty);
            }

            TextView tvInfo = new TextView(this);
            StringBuilder extraMeta = new StringBuilder();
            if (o.invoiceNo != null && !o.invoiceNo.isEmpty()) {
                extraMeta.append(" ｜ 🧾").append(o.invoiceNo);
                if ("VOIDED".equals(o.invoiceStatus)) extraMeta.append("(已作廢)");
                else if ("SANDBOX".equals(o.invoiceStatus)) extraMeta.append("(模擬)");
            }
            if (o.paymentTxId != null && !o.paymentTxId.isEmpty()) {
                extraMeta.append(" ｜ 💳").append(o.paymentTxId);
            }
            tvInfo.setText(o.callNum + " [" + o.orderType + "] " + o.time
                + ((o.staffName != null && !o.staffName.isEmpty()) ? " 👤" + o.staffName : "")
                + (o.voided ? " (❌已作廢)" : " (✅有效)")
                + "\n" + desc.toString() + " ｜ " + o.paymentMethod + " $" + o.total + extraMeta.toString());
            tvInfo.setTextColor(o.voided ? Color.parseColor("#EF4444") : Color.WHITE);
            tvInfo.setTextSize(13);
            card.addView(tvInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button btnPreview = new Button(this);
            btnPreview.setText("👁️ 預覽");
            btnPreview.setAllCaps(false);
            btnPreview.setTextSize(12);
            btnPreview.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showPrintPreviewAndDispatch(o, false); }
            });
            card.addView(btnPreview);

            if (o.invoiceNo != null && !o.invoiceNo.isEmpty()) {
                Button btnProofBmp = new Button(this);
                btnProofBmp.setText("🧾 證明聯");
                btnProofBmp.setAllCaps(false);
                btnProofBmp.setTextSize(12);
                btnProofBmp.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        android.graphics.Bitmap proof = PosPrintEngine.renderEinvoiceProofBitmap(o, receiptWidthMm);
                        showBitmapPreviewDialog("🧾 電子發票證明聯預覽 (" + o.invoiceNo + ")", proof);
                    }
                });
                LinearLayout.LayoutParams lpPr = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lpPr.leftMargin = dp(4);
                card.addView(btnProofBmp, lpPr);
            }

            Button btnReprintRec = new Button(this);
            btnReprintRec.setText("🧾 補印收據");
            btnReprintRec.setAllCaps(false);
            btnReprintRec.setTextSize(12);
            btnReprintRec.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    OrderRecord copy = copyOrderForSinglePrint(o, true, false);
                    PosPrintEngine.printOrderAsync(copy, receiptIp, receiptPort, receiptWidthMm,
                        labelIp, labelPort, labelProtocol, new PosPrintEngine.PrintCallback() {
                            @Override public void onComplete(boolean ok, String msg) {
                                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                            }
                        });
                }
            });
            LinearLayout.LayoutParams lpRR = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpRR.leftMargin = dp(4);
            card.addView(btnReprintRec, lpRR);

            Button btnReprintLbl = new Button(this);
            btnReprintLbl.setText("🏷️ 補印貼紙");
            btnReprintLbl.setAllCaps(false);
            btnReprintLbl.setTextSize(12);
            btnReprintLbl.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    OrderRecord copy = copyOrderForSinglePrint(o, false, true);
                    PosPrintEngine.printOrderAsync(copy, receiptIp, receiptPort, receiptWidthMm,
                        labelIp, labelPort, labelProtocol, new PosPrintEngine.PrintCallback() {
                            @Override public void onComplete(boolean ok, String msg) {
                                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                            }
                        });
                }
            });
            LinearLayout.LayoutParams lpRL = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpRL.leftMargin = dp(4);
            card.addView(btnReprintLbl, lpRL);

            if (!o.voided) {
                Button btnVoid = new Button(this);
                btnVoid.setText("作廢退單");
                btnVoid.setAllCaps(false);
                btnVoid.setTextSize(12);
                btnVoid.setTextColor(Color.WHITE);
                btnVoid.setBackground(roundBg(Color.parseColor("#EF4444"), 0, 0, 6));
                btnVoid.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        authorizeAction("作廢退單 (" + o.callNum + ")", true, new Runnable() {
                            @Override public void run() {
                                o.voided = true;
                                if (o.dbId > 0) dbHelper.markOrderVoided(o.dbId, true);
                                if (o.invoiceNo != null && !o.invoiceNo.isEmpty() && !"VOIDED".equals(o.invoiceStatus)) {
                                    TaiwanCommerceGateway.voidEinvoiceAsync(o, new TaiwanCommerceGateway.InvoiceCallback() {
                                        @Override public void onResult(TaiwanCommerceGateway.InvoiceResult res) {
                                            o.invoiceStatus = res.status;
                                            dbHelper.updateOrderInvoice(o);
                                            Toast.makeText(MainActivity.this, res.message, Toast.LENGTH_LONG).show();
                                            rebuildOrdersTab();
                                        }
                                    });
                                }
                                if (auditLogEnabled || staffModeEnabled) {
                                    dbHelper.insertAuditLog(
                                        staffModeEnabled ? currentStaffName : "店長",
                                        "VOID_ORDER", o.callNum,
                                        "作廢退單 $" + o.total + " (" + o.paymentMethod + ")");
                                }
                                updateOrdersBadge();
                                rebuildOrdersTab();
                            }
                        });
                    }
                });
                LinearLayout.LayoutParams lpV = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lpV.leftMargin = dp(6);
                card.addView(btnVoid, lpV);
            }
            list.addView(card);
        }
    }

    private OrderRecord copyOrderForSinglePrint(OrderRecord src, boolean wantReceipt, boolean wantStickers) {
        OrderRecord c = new OrderRecord();
        c.callNum = src.callNum;
        c.time = src.time;
        c.orderType = src.orderType;
        c.orderDiscount = src.orderDiscount;
        c.total = src.total;
        c.paymentMethod = src.paymentMethod;
        c.cashReceived = src.cashReceived;
        c.cashChange = src.cashChange;
        c.printReceipt = wantReceipt;
        int totalQty = 0;
        for (CartItem ci : src.items) {
            CartItem itemCopy = new CartItem();
            itemCopy.menu = ci.menu;
            itemCopy.qty = ci.qty;
            itemCopy.selectedChoice = ci.selectedChoice;
            itemCopy.drinkSweet = ci.drinkSweet;
            itemCopy.drinkIce = ci.drinkIce;
            itemCopy.mealDrinkName = ci.mealDrinkName;
            itemCopy.mealDrinkDiff = ci.mealDrinkDiff;
            itemCopy.mealDrinkSweet = ci.mealDrinkSweet;
            itemCopy.mealDrinkIce = ci.mealDrinkIce;
            itemCopy.addons.addAll(ci.addons);
            itemCopy.itemDiscount = ci.itemDiscount;
            itemCopy.note = ci.note;
            itemCopy.printLabel = wantStickers && (ci.printLabel || (!ci.hasAttachedDrink() && src.stickerCount == 0));
            itemCopy.printDrinkLabel = wantStickers && ci.hasAttachedDrink();
            totalQty += itemCopy.getItemStickerCount();
            c.items.add(itemCopy);
        }
        c.stickerCount = wantStickers ? totalQty : 0;
        return c;
    }

    // ==================== TAB 2: DAILY CLOSING (日結關帳 + CSV匯出 + 雲端同步) ====================
    private void rebuildClosingTab() {
        viewClosing.removeAllViews();
        viewClosing.setPadding(dp(12), dp(10), dp(12), dp(10));

        int validCount = 0, voidCount = 0, netSales = 0, cashSales = 0;
        final Map<String, Integer> byPay = new LinkedHashMap<>();
        final Map<String, Integer> itemRank = new LinkedHashMap<>();

        for (OrderRecord o : ordersHistory) {
            if (o.voided) {
                voidCount++;
            } else {
                validCount++;
                netSales += o.total;
                if ("現金".equals(o.paymentMethod)) cashSales += o.total;
                byPay.put(o.paymentMethod, (byPay.containsKey(o.paymentMethod) ? byPay.get(o.paymentMethod) : 0) + o.total);
                for (CartItem ci : o.items) {
                    int prev = itemRank.containsKey(ci.menu.name) ? itemRank.get(ci.menu.name) : 0;
                    itemRank.put(ci.menu.name, prev + ci.qty);
                }
            }
        }

        final int fValid = validCount;
        final int fVoid = voidCount;
        final int fNet = netSales;
        final int fCash = cashSales;

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        final CheckBox cbPrintClosing = new CheckBox(this);
        cbPrintClosing.setText("🖨️ 關帳時用 HPRT TP805L 印紙本日結單");
        cbPrintClosing.setTextColor(Color.parseColor("#A7F3D0"));
        cbPrintClosing.setChecked(true);
        headerRow.addView(cbPrintClosing, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvFloatLbl = new TextView(this);
        tvFloatLbl.setText("期初零用金$:");
        tvFloatLbl.setTextColor(Color.WHITE);
        tvFloatLbl.setTextSize(13);
        headerRow.addView(tvFloatLbl);

        final EditText etFloat = new EditText(this);
        etFloat.setInputType(InputType.TYPE_CLASS_NUMBER);
        etFloat.setText(String.valueOf(openingFloat));
        etFloat.setTextColor(Color.parseColor("#FCD34D"));
        etFloat.setTextSize(13);
        headerRow.addView(etFloat, new LinearLayout.LayoutParams(dp(75), ViewGroup.LayoutParams.WRAP_CONTENT));

        Button btnCloseDay = new Button(this);
        btnCloseDay.setText("✅ 執行今日關帳 & 匯出CSV & 上傳雲端 (叫號歸零 #001)");
        btnCloseDay.setAllCaps(false);
        btnCloseDay.setTextSize(13);
        btnCloseDay.setTextColor(Color.parseColor("#052E16"));
        btnCloseDay.setTypeface(null, Typeface.BOLD);
        btnCloseDay.setBackground(roundBg(Color.parseColor("#10B981"), 0, 0, 8));
        btnCloseDay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    openingFloat = Integer.parseInt(etFloat.getText().toString().trim());
                    dbHelper.setSetting("opening_float", String.valueOf(openingFloat));
                } catch (Exception ignored) {}

                final List<OrderRecord> batchOrders = new ArrayList<>(ordersHistory);
                final ClosingRecord cr = new ClosingRecord();
                cr.time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TAIWAN).format(new Date());
                cr.validCount = fValid;
                cr.voidCount = fVoid;
                cr.netSales = fNet;
                cr.cashSales = fCash;
                cr.openingFloat = openingFloat;
                cr.byPayJson = new org.json.JSONObject(byPay).toString();
                cr.itemRankJson = new org.json.JSONObject(itemRank).toString();
                cr.printedPaper = cbPrintClosing.isChecked();

                // 1. 匯出 UTF-8 BOM CSV 至 Download/POS_Reports/
                cr.csvPath = dbHelper.exportClosingToCsv(cr, batchOrders, byPay, itemRank);
                cr.cloudStatus = "⏳ CSV已匯出，正在同步雲端...";

                // 2. 寫入 SQLite daily_closings 並將當日訂單標記為已關帳，取餐號歸零 #001
                dbHelper.insertDailyClosing(cr, batchOrders);
                closingHistory.add(0, cr);
                ordersHistory.clear();
                callSequence = 1;
                lastHandoverOrderDbId = 0L;
                shiftStartTime = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TAIWAN).format(new Date());
                dbHelper.setSetting("call_sequence", "1");
                dbHelper.setSetting("last_handover_order_id", "0");
                dbHelper.setSetting("shift_start_time", shiftStartTime);
                if (auditLogEnabled || staffModeEnabled) {
                    dbHelper.insertAuditLog(
                        staffModeEnabled ? currentStaffName : "店長",
                        "DAILY_CLOSING", cr.time,
                        "日結淨營業額 $" + cr.netSales + " (有效 " + cr.validCount + " 筆)");
                }
                tvTopCallNum.setText("取餐號 #001");
                updateOrdersBadge();
                rebuildClosingTab();

                // 3. 若勾選列印紙本日結單，發送至 HPRT TP805L
                if (cr.printedPaper) {
                    PosPrintEngine.printClosingReportAsync(cr, byPay, itemRank,
                        receiptIp, receiptPort, receiptWidthMm, new PosPrintEngine.PrintCallback() {
                            @Override public void onComplete(boolean ok, String msg) {
                                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                            }
                        });
                }

                // 4. 同步上傳至 Google 試算表 (若斷網自動存入待補傳佇列)
                CloudSyncManager.uploadClosingAndOrdersAsync(
                    googleSheetUrl, cr, batchOrders, byPay, itemRank, dbHelper,
                    new CloudSyncManager.SyncCallback() {
                        @Override public void onResult(boolean success, String message) {
                            cr.cloudSynced = success;
                            cr.cloudStatus = message;
                            if (cr.dbId > 0) {
                                dbHelper.updateClosingCloudStatus(cr.dbId, success, message);
                            }
                            rebuildClosingTab();
                        }
                    });

                Toast.makeText(MainActivity.this,
                    "🎉 關帳完成！已匯出 CSV：" + cr.csvPath, Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams lpCloseBtn = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lpCloseBtn.leftMargin = dp(8);
        headerRow.addView(btnCloseDay, lpCloseBtn);

        Button btnOpenDrawer = new Button(this);
        btnOpenDrawer.setText("💰 開錢箱");
        btnOpenDrawer.setAllCaps(false);
        btnOpenDrawer.setTextSize(12);
        btnOpenDrawer.setTextColor(Color.WHITE);
        btnOpenDrawer.setBackground(roundBg(Color.parseColor("#B45309"), 0, 0, 8));
        btnOpenDrawer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!ensureFeatureAllowed(LicenseManager.FEATURE_CASH_DRAWER, "錢櫃 RJ-11 控制")) return;
                authorizeAction("手動開啟錢櫃", false, new Runnable() {
                    @Override public void run() {
                        if (auditLogEnabled || staffModeEnabled) {
                            dbHelper.insertAuditLog(
                                staffModeEnabled ? currentStaffName : "店長",
                                "OPEN_DRAWER", receiptIp, "手動開啟錢櫃");
                        }
                        PosPrintEngine.openCashDrawerAsync(receiptIp, receiptPort, new PosPrintEngine.PrintCallback() {
                            @Override public void onComplete(boolean ok, String msg) {
                                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                });
            }
        });
        LinearLayout.LayoutParams lpDrw = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lpDrw.leftMargin = dp(6);
        headerRow.addView(btnOpenDrawer, lpDrw);
        viewClosing.addView(headerRow);

        TextView tvSummary = new TextView(this);
        tvSummary.setText("📊 當日未關帳營業統計：\n"
            + "• 有效訂單：" + validCount + " 筆 ｜ 作廢退單：" + voidCount + " 筆 ｜ 今日淨營業額：$" + netSales + "\n"
            + "• 現金實收小計：$" + cashSales + " ＋ 期初零用金：$" + openingFloat
            + " ➔ 錢櫃應有總現金：$" + (openingFloat + cashSales) + "\n"
            + "• 各付款別明細：" + (byPay.isEmpty() ? "尚無收款紀錄" : byPay.toString()) + "\n"
            + "• 品項銷售數量：" + (itemRank.isEmpty() ? "尚無銷售紀錄" : itemRank.toString()));
        tvSummary.setTextColor(Color.parseColor("#FCD34D"));
        tvSummary.setTextSize(13);
        tvSummary.setBackground(roundBg(Color.parseColor("#1E293B"), Color.parseColor("#475569"), 1, 8));
        tvSummary.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lpSum = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpSum.topMargin = dp(8);
        viewClosing.addView(tvSummary, lpSum);

        if (shiftHandoverEnabled || auditLogEnabled || staffModeEnabled) {
            LinearLayout shiftBar = new LinearLayout(this);
            shiftBar.setOrientation(LinearLayout.HORIZONTAL);
            shiftBar.setGravity(Gravity.CENTER_VERTICAL);
            shiftBar.setBackground(roundBg(Color.parseColor("#172554"), Color.parseColor("#38BDF8"), 1, 8));
            shiftBar.setPadding(dp(10), dp(6), dp(10), dp(6));
            LinearLayout.LayoutParams lpSh = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpSh.topMargin = dp(6);
            shiftBar.setLayoutParams(lpSh);

            TextView tvShiftInfo = new TextView(this);
            tvShiftInfo.setText((staffModeEnabled ? "👤 當班：" + currentStaffName + " ｜ " : "")
                + "開班時間：" + shiftStartTime);
            tvShiftInfo.setTextColor(Color.parseColor("#E0F2FE"));
            tvShiftInfo.setTextSize(12);
            shiftBar.addView(tvShiftInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (shiftHandoverEnabled) {
                Button btnDoHandover = new Button(this);
                btnDoHandover.setText("🤝 班別交班盤點");
                btnDoHandover.setAllCaps(false);
                btnDoHandover.setTextSize(12);
                btnDoHandover.setTypeface(null, Typeface.BOLD);
                btnDoHandover.setTextColor(Color.parseColor("#052E16"));
                btnDoHandover.setBackground(roundBg(Color.parseColor("#F59E0B"), 0, 0, 6));
                btnDoHandover.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { showShiftHandoverDialog(); }
                });
                LinearLayout.LayoutParams lpHo = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
                lpHo.rightMargin = dp(6);
                shiftBar.addView(btnDoHandover, lpHo);
            }

            Button btnViewLogs = new Button(this);
            btnViewLogs.setText("📜 交班與操作稽核日誌");
            btnViewLogs.setAllCaps(false);
            btnViewLogs.setTextSize(12);
            btnViewLogs.setTextColor(Color.WHITE);
            btnViewLogs.setBackground(roundBg(Color.parseColor("#334155"), 0, 0, 6));
            btnViewLogs.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showShiftAndAuditLogsDialog(); }
            });
            shiftBar.addView(btnViewLogs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
            viewClosing.addView(shiftBar);
        }

        // ==================== 區間營收與餐點排行快速查詢列 ====================
        LinearLayout rangeBar = new LinearLayout(this);
        rangeBar.setOrientation(LinearLayout.HORIZONTAL);
        rangeBar.setGravity(Gravity.CENTER_VERTICAL);
        rangeBar.setBackground(roundBg(Color.parseColor("#062419"), Color.parseColor("#10B981"), 1, 8));
        rangeBar.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams lpRange = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpRange.topMargin = dp(8);
        rangeBar.setLayoutParams(lpRange);

        TextView tvRangeTitle = new TextView(this);
        tvRangeTitle.setText("📈 區間營收&餐點統計：");
        tvRangeTitle.setTextColor(Color.parseColor("#A7F3D0"));
        tvRangeTitle.setTextSize(13);
        tvRangeTitle.setTypeface(null, Typeface.BOLD);
        rangeBar.addView(tvRangeTitle);

        Button btnLast7 = makeMiniTuneBtn("📅 近7天", new View.OnClickListener() {
            @Override public void onClick(View v) {
                showPeriodAnalyticsDialog("近 7 天累計統計", getOffsetDateStr(-6), getOffsetDateStr(0));
            }
        });
        LinearLayout.LayoutParams lpB1 = new LinearLayout.LayoutParams(0, dp(34), 1f);
        lpB1.leftMargin = dp(6);
        rangeBar.addView(btnLast7, lpB1);

        Button btnLast14 = makeMiniTuneBtn("📅 前兩週(近14天)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                showPeriodAnalyticsDialog("前兩週 (近 14 天) 累計統計", getOffsetDateStr(-13), getOffsetDateStr(0));
            }
        });
        LinearLayout.LayoutParams lpB2 = new LinearLayout.LayoutParams(0, dp(34), 1.2f);
        lpB2.leftMargin = dp(6);
        rangeBar.addView(btnLast14, lpB2);

        Button btnThisMonth = makeMiniTuneBtn("📅 本月累計", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String ym = new SimpleDateFormat("yyyy-MM", Locale.TAIWAN).format(new Date());
                showPeriodAnalyticsDialog("本月 (" + ym + ") 累計統計", ym + "-01", ym + "-31");
            }
        });
        LinearLayout.LayoutParams lpB3 = new LinearLayout.LayoutParams(0, dp(34), 1f);
        lpB3.leftMargin = dp(6);
        rangeBar.addView(btnThisMonth, lpB3);

        Button btnLastMonth = makeMiniTuneBtn("📅 上個月", new View.OnClickListener() {
            @Override public void onClick(View v) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.add(java.util.Calendar.MONTH, -1);
                String ym = new SimpleDateFormat("yyyy-MM", Locale.TAIWAN).format(cal.getTime());
                showPeriodAnalyticsDialog("上個月 (" + ym + ") 累計統計", ym + "-01", ym + "-31");
            }
        });
        LinearLayout.LayoutParams lpB4 = new LinearLayout.LayoutParams(0, dp(34), 1f);
        lpB4.leftMargin = dp(6);
        rangeBar.addView(btnLastMonth, lpB4);

        Button btnCustomRange = makeMiniTuneBtn("🔍 指定月份/日期查詢", new View.OnClickListener() {
            @Override public void onClick(View v) {
                showCustomDateRangePickerDialog();
            }
        });
        btnCustomRange.setBackground(roundBg(Color.parseColor("#0284C7"), 0, 0, 6));
        LinearLayout.LayoutParams lpB5 = new LinearLayout.LayoutParams(0, dp(34), 1.3f);
        lpB5.leftMargin = dp(6);
        rangeBar.addView(btnCustomRange, lpB5);

        viewClosing.addView(rangeBar);

        TextView tvHist = new TextView(this);
        tvHist.setText("🗂️ 歷史日結關帳紀錄（點擊可補傳 Google 試算表或補印紙本日結單）：");
        tvHist.setTextColor(Color.WHITE);
        tvHist.setTextSize(14);
        tvHist.setTypeface(null, Typeface.BOLD);
        tvHist.setPadding(0, dp(8), 0, dp(4));
        viewClosing.addView(tvHist);

        ScrollView sv = new ScrollView(this);
        LinearLayout histList = new LinearLayout(this);
        histList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(histList);
        viewClosing.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        for (final ClosingRecord cr : closingHistory) {
            LinearLayout rowBox = new LinearLayout(this);
            rowBox.setOrientation(LinearLayout.HORIZONTAL);
            rowBox.setGravity(Gravity.CENTER_VERTICAL);
            rowBox.setBackground(roundBg(Color.parseColor("#1E293B"), Color.parseColor("#334155"), 1, 6));
            rowBox.setPadding(dp(8), dp(6), dp(8), dp(6));
            LinearLayout.LayoutParams lpR = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpR.bottomMargin = dp(5);
            rowBox.setLayoutParams(lpR);

            TextView tvRow = new TextView(this);
            tvRow.setText(cr.time + " ｜ 有效 " + cr.validCount + " 筆 ｜ 淨營收 $" + cr.netSales
                + " ｜ 應有現金 $" + (cr.openingFloat + cr.cashSales)
                + "\nCSV: " + (cr.csvPath != null && !cr.csvPath.isEmpty() ? cr.csvPath : "已保存於SQLite")
                + " ｜ " + cr.cloudStatus);
            tvRow.setTextColor(Color.parseColor("#A7F3D0"));
            tvRow.setTextSize(12);
            rowBox.addView(tvRow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button btnReSync = new Button(this);
            btnReSync.setText("☁️ 補傳雲端");
            btnReSync.setAllCaps(false);
            btnReSync.setTextSize(11);
            btnReSync.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    CloudSyncManager.uploadClosingAndOrdersAsync(
                        googleSheetUrl, cr, new ArrayList<OrderRecord>(),
                        parseJsonIntMap(cr.byPayJson), parseJsonIntMap(cr.itemRankJson),
                        new CloudSyncManager.SyncCallback() {
                            @Override public void onResult(boolean ok, String msg) {
                                cr.cloudSynced = ok;
                                cr.cloudStatus = msg;
                                if (cr.dbId > 0) dbHelper.updateClosingCloudStatus(cr.dbId, ok, msg);
                                rebuildClosingTab();
                                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                            }
                        });
                }
            });
            rowBox.addView(btnReSync);

            Button btnRePrintClose = new Button(this);
            btnRePrintClose.setText("🖨️ 補印日結");
            btnRePrintClose.setAllCaps(false);
            btnRePrintClose.setTextSize(11);
            btnRePrintClose.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    PosPrintEngine.printClosingReportAsync(
                        cr, parseJsonIntMap(cr.byPayJson), parseJsonIntMap(cr.itemRankJson),
                        receiptIp, receiptPort, receiptWidthMm,
                        new PosPrintEngine.PrintCallback() {
                            @Override public void onComplete(boolean ok, String msg) {
                                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                            }
                        });
                }
            });
            LinearLayout.LayoutParams lpRP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpRP.leftMargin = dp(4);
            rowBox.addView(btnRePrintClose, lpRP);

            histList.addView(rowBox);
        }
    }

    private Map<String, Integer> parseJsonIntMap(String json) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) return map;
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                map.put(k, o.optInt(k, 0));
            }
        } catch (Exception ignored) {}
        return map;
    }

    // ==================== TAB 3: PRINTERS, CLOUD & MENU SETTINGS ====================
    private void rebuildSettingsTab() {
        viewSettings.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(24));
        sv.addView(box);
        viewSettings.addView(sv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 0. 門市品牌白牌設定與無痛資料轉移 (商業化多店通用核心)
        addSectionHeader(box, "🏪 0. 門市品牌、白牌開通設定 & 本機/跨版無痛資料轉移 (Schema v2)");
        LinearLayout brandPanel = new LinearLayout(this);
        brandPanel.setOrientation(LinearLayout.VERTICAL);
        brandPanel.setBackground(roundBg(Color.parseColor("#062419"), Color.parseColor("#10B981"), 1, 8));
        brandPanel.setPadding(dp(10), dp(8), dp(10), dp(8));

        LinearLayout brandRow1 = new LinearLayout(this);
        brandRow1.setOrientation(LinearLayout.HORIZONTAL);
        final EditText etStoreCode = new EditText(this);
        etStoreCode.setHint("門市代碼 (如 STORE-001)");
        etStoreCode.setText(PosPrintEngine.storeCode);
        etStoreCode.setTextColor(Color.parseColor("#FCD34D"));
        etStoreCode.setTextSize(12);
        brandRow1.addView(etStoreCode, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f));

        final EditText etStoreShort = new EditText(this);
        etStoreShort.setHint("簡稱 (頂部列/貼紙底欄)");
        etStoreShort.setText(PosPrintEngine.storeShortName);
        etStoreShort.setTextColor(Color.WHITE);
        etStoreShort.setTextSize(12);
        brandRow1.addView(etStoreShort, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final EditText etStoreName = new EditText(this);
        etStoreName.setHint("完整店名 (收據抬頭/桌牌)");
        etStoreName.setText(PosPrintEngine.storeName);
        etStoreName.setTextColor(Color.WHITE);
        etStoreName.setTextSize(12);
        brandRow1.addView(etStoreName, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f));
        brandPanel.addView(brandRow1);

        LinearLayout brandRow2 = new LinearLayout(this);
        brandRow2.setOrientation(LinearLayout.HORIZONTAL);
        final EditText etStoreSub = new EditText(this);
        etStoreSub.setHint("地址與電話/副標 (如 台北市信義區...｜02-xxxx-xxxx)");
        etStoreSub.setText(PosPrintEngine.storeSubtitle);
        etStoreSub.setTextColor(Color.WHITE);
        etStoreSub.setTextSize(12);
        brandRow2.addView(etStoreSub, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));

        final EditText etStoreFooter = new EditText(this);
        etStoreFooter.setHint("收據頁尾備註 (可選，如 Wi-Fi 密碼)");
        etStoreFooter.setText(PosPrintEngine.storeReceiptFooter);
        etStoreFooter.setTextColor(Color.parseColor("#CBD5E1"));
        etStoreFooter.setTextSize(12);
        brandRow2.addView(etStoreFooter, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        brandPanel.addView(brandRow2);

        LinearLayout brandBtnRow = new LinearLayout(this);
        brandBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnSaveBrand = new Button(this);
        btnSaveBrand.setText("💾 儲存門市品牌設定");
        btnSaveBrand.setAllCaps(false);
        btnSaveBrand.setTextSize(12);
        btnSaveBrand.setTypeface(null, Typeface.BOLD);
        btnSaveBrand.setTextColor(Color.parseColor("#052E16"));
        btnSaveBrand.setBackground(roundBg(Color.parseColor("#10B981"), 0, 0, 6));
        btnSaveBrand.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String sc = etStoreCode.getText().toString().trim();
                String sn = etStoreName.getText().toString().trim();
                String ss = etStoreShort.getText().toString().trim();
                String sub = etStoreSub.getText().toString().trim();
                String ft = etStoreFooter.getText().toString().trim();
                if (sn.isEmpty()) sn = "我的餐飲門市";
                if (ss.isEmpty()) ss = sn;
                if (sc.isEmpty()) sc = "STORE-001";
                PosPrintEngine.storeCode = sc;
                PosPrintEngine.storeName = sn;
                PosPrintEngine.storeShortName = ss;
                PosPrintEngine.storeSubtitle = sub;
                PosPrintEngine.storeReceiptFooter = ft;
                dbHelper.setSetting("store_code", sc);
                dbHelper.setSetting("store_name", sn);
                dbHelper.setSetting("store_short_name", ss);
                dbHelper.setSetting("store_subtitle", sub);
                dbHelper.setSetting("store_receipt_footer", ft);
                dbHelper.setSetting("store_initialized", "1");
                refreshLicenseState();
                updateBrandAndStaffHeader();
                refreshCartUI();
                rebuildSettingsTab();
                Toast.makeText(MainActivity.this, "✅ 已儲存門市品牌：【" + ss + "】", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams lpSb = new LinearLayout.LayoutParams(0, dp(36), 1f);
        lpSb.rightMargin = dp(6);
        brandBtnRow.addView(btnSaveBrand, lpSb);

        Button btnExportLocalSnap = new Button(this);
        btnExportLocalSnap.setText("📤 匯出遷移快照 (.json)");
        btnExportLocalSnap.setAllCaps(false);
        btnExportLocalSnap.setTextSize(12);
        btnExportLocalSnap.setTextColor(Color.WHITE);
        btnExportLocalSnap.setBackground(roundBg(Color.parseColor("#0284C7"), 0, 0, 6));
        btnExportLocalSnap.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                org.json.JSONObject snap = dbHelper.exportFullSnapshotJson(menuItems, closingHistory);
                String path = dbHelper.exportSnapshotToLocalFile(snap);
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("✅ 已匯出標準化遷移快照 (Schema v2)")
                    .setMessage("包含門市設定、完整菜單、桌位排列、員工權限與歷史帳務：\n\n" + path
                        + "\n\n💡 此檔案可直接匯入新平板或未來升級匯入 SaaS 總部平台，達到無痛資料轉移。")
                    .setPositiveButton("好", null)
                    .show();
            }
        });
        LinearLayout.LayoutParams lpEx = new LinearLayout.LayoutParams(0, dp(36), 1f);
        lpEx.rightMargin = dp(6);
        brandBtnRow.addView(btnExportLocalSnap, lpEx);

        Button btnImportLocalSnap = new Button(this);
        btnImportLocalSnap.setText("📥 從本機快照匯入/回復");
        btnImportLocalSnap.setAllCaps(false);
        btnImportLocalSnap.setTextSize(12);
        btnImportLocalSnap.setTextColor(Color.WHITE);
        btnImportLocalSnap.setBackground(roundBg(Color.parseColor("#B45309"), 0, 0, 6));
        btnImportLocalSnap.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showLocalSnapshotImportDialog(); }
        });
        brandBtnRow.addView(btnImportLocalSnap, new LinearLayout.LayoutParams(0, dp(36), 1f));
        brandPanel.addView(brandBtnRow);

        // 商業授權狀態與設備指紋列
        if (licenseState == null) refreshLicenseState();
        LinearLayout licRow = new LinearLayout(this);
        licRow.setOrientation(LinearLayout.HORIZONTAL);
        licRow.setGravity(Gravity.CENTER_VERTICAL);
        licRow.setPadding(0, dp(6), 0, 0);

        TextView tvLicStatus = new TextView(this);
        tvLicStatus.setText("🔑 設備碼：" + licenseState.deviceCode + " ｜ " + licenseState.statusSummary);
        tvLicStatus.setTextColor(licenseState.canCheckout ? Color.parseColor("#A7F3D0") : Color.parseColor("#FCA5A5"));
        tvLicStatus.setTextSize(12);
        licRow.addView(tvLicStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnManageLic = new Button(this);
        btnManageLic.setText("🔑 輸入授權碼 / 方案明細");
        btnManageLic.setAllCaps(false);
        btnManageLic.setTextSize(11);
        btnManageLic.setTextColor(Color.parseColor("#052E16"));
        btnManageLic.setTypeface(null, Typeface.BOLD);
        btnManageLic.setBackground(roundBg(Color.parseColor("#F59E0B"), 0, 0, 6));
        btnManageLic.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showLicenseActivationDialog(null); }
        });
        licRow.addView(btnManageLic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        brandPanel.addView(licRow);
        box.addView(brandPanel);

        // 1. 出單機設定 (支援 Wi-Fi / 藍牙 BT / USB OTG + 字體大小與防裁切邊距)
        addSectionHeader(box, "🧾 1. 出單機設定 (Wi-Fi Port 9100 / 藍牙 BT / USB OTG)");
        LinearLayout recRow = new LinearLayout(this);
        recRow.setOrientation(LinearLayout.HORIZONTAL);
        recRow.setGravity(Gravity.CENTER_VERTICAL);

        final EditText etRecIp = new EditText(this);
        etRecIp.setHint("出單機 IP (如 192.168.0.200)");
        etRecIp.setText(receiptIp);
        etRecIp.setTextColor(Color.WHITE);
        etRecIp.setTextSize(13);
        etRecIp.setVisibility(PosPrintEngine.CONN_WIFI.equals(PosPrintEngine.receiptConnMode) ? View.VISIBLE : View.GONE);
        recRow.addView(etRecIp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnPreviewRecBmp = new Button(this);
        btnPreviewRecBmp.setText("👁️ 預覽出單排版圖");
        btnPreviewRecBmp.setAllCaps(false);
        btnPreviewRecBmp.setTextSize(12);
        btnPreviewRecBmp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                android.graphics.Bitmap bmp = PosPrintEngine.renderReceiptBitmap(PosPrintEngine.createDemoOrder(), receiptWidthMm);
                showBitmapPreviewDialog("🧾 HPRT TP805L 實際點陣預覽 (" + bmp.getWidth() + " dots 寬)", bmp);
            }
        });
        recRow.addView(btnPreviewRecBmp);

        Button btnTestRec = new Button(this);
        btnTestRec.setText("🖨️ 測試印收據");
        btnTestRec.setAllCaps(false);
        btnTestRec.setTextSize(12);
        btnTestRec.setTextColor(Color.parseColor("#052E16"));
        btnTestRec.setTypeface(null, Typeface.BOLD);
        btnTestRec.setBackground(roundBg(Color.parseColor("#38BDF8"), 0, 0, 6));
        btnTestRec.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                receiptIp = etRecIp.getText().toString().trim();
                dbHelper.setSetting("receipt_ip", receiptIp);
                String target = PosPrintEngine.formatTarget(
                    PosPrintEngine.receiptConnMode, receiptIp, PosPrintEngine.receiptBtMac, PosPrintEngine.receiptUsbId);
                Toast.makeText(MainActivity.this, "連線測試出單機 (" + target + ")...", Toast.LENGTH_SHORT).show();
                PosPrintEngine.printTestReceiptAsync(receiptIp, receiptPort, receiptWidthMm,
                    new PosPrintEngine.PrintCallback() {
                        @Override public void onComplete(boolean ok, String msg) {
                            refreshPrinterStatusBadge();
                            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                        }
                    });
            }
        });
        LinearLayout.LayoutParams lpTestRec = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        lpTestRec.leftMargin = dp(6);
        recRow.addView(btnTestRec, lpTestRec);
        box.addView(buildPrinterTransportPanel(true, etRecIp));
        box.addView(recRow);

        // 出單機【字體大小】與【防裁切邊距/有效點寬】微調面板
        LinearLayout recTunePanel = new LinearLayout(this);
        recTunePanel.setOrientation(LinearLayout.VERTICAL);
        recTunePanel.setBackground(roundBg(Color.parseColor("#1E293B"), Color.parseColor("#334155"), 1, 8));
        recTunePanel.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lpTune = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpTune.topMargin = dp(4);
        lpTune.bottomMargin = dp(6);
        recTunePanel.setLayoutParams(lpTune);

        // (A) 字體大小調整列
        LinearLayout fontRow = new LinearLayout(this);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);
        fontRow.setGravity(Gravity.CENTER_VERTICAL);

        final TextView tvFontVal = new TextView(this);
        tvFontVal.setText("🔤 出單字體大小：" + PosPrintEngine.receiptFontScalePercent + "%");
        tvFontVal.setTextColor(Color.parseColor("#FCD34D"));
        tvFontVal.setTextSize(13);
        tvFontVal.setTypeface(null, Typeface.BOLD);
        fontRow.addView(tvFontVal, new LinearLayout.LayoutParams(dp(175), ViewGroup.LayoutParams.WRAP_CONTENT));

        final int[] fontPresets = {100, 125, 145, 165, 185, 210};
        final String[] fontLabels = {"標準100%", "適中125%", "加大145%", "特大165%", "超大185%", "巨字210%"};
        for (int i = 0; i < fontPresets.length; i++) {
            final int fVal = fontPresets[i];
            Button bFont = new Button(this);
            bFont.setText(fontLabels[i]);
            bFont.setAllCaps(false);
            bFont.setTextSize(11);
            bFont.setPadding(dp(4), dp(2), dp(4), dp(2));
            boolean isCur = (PosPrintEngine.receiptFontScalePercent == fVal);
            bFont.setTextColor(isCur ? Color.parseColor("#052E16") : Color.WHITE);
            bFont.setBackground(roundBg(
                isCur ? Color.parseColor("#10B981") : Color.parseColor("#334155"), 0, 0, 6));
            bFont.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    PosPrintEngine.receiptFontScalePercent = fVal;
                    dbHelper.setSetting("receipt_font_scale", String.valueOf(fVal));
                    rebuildSettingsTab();
                }
            });
            LinearLayout.LayoutParams lpF = new LinearLayout.LayoutParams(0, dp(34), 1f);
            lpF.leftMargin = dp(3);
            fontRow.addView(bFont, lpF);
        }
        recTunePanel.addView(fontRow);

        // (B) 有效列印寬度選擇列 (解決右邊緣被裁切問題)
        LinearLayout widthRow = new LinearLayout(this);
        widthRow.setOrientation(LinearLayout.HORIZONTAL);
        widthRow.setGravity(Gravity.CENTER_VERTICAL);
        widthRow.setPadding(0, dp(6), 0, 0);

        TextView tvWidthLbl = new TextView(this);
        tvWidthLbl.setText("📐 紙張可印寬度：" + PosPrintEngine.receiptPrintableDots + "點");
        tvWidthLbl.setTextColor(Color.parseColor("#93C5FD"));
        tvWidthLbl.setTextSize(13);
        tvWidthLbl.setTypeface(null, Typeface.BOLD);
        widthRow.addView(tvWidthLbl, new LinearLayout.LayoutParams(dp(175), ViewGroup.LayoutParams.WRAP_CONTENT));

        final int[] dotPresets = {352, 384, 480, 512, 544, 576};
        final String[] dotLabels = {
            "58窄(352)", "58標(384)", "80窄安(480)", "80防切(512)", "80寬(544)", "80滿(576)"
        };
        for (int i = 0; i < dotPresets.length; i++) {
            final int dVal = dotPresets[i];
            Button bDot = new Button(this);
            bDot.setText(dotLabels[i]);
            bDot.setAllCaps(false);
            bDot.setTextSize(11);
            bDot.setPadding(dp(4), dp(2), dp(4), dp(2));
            boolean isCur = (PosPrintEngine.receiptPrintableDots == dVal);
            bDot.setTextColor(isCur ? Color.parseColor("#052E16") : Color.WHITE);
            bDot.setBackground(roundBg(
                isCur ? Color.parseColor("#38BDF8") : Color.parseColor("#334155"), 0, 0, 6));
            bDot.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    PosPrintEngine.receiptPrintableDots = dVal;
                    receiptWidthMm = (dVal <= 400) ? 58 : 80;
                    dbHelper.setSetting("receipt_printable_dots", String.valueOf(dVal));
                    dbHelper.setSetting("receipt_width_mm", String.valueOf(receiptWidthMm));
                    rebuildSettingsTab();
                }
            });
            LinearLayout.LayoutParams lpD = new LinearLayout.LayoutParams(0, dp(34), 1f);
            lpD.leftMargin = dp(3);
            widthRow.addView(bDot, lpD);
        }
        recTunePanel.addView(widthRow);

        // (C) 左右安全邊距微調列 (若左邊被切調大左邊距，若右邊被切調大右邊距)
        LinearLayout marginRow = new LinearLayout(this);
        marginRow.setOrientation(LinearLayout.HORIZONTAL);
        marginRow.setGravity(Gravity.CENTER_VERTICAL);
        marginRow.setPadding(0, dp(6), 0, 0);

        TextView tvMarginInfo = new TextView(this);
        tvMarginInfo.setText("🛡️ 邊緣防裁切留白 ➔ 左邊距：" + PosPrintEngine.receiptLeftMarginDots
            + "點 ｜ 右內縮：" + PosPrintEngine.receiptRightMarginDots + "點");
        tvMarginInfo.setTextColor(Color.parseColor("#A7F3D0"));
        tvMarginInfo.setTextSize(12);
        marginRow.addView(tvMarginInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));

        Button btnLeftMinus = makeMiniTuneBtn("左邊距 -8", new View.OnClickListener() {
            @Override public void onClick(View v) {
                PosPrintEngine.receiptLeftMarginDots = Math.max(0, PosPrintEngine.receiptLeftMarginDots - 8);
                dbHelper.setSetting("receipt_left_margin", String.valueOf(PosPrintEngine.receiptLeftMarginDots));
                rebuildSettingsTab();
            }
        });
        marginRow.addView(btnLeftMinus, new LinearLayout.LayoutParams(0, dp(32), 0.45f));

        Button btnLeftPlus = makeMiniTuneBtn("左邊距 +8(往右移)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                PosPrintEngine.receiptLeftMarginDots = Math.min(96, PosPrintEngine.receiptLeftMarginDots + 8);
                dbHelper.setSetting("receipt_left_margin", String.valueOf(PosPrintEngine.receiptLeftMarginDots));
                rebuildSettingsTab();
            }
        });
        marginRow.addView(btnLeftPlus, new LinearLayout.LayoutParams(0, dp(32), 0.65f));

        Button btnRightMinus = makeMiniTuneBtn("右內縮 -8", new View.OnClickListener() {
            @Override public void onClick(View v) {
                PosPrintEngine.receiptRightMarginDots = Math.max(8, PosPrintEngine.receiptRightMarginDots - 8);
                dbHelper.setSetting("receipt_right_margin", String.valueOf(PosPrintEngine.receiptRightMarginDots));
                rebuildSettingsTab();
            }
        });
        marginRow.addView(btnRightMinus, new LinearLayout.LayoutParams(0, dp(32), 0.45f));

        Button btnRightPlus = makeMiniTuneBtn("右內縮 +8(防右切)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                PosPrintEngine.receiptRightMarginDots = Math.min(120, PosPrintEngine.receiptRightMarginDots + 8);
                dbHelper.setSetting("receipt_right_margin", String.valueOf(PosPrintEngine.receiptRightMarginDots));
                rebuildSettingsTab();
            }
        });
        marginRow.addView(btnRightPlus, new LinearLayout.LayoutParams(0, dp(32), 0.65f));

        recTunePanel.addView(marginRow);

        // (D) 錢櫃 RJ-11 連動彈開設定 (透過出單機 ESC/POS ESC p 脈衝)
        LinearLayout drawerRow = new LinearLayout(this);
        drawerRow.setOrientation(LinearLayout.HORIZONTAL);
        drawerRow.setGravity(Gravity.CENTER_VERTICAL);
        drawerRow.setPadding(0, dp(6), 0, 0);

        Button btnToggleAutoDrawer = makeMiniTuneBtn(
            PosPrintEngine.autoOpenCashDrawerOnCash
                ? "💰 現金結帳自動彈開錢櫃：✅ 已開啟"
                : "💰 現金結帳自動彈開錢櫃：⚪ 已關閉",
            new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!ensureFeatureAllowed(LicenseManager.FEATURE_CASH_DRAWER, "錢櫃 RJ-11 控制")) return;
                    PosPrintEngine.autoOpenCashDrawerOnCash = !PosPrintEngine.autoOpenCashDrawerOnCash;
                    dbHelper.setSetting("auto_open_cash_drawer", PosPrintEngine.autoOpenCashDrawerOnCash ? "1" : "0");
                    rebuildSettingsTab();
                }
            });
        if (PosPrintEngine.autoOpenCashDrawerOnCash) {
            btnToggleAutoDrawer.setTextColor(Color.parseColor("#052E16"));
            btnToggleAutoDrawer.setBackground(roundBg(Color.parseColor("#10B981"), 0, 0, 6));
        }
        LinearLayout.LayoutParams lpAd = new LinearLayout.LayoutParams(0, dp(34), 1.4f);
        lpAd.rightMargin = dp(6);
        drawerRow.addView(btnToggleAutoDrawer, lpAd);

        Button btnTestDrawer = makeMiniTuneBtn("🔓 測試彈開錢箱 (RJ-11)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!ensureFeatureAllowed(LicenseManager.FEATURE_CASH_DRAWER, "錢櫃 RJ-11 控制")) return;
                PosPrintEngine.openCashDrawerAsync(receiptIp, receiptPort, new PosPrintEngine.PrintCallback() {
                    @Override public void onComplete(boolean ok, String msg) {
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
        drawerRow.addView(btnTestDrawer, new LinearLayout.LayoutParams(0, dp(34), 1f));
        recTunePanel.addView(drawerRow);

        box.addView(recTunePanel);

        // 2. 貼紙機設定與連線測試 (Wi-Fi / 藍牙 BT / USB OTG)
        addSectionHeader(box, "🏷️ 2. 貼紙機設定 (40×30mm 點陣防亂碼：Wi-Fi Port 9100 / 藍牙 BT / USB OTG)");
        LinearLayout labRow = new LinearLayout(this);
        labRow.setOrientation(LinearLayout.HORIZONTAL);
        labRow.setGravity(Gravity.CENTER_VERTICAL);

        final EditText etLabIp = new EditText(this);
        etLabIp.setHint("貼紙機 IP (如 192.168.0.201)");
        etLabIp.setText(labelIp);
        etLabIp.setTextColor(Color.WHITE);
        etLabIp.setTextSize(13);
        etLabIp.setVisibility(PosPrintEngine.CONN_WIFI.equals(PosPrintEngine.labelConnMode) ? View.VISIBLE : View.GONE);
        labRow.addView(etLabIp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Button btnToggleProto = new Button(this);
        btnToggleProto.setText("指令：" + labelProtocol);
        btnToggleProto.setAllCaps(false);
        btnToggleProto.setTextSize(12);
        btnToggleProto.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                labelProtocol = "EZPL".equals(labelProtocol) ? "TSPL" : "EZPL";
                dbHelper.setSetting("label_protocol", labelProtocol);
                btnToggleProto.setText("指令：" + labelProtocol);
            }
        });
        labRow.addView(btnToggleProto);

        Button btnToggleLblFont = new Button(this);
        btnToggleLblFont.setText("貼紙字體：" + PosPrintEngine.labelFontScalePercent + "%");
        btnToggleLblFont.setAllCaps(false);
        btnToggleLblFont.setTextSize(12);
        btnToggleLblFont.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (PosPrintEngine.labelFontScalePercent == 100) PosPrintEngine.labelFontScalePercent = 115;
                else if (PosPrintEngine.labelFontScalePercent == 115) PosPrintEngine.labelFontScalePercent = 130;
                else PosPrintEngine.labelFontScalePercent = 100;
                dbHelper.setSetting("label_font_scale", String.valueOf(PosPrintEngine.labelFontScalePercent));
                rebuildSettingsTab();
            }
        });
        labRow.addView(btnToggleLblFont);

        Button btnPreviewLblBmp = new Button(this);
        btnPreviewLblBmp.setText("👁️ 預覽貼紙圖");
        btnPreviewLblBmp.setAllCaps(false);
        btnPreviewLblBmp.setTextSize(12);
        btnPreviewLblBmp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                android.graphics.Bitmap bmp = PosPrintEngine.renderLabelBitmap40x30(
                    "#001", "外帶", 1, 1, "▲檸檬冬瓜", "▲甜度固定 / 3分微冰 / +寒天愛玉($15)", "12:30", 65);
                showBitmapPreviewDialog("🏷️ GoDEX DT2x 40×30mm (320×240 dots) 實際點陣預覽", bmp);
            }
        });
        labRow.addView(btnPreviewLblBmp);

        Button btnTestLab = new Button(this);
        btnTestLab.setText("🏷️ 測試印貼紙");
        btnTestLab.setAllCaps(false);
        btnTestLab.setTextSize(12);
        btnTestLab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                labelIp = etLabIp.getText().toString().trim();
                dbHelper.setSetting("label_ip", labelIp);
                String target = PosPrintEngine.formatTarget(
                    PosPrintEngine.labelConnMode, labelIp, PosPrintEngine.labelBtMac, PosPrintEngine.labelUsbId);
                Toast.makeText(MainActivity.this, "連線測試貼紙機 (" + target + ")...", Toast.LENGTH_SHORT).show();
                PosPrintEngine.printTestLabelAsync(labelIp, labelPort, labelProtocol,
                    new PosPrintEngine.PrintCallback() {
                        @Override public void onComplete(boolean ok, String msg) {
                            refreshPrinterStatusBadge();
                            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                        }
                    });
            }
        });
        labRow.addView(btnTestLab);
        box.addView(buildPrinterTransportPanel(false, etLabIp));
        box.addView(labRow);

        Button btnDiscoverPrinters = new Button(this);
        btnDiscoverPrinters.setText("🔍 區網自動搜尋印表機 IP（不怕路由器重開機跳號）");
        btnDiscoverPrinters.setAllCaps(false);
        btnDiscoverPrinters.setTextSize(12);
        btnDiscoverPrinters.setTypeface(null, Typeface.BOLD);
        btnDiscoverPrinters.setTextColor(Color.WHITE);
        btnDiscoverPrinters.setBackground(roundBg(Color.parseColor("#0284C7"), Color.parseColor("#38BDF8"), 1, 8));
        btnDiscoverPrinters.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPrinterDiscoveryDialog(); }
        });
        LinearLayout.LayoutParams lpDiscPrn = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        lpDiscPrn.topMargin = dp(6);
        box.addView(btnDiscoverPrinters, lpDiscPrn);

        // 3. Google 試算表雲端設定與雙向同步
        addSectionHeader(box, "☁️ 3. Google 試算表 (Apps Script Web App) 雲端資料庫網址");
        final EditText etSheet = new EditText(this);
        etSheet.setHint("https://script.google.com/macros/s/.../exec");
        etSheet.setText(googleSheetUrl);
        etSheet.setTextColor(Color.WHITE);
        etSheet.setHintTextColor(Color.parseColor("#94A3B8"));
        etSheet.setTextSize(13);
        box.addView(etSheet);

        LinearLayout cloudBtnRow = new LinearLayout(this);
        cloudBtnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnSaveCfg = new Button(this);
        btnSaveCfg.setText("💾 儲存印表機通道與雲端網址");
        btnSaveCfg.setAllCaps(false);
        btnSaveCfg.setTextSize(12);
        btnSaveCfg.setTextColor(Color.parseColor("#052E16"));
        btnSaveCfg.setTypeface(null, Typeface.BOLD);
        btnSaveCfg.setBackground(roundBg(Color.parseColor("#10B981"), 0, 0, 8));
        btnSaveCfg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                receiptIp = etRecIp.getText().toString().trim();
                labelIp = etLabIp.getText().toString().trim();
                googleSheetUrl = etSheet.getText().toString().trim();
                dbHelper.setSetting("receipt_ip", receiptIp);
                dbHelper.setSetting("label_ip", labelIp);
                dbHelper.setSetting("google_sheet_url", googleSheetUrl);
                refreshPrinterStatusBadge();
                Toast.makeText(MainActivity.this, "✅ 已永久保存於平板 SQLite 資料庫！", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams lpSave = new LinearLayout.LayoutParams(0, dp(38), 1f);
        lpSave.rightMargin = dp(6);
        cloudBtnRow.addView(btnSaveCfg, lpSave);

        final Button btnUploadMenu = new Button(this);
        btnUploadMenu.setText("⬆️ 上傳菜單備份至試算表");
        btnUploadMenu.setAllCaps(false);
        btnUploadMenu.setTextSize(12);
        btnUploadMenu.setTextColor(Color.WHITE);
        btnUploadMenu.setBackground(roundBg(Color.parseColor("#0284C7"), 0, 0, 8));
        btnUploadMenu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                googleSheetUrl = etSheet.getText().toString().trim();
                dbHelper.setSetting("google_sheet_url", googleSheetUrl);
                if (googleSheetUrl.isEmpty()) {
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("⚠️ 尚未填寫試算表網址")
                        .setMessage("請先在上方網址欄貼上 Google Apps Script Web App 網址 (/exec 結尾) 再按上傳！")
                        .setPositiveButton("好", null)
                        .show();
                    return;
                }
                btnUploadMenu.setText("⏳ 正在上傳 " + menuItems.size() + " 道菜單...");
                btnUploadMenu.setEnabled(false);
                Toast.makeText(MainActivity.this, "⏳ 正在上傳菜單至 Google 試算表，請稍候 3~5 秒...", Toast.LENGTH_SHORT).show();
                CloudSyncManager.uploadMenuAsync(googleSheetUrl, menuItems, new CloudSyncManager.SyncCallback() {
                    @Override public void onResult(boolean ok, String msg) {
                        btnUploadMenu.setText("⬆️ 上傳菜單備份至試算表");
                        btnUploadMenu.setEnabled(true);
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle(ok ? "✅ 菜單上傳成功！" : "❌ 菜單上傳失敗")
                            .setMessage(msg + (ok
                                ? "\n\n🎉 雲端「菜單設定」分頁已建立完成！現在客人掃描各桌 QR Code 即可看到完整 " + menuItems.size() + " 道餐點與飲品！"
                                : "\n\n請確認平板已連上店內 Wi-Fi 且網址正確。"))
                            .setPositiveButton("📱 開啟各桌 QR Code 條碼", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    showQrOrdersAndStandeeDialog();
                                }
                            })
                            .setNegativeButton("確定", null)
                            .show();
                    }
                });
            }
        });
        LinearLayout.LayoutParams lpUp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        lpUp.rightMargin = dp(6);
        cloudBtnRow.addView(btnUploadMenu, lpUp);

        final Button btnDownloadMenu = new Button(this);
        btnDownloadMenu.setText("⬇️ 從試算表下載更新菜單");
        btnDownloadMenu.setAllCaps(false);
        btnDownloadMenu.setTextSize(12);
        btnDownloadMenu.setTextColor(Color.WHITE);
        btnDownloadMenu.setBackground(roundBg(Color.parseColor("#7C3AED"), 0, 0, 8));
        btnDownloadMenu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                googleSheetUrl = etSheet.getText().toString().trim();
                dbHelper.setSetting("google_sheet_url", googleSheetUrl);
                btnDownloadMenu.setText("⏳ 正在從試算表下載...");
                btnDownloadMenu.setEnabled(false);
                CloudSyncManager.downloadMenuAsync(googleSheetUrl, new CloudSyncManager.MenuDownloadCallback() {
                    @Override public void onResult(boolean ok, String msg, List<MenuItem> downloaded) {
                        btnDownloadMenu.setText("⬇️ 從試算表下載更新菜單");
                        btnDownloadMenu.setEnabled(true);
                        if (ok && downloaded != null && !downloaded.isEmpty()) {
                            menuItems.clear();
                            menuItems.addAll(downloaded);
                            rebuildMealDrinkPool();
                            dbHelper.saveMenuList(menuItems);
                            refreshAllPosViews();
                            rebuildSettingsTab();
                        }
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle(ok ? "✅ 下載更新菜單成功" : "⚠️ 下載菜單提示")
                            .setMessage(msg)
                            .setPositiveButton("確定", null)
                            .show();
                    }
                });
            }
        });
        cloudBtnRow.addView(btnDownloadMenu, new LinearLayout.LayoutParams(0, dp(38), 1f));
        box.addView(cloudBtnRow);

        Button btnOpenQrCenterInSettings = new Button(this);
        btnOpenQrCenterInSettings.setText("📱 點此開啟：【各桌掃碼點餐 QR Code 條碼 & 出單機印桌牌 / 線上待接單中心】");
        btnOpenQrCenterInSettings.setAllCaps(false);
        btnOpenQrCenterInSettings.setTextSize(13);
        btnOpenQrCenterInSettings.setTypeface(null, Typeface.BOLD);
        btnOpenQrCenterInSettings.setTextColor(Color.parseColor("#052E16"));
        btnOpenQrCenterInSettings.setBackground(roundBg(Color.parseColor("#F59E0B"), Color.WHITE, 1, 8));
        btnOpenQrCenterInSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showQrOrdersAndStandeeDialog();
            }
        });
        LinearLayout.LayoutParams lpQrSet = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        lpQrSet.topMargin = dp(8);
        box.addView(btnOpenQrCenterInSettings, lpQrSet);

        // 3C. 台灣電子發票與多元支付閘道設定 (LINE Pay / 街口 / TWQR / 加值中心預留)
        addSectionHeader(box, "💳 3C. 台灣電子發票與多元支付閘道設定 (LINE Pay / 街口 / TWQR / 加值中心預留)");
        LinearLayout twPayPanel = new LinearLayout(this);
        twPayPanel.setOrientation(LinearLayout.VERTICAL);
        twPayPanel.setBackground(roundBg(Color.parseColor("#1E293B"), Color.parseColor("#334155"), 1, 8));
        twPayPanel.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView tvTwPaySummary = new TextView(this);
        String invSummary = TaiwanCommerceGateway.invoiceCfg.enabled
            ? ("✅ 已啟用 ("
                + (TaiwanCommerceGateway.INV_MODE_WEBHOOK.equals(TaiwanCommerceGateway.invoiceCfg.mode) ? "加值中心中繼" : "本機模擬字軌")
                + (TaiwanCommerceGateway.invoiceCfg.sellerTaxId.isEmpty() ? "" : "｜統編 " + TaiwanCommerceGateway.invoiceCfg.sellerTaxId)
                + ")")
            : "⚪ 未啟用（免用統一發票小店適用）";
        String paySummary = "LINE Pay[" + TaiwanCommerceGateway.paymentCfg.linePayMode
            + "] ｜ 街口[" + TaiwanCommerceGateway.paymentCfg.jkoPayMode
            + "] ｜ TWQR[" + (TaiwanCommerceGateway.paymentCfg.twqrEnabled ? "ON" : "OFF") + "]";
        tvTwPaySummary.setText("🧾 電子發票：" + invSummary + "\n📱 多元支付：" + paySummary);
        tvTwPaySummary.setTextColor(Color.parseColor("#E2E8F0"));
        tvTwPaySummary.setTextSize(12);
        tvTwPaySummary.setPadding(0, 0, 0, dp(6));
        twPayPanel.addView(tvTwPaySummary);

        LinearLayout twBtnRow = new LinearLayout(this);
        twBtnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnCfgEinvoice = makeMiniTuneBtn("🧾 設定台灣電子發票 / 統編 / 加值中心", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!ensureFeatureAllowed(LicenseManager.FEATURE_EINVOICE, "台灣電子發票整合")) return;
                showEinvoiceSettingsDialog();
            }
        });
        btnCfgEinvoice.setBackground(roundBg(Color.parseColor("#0F766E"), 0, 0, 6));
        LinearLayout.LayoutParams lpEi = new LinearLayout.LayoutParams(0, dp(36), 1f);
        lpEi.rightMargin = dp(6);
        twBtnRow.addView(btnCfgEinvoice, lpEi);

        Button btnCfgMultiPay = makeMiniTuneBtn("📱 設定 LINE Pay / 街口 / TWQR", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!ensureFeatureAllowed(LicenseManager.FEATURE_MULTI_PAY, "多元電子支付閘道")) return;
                showMultiPaymentSettingsDialog();
            }
        });
        btnCfgMultiPay.setBackground(roundBg(Color.parseColor("#1D4ED8"), 0, 0, 6));
        twBtnRow.addView(btnCfgMultiPay, new LinearLayout.LayoutParams(0, dp(36), 1f));
        twPayPanel.addView(twBtnRow);
        box.addView(twPayPanel);

        // 3B. 店家私人 Google Drive 完整備份 / 一鍵回復系統 / 系統 OTA 更新 / 店長 PIN 碼
        addSectionHeader(box, "🛡️ 3B. 店家雲端硬碟備份、一鍵回復系統、OTA 遠端更新與權限鎖");

        LinearLayout drRow = new LinearLayout(this);
        drRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnFullBackup = new Button(this);
        btnFullBackup.setText("☁️ 完整備份至店家 Google 雲端硬碟");
        btnFullBackup.setAllCaps(false);
        btnFullBackup.setTextSize(12);
        btnFullBackup.setTypeface(null, Typeface.BOLD);
        btnFullBackup.setTextColor(Color.WHITE);
        btnFullBackup.setBackground(roundBg(Color.parseColor("#0369A1"), 0, 0, 8));
        btnFullBackup.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                googleSheetUrl = etSheet.getText().toString().trim();
                dbHelper.setSetting("google_sheet_url", googleSheetUrl);
                triggerCloudFullBackup();
            }
        });
        LinearLayout.LayoutParams lpBk = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lpBk.rightMargin = dp(6);
        drRow.addView(btnFullBackup, lpBk);

        Button btnFullRestore = new Button(this);
        btnFullRestore.setText("♻️ 從雲端一鍵回復系統 (換機用)");
        btnFullRestore.setAllCaps(false);
        btnFullRestore.setTextSize(12);
        btnFullRestore.setTypeface(null, Typeface.BOLD);
        btnFullRestore.setTextColor(Color.WHITE);
        btnFullRestore.setBackground(roundBg(Color.parseColor("#B45309"), 0, 0, 8));
        btnFullRestore.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                googleSheetUrl = etSheet.getText().toString().trim();
                dbHelper.setSetting("google_sheet_url", googleSheetUrl);
                triggerCloudRestore();
            }
        });
        drRow.addView(btnFullRestore, new LinearLayout.LayoutParams(0, dp(40), 1f));
        box.addView(drRow);

        final EditText etOtaUrl = new EditText(this);
        etOtaUrl.setHint("系統 OTA 更新清單網址 (version.json)");
        etOtaUrl.setText(otaManifestUrl);
        etOtaUrl.setTextColor(Color.parseColor("#CBD5E1"));
        etOtaUrl.setTextSize(12);
        box.addView(etOtaUrl);

        LinearLayout otaRow = new LinearLayout(this);
        otaRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnCheckOta = new Button(this);
        btnCheckOta.setText("🔄 檢查系統遠端更新 (目前 v" + getAppVersionName() + ")");
        btnCheckOta.setAllCaps(false);
        btnCheckOta.setTextSize(12);
        btnCheckOta.setTypeface(null, Typeface.BOLD);
        btnCheckOta.setTextColor(Color.parseColor("#052E16"));
        btnCheckOta.setBackground(roundBg(Color.parseColor("#34D399"), 0, 0, 8));
        btnCheckOta.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                otaManifestUrl = etOtaUrl.getText().toString().trim();
                dbHelper.setSetting("ota_manifest_url", otaManifestUrl);
                showOtaUpdateDialog();
            }
        });
        LinearLayout.LayoutParams lpOta = new LinearLayout.LayoutParams(0, dp(38), 1.3f);
        lpOta.rightMargin = dp(6);
        otaRow.addView(btnCheckOta, lpOta);

        Button btnPinLock = new Button(this);
        btnPinLock.setText(managerPin.isEmpty() ? "🔓 店長 PIN 碼：未啟用" : "🔐 店長 PIN 碼：已啟用");
        btnPinLock.setAllCaps(false);
        btnPinLock.setTextSize(12);
        btnPinLock.setTextColor(Color.WHITE);
        btnPinLock.setBackground(roundBg(Color.parseColor("#475569"), 0, 0, 8));
        btnPinLock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSetManagerPinDialog(); }
        });
        LinearLayout.LayoutParams lpPin = new LinearLayout.LayoutParams(0, dp(38), 1f);
        lpPin.rightMargin = dp(6);
        otaRow.addView(btnPinLock, lpPin);

        Button btnTechLogs = new Button(this);
        btnTechLogs.setText("🩺 技術日誌");
        btnTechLogs.setAllCaps(false);
        btnTechLogs.setTextSize(12);
        btnTechLogs.setTextColor(Color.WHITE);
        btnTechLogs.setBackground(roundBg(Color.parseColor("#334155"), 0, 0, 8));
        btnTechLogs.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTechLogsDialog(); }
        });
        otaRow.addView(btnTechLogs, new LinearLayout.LayoutParams(dp(95), dp(38)));
        box.addView(otaRow);

        // 3C. 彈性員工權限、交班盤點與操作稽核設定 (可依 1~3 人小店 vs 多人門市自行決定細度)
        addSectionHeader(box, "👥 3C. 員工權限分級、交班盤點與稽核日誌（可自由決定管理細度）");
        LinearLayout staffPanel = new LinearLayout(this);
        staffPanel.setOrientation(LinearLayout.VERTICAL);
        staffPanel.setBackground(roundBg(Color.parseColor("#1E293B"), Color.parseColor("#475569"), 1, 8));
        staffPanel.setPadding(dp(10), dp(8), dp(10), dp(8));

        LinearLayout toggleRow = new LinearLayout(this);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnToggleStaffMode = new Button(this);
        btnToggleStaffMode.setText(staffModeEnabled ? "✅ 人員切換：多員工分權" : "🟢 人員切換：極簡共用 (免切換)");
        btnToggleStaffMode.setAllCaps(false);
        btnToggleStaffMode.setTextSize(11);
        btnToggleStaffMode.setTextColor(staffModeEnabled ? Color.parseColor("#052E16") : Color.WHITE);
        btnToggleStaffMode.setBackground(roundBg(
            staffModeEnabled ? Color.parseColor("#38BDF8") : Color.parseColor("#334155"), 0, 0, 6));
        btnToggleStaffMode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!staffModeEnabled && !ensureFeatureAllowed(LicenseManager.FEATURE_STAFF, "多員工權限與交班管理")) return;
                staffModeEnabled = !staffModeEnabled;
                dbHelper.setSetting("staff_mode_enabled", staffModeEnabled ? "1" : "0");
                if (staffModeEnabled) {
                    dbHelper.seedDefaultStaffIfEmpty(managerPin);
                    staffMembers.clear();
                    staffMembers.addAll(dbHelper.loadStaffMembers());
                }
                updateBrandAndStaffHeader();
                rebuildSettingsTab();
            }
        });
        LinearLayout.LayoutParams lpSm = new LinearLayout.LayoutParams(0, dp(36), 1f);
        lpSm.rightMargin = dp(6);
        toggleRow.addView(btnToggleStaffMode, lpSm);

        Button btnToggleHandover = new Button(this);
        btnToggleHandover.setText(shiftHandoverEnabled ? "✅ 交班盤點：已啟用" : "⚪ 交班盤點：關閉 (僅日結)");
        btnToggleHandover.setAllCaps(false);
        btnToggleHandover.setTextSize(11);
        btnToggleHandover.setTextColor(shiftHandoverEnabled ? Color.parseColor("#052E16") : Color.WHITE);
        btnToggleHandover.setBackground(roundBg(
            shiftHandoverEnabled ? Color.parseColor("#F59E0B") : Color.parseColor("#334155"), 0, 0, 6));
        btnToggleHandover.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!shiftHandoverEnabled && !ensureFeatureAllowed(LicenseManager.FEATURE_STAFF, "多員工權限與交班管理")) return;
                shiftHandoverEnabled = !shiftHandoverEnabled;
                dbHelper.setSetting("shift_handover_enabled", shiftHandoverEnabled ? "1" : "0");
                rebuildClosingTab();
                rebuildSettingsTab();
            }
        });
        LinearLayout.LayoutParams lpHoSet = new LinearLayout.LayoutParams(0, dp(36), 1f);
        lpHoSet.rightMargin = dp(6);
        toggleRow.addView(btnToggleHandover, lpHoSet);

        Button btnToggleAudit = new Button(this);
        btnToggleAudit.setText(auditLogEnabled ? "✅ 操作稽核：已啟用" : "⚪ 操作稽核：關閉");
        btnToggleAudit.setAllCaps(false);
        btnToggleAudit.setTextSize(11);
        btnToggleAudit.setTextColor(auditLogEnabled ? Color.parseColor("#052E16") : Color.WHITE);
        btnToggleAudit.setBackground(roundBg(
            auditLogEnabled ? Color.parseColor("#34D399") : Color.parseColor("#334155"), 0, 0, 6));
        btnToggleAudit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                auditLogEnabled = !auditLogEnabled;
                dbHelper.setSetting("audit_log_enabled", auditLogEnabled ? "1" : "0");
                rebuildClosingTab();
                rebuildSettingsTab();
            }
        });
        toggleRow.addView(btnToggleAudit, new LinearLayout.LayoutParams(0, dp(36), 1f));
        staffPanel.addView(toggleRow);

        TextView tvStaffHint = new TextView(this);
        tvStaffHint.setText("💡 1~3 人小店可維持「極簡共用」零負擔；多員工門市可自由開啟人員切換、交班對帳與退單/折扣稽核。");
        tvStaffHint.setTextColor(Color.parseColor("#94A3B8"));
        tvStaffHint.setTextSize(11);
        tvStaffHint.setPadding(0, dp(6), 0, dp(4));
        staffPanel.addView(tvStaffHint);

        if (staffModeEnabled) {
            LinearLayout staffHdr = new LinearLayout(this);
            staffHdr.setOrientation(LinearLayout.HORIZONTAL);
            staffHdr.setGravity(Gravity.CENTER_VERTICAL);
            TextView tvSl = new TextView(this);
            tvSl.setText("目前員工名單（點擊切換或管理，PIN 留空可一鍵免密碼切換）：");
            tvSl.setTextColor(Color.parseColor("#E0F2FE"));
            tvSl.setTextSize(12);
            staffHdr.addView(tvSl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button btnAddStaff = new Button(this);
            btnAddStaff.setText("➕ 新增員工");
            btnAddStaff.setAllCaps(false);
            btnAddStaff.setTextSize(11);
            btnAddStaff.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showEditStaffDialog(null); }
            });
            staffHdr.addView(btnAddStaff);
            staffPanel.addView(staffHdr);

            for (final StaffMember sm : staffMembers) {
                LinearLayout sRow = new LinearLayout(this);
                sRow.setOrientation(LinearLayout.HORIZONTAL);
                sRow.setGravity(Gravity.CENTER_VERTICAL);
                sRow.setPadding(dp(4), dp(2), dp(4), dp(2));
                TextView tvS = new TextView(this);
                tvS.setText("• " + sm.name + " [" + sm.role + "] " + (sm.pin.isEmpty() ? "(免PIN)" : "(🔐PIN保護)"));
                tvS.setTextColor(Color.WHITE);
                tvS.setTextSize(12);
                sRow.addView(tvS, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                Button bEditS = new Button(this);
                bEditS.setText("✏️ 編輯");
                bEditS.setAllCaps(false);
                bEditS.setTextSize(11);
                bEditS.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { showEditStaffDialog(sm); }
                });
                sRow.addView(bEditS);

                if (staffMembers.size() > 1) {
                    Button bDelS = new Button(this);
                    bDelS.setText("🗑️");
                    bDelS.setAllCaps(false);
                    bDelS.setTextSize(11);
                    bDelS.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            dbHelper.deleteStaffMember(sm.id);
                            staffMembers.remove(sm);
                            rebuildSettingsTab();
                        }
                    });
                    sRow.addView(bDelS);
                }
                staffPanel.addView(sRow);
            }
        }
        box.addView(staffPanel);

        // 4. 平板本地菜單新增 / 改價 / 範本 / 清空管理
        LinearLayout menuHeaderRow = new LinearLayout(this);
        menuHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        menuHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        menuHeaderRow.setPadding(0, dp(10), 0, dp(4));

        TextView tvMenuTitle = new TextView(this);
        tvMenuTitle.setText("📋 4. 【" + PosPrintEngine.getEffectiveShortName() + "】菜單管理 (共 " + menuItems.size() + " 項)");
        tvMenuTitle.setTextColor(Color.parseColor("#A7F3D0"));
        tvMenuTitle.setTextSize(13);
        tvMenuTitle.setTypeface(null, Typeface.BOLD);
        menuHeaderRow.addView(tvMenuTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnAddMenu = new Button(this);
        btnAddMenu.setText("➕ 新增品項");
        btnAddMenu.setAllCaps(false);
        btnAddMenu.setTextSize(12);
        btnAddMenu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showEditMenuItemDialog(null); }
        });
        menuHeaderRow.addView(btnAddMenu);

        Button btnResetMenu = new Button(this);
        btnResetMenu.setText("📥 匯入示範菜單(66道)");
        btnResetMenu.setAllCaps(false);
        btnResetMenu.setTextSize(12);
        btnResetMenu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("📥 匯入茶飲簡餐示範菜單 (66道)")
                    .setMessage("這會以內建的「茶飲簡餐 66 道示範菜單」覆蓋目前菜單（方便新店測試或快速修改）。確定要匯入嗎？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("確定匯入", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            initMenuData();
                            dbHelper.saveMenuList(menuItems);
                            refreshAllPosViews();
                            rebuildSettingsTab();
                            Toast.makeText(MainActivity.this, "✅ 已匯入 66 道示範菜單", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
            }
        });
        menuHeaderRow.addView(btnResetMenu);

        Button btnClearMenu = new Button(this);
        btnClearMenu.setText("🧹 清空菜單");
        btnClearMenu.setAllCaps(false);
        btnClearMenu.setTextSize(12);
        btnClearMenu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("🧹 確定要清空所有菜單品項？")
                    .setMessage("清空後可透過「➕ 新增品項」或試算表下載來建立貴店專屬菜單（不影響歷史帳務）。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("確定清空", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            menuItems.clear();
                            rebuildMealDrinkPool();
                            dbHelper.saveMenuList(menuItems);
                            refreshAllPosViews();
                            rebuildSettingsTab();
                            Toast.makeText(MainActivity.this, "🧹 已清空菜單", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
            }
        });
        menuHeaderRow.addView(btnClearMenu);
        box.addView(menuHeaderRow);

        for (int i = 0; i < menuItems.size(); i++) {
            final MenuItem m = menuItems.get(i);
            LinearLayout itemRow = new LinearLayout(this);
            itemRow.setOrientation(LinearLayout.HORIZONTAL);
            itemRow.setGravity(Gravity.CENTER_VERTICAL);
            itemRow.setPadding(dp(4), dp(2), dp(4), dp(2));

            TextView tv = new TextView(this);
            tv.setText("[" + m.cat + "] " + m.name + " ($" + m.price + ") — "
                + (m.isDrink ? "☕常態印貼紙" : "🌿外帶/外送印貼紙") + "｜" + m.sub);
            tv.setTextColor(Color.parseColor("#CBD5E1"));
            tv.setTextSize(12);
            itemRow.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button btnEdit = new Button(this);
            btnEdit.setText("✏️ 改價/編輯");
            btnEdit.setAllCaps(false);
            btnEdit.setTextSize(11);
            btnEdit.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showEditMenuItemDialog(m); }
            });
            itemRow.addView(btnEdit);

            Button btnDel = new Button(this);
            btnDel.setText("🗑️");
            btnDel.setAllCaps(false);
            btnDel.setTextSize(11);
            btnDel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dbHelper.deleteMenuItem(m.id);
                    menuItems.remove(m);
                    rebuildMealDrinkPool();
                    refreshAllPosViews();
                    rebuildSettingsTab();
                }
            });
            itemRow.addView(btnDel);
            box.addView(itemRow);
        }
    }

    private void showEditMenuItemDialog(final MenuItem existing) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(12), dp(16), dp(12));

        final EditText etCat = new EditText(this);
        etCat.setHint("分類 (如：義大利麵&飯食 / 麻辣燙/藥膳燙 / 纖果茶)");
        etCat.setText(existing != null ? existing.cat : currentCategory);
        form.addView(etCat);

        final EditText etName = new EditText(this);
        etName.setHint("品項名稱");
        etName.setText(existing != null ? existing.name : "");
        form.addView(etName);

        final EditText etPrice = new EditText(this);
        etPrice.setInputType(InputType.TYPE_CLASS_NUMBER);
        etPrice.setHint("單價 ($)");
        etPrice.setText(existing != null ? String.valueOf(existing.price) : "100");
        form.addView(etPrice);

        final EditText etSub = new EditText(this);
        etSub.setHint("副標題說明 (如：純素｜附冷壓萃茶)");
        etSub.setText(existing != null ? existing.sub : "純素");
        form.addView(etSub);

        final CheckBox cbIsDrink = new CheckBox(this);
        cbIsDrink.setText("屬於飲品類 (常態預設印貼紙)");
        cbIsDrink.setChecked(existing != null && existing.isDrink);
        form.addView(cbIsDrink);

        final CheckBox cbFreeTea = new CheckBox(this);
        cbFreeTea.setText("隨餐附贈冷壓萃茶 (可換購其他飲品)");
        cbFreeTea.setChecked(existing != null && existing.hasFreeTea);
        form.addView(cbFreeTea);

        new AlertDialog.Builder(this)
            .setTitle(existing != null ? "✏️ 編輯菜單品項" : "➕ 新增菜單品項")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("儲存", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    String cat = etCat.getText().toString().trim();
                    String name = etName.getText().toString().trim();
                    if (cat.isEmpty() || name.isEmpty()) return;
                    int price = 0;
                    try { price = Math.max(0, Integer.parseInt(etPrice.getText().toString().trim())); } catch (Exception ignored) {}
                    String sub = etSub.getText().toString().trim();

                    if (existing != null) {
                        existing.cat = cat;
                        existing.name = name;
                        existing.price = price;
                        existing.sub = sub;
                        existing.isDrink = cbIsDrink.isChecked();
                        existing.hasFreeTea = cbFreeTea.isChecked();
                        dbHelper.upsertMenuItem(existing);
                    } else {
                        int maxId = 9000;
                        for (MenuItem mm : menuItems) {
                            if (mm.id >= maxId) maxId = mm.id;
                        }
                        int nextId = maxId + 1;
                        MenuItem created = new MenuItem(
                            nextId, cat, name, sub, price,
                            cbIsDrink.isChecked(), cbFreeTea.isChecked(),
                            false, false, false,
                            "麻辣燙/藥膳燙".equals(cat) ? new String[]{"🌶️麻辣燙", "🌿藥膳燙"} : new String[]{"純素", "奶素"}
                        );
                        menuItems.add(created);
                        dbHelper.upsertMenuItem(created);
                    }
                    rebuildMealDrinkPool();
                    refreshAllPosViews();
                    rebuildSettingsTab();
                }
            })
            .show();
    }

    private Button makeMiniTuneBtn(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(4), dp(2), dp(4), dp(2));
        b.setBackground(roundBg(Color.parseColor("#334155"), Color.parseColor("#475569"), 1, 6));
        b.setOnClickListener(listener);
        return b;
    }

    private void showBitmapPreviewDialog(String title, android.graphics.Bitmap bmp) {
        ScrollView sv = new ScrollView(this);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.setBackgroundColor(Color.parseColor("#0F172A"));
        wrap.setPadding(dp(16), dp(12), dp(16), dp(12));

        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        wrap.addView(iv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.addView(wrap);

        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("關閉預覽", null)
            .show();
    }

    private String getOffsetDateStr(int daysOffset) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.DAY_OF_YEAR, daysOffset);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN).format(cal.getTime());
    }

    private void showCustomDateRangePickerDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.parseColor("#1E293B"));
        box.setPadding(dp(16), dp(12), dp(16), dp(12));

        addSectionHeader(box, "1. 快速選擇月份（點擊直接查看該月營收與餐點銷量排行）：");
        LinearLayout monthGrid = new LinearLayout(this);
        monthGrid.setOrientation(LinearLayout.HORIZONTAL);
        String curYear = new SimpleDateFormat("yyyy", Locale.TAIWAN).format(new Date());
        final AlertDialog[] holder = new AlertDialog[1];

        for (int m = 1; m <= 12; m++) {
            final String ym = String.format(Locale.US, "%s-%02d", curYear, m);
            Button bm = makeMiniTuneBtn(m + "月", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (holder[0] != null) holder[0].dismiss();
                    showPeriodAnalyticsDialog(ym + " 月份統計", ym + "-01", ym + "-31");
                }
            });
            LinearLayout.LayoutParams lpM = new LinearLayout.LayoutParams(0, dp(34), 1f);
            lpM.rightMargin = dp(2);
            monthGrid.addView(bm, lpM);
        }
        box.addView(monthGrid);

        addSectionHeader(box, "2. 或手動輸入任意起始日期 ～ 結束日期 (格式 YYYY-MM-DD)：");
        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);

        final EditText etStart = new EditText(this);
        etStart.setHint("起始日 (如 2026-10-01)");
        etStart.setText(getOffsetDateStr(-13));
        etStart.setTextColor(Color.WHITE);
        dateRow.addView(etStart, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final EditText etEnd = new EditText(this);
        etEnd.setHint("結束日 (如 2026-10-31)");
        etEnd.setText(getOffsetDateStr(0));
        etEnd.setTextColor(Color.WHITE);
        dateRow.addView(etEnd, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(dateRow);

        holder[0] = new AlertDialog.Builder(this)
            .setTitle("🔍 指定月份或自訂日期區間統計查詢")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("查詢區間統計", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String s = etStart.getText().toString().trim();
                    String e = etEnd.getText().toString().trim();
                    showPeriodAnalyticsDialog("自訂區間 (" + s + " ~ " + e + ")", s, e);
                }
            })
            .create();
        holder[0].show();
    }

    private void showPeriodAnalyticsDialog(final String periodTitle, String startDate, String endDate) {
        int closingBatchCount = 0;
        int totalValid = 0, totalVoid = 0, totalNetSales = 0, totalCashSales = 0;
        final Map<String, Integer> sumByPay = new LinkedHashMap<>();
        final Map<String, Integer> sumItemRank = new LinkedHashMap<>();

        for (ClosingRecord cr : closingHistory) {
            String d = (cr.time != null && cr.time.length() >= 10) ? cr.time.substring(0, 10) : "";
            if (d.compareTo(startDate) >= 0 && d.compareTo(endDate) <= 0) {
                closingBatchCount++;
                totalValid += cr.validCount;
                totalVoid += cr.voidCount;
                totalNetSales += cr.netSales;
                totalCashSales += cr.cashSales;

                Map<String, Integer> payMap = parseJsonIntMap(cr.byPayJson);
                for (Map.Entry<String, Integer> e : payMap.entrySet()) {
                    int prev = sumByPay.containsKey(e.getKey()) ? sumByPay.get(e.getKey()) : 0;
                    sumByPay.put(e.getKey(), prev + e.getValue());
                }
                Map<String, Integer> itemMap = parseJsonIntMap(cr.itemRankJson);
                for (Map.Entry<String, Integer> e : itemMap.entrySet()) {
                    int prev = sumItemRank.containsKey(e.getKey()) ? sumItemRank.get(e.getKey()) : 0;
                    sumItemRank.put(e.getKey(), prev + e.getValue());
                }
            }
        }

        // 將品項銷售數量由高到低排序
        List<Map.Entry<String, Integer>> sortedItems = new ArrayList<>(sumItemRank.entrySet());
        java.util.Collections.sort(sortedItems, new java.util.Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue().compareTo(a.getValue());
            }
        });
        final Map<String, Integer> sortedItemMap = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : sortedItems) {
            sortedItemMap.put(e.getKey(), e.getValue());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📅 查詢區間：").append(startDate).append(" ～ ").append(endDate).append("\n");
        sb.append("• 包含關帳批次：").append(closingBatchCount).append(" 次\n");
        sb.append("• 累計有效訂單：").append(totalValid).append(" 筆 ｜ 作廢退單：").append(totalVoid).append(" 筆\n");
        sb.append("• 區間總淨營業額：$").append(totalNetSales).append("\n");
        sb.append("• 區間現金實收總計：$").append(totalCashSales).append("\n");
        sb.append("• 平均每單客單價：$").append(totalValid > 0 ? (totalNetSales / totalValid) : 0).append("\n\n");

        sb.append("================ 【各付款方式累計金額】 ================\n");
        if (sumByPay.isEmpty()) {
            sb.append("  (此區間尚無關帳收款資料)\n");
        } else {
            for (Map.Entry<String, Integer> e : sumByPay.entrySet()) {
                sb.append("  • ").append(e.getKey()).append("：$").append(e.getValue()).append("\n");
            }
        }

        sb.append("\n================ 【餐點銷售數量總排行榜】 ================\n");
        if (sortedItemMap.isEmpty()) {
            sb.append("  (此區間尚無餐點銷售資料)\n");
        } else {
            int rank = 1;
            for (Map.Entry<String, Integer> e : sortedItemMap.entrySet()) {
                sb.append("  ").append(rank++).append(". ").append(e.getKey())
                  .append("  x ").append(e.getValue()).append(" 份\n");
            }
        }

        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        tv.setBackgroundColor(Color.parseColor("#0F172A"));
        sv.addView(tv);

        final ClosingRecord syntheticCr = new ClosingRecord();
        syntheticCr.time = periodTitle + " (" + startDate + "~" + endDate + ")";
        syntheticCr.validCount = totalValid;
        syntheticCr.voidCount = totalVoid;
        syntheticCr.netSales = totalNetSales;
        syntheticCr.cashSales = totalCashSales;
        syntheticCr.openingFloat = 0;

        new AlertDialog.Builder(this)
            .setTitle("📊 " + periodTitle)
            .setView(sv)
            .setNeutralButton("🖨️ 用出單機印出此區間報表", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    PosPrintEngine.printClosingReportAsync(
                        syntheticCr, sumByPay, sortedItemMap,
                        receiptIp, receiptPort, receiptWidthMm,
                        new PosPrintEngine.PrintCallback() {
                            @Override public void onComplete(boolean ok, String msg) {
                                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                            }
                        });
                }
            })
            .setPositiveButton("關閉", null)
            .show();
    }

    // ==================== 雙機連線燈號 / 區網尋回 / 店長PIN / 雲端備份回復 / OTA更新 ====================
    private int getAppVersionCode() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return (int) (android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        } catch (Exception e) {
            return 3;
        }
    }

    private String getAppVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.2";
        }
    }

    private static String badgeConnSuffix(String mode) {
        if (PosPrintEngine.CONN_BT.equals(mode)) return "(BT)";
        if (PosPrintEngine.CONN_USB.equals(mode)) return "(USB)";
        return "";
    }

    private void refreshPrinterStatusBadge() {
        if (tvPrinterStatusBadge == null) return;
        PosPrintEngine.probePrintersStatusAsync(receiptIp, receiptPort, labelIp, labelPort,
            new PosPrintEngine.StatusCallback() {
                @Override public void onStatus(boolean receiptOnline, boolean labelOnline) {
                    tvPrinterStatusBadge.setText(
                        (receiptOnline ? "🟢收據" : "🔴收據") + badgeConnSuffix(PosPrintEngine.receiptConnMode)
                        + " "
                        + (labelOnline ? "🟢貼紙" : "🔴貼紙") + badgeConnSuffix(PosPrintEngine.labelConnMode));
                    tvPrinterStatusBadge.setBackground(roundBg(
                        (receiptOnline && labelOnline) ? Color.parseColor("#064E3B") : Color.parseColor("#450A0A"),
                        (receiptOnline && labelOnline) ? Color.parseColor("#10B981") : Color.parseColor("#EF4444"),
                        1, 6));
                }
            });
    }

    private LinearLayout buildPrinterTransportPanel(final boolean isReceipt, final EditText etIp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(4));

        final String curMode = isReceipt ? PosPrintEngine.receiptConnMode : PosPrintEngine.labelConnMode;
        final String[] modes = {PosPrintEngine.CONN_WIFI, PosPrintEngine.CONN_BT, PosPrintEngine.CONN_USB};
        final String[] labels = {"📶 Wi-Fi", "📘 藍牙 BT", "🔌 USB OTG"};

        for (int i = 0; i < modes.length; i++) {
            final String m = modes[i];
            Button btnMode = new Button(this);
            btnMode.setText(labels[i]);
            btnMode.setAllCaps(false);
            btnMode.setTextSize(11);
            btnMode.setPadding(dp(8), dp(2), dp(8), dp(2));
            boolean active = m.equals(curMode);
            btnMode.setTextColor(active ? Color.parseColor("#052E16") : Color.WHITE);
            btnMode.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            btnMode.setBackground(roundBg(
                active ? Color.parseColor("#38BDF8") : Color.parseColor("#334155"), 0, 0, 6));
            btnMode.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (isReceipt) {
                        PosPrintEngine.receiptConnMode = m;
                        dbHelper.setSetting("receipt_conn_mode", m);
                    } else {
                        PosPrintEngine.labelConnMode = m;
                        dbHelper.setSetting("label_conn_mode", m);
                    }
                    if (etIp != null) {
                        etIp.setVisibility(PosPrintEngine.CONN_WIFI.equals(m) ? View.VISIBLE : View.GONE);
                    }
                    refreshPrinterStatusBadge();
                    rebuildSettingsTab();
                }
            });
            LinearLayout.LayoutParams lpM = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
            lpM.rightMargin = dp(4);
            row.addView(btnMode, lpM);
        }

        if (PosPrintEngine.CONN_BT.equals(curMode)) {
            String curMac = isReceipt ? PosPrintEngine.receiptBtMac : PosPrintEngine.labelBtMac;
            Button btnPickBt = new Button(this);
            btnPickBt.setText("📘 選擇已配對藍牙：" + (curMac.isEmpty() ? "(尚未選取)" : curMac));
            btnPickBt.setAllCaps(false);
            btnPickBt.setTextSize(11);
            btnPickBt.setTextColor(Color.WHITE);
            btnPickBt.setBackground(roundBg(Color.parseColor("#1D4ED8"), 0, 0, 6));
            btnPickBt.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showBluetoothPrinterPickerDialog(isReceipt); }
            });
            row.addView(btnPickBt, new LinearLayout.LayoutParams(0, dp(32), 1f));
        } else if (PosPrintEngine.CONN_USB.equals(curMode)) {
            String curUsb = isReceipt ? PosPrintEngine.receiptUsbId : PosPrintEngine.labelUsbId;
            String usbText = (curUsb == null || curUsb.isEmpty() || "AUTO".equalsIgnoreCase(curUsb))
                ? "自動偵測印表機" : curUsb;
            Button btnPickUsb = new Button(this);
            btnPickUsb.setText("🔌 選擇 / 授權 USB OTG：" + usbText);
            btnPickUsb.setAllCaps(false);
            btnPickUsb.setTextSize(11);
            btnPickUsb.setTextColor(Color.WHITE);
            btnPickUsb.setBackground(roundBg(Color.parseColor("#0F766E"), 0, 0, 6));
            btnPickUsb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showUsbPrinterPickerDialog(isReceipt); }
            });
            row.addView(btnPickUsb, new LinearLayout.LayoutParams(0, dp(32), 1f));
        } else {
            TextView tvHint = new TextView(this);
            tvHint.setText("走 TCP Port 9100（下方輸入 IP 或按區網搜尋）");
            tvHint.setTextColor(Color.parseColor("#94A3B8"));
            tvHint.setTextSize(11);
            tvHint.setPadding(dp(4), 0, 0, 0);
            row.addView(tvHint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private void showBluetoothPrinterPickerDialog(final boolean isReceipt) {
        if (Build.VERSION.SDK_INT >= 31
            && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            pendingBtPickerForReceipt = isReceipt;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLUETOOTH_CONNECT);
            return;
        }
        try {
            final List<PosPrintEngine.BtDeviceItem> list = PosPrintEngine.listBondedBluetoothDevices(this);
            if (list.isEmpty()) {
                new AlertDialog.Builder(this)
                    .setTitle("📘 找不到已配對藍牙裝置")
                    .setMessage("請先打開印表機電源，並到平板「系統設定 ➔ 藍牙」完成配對（常見 PIN 為 0000 或 1234）後，再回來選取！")
                    .setPositiveButton("我知道了", null)
                    .show();
                return;
            }
            String[] items = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                PosPrintEngine.BtDeviceItem b = list.get(i);
                items[i] = "🖨️ " + b.name + "  [" + b.mac + "]";
            }
            new AlertDialog.Builder(this)
                .setTitle("📘 選取" + (isReceipt ? "收據出單機" : "標籤貼紙機") + "藍牙裝置")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        PosPrintEngine.BtDeviceItem chosen = list.get(which);
                        if (isReceipt) {
                            PosPrintEngine.receiptBtMac = chosen.mac;
                            dbHelper.setSetting("receipt_bt_mac", chosen.mac);
                        } else {
                            PosPrintEngine.labelBtMac = chosen.mac;
                            dbHelper.setSetting("label_bt_mac", chosen.mac);
                        }
                        refreshPrinterStatusBadge();
                        rebuildSettingsTab();
                        Toast.makeText(MainActivity.this,
                            "✅ 已綁定藍牙：" + chosen.name + " (" + chosen.mac + ")", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        } catch (Exception e) {
            Toast.makeText(this, "⚠️ " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showUsbPrinterPickerDialog(final boolean isReceipt) {
        final List<PosPrintEngine.UsbDeviceItem> list = PosPrintEngine.listUsbPrinterCandidates(this);
        if (list.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("🔌 未偵測到 USB OTG 列印裝置")
                .setMessage("請確認：\n1. 平板已透過 OTG 轉接線連接出單機 / 貼紙機 USB 孔\n2. 印表機電源已開啟\n\n也可先設為「自動偵測印表機」，插上即可直接列印。")
                .setPositiveButton("設為自動偵測", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (isReceipt) {
                            PosPrintEngine.receiptUsbId = "AUTO";
                            dbHelper.setSetting("receipt_usb_id", "AUTO");
                        } else {
                            PosPrintEngine.labelUsbId = "AUTO";
                            dbHelper.setSetting("label_usb_id", "AUTO");
                        }
                        refreshPrinterStatusBadge();
                        rebuildSettingsTab();
                    }
                })
                .setNegativeButton("關閉", null)
                .show();
            return;
        }
        String[] items = new String[list.size() + 1];
        items[0] = "🔄 自動偵測第一台 USB 印表機 (AUTO)";
        for (int i = 0; i < list.size(); i++) {
            items[i + 1] = list.get(i).label;
        }
        new AlertDialog.Builder(this)
            .setTitle("🔌 選取 / 授權 USB OTG " + (isReceipt ? "出單機" : "貼紙機"))
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    String vidPid = (which == 0) ? "AUTO" : list.get(which - 1).vidPid;
                    if (isReceipt) {
                        PosPrintEngine.receiptUsbId = vidPid;
                        dbHelper.setSetting("receipt_usb_id", vidPid);
                    } else {
                        PosPrintEngine.labelUsbId = vidPid;
                        dbHelper.setSetting("label_usb_id", vidPid);
                    }
                    PosPrintEngine.requestUsbPermissionAsync(MainActivity.this, vidPid,
                        new PosPrintEngine.PrintCallback() {
                            @Override public void onComplete(boolean ok, String msg) {
                                refreshPrinterStatusBadge();
                                rebuildSettingsTab();
                                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                            }
                        });
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLUETOOTH_CONNECT) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showBluetoothPrinterPickerDialog(pendingBtPickerForReceipt);
            } else {
                Toast.makeText(this, "⚠️ 未允許藍牙「鄰近裝置」權限，無法列舉或連線藍牙出單機", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void authorizeAction(String actionTitle, boolean requireManagerRole, final Runnable onAuthorized) {
        boolean needManagerPin = false;
        if (managerPin != null && !managerPin.trim().isEmpty()) {
            if (!staffModeEnabled || requireManagerRole || !"店長".equals(currentStaffRole)) {
                needManagerPin = true;
            }
        } else if (staffModeEnabled && requireManagerRole && !"店長".equals(currentStaffRole)) {
            // 若未設定全域 managerPin，檢查是否有設定「店長」角色的個人 PIN
            for (StaffMember sm : staffMembers) {
                if ("店長".equals(sm.role) && sm.pin != null && !sm.pin.trim().isEmpty()) {
                    needManagerPin = true;
                    break;
                }
            }
        }
        if (!needManagerPin) {
            onAuthorized.run();
            return;
        }
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        et.setHint("請輸入店長 PIN 碼");
        new AlertDialog.Builder(this)
            .setTitle("🔐 權限驗證：" + actionTitle)
            .setView(et)
            .setNegativeButton("取消", null)
            .setPositiveButton("確認", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String entered = et.getText().toString().trim();
                    boolean ok = (!managerPin.trim().isEmpty() && managerPin.equals(entered));
                    if (!ok && staffModeEnabled) {
                        for (StaffMember sm : staffMembers) {
                            if ("店長".equals(sm.role) && !sm.pin.trim().isEmpty() && sm.pin.equals(entered)) {
                                ok = true;
                                break;
                            }
                        }
                    }
                    if (ok) {
                        onAuthorized.run();
                    } else {
                        Toast.makeText(MainActivity.this, "❌ PIN 碼錯誤！", Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .show();
    }

    private void showSwitchStaffDialog() {
        if (staffMembers.isEmpty()) {
            dbHelper.seedDefaultStaffIfEmpty(managerPin);
            staffMembers.clear();
            staffMembers.addAll(dbHelper.loadStaffMembers());
        }
        String[] labels = new String[staffMembers.size()];
        for (int i = 0; i < staffMembers.size(); i++) {
            StaffMember s = staffMembers.get(i);
            String mark = s.name.equals(currentStaffName) ? "✔ " : "";
            labels[i] = mark + s.name + " [" + s.role + "]" + (s.pin.isEmpty() ? "" : " 🔐");
        }
        new AlertDialog.Builder(this)
            .setTitle("👤 切換當班操作人員")
            .setItems(labels, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    final StaffMember target = staffMembers.get(which);
                    if (target.pin == null || target.pin.trim().isEmpty()) {
                        applyActiveStaff(target);
                        return;
                    }
                    final EditText et = new EditText(MainActivity.this);
                    et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
                    et.setHint("請輸入 " + target.name + " 的 PIN 碼");
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("🔐 登入驗證：" + target.name)
                        .setView(et)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("登入", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d2, int w2) {
                                if (target.pin.equals(et.getText().toString().trim())) {
                                    applyActiveStaff(target);
                                } else {
                                    Toast.makeText(MainActivity.this, "❌ PIN 碼錯誤", Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                        .show();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void applyActiveStaff(StaffMember target) {
        currentStaffName = target.name;
        currentStaffRole = target.role;
        dbHelper.setSetting("current_staff_name", currentStaffName);
        dbHelper.setSetting("current_staff_role", currentStaffRole);
        if (auditLogEnabled) {
            dbHelper.insertAuditLog(currentStaffName, "STAFF_SWITCH", currentStaffRole, "切換當班人員");
        }
        updateBrandAndStaffHeader();
        if (viewClosing.getVisibility() == View.VISIBLE) rebuildClosingTab();
        Toast.makeText(this, "👤 目前當班人員：" + currentStaffName + " (" + currentStaffRole + ")", Toast.LENGTH_SHORT).show();
    }

    private void showEditStaffDialog(final StaffMember existing) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(12), dp(16), dp(12));

        final EditText etName = new EditText(this);
        etName.setHint("姓名 / 稱呼 (如：小美、晚班人員)");
        etName.setText(existing != null ? existing.name : "");
        form.addView(etName);

        final String[] roles = {"店員", "店長"};
        final String[] selRole = {existing != null ? existing.role : "店員"};
        final Button btnRole = new Button(this);
        btnRole.setText("角色權限：" + selRole[0] + " (點擊切換)");
        btnRole.setAllCaps(false);
        btnRole.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                selRole[0] = "店長".equals(selRole[0]) ? "店員" : "店長";
                btnRole.setText("角色權限：" + selRole[0] + " (點擊切換)");
            }
        });
        form.addView(btnRole);

        final EditText etPin = new EditText(this);
        etPin.setInputType(InputType.TYPE_CLASS_NUMBER);
        etPin.setHint("PIN 碼 (留空表示免密碼一鍵切換)");
        etPin.setText(existing != null ? existing.pin : "");
        form.addView(etPin);

        new AlertDialog.Builder(this)
            .setTitle(existing != null ? "✏️ 編輯員工資料" : "➕ 新增員工")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("儲存", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) return;
                    StaffMember sm = (existing != null) ? existing : new StaffMember();
                    sm.name = name;
                    sm.role = selRole[0];
                    sm.pin = etPin.getText().toString().trim();
                    sm.enabled = true;
                    dbHelper.upsertStaffMember(sm);
                    staffMembers.clear();
                    staffMembers.addAll(dbHelper.loadStaffMembers());
                    rebuildSettingsTab();
                }
            })
            .show();
    }

    private void showShiftHandoverDialog() {
        int orderCnt = 0, voidCnt = 0, cashSum = 0, nonCashSum = 0;
        long maxDbId = lastHandoverOrderDbId;
        for (OrderRecord o : ordersHistory) {
            if (o.dbId > lastHandoverOrderDbId || o.dbId <= 0) {
                if (o.dbId > maxDbId) maxDbId = o.dbId;
                if (o.voided) {
                    voidCnt++;
                } else {
                    orderCnt++;
                    if ("現金".equals(o.paymentMethod)) cashSum += o.total;
                    else nonCashSum += o.total;
                }
            }
        }
        final long fMaxId = maxDbId;
        final int fOrderCnt = orderCnt;
        final int fVoidCnt = voidCnt;
        final int fCashSum = cashSum;
        final int fNonCashSum = nonCashSum;
        final int expectedCash = openingFloat + cashSum;
        final String nowTime = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TAIWAN).format(new Date());

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView tvSummary = new TextView(this);
        tvSummary.setText("• 交班人員：" + (staffModeEnabled ? currentStaffName : "當班人員")
            + "\n• 開班時間：" + shiftStartTime + " ～ " + nowTime
            + "\n• 本班有效單：" + fOrderCnt + " 筆 ｜ 作廢：" + fVoidCnt + " 筆"
            + "\n• 本班現金收入：$" + fCashSum + " ＋ 期初零用金：$" + openingFloat
            + "\n• 本班非現金收入：$" + fNonCashSum
            + "\n👉 錢櫃應有總現金：$" + expectedCash);
        tvSummary.setTextSize(14);
        tvSummary.setPadding(0, 0, 0, dp(10));
        form.addView(tvSummary);

        final EditText etCounted = new EditText(this);
        etCounted.setInputType(InputType.TYPE_CLASS_NUMBER);
        etCounted.setHint("請輸入錢櫃實點現金總額 ($)");
        etCounted.setText(String.valueOf(expectedCash));
        form.addView(etCounted);

        final EditText etNote = new EditText(this);
        etNote.setHint("交班備註 (可留空，如短差原因或交接事項)");
        form.addView(etNote);

        final CheckBox cbPrint = new CheckBox(this);
        cbPrint.setText("🖨️ 用收據機印出交班對帳單");
        cbPrint.setChecked(true);
        form.addView(cbPrint);

        new AlertDialog.Builder(this)
            .setTitle("🤝 班別交班現金盤點結算")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("✅ 確認交班", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    int counted = expectedCash;
                    try { counted = Integer.parseInt(etCounted.getText().toString().trim()); } catch (Exception ignored) {}
                    ShiftHandoverRecord sh = new ShiftHandoverRecord();
                    sh.shiftStart = shiftStartTime;
                    sh.shiftEnd = nowTime;
                    sh.staffName = staffModeEnabled ? currentStaffName : "當班人員";
                    sh.openingCash = openingFloat;
                    sh.cashSales = fCashSum;
                    sh.nonCashSales = fNonCashSum;
                    sh.expectedCash = expectedCash;
                    sh.countedCash = counted;
                    sh.diffCash = counted - expectedCash;
                    sh.orderCount = fOrderCnt;
                    sh.voidCount = fVoidCnt;
                    sh.note = etNote.getText().toString().trim();

                    dbHelper.insertShiftHandover(sh);
                    lastHandoverOrderDbId = fMaxId;
                    shiftStartTime = nowTime;
                    dbHelper.setSetting("last_handover_order_id", String.valueOf(lastHandoverOrderDbId));
                    dbHelper.setSetting("shift_start_time", shiftStartTime);

                    if (auditLogEnabled || staffModeEnabled) {
                        dbHelper.insertAuditLog(sh.staffName, "SHIFT_CLOSE", nowTime,
                            "交班應有 $" + expectedCash + " / 實點 $" + counted + " (差 $" + sh.diffCash + ")");
                    }
                    if (cbPrint.isChecked()) {
                        PosPrintEngine.printShiftHandoverAsync(sh, receiptIp, receiptPort, receiptWidthMm,
                            new PosPrintEngine.PrintCallback() {
                                @Override public void onComplete(boolean ok, String msg) {
                                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                                }
                            });
                    }
                    rebuildClosingTab();
                    Toast.makeText(MainActivity.this,
                        "🤝 交班完成！實點 $" + counted + " (溢/短差 $" + sh.diffCash + ")",
                        Toast.LENGTH_LONG).show();
                }
            })
            .show();
    }

    private void showShiftAndAuditLogsDialog() {
        List<ShiftHandoverRecord> shifts = dbHelper.loadShiftHandovers(30);
        List<AuditLogRecord> audits = dbHelper.loadAuditLogs(50);

        StringBuilder sb = new StringBuilder();
        sb.append("================ 【近期班別交班盤點紀錄】 ================\n");
        if (shifts.isEmpty()) {
            sb.append("  (尚無交班紀錄)\n");
        } else {
            for (ShiftHandoverRecord sh : shifts) {
                String diffPrefix = sh.diffCash > 0 ? "+" : "";
                sb.append("• ").append(sh.shiftEnd).append(" 👤").append(sh.staffName)
                  .append("\n  本班 ").append(sh.orderCount).append(" 單｜現金 $").append(sh.cashSales)
                  .append("｜應有 $").append(sh.expectedCash).append("｜實點 $").append(sh.countedCash)
                  .append(" (差 ").append(diffPrefix).append("$").append(sh.diffCash).append(")");
                if (sh.note != null && !sh.note.isEmpty()) sb.append("｜備註:").append(sh.note);
                sb.append("\n");
            }
        }
        sb.append("\n================ 【敏感操作稽核日誌 (作廢/折扣/交班/切換)】 ================\n");
        if (audits.isEmpty()) {
            sb.append("  (尚無稽核紀錄)\n");
        } else {
            for (AuditLogRecord al : audits) {
                sb.append("[").append(al.createdAt).append("] 👤").append(al.staffName)
                  .append("｜").append(al.actionType).append(" ").append(al.targetRef)
                  .append(" ➔ ").append(al.detail).append("\n");
            }
        }

        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        tv.setBackgroundColor(Color.parseColor("#0F172A"));
        sv.addView(tv);
        new AlertDialog.Builder(this)
            .setTitle("📜 交班盤點與敏感操作稽核日誌")
            .setView(sv)
            .setPositiveButton("關閉", null)
            .show();
    }

    private void showLocalSnapshotImportDialog() {
        final List<java.io.File> files = dbHelper.listLocalSnapshotFiles();
        if (files.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("📥 找不到本機遷移快照檔")
                .setMessage("請先將 .json 快照檔放入平板的 Download/POS_Reports/ 資料夾，或先點「📤 匯出遷移快照 (.json)」。")
                .setPositiveButton("好", null)
                .show();
            return;
        }
        String[] names = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            names[i] = files.get(i).getName() + " (" + (files.get(i).length() / 1024) + " KB)";
        }
        new AlertDialog.Builder(this)
            .setTitle("📥 選擇要匯入還原的系統快照檔 (.json)：")
            .setItems(names, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, final int which) {
                    final java.io.File chosen = files.get(which);
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("♻️ 確認從本機快照匯入？")
                        .setMessage("將從【" + chosen.getName() + "】還原門市設定、完整菜單、桌位、員工與歷史帳務，覆蓋目前本機資料。\n確定執行嗎？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("立即匯入還原", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d2, int w2) {
                                try {
                                    org.json.JSONObject snap = dbHelper.readSnapshotFromFile(chosen);
                                    String summary = dbHelper.restoreFromSnapshotJson(snap);
                                    loadDatabaseState();
                                    updateBrandAndStaffHeader();
                                    refreshAllPosViews();
                                    refreshPrinterStatusBadge();
                                    rebuildSettingsTab();
                                    new AlertDialog.Builder(MainActivity.this)
                                        .setTitle("✅ 本機快照無痛匯入完成！")
                                        .setMessage("已成功還原：\n" + summary)
                                        .setPositiveButton("太棒了", null)
                                        .show();
                                } catch (Exception e) {
                                    Toast.makeText(MainActivity.this, "❌ 匯入失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            }
                        })
                        .show();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showStoreOnboardingDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView tvIntro = new TextView(this);
        tvIntro.setText("歡迎使用智慧餐飲 POS！請先輸入貴店名稱，並選擇初始化方式：");
        tvIntro.setTextSize(14);
        tvIntro.setPadding(0, 0, 0, dp(8));
        box.addView(tvIntro);

        final EditText etName = new EditText(this);
        etName.setHint("完整店名 (如：晨光早午餐 / 好茶飲)");
        box.addView(etName);

        final EditText etShort = new EditText(this);
        etShort.setHint("門市簡稱 (顯示於頂部與貼紙，可留空自動取店名)");
        box.addView(etShort);

        final EditText etSub = new EditText(this);
        etSub.setHint("地址與電話 (印於收據抬頭第二行，可留空)");
        box.addView(etSub);

        new AlertDialog.Builder(this)
            .setTitle("🏪 新門市快速開通精靈")
            .setView(box)
            .setCancelable(true)
            .setPositiveButton("📋 套用66道示範菜單試用", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    applyOnboardingBrand(etName.getText().toString().trim(),
                        etShort.getText().toString().trim(), etSub.getText().toString().trim());
                    initMenuData();
                    dbHelper.saveMenuList(menuItems);
                    refreshAllPosViews();
                    Toast.makeText(MainActivity.this, "✅ 已開通門市並載入 66 道示範菜單", Toast.LENGTH_SHORT).show();
                }
            })
            .setNeutralButton("🧹 空白門市開始 (自行建單)", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    applyOnboardingBrand(etName.getText().toString().trim(),
                        etShort.getText().toString().trim(), etSub.getText().toString().trim());
                    switchMainTab(3);
                }
            })
            .setNegativeButton("📥 從本機快照匯入", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    dbHelper.setSetting("store_initialized", "1");
                    showLocalSnapshotImportDialog();
                }
            })
            .show();
    }

    private void applyOnboardingBrand(String sn, String ss, String sub) {
        if (sn.isEmpty()) sn = "我的餐飲門市";
        if (ss.isEmpty()) ss = sn;
        PosPrintEngine.storeName = sn;
        PosPrintEngine.storeShortName = ss;
        PosPrintEngine.storeSubtitle = sub;
        dbHelper.setSetting("store_name", sn);
        dbHelper.setSetting("store_short_name", ss);
        dbHelper.setSetting("store_subtitle", sub);
        dbHelper.setSetting("store_initialized", "1");
        updateBrandAndStaffHeader();
        refreshCartUI();
    }

    private void showSetManagerPinDialog() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("輸入 4 位數 PIN 碼 (留空則關閉鎖定)");
        et.setText(managerPin);
        new AlertDialog.Builder(this)
            .setTitle("🔐 設定店長權限 PIN 碼（保護退單/關帳/設定）")
            .setView(et)
            .setNegativeButton("取消", null)
            .setPositiveButton("儲存", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    managerPin = et.getText().toString().trim();
                    dbHelper.setSetting("manager_pin", managerPin);
                    rebuildSettingsTab();
                    Toast.makeText(MainActivity.this,
                        managerPin.isEmpty() ? "🔓 已關閉店長 PIN 碼鎖定" : "🔐 已啟用店長 PIN 碼保護！",
                        Toast.LENGTH_SHORT).show();
                }
            })
            .show();
    }

    private void showPrinterDiscoveryDialog() {
        Toast.makeText(this, "🔍 正在掃描店內區網 Port 9100 印表機，約需 3~5 秒...", Toast.LENGTH_SHORT).show();
        PosPrintEngine.discoverPrintersAsync(new PosPrintEngine.DiscoveryCallback() {
            @Override public void onResult(String subnetInfo, final List<PosPrintEngine.DiscoveredPrinter> printers) {
                if (printers.isEmpty()) {
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("🔍 區網掃描完成 (" + subnetInfo + ")")
                        .setMessage("未偵測到開啟 Port 9100 的網路印表機。\n請確認：\n1. 平板與印表機連在同一個店內 Wi-Fi 路由器\n2. 印表機電源與網路線已插妥")
                        .setNeutralButton("重試掃描", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { showPrinterDiscoveryDialog(); }
                        })
                        .setPositiveButton("關閉", null)
                        .show();
                    return;
                }
                String[] labels = new String[printers.size()];
                for (int i = 0; i < printers.size(); i++) {
                    PosPrintEngine.DiscoveredPrinter dpItem = printers.get(i);
                    labels[i] = dpItem.ip + "  —  " + dpItem.hint;
                }
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("🖨️ 找到 " + printers.size() + " 台印表機 (" + subnetInfo + ")，點擊套用：")
                    .setItems(labels, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, final int which) {
                            final PosPrintEngine.DiscoveredPrinter chosen = printers.get(which);
                            new AlertDialog.Builder(MainActivity.this)
                                .setTitle("套用 IP：" + chosen.ip)
                                .setMessage("請問要將此 IP 設為哪一台印表機？")
                                .setPositiveButton("🧾 設為收據出單機 (Wi-Fi)", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d2, int w2) {
                                        receiptIp = chosen.ip;
                                        PosPrintEngine.receiptConnMode = PosPrintEngine.CONN_WIFI;
                                        dbHelper.setSetting("receipt_ip", receiptIp);
                                        dbHelper.setSetting("receipt_conn_mode", PosPrintEngine.CONN_WIFI);
                                        refreshPrinterStatusBadge();
                                        rebuildSettingsTab();
                                        Toast.makeText(MainActivity.this, "✅ 已更新收據機 Wi-Fi IP 為 " + receiptIp, Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNeutralButton("🏷️ 設為貼紙機 (Wi-Fi)", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d2, int w2) {
                                        labelIp = chosen.ip;
                                        PosPrintEngine.labelConnMode = PosPrintEngine.CONN_WIFI;
                                        dbHelper.setSetting("label_ip", labelIp);
                                        dbHelper.setSetting("label_conn_mode", PosPrintEngine.CONN_WIFI);
                                        refreshPrinterStatusBadge();
                                        rebuildSettingsTab();
                                        Toast.makeText(MainActivity.this, "✅ 已更新貼紙機 Wi-Fi IP 為 " + labelIp, Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                        }
                    })
                    .setNegativeButton("關閉", null)
                    .show();
            }
        });
    }

    private void triggerCloudFullBackup() {
        if (!ensureFeatureAllowed(LicenseManager.FEATURE_CLOUD_BACKUP, "Google 雲端硬碟備份與同步")) return;
        org.json.JSONObject snap = dbHelper.exportFullSnapshotJson(menuItems, closingHistory);
        Toast.makeText(this, "⏳ 正在備份完整系統與營業資料至店家 Google 雲端硬碟...", Toast.LENGTH_SHORT).show();
        CloudSyncManager.backupFullSnapshotAsync(googleSheetUrl, snap, new CloudSyncManager.SyncCallback() {
            @Override public void onResult(boolean ok, String msg) {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle(ok ? "✅ 雲端硬碟備份成功" : "❌ 雲端硬碟備份失敗")
                    .setMessage(msg)
                    .setPositiveButton("確定", null)
                    .show();
            }
        });
    }

    private void triggerCloudRestore() {
        new AlertDialog.Builder(this)
            .setTitle("♻️ 確認從店家 Google 雲端硬碟一鍵回復？")
            .setMessage("此操作將從雲端【" + PosPrintEngine.getBackupFolderName() + "】資料夾讀取最新備份檔，並覆寫本機的：\n• 門市品牌與設定\n• 菜單品項與價格\n• 視覺化桌位排列\n• 員工權限與歷史關帳紀錄\n\n確定要立即還原嗎？")
            .setNegativeButton("取消", null)
            .setPositiveButton("立即一鍵還原", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    Toast.makeText(MainActivity.this, "⏳ 正在從 Google 雲端硬碟讀取最新備份...", Toast.LENGTH_SHORT).show();
                    CloudSyncManager.restoreLatestSnapshotAsync(googleSheetUrl, new CloudSyncManager.SnapshotCallback() {
                        @Override public void onResult(boolean ok, String msg, org.json.JSONObject snapshot) {
                            if (!ok || snapshot == null) {
                                new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("⚠️ 無法回復")
                                    .setMessage(msg)
                                    .setPositiveButton("好", null)
                                    .show();
                                return;
                            }
                            try {
                                String summary = dbHelper.restoreFromSnapshotJson(snapshot);
                                loadDatabaseState();
                                updateBrandAndStaffHeader();
                                refreshAllPosViews();
                                refreshPrinterStatusBadge();
                                rebuildSettingsTab();
                                new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("✅ 系統一鍵回復完成！")
                                    .setMessage("已成功從雲端還原：\n" + summary)
                                    .setPositiveButton("太棒了", null)
                                    .show();
                            } catch (Exception e) {
                                Toast.makeText(MainActivity.this, "還原寫入錯誤：" + e.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                }
            })
            .show();
    }

    private void showOtaUpdateDialog() {
        final int curCode = getAppVersionCode();
        Toast.makeText(this, "🔄 正在連線系統雲端檢查最新版本...", Toast.LENGTH_SHORT).show();
        CloudSyncManager.checkOtaUpdateAsync(otaManifestUrl, curCode, new CloudSyncManager.OtaCallback() {
            @Override public void onResult(boolean ok, String msg, final CloudSyncManager.OtaInfo info) {
                if (!ok || info == null) {
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("⚠️ 無法檢查更新")
                        .setMessage(msg)
                        .setPositiveButton("確定", null)
                        .show();
                    return;
                }
                AlertDialog.Builder b = new AlertDialog.Builder(MainActivity.this)
                    .setTitle(info.hasUpdate
                        ? "🚀 發現新版本 v" + info.versionName + " (Build " + info.versionCode + ")"
                        : "✅ 目前已是最新版本 (v" + getAppVersionName() + ")")
                    .setMessage("雲端最新版本：v" + info.versionName + " (Build " + info.versionCode + ")\n"
                        + "本機目前版本：v" + getAppVersionName() + " (Build " + curCode + ")\n\n"
                        + "📋 更新內容：\n" + (info.releaseNotes.isEmpty() ? "(無)" : info.releaseNotes));
                if (info.hasUpdate) {
                    b.setPositiveButton("⬇️ 立即下載並更新", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            startOtaDownloadAndInstall(info.apkUrl, "pos_update_v" + info.versionCode + ".apk");
                        }
                    });
                }
                if (info.rollbackUrl != null && !info.rollbackUrl.isEmpty()) {
                    b.setNeutralButton("⏪ 退回上一穩定版", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            startOtaDownloadAndInstall(info.rollbackUrl, "pos_rollback.apk");
                        }
                    });
                }
                b.setNegativeButton("關閉", null).show();
            }
        });
    }

    private void startOtaDownloadAndInstall(String apkUrl, String fileName) {
        final java.io.File destFile = new java.io.File(PosFileProvider.getOtaDir(this), fileName);
        Toast.makeText(this, "⬇️ 正在下載系統更新檔，請稍候...", Toast.LENGTH_SHORT).show();
        CloudSyncManager.downloadApkAsync(apkUrl, destFile, new CloudSyncManager.DownloadCallback() {
            @Override public void onProgress(int percent) {
                if (percent == 50) {
                    Toast.makeText(MainActivity.this, "⬇️ 下載進度 50%...", Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onComplete(boolean success, String message, java.io.File apkFile) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                if (success && apkFile != null && apkFile.exists()) {
                    try {
                        android.net.Uri apkUri = PosFileProvider.getApkUri(MainActivity.this, apkFile.getName());
                        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("⚠️ 請允許安裝未知應用程式")
                            .setMessage("請至平板「設定 ➔ 應用程式 ➔ 智慧餐飲 POS ➔ 安裝未知應用程式」開啟允許後，再點一次更新。\n錯誤：" + e.getMessage())
                            .setPositiveButton("確定", null)
                            .show();
                    }
                }
            }
        });
    }

    private void showTechLogsDialog() {
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(PosPrintEngine.getTechLogsText());
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        tv.setBackgroundColor(Color.parseColor("#0F172A"));
        sv.addView(tv);
        new AlertDialog.Builder(this)
            .setTitle("🩺 系統連線與印表機技術日誌 (最近 50 筆)")
            .setView(sv)
            .setPositiveButton("關閉", null)
            .show();
    }

    private void showLicenseActivationDialog(String reasonBanner) {
        if (licenseState == null) refreshLicenseState();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));

        if (reasonBanner != null && !reasonBanner.isEmpty()) {
            TextView tvWarn = new TextView(this);
            tvWarn.setText(reasonBanner);
            tvWarn.setTextColor(Color.parseColor("#B91C1C"));
            tvWarn.setTextSize(13);
            tvWarn.setTypeface(null, Typeface.BOLD);
            tvWarn.setPadding(0, 0, 0, dp(8));
            box.addView(tvWarn);
        }

        TextView tvInfo = new TextView(this);
        tvInfo.setText("• 本機設備識別碼：" + licenseState.deviceCode + "（請傳給供應商以簽發授權碼）\n"
            + "• 門市代碼：" + PosPrintEngine.storeCode + "\n"
            + "• 目前方案：" + licenseState.planLabel + "\n"
            + "• 目前狀態：" + licenseState.statusSummary + "\n"
            + "• 已解鎖功能模組：" + licenseState.enabledFeatures
            + "\n\n【模組代碼說明】core=基礎收銀出單｜qr=手機掃碼點餐｜cloud_backup=雲端硬碟備份｜staff=員工交班稽核｜cash_drawer=錢櫃彈開｜einvoice=電子發票｜multi_pay=多元支付");
        tvInfo.setTextSize(13);
        tvInfo.setPadding(0, 0, 0, dp(10));
        box.addView(tvInfo);

        final EditText etToken = new EditText(this);
        etToken.setHint("請貼上 POS1.xxxxx.yyyyy 商業授權碼");
        etToken.setText(dbHelper.getSetting("license_token", ""));
        etToken.setTextSize(12);
        etToken.setMinLines(2);
        box.addView(etToken);

        new AlertDialog.Builder(this)
            .setTitle("🔑 商業授權管理與功能分級開通")
            .setView(box)
            .setNegativeButton("關閉", null)
            .setNeutralButton("📤 匯出本機資料快照", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    org.json.JSONObject snap = dbHelper.exportFullSnapshotJson(menuItems, closingHistory);
                    String path = dbHelper.exportSnapshotToLocalFile(snap);
                    Toast.makeText(MainActivity.this, "✅ 已匯出：" + path, Toast.LENGTH_LONG).show();
                }
            })
            .setPositiveButton("✅ 驗證並啟用授權碼", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    try {
                        LicenseManager.LicenseState updated = LicenseManager.verifyAndSaveToken(
                            MainActivity.this, dbHelper, etToken.getText().toString().trim(), PosPrintEngine.storeCode);
                        licenseState = updated;
                        refreshLicenseState();
                        rebuildSettingsTab();
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("✅ 授權驗證成功！")
                            .setMessage("目前方案：" + updated.planLabel
                                + "\n狀態：" + updated.statusSummary
                                + "\n已解鎖模組：" + updated.enabledFeatures)
                            .setPositiveButton("太棒了", null)
                            .show();
                    } catch (Exception e) {
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("❌ 授權碼無效")
                            .setMessage(e.getMessage())
                            .setPositiveButton("確定", null)
                            .show();
                    }
                }
            })
            .show();
    }

    private void showEinvoiceSettingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(10), dp(16), dp(10));

        final CheckBox cbEnabled = new CheckBox(this);
        cbEnabled.setText("✅ 啟用結帳開立台灣電子發票（免開發票小店維持不勾選即可）");
        cbEnabled.setChecked(TaiwanCommerceGateway.invoiceCfg.enabled);
        box.addView(cbEnabled);

        final CheckBox cbPrintWithCarrier = new CheckBox(this);
        cbPrintWithCarrier.setText("使用手機條碼 / 愛心碼時仍列印紙本證明聯（預設無紙化不印）");
        cbPrintWithCarrier.setChecked(TaiwanCommerceGateway.invoiceCfg.printProofWithCarrier);
        box.addView(cbPrintWithCarrier);

        final String[] modeChoice = {TaiwanCommerceGateway.invoiceCfg.mode};
        final Button btnMode = new Button(this);
        btnMode.setAllCaps(false);
        btnMode.setText(TaiwanCommerceGateway.INV_MODE_WEBHOOK.equals(modeChoice[0])
            ? "🌐 模式：通用加值中心 Webhook 中繼（不綁定單一平台）"
            : "🧪 模式：本機模擬 Sandbox 字軌（免金鑰直接測證明聯排版）");
        btnMode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                modeChoice[0] = TaiwanCommerceGateway.INV_MODE_WEBHOOK.equals(modeChoice[0])
                    ? TaiwanCommerceGateway.INV_MODE_SANDBOX
                    : TaiwanCommerceGateway.INV_MODE_WEBHOOK;
                btnMode.setText(TaiwanCommerceGateway.INV_MODE_WEBHOOK.equals(modeChoice[0])
                    ? "🌐 模式：通用加值中心 Webhook 中繼（不綁定單一平台）"
                    : "🧪 模式：本機模擬 Sandbox 字軌（免金鑰直接測證明聯排版）");
            }
        });
        box.addView(btnMode);

        final EditText etTaxId = new EditText(this);
        etTaxId.setHint("賣方 8 碼統一編號 (例 12345675)");
        etTaxId.setInputType(InputType.TYPE_CLASS_NUMBER);
        etTaxId.setText(TaiwanCommerceGateway.invoiceCfg.sellerTaxId);
        box.addView(etTaxId);

        final EditText etTrack = new EditText(this);
        etTrack.setHint("模擬字軌前綴 (2 碼大寫英文，預設 TW)");
        etTrack.setText(TaiwanCommerceGateway.invoiceCfg.trackPrefix);
        box.addView(etTrack);

        final EditText etRelay = new EditText(this);
        etRelay.setHint("加值中心 Webhook URL (預留：綠界/藍新/關貿或店家自架中繼)");
        etRelay.setText(TaiwanCommerceGateway.invoiceCfg.relayUrl);
        box.addView(etRelay);

        final EditText etMerch = new EditText(this);
        etMerch.setHint("加值中心商店代號 Merchant ID (選填)");
        etMerch.setText(TaiwanCommerceGateway.invoiceCfg.merchantId);
        box.addView(etMerch);

        final EditText etApiKey = new EditText(this);
        etApiKey.setHint("加值中心 HMAC API Key (選填，僅存本機)");
        etApiKey.setText(TaiwanCommerceGateway.invoiceCfg.apiKey);
        box.addView(etApiKey);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
            .setTitle("🧾 台灣電子發票整合設定（各加值中心通用預留）")
            .setView(sv)
            .setNegativeButton("取消", null)
            .setNeutralButton("👁️ 預覽模擬證明聯", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    OrderRecord demo = PosPrintEngine.createDemoOrder();
                    demo.invoiceNo = "TW-00100001";
                    demo.invoicePeriod = TaiwanCommerceGateway.formatRocPeriod(new Date());
                    demo.invoiceRandom = "5678";
                    demo.invoiceStatus = "SANDBOX";
                    demo.invoiceQrLeft = "TW00100001115111356780000012c0000012c0000000000000000DEMO";
                    demo.invoiceQrRight = "**白醬起司麵:1:300";
                    showBitmapPreviewDialog("🧾 台灣電子發票證明聯樣張預覽",
                        PosPrintEngine.renderEinvoiceProofBitmap(demo, receiptWidthMm));
                }
            })
            .setPositiveButton("💾 儲存發票設定", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String tax = etTaxId.getText().toString().trim();
                    if (!tax.isEmpty() && !TaiwanCommerceGateway.isValidTaiwanTaxId(tax)) {
                        Toast.makeText(MainActivity.this, "❌ 賣方統編檢查碼錯誤，未儲存", Toast.LENGTH_LONG).show();
                        return;
                    }
                    String tr = etTrack.getText().toString().trim().toUpperCase(Locale.US);
                    if (!tr.matches("^[A-Z]{2}$")) tr = "TW";
                    TaiwanCommerceGateway.invoiceCfg.enabled = cbEnabled.isChecked();
                    TaiwanCommerceGateway.invoiceCfg.printProofWithCarrier = cbPrintWithCarrier.isChecked();
                    TaiwanCommerceGateway.invoiceCfg.mode = modeChoice[0];
                    TaiwanCommerceGateway.invoiceCfg.sellerTaxId = tax;
                    TaiwanCommerceGateway.invoiceCfg.trackPrefix = tr;
                    TaiwanCommerceGateway.invoiceCfg.relayUrl = etRelay.getText().toString().trim();
                    TaiwanCommerceGateway.invoiceCfg.merchantId = etMerch.getText().toString().trim();
                    TaiwanCommerceGateway.invoiceCfg.apiKey = etApiKey.getText().toString().trim();
                    TaiwanCommerceGateway.saveToDb(dbHelper);
                    rebuildSettingsTab();
                    Toast.makeText(MainActivity.this, "✅ 已儲存電子發票設定", Toast.LENGTH_SHORT).show();
                }
            })
            .show();
    }

    private void showMultiPaymentSettingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(10), dp(16), dp(10));

        final String[] lpMode = {TaiwanCommerceGateway.paymentCfg.linePayMode};
        final Button btnLpMode = new Button(this);
        btnLpMode.setAllCaps(false);
        btnLpMode.setText("🟢 LINE Pay 掃碼模式：" + lpMode[0]);
        btnLpMode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (TaiwanCommerceGateway.PAY_MODE_OFF.equals(lpMode[0])) lpMode[0] = TaiwanCommerceGateway.PAY_MODE_SANDBOX;
                else if (TaiwanCommerceGateway.PAY_MODE_SANDBOX.equals(lpMode[0])) lpMode[0] = TaiwanCommerceGateway.PAY_MODE_LIVE;
                else lpMode[0] = TaiwanCommerceGateway.PAY_MODE_OFF;
                btnLpMode.setText("🟢 LINE Pay 掃碼模式：" + lpMode[0]);
            }
        });
        box.addView(btnLpMode);

        final EditText etLpChId = new EditText(this);
        etLpChId.setHint("LINE Pay Channel ID (LIVE 模式必填)");
        etLpChId.setText(TaiwanCommerceGateway.paymentCfg.linePayChannelId);
        box.addView(etLpChId);

        final EditText etLpSecret = new EditText(this);
        etLpSecret.setHint("LINE Pay Channel Secret");
        etLpSecret.setText(TaiwanCommerceGateway.paymentCfg.linePayChannelSecret);
        box.addView(etLpSecret);

        final EditText etLpEp = new EditText(this);
        etLpEp.setHint("LINE Pay Offline API Endpoint");
        etLpEp.setText(TaiwanCommerceGateway.paymentCfg.linePayEndpoint);
        box.addView(etLpEp);

        final String[] jkoMode = {TaiwanCommerceGateway.paymentCfg.jkoPayMode};
        final Button btnJkoMode = new Button(this);
        btnJkoMode.setAllCaps(false);
        btnJkoMode.setText("🔴 街口支付掃碼模式：" + jkoMode[0]);
        btnJkoMode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (TaiwanCommerceGateway.PAY_MODE_OFF.equals(jkoMode[0])) jkoMode[0] = TaiwanCommerceGateway.PAY_MODE_SANDBOX;
                else if (TaiwanCommerceGateway.PAY_MODE_SANDBOX.equals(jkoMode[0])) jkoMode[0] = TaiwanCommerceGateway.PAY_MODE_LIVE;
                else jkoMode[0] = TaiwanCommerceGateway.PAY_MODE_OFF;
                btnJkoMode.setText("🔴 街口支付掃碼模式：" + jkoMode[0]);
            }
        });
        box.addView(btnJkoMode);

        final EditText etJkoStore = new EditText(this);
        etJkoStore.setHint("街口商店代號 Store ID");
        etJkoStore.setText(TaiwanCommerceGateway.paymentCfg.jkoStoreId);
        box.addView(etJkoStore);

        final EditText etJkoRelay = new EditText(this);
        etJkoRelay.setHint("街口 POS 中繼 API URL (LIVE 模式必填)");
        etJkoRelay.setText(TaiwanCommerceGateway.paymentCfg.jkoRelayUrl);
        box.addView(etJkoRelay);

        final EditText etJkoKey = new EditText(this);
        etJkoKey.setHint("街口 Merchant Secret Key");
        etJkoKey.setText(TaiwanCommerceGateway.paymentCfg.jkoMerchantKey);
        box.addView(etJkoKey);

        final CheckBox cbTwqr = new CheckBox(this);
        cbTwqr.setText("📱 啟用「TWQR 掃碼」收款視窗 (可顯示店家共通收款 QR Code)");
        cbTwqr.setChecked(TaiwanCommerceGateway.paymentCfg.twqrEnabled);
        box.addView(cbTwqr);

        final EditText etTwqrTpl = new EditText(this);
        etTwqrTpl.setHint("TWQR 字串範本 (支援 {AMOUNT} 與 {ORDER_NO})");
        etTwqrTpl.setText(TaiwanCommerceGateway.paymentCfg.twqrPayloadTemplate);
        box.addView(etTwqrTpl);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
            .setTitle("💳 多元支付設定 (OFF=純記帳 ｜ SANDBOX=本機模擬 ｜ LIVE=正式連線)")
            .setView(sv)
            .setNegativeButton("取消", null)
            .setPositiveButton("💾 儲存多元支付設定", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    TaiwanCommerceGateway.paymentCfg.linePayMode = lpMode[0];
                    TaiwanCommerceGateway.paymentCfg.linePayChannelId = etLpChId.getText().toString().trim();
                    TaiwanCommerceGateway.paymentCfg.linePayChannelSecret = etLpSecret.getText().toString().trim();
                    TaiwanCommerceGateway.paymentCfg.linePayEndpoint = etLpEp.getText().toString().trim();

                    TaiwanCommerceGateway.paymentCfg.jkoPayMode = jkoMode[0];
                    TaiwanCommerceGateway.paymentCfg.jkoStoreId = etJkoStore.getText().toString().trim();
                    TaiwanCommerceGateway.paymentCfg.jkoRelayUrl = etJkoRelay.getText().toString().trim();
                    TaiwanCommerceGateway.paymentCfg.jkoMerchantKey = etJkoKey.getText().toString().trim();

                    TaiwanCommerceGateway.paymentCfg.twqrEnabled = cbTwqr.isChecked();
                    TaiwanCommerceGateway.paymentCfg.twqrPayloadTemplate = etTwqrTpl.getText().toString().trim();
                    TaiwanCommerceGateway.saveToDb(dbHelper);
                    rebuildSettingsTab();
                    Toast.makeText(MainActivity.this, "✅ 已儲存多元支付閘道設定", Toast.LENGTH_SHORT).show();
                }
            })
            .show();
    }
}
