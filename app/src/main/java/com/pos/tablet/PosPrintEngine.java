package com.pos.tablet;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wi-Fi 雙印表機直連引擎 (TCP Port 9100)：
 * 1. HPRT TP805L (支援動態調整字體大小倍率、有效列印點寬、左右防裁切安全邊距 + 全區塊 StaticLayout 自動換行)
 * 2. GoDEX DT2x (40x30mm = 320x240 dots @ 203DPI Canvas 點陣圖封裝 EZPL / TSPL，支援字體大小微調)
 */
public class PosPrintEngine {

    public interface PrintCallback {
        void onComplete(boolean allSuccess, String message);
    }

    public interface StatusCallback {
        void onStatus(boolean receiptOnline, boolean labelOnline);
    }

    public static class DiscoveredPrinter {
        public String ip;
        public String hint;
        public boolean isGoDex;
        public DiscoveredPrinter(String ip, String hint, boolean isGoDex) {
            this.ip = ip;
            this.hint = hint;
            this.isGoDex = isGoDex;
        }
    }

    public interface DiscoveryCallback {
        void onResult(String subnetInfo, List<DiscoveredPrinter> printers);
    }

    private static final List<String> TECH_LOGS = new ArrayList<>();

    public static synchronized void addTechLog(String msg) {
        String ts = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.TAIWAN)
            .format(new java.util.Date());
        TECH_LOGS.add(0, "[" + ts + "] " + msg);
        while (TECH_LOGS.size() > 50) TECH_LOGS.remove(TECH_LOGS.size() - 1);
    }

    public static synchronized String getTechLogsText() {
        if (TECH_LOGS.isEmpty()) return "(目前尚無異常或連線日誌)";
        StringBuilder sb = new StringBuilder();
        for (String line : TECH_LOGS) sb.append(line).append("\n");
        return sb.toString();
    }

    // ==================== 白牌門市品牌設定 (由 SQLite app_settings 動態載入) ====================
    public static volatile String storeCode = "STORE-001";
    public static volatile String storeName = "我的餐飲門市";
    public static volatile String storeShortName = "我的門市";
    public static volatile String storeSubtitle = "";
    public static volatile String storeReceiptFooter = "";

    public static String getEffectiveShortName() {
        if (storeShortName != null && !storeShortName.trim().isEmpty()) return storeShortName.trim();
        if (storeName != null && !storeName.trim().isEmpty()) return storeName.trim();
        return "智慧餐飲";
    }

    public static String getBackupFolderName() {
        String base = getEffectiveShortName().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (base.isEmpty()) base = "POS門市";
        return base + "_POS營業備份";
    }

    // ==================== 可動態調整之出單機與貼紙機版面參數 ====================
    // 出單機字體縮放百分比：100(標準), 125(適中), 145(加大-預設), 165(特大), 185(超大), 210(巨字)
    public static volatile int receiptFontScalePercent = 145;
    // 出單機總點陣寬度：352(58mm防裁切), 384(58mm標準), 480(80mm窄安全), 512(80mm防裁切預設), 544(80mm寬), 576(80mm滿版)
    public static volatile int receiptPrintableDots = 512;
    // 出單機左側留白點數 (防止左邊緣裁切)
    public static volatile int receiptLeftMarginDots = 24;
    // 出單機右側安全內縮點數 (防止右邊緣裁切)
    public static volatile int receiptRightMarginDots = 28;

    // 貼紙機字體縮放百分比：100(標準), 115(加大-預設), 130(特大)
    public static volatile int labelFontScalePercent = 115;
    // 貼紙機左右安全內縮點數
    public static volatile int labelMarginDots = 12;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int SO_TIMEOUT_MS = 5000;

    // ==================== 1. 訂單雙機併發列印 (收據 + 貼紙) ====================
    public static void printOrderAsync(final MainActivity.OrderRecord order,
                                       final String receiptIp, final int receiptPort, final int receiptWidthMm,
                                       final String labelIp, final int labelPort, final String labelProtocol,
                                       final PrintCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                final List<String> logs = new ArrayList<>();
                boolean ok = true;

                // A. 列印 HPRT TP805L 收據/廚房總單
                if (order.printReceipt) {
                    try {
                        Bitmap recBmp = renderReceiptBitmap(order, receiptWidthMm);
                        byte[] escPosBytes = encodeEscPosRasterReceipt(recBmp, true);
                        sendTcpWithRetry(receiptIp, receiptPort, escPosBytes);
                        logs.add("🧾 出單機(" + receiptIp + ")：列印成功");
                    } catch (Exception e) {
                        ok = false;
                        addTechLog("出單機連線失敗(" + receiptIp + ":" + receiptPort + "): " + e.getMessage());
                        logs.add("⚠️ 出單機(" + receiptIp + ")未連線：" + e.getMessage());
                    }
                }

                // B. 列印 GoDEX DT2x 40x30mm 貼紙 (支援主品項與隨餐附贈/換購飲料自動拆張 1/N)
                if (order.stickerCount > 0) {
                    try {
                        ByteArrayOutputStream allLabels = new ByteArrayOutputStream();
                        int cur = 1;
                        for (MainActivity.CartItem ci : order.items) {
                            // 1. 主品項貼紙 (單點飲料 或 外帶/外送餐盒)
                            if (ci.printLabel) {
                                int unitActual = Math.max(0, ci.getSubTotal() / Math.max(1, ci.qty));
                                for (int k = 0; k < ci.qty; k++) {
                                    Bitmap lblBmp = renderLabelBitmap40x30(
                                        order.callNum, order.orderType, cur, order.stickerCount,
                                        ci.menu.name, ci.getModSummary(), order.time, unitActual);
                                    byte[] cmd = "TSPL".equalsIgnoreCase(labelProtocol)
                                        ? encodeTsplLabel40x30(lblBmp)
                                        : encodeEzplLabel40x30(lblBmp);
                                    allLabels.write(cmd);
                                    cur++;
                                }
                            }
                            // 2. 主餐隨餐附贈 / 補差價換購之飲料專屬貼紙 (內用、外帶、外送皆自動印飲料杯貼紙)
                            if (ci.hasAttachedDrink() && ci.printDrinkLabel) {
                                String drinkTitle = "[附飲] " + ci.mealDrinkName;
                                String drinkMods = ci.getAttachedDrinkModSummary();
                                int drinkDiff = Math.max(0, ci.mealDrinkDiff);
                                for (int k = 0; k < ci.qty; k++) {
                                    Bitmap drinkBmp = renderLabelBitmap40x30(
                                        order.callNum, order.orderType, cur, order.stickerCount,
                                        drinkTitle, drinkMods, order.time, drinkDiff);
                                    byte[] cmd = "TSPL".equalsIgnoreCase(labelProtocol)
                                        ? encodeTsplLabel40x30(drinkBmp)
                                        : encodeEzplLabel40x30(drinkBmp);
                                    allLabels.write(cmd);
                                    cur++;
                                }
                            }
                        }
                        if (allLabels.size() > 0) {
                            sendTcpWithRetry(labelIp, labelPort, allLabels.toByteArray());
                            logs.add("🏷️ 貼紙機(" + labelIp + ")：已印 " + (cur - 1) + " 張");
                        }
                    } catch (Exception e) {
                        ok = false;
                        logs.add("⚠️ 貼紙機(" + labelIp + ")未連線：" + e.getMessage());
                    }
                }

                final boolean finalOk = ok;
                final String msg = logs.isEmpty() ? "本次未勾選硬體出單" : joinLogs(logs);
                MAIN_HANDLER.post(new Runnable() {
                    @Override public void run() {
                        if (callback != null) callback.onComplete(finalOk, msg);
                    }
                });
            }
        });
    }

    // ==================== 2. 每日關帳日結報表列印 (HPRT TP805L) ====================
    public static void printClosingReportAsync(final MainActivity.ClosingRecord cr,
                                               final Map<String, Integer> byPay,
                                               final Map<String, Integer> itemRank,
                                               final String receiptIp, final int receiptPort,
                                               final int receiptWidthMm,
                                               final PrintCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Bitmap bmp = renderClosingReportBitmap(cr, byPay, itemRank, receiptWidthMm);
                    byte[] payload = encodeEscPosRasterReceipt(bmp, true);
                    sendTcpWithRetry(receiptIp, receiptPort, payload);
                    postResult(callback, true, "🧾 HPRT TP805L 日結報表列印成功！");
                } catch (Exception e) {
                    postResult(callback, false, "⚠️ 日結單列印失敗 (" + receiptIp + ")：" + e.getMessage());
                }
            }
        });
    }

    public static void printShiftHandoverAsync(final MainActivity.ShiftHandoverRecord sh,
                                               final String receiptIp, final int receiptPort,
                                               final int receiptWidthMm,
                                               final PrintCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Bitmap bmp = renderShiftHandoverBitmap(sh, receiptWidthMm);
                    byte[] payload = encodeEscPosRasterReceipt(bmp, true);
                    sendTcpWithRetry(receiptIp, receiptPort, payload);
                    postResult(callback, true, "🤝 HPRT TP805L 交班對帳單列印成功！");
                } catch (Exception e) {
                    postResult(callback, false, "⚠️ 交班單列印失敗 (" + receiptIp + ")：" + e.getMessage());
                }
            }
        });
    }

    // ==================== 3. 測試出單機與測試貼紙機 ====================
    public static MainActivity.OrderRecord createDemoOrder() {
        MainActivity.OrderRecord demo = new MainActivity.OrderRecord();
        demo.callNum = "#001";
        demo.time = "12:30";
        demo.orderType = "內用(1桌)";
        demo.paymentMethod = "現金";
        demo.total = 300;
        demo.cashReceived = 500;
        demo.cashChange = 200;

        MainActivity.MenuItem m1 = new MainActivity.MenuItem(
            101, "義大利麵&飯食", "白醬起司麵", "", 200, false, true, false, false, false, new String[]{"奶素"});
        MainActivity.CartItem ci1 = new MainActivity.CartItem();
        ci1.menu = m1;
        ci1.qty = 1;
        ci1.selectedChoice = "奶素";
        ci1.mealDrinkName = "▲檸檬冬瓜";
        ci1.mealDrinkDiff = 20;
        ci1.mealDrinkSweet = "▲甜度固定";
        ci1.mealDrinkIce = "3分微冰";
        ci1.addons.add(new MainActivity.AddonOption("升級全素起司", 80));
        demo.items.add(ci1);
        return demo;
    }

    public static void printTestReceiptAsync(final String ip, final int port, final int widthMm,
                                             final PrintCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    MainActivity.OrderRecord demo = createDemoOrder();
                    Bitmap bmp = renderReceiptBitmap(demo, widthMm);
                    byte[] payload = encodeEscPosRasterReceipt(bmp, true);
                    sendTcpWithRetry(ip, port, payload);
                    postResult(callback, true, "✅ HPRT TP805L (" + ip + ":" + port + ") 測試收據列印成功！");
                } catch (Exception e) {
                    postResult(callback, false, "❌ HPRT TP805L (" + ip + ":" + port + ") 連線失敗：" + e.getMessage());
                }
            }
        });
    }

    public static void printTestLabelAsync(final String ip, final int port, final String protocol,
                                           final PrintCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Bitmap bmp = renderLabelBitmap40x30(
                        "#001", "外帶", 1, 1,
                        "▲檸檬冬瓜", "▲甜度固定 / 3分微冰 / +寒天愛玉($15)", "12:30", 65);
                    byte[] payload = "TSPL".equalsIgnoreCase(protocol)
                        ? encodeTsplLabel40x30(bmp)
                        : encodeEzplLabel40x30(bmp);
                    sendTcpWithRetry(ip, port, payload);
                    postResult(callback, true, "✅ GoDEX DT2x (" + ip + ":" + port + ") 測試貼紙列印成功！");
                } catch (Exception e) {
                    postResult(callback, false, "❌ GoDEX DT2x (" + ip + ":" + port + ") 連線失敗：" + e.getMessage());
                }
            }
        });
    }

    public static void printTableQrStandeeAsync(final String tableName, final String orderUrl,
                                                final String receiptIp, final int receiptPort,
                                                final PrintCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Bitmap bmp = QrCodeGenerator.renderTableQrStandeeBitmap(
                        tableName, orderUrl, receiptPrintableDots);
                    byte[] payload = encodeEscPosRasterReceipt(bmp, true);
                    sendTcpWithRetry(receiptIp, receiptPort, payload);
                    postResult(callback, true, "✅ 已從 HPRT TP805L 印出【" + tableName + "】掃碼點餐立牌！");
                } catch (Exception e) {
                    postResult(callback, false, "❌ 列印桌牌失敗 (" + receiptIp + ")：" + e.getMessage());
                }
            }
        });
    }

    private static void postResult(final PrintCallback cb, final boolean ok, final String msg) {
        MAIN_HANDLER.post(new Runnable() {
            @Override public void run() {
                if (cb != null) cb.onComplete(ok, msg);
            }
        });
    }

    // ==================== TCP Socket 傳送 (含逾時保護與自動重試) ====================
    private static void sendTcpWithRetry(String ip, int port, byte[] data) throws Exception {
        if (ip == null || ip.trim().isEmpty()) {
            throw new IllegalArgumentException("未設定印表機 IP");
        }
        Exception lastErr = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(ip.trim(), port), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(SO_TIMEOUT_MS);
                OutputStream os = socket.getOutputStream();
                os.write(data);
                os.flush();
                return;
            } catch (Exception e) {
                lastErr = e;
            } finally {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
        throw lastErr != null ? lastErr : new RuntimeException("TCP 傳送失敗");
    }

    // ==================== HPRT TP805L 收據渲染 (全 StaticLayout 自動換行 + 防邊緣裁切) ====================
    private static TextPaint makeBoldPaint(float sizePx) {
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.BLACK);
        tp.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        tp.setTextSize(sizePx);
        return tp;
    }

    private static StaticLayout buildLayout(String text, TextPaint paint, int width, Layout.Alignment align) {
        String safe = (text != null) ? text : "";
        return StaticLayout.Builder
            .obtain(safe, 0, safe.length(), paint, Math.max(120, width))
            .setAlignment(align)
            .setLineSpacing(4f, 1.05f)
            .setIncludePad(true)
            .build();
    }

    public static Bitmap renderReceiptBitmap(MainActivity.OrderRecord order, int widthMm) {
        // 確保總點寬為 8 的倍數
        int totalDots = Math.max(320, (receiptPrintableDots / 8) * 8);
        int leftPad = Math.max(0, receiptLeftMarginDots);
        int rightPad = Math.max(8, receiptRightMarginDots);
        int innerW = Math.max(180, totalDots - leftPad - rightPad);

        float scale = Math.max(0.8f, receiptFontScalePercent / 100f);
        // 針對 58mm 窄紙自動微調基準字級，80mm 則使用大基準字級
        float widthFactor = (totalDots <= 400) ? 0.82f : 1.0f;

        TextPaint tpStore = makeBoldPaint(28f * widthFactor * scale);
        TextPaint tpSub = makeBoldPaint(18f * widthFactor * scale);
        TextPaint tpCall = makeBoldPaint(36f * widthFactor * scale);
        TextPaint tpItem = makeBoldPaint(28f * widthFactor * scale);
        TextPaint tpMod = makeBoldPaint(23f * widthFactor * scale);
        TextPaint tpTotal = makeBoldPaint(32f * widthFactor * scale);

        // 1. 標題區 (動態門市品牌資訊)
        String headerTitle = (storeName != null && !storeName.trim().isEmpty()) ? storeName.trim() : "智慧餐飲 POS";
        StaticLayout slStore = buildLayout(headerTitle, tpStore, innerW, Layout.Alignment.ALIGN_CENTER);
        StaticLayout slAddr = (storeSubtitle != null && !storeSubtitle.trim().isEmpty())
            ? buildLayout(storeSubtitle.trim(), tpSub, innerW, Layout.Alignment.ALIGN_CENTER)
            : null;

        // 2. 取餐號與時間區 (使用自動換行避免字體放大後右邊被切掉)
        String callHeader = order.callNum + "  [" + order.orderType + "]  " + order.time
            + ((order.staffName != null && !order.staffName.isEmpty()) ? "  經手:" + order.staffName : "");
        StaticLayout slCall = buildLayout(callHeader, tpCall, innerW, Layout.Alignment.ALIGN_NORMAL);

        // 3. 逐筆品項（主品項放大粗體 + 客製明細縮排自動換行）
        List<StaticLayout> itemLayouts = new ArrayList<>();
        for (MainActivity.CartItem ci : order.items) {
            String line1 = "■ " + ci.menu.name + "  x" + ci.qty + "   $" + ci.getSubTotal();
            itemLayouts.add(buildLayout(line1, tpItem, innerW, Layout.Alignment.ALIGN_NORMAL));

            String line2 = "  └ " + ci.getModSummary();
            itemLayouts.add(buildLayout(line2, tpMod, innerW, Layout.Alignment.ALIGN_NORMAL));
        }

        // 4. 底部總計區（修正原本 Align.CENTER 導致左邊被切掉的問題，全面改用 ALIGN_NORMAL 自動換行）
        StringBuilder footerSb = new StringBuilder();
        if (order.orderDiscount > 0) {
            footerSb.append("整單折扣：-$").append(order.orderDiscount).append("\n");
        }
        footerSb.append("應收(").append(order.paymentMethod).append(")：$").append(order.total);
        StaticLayout slTotal = buildLayout(footerSb.toString(), tpTotal, innerW, Layout.Alignment.ALIGN_NORMAL);

        StaticLayout slCash = null;
        if ("現金".equals(order.paymentMethod)) {
            String cashLine = "實收：$" + order.cashReceived + "   找零：$" + order.cashChange;
            slCash = buildLayout(cashLine, tpItem, innerW, Layout.Alignment.ALIGN_NORMAL);
        }
        StaticLayout slCustomFooter = (storeReceiptFooter != null && !storeReceiptFooter.trim().isEmpty())
            ? buildLayout(storeReceiptFooter.trim(), tpSub, innerW, Layout.Alignment.ALIGN_CENTER)
            : null;

        // 計算總高度
        int totalH = 24 + slStore.getHeight() + 4 + (slAddr != null ? slAddr.getHeight() + 14 : 8)
            + slCall.getHeight() + 20;
        for (StaticLayout sl : itemLayouts) {
            totalH += sl.getHeight() + 6;
        }
        totalH += 24 + slTotal.getHeight() + 10;
        if (slCash != null) totalH += slCash.getHeight() + 10;
        if (slCustomFooter != null) totalH += slCustomFooter.getHeight() + 12;
        totalH += 30;

        Bitmap bmp = Bitmap.createBitmap(totalDots, totalH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);

        Paint linePaint = new Paint();
        linePaint.setColor(Color.BLACK);
        linePaint.setStrokeWidth(3f);

        int y = 12;
        drawStaticLayout(canvas, slStore, leftPad, y);
        y += slStore.getHeight() + 4;

        if (slAddr != null) {
            drawStaticLayout(canvas, slAddr, leftPad, y);
            y += slAddr.getHeight() + 10;
        } else {
            y += 6;
        }

        drawStaticLayout(canvas, slCall, leftPad, y);
        y += slCall.getHeight() + 8;

        canvas.drawLine(leftPad, y, leftPad + innerW, y, linePaint);
        y += 10;

        for (int i = 0; i < itemLayouts.size(); i++) {
            StaticLayout sl = itemLayouts.get(i);
            drawStaticLayout(canvas, sl, leftPad, y);
            y += sl.getHeight() + (i % 2 == 1 ? 10 : 2);
        }

        y += 4;
        canvas.drawLine(leftPad, y, leftPad + innerW, y, linePaint);
        y += 12;

        drawStaticLayout(canvas, slTotal, leftPad, y);
        y += slTotal.getHeight() + 6;

        if (slCash != null) {
            drawStaticLayout(canvas, slCash, leftPad, y);
            y += slCash.getHeight() + 6;
        }
        if (slCustomFooter != null) {
            drawStaticLayout(canvas, slCustomFooter, leftPad, y + 4);
        }

        return bmp;
    }

    private static void drawStaticLayout(Canvas canvas, StaticLayout layout, int x, int y) {
        canvas.save();
        canvas.translate(x, y);
        layout.draw(canvas);
        canvas.restore();
    }

    public static Bitmap renderClosingReportBitmap(MainActivity.ClosingRecord cr,
                                                   Map<String, Integer> byPay,
                                                   Map<String, Integer> itemRank,
                                                   int widthMm) {
        int totalDots = Math.max(320, (receiptPrintableDots / 8) * 8);
        int leftPad = Math.max(0, receiptLeftMarginDots);
        int rightPad = Math.max(8, receiptRightMarginDots);
        int innerW = Math.max(180, totalDots - leftPad - rightPad);

        float scale = Math.max(0.8f, receiptFontScalePercent / 100f);
        float widthFactor = (totalDots <= 400) ? 0.82f : 1.0f;

        StringBuilder sb = new StringBuilder();
        sb.append(getEffectiveShortName()).append(" - 每日關帳日結單\n");
        sb.append("時間：").append(cr.time).append("\n");
        sb.append("========================\n");
        sb.append("有效訂單：").append(cr.validCount).append(" 筆\n");
        sb.append("作廢退單：").append(cr.voidCount).append(" 筆\n");
        sb.append("今日淨營業額：$").append(cr.netSales).append("\n");
        sb.append("現金實收小計：$").append(cr.cashSales).append("\n");
        sb.append("期初留底零用金：$").append(cr.openingFloat).append("\n");
        sb.append("錢櫃應有總現金：$").append(cr.openingFloat + cr.cashSales).append("\n");
        sb.append("========================\n");
        sb.append("【各付款方式統計】\n");
        for (Map.Entry<String, Integer> e : byPay.entrySet()) {
            sb.append("• ").append(e.getKey()).append("：$").append(e.getValue()).append("\n");
        }
        sb.append("========================\n");
        sb.append("【熱銷品項統計】\n");
        int shown = 0;
        for (Map.Entry<String, Integer> e : itemRank.entrySet()) {
            sb.append("• ").append(e.getKey()).append(" x").append(e.getValue()).append("\n");
            if (++shown >= 30) break;
        }

        TextPaint tp = makeBoldPaint(25f * widthFactor * scale);
        StaticLayout layout = buildLayout(sb.toString(), tp, innerW, Layout.Alignment.ALIGN_NORMAL);

        int h = layout.getHeight() + 50;
        Bitmap bmp = Bitmap.createBitmap(totalDots, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);
        drawStaticLayout(canvas, layout, leftPad, 16);
        return bmp;
    }

    public static Bitmap renderShiftHandoverBitmap(MainActivity.ShiftHandoverRecord sh, int widthMm) {
        int totalDots = Math.max(320, (receiptPrintableDots / 8) * 8);
        int leftPad = Math.max(0, receiptLeftMarginDots);
        int rightPad = Math.max(8, receiptRightMarginDots);
        int innerW = Math.max(180, totalDots - leftPad - rightPad);

        float scale = Math.max(0.8f, receiptFontScalePercent / 100f);
        float widthFactor = (totalDots <= 400) ? 0.82f : 1.0f;

        StringBuilder sb = new StringBuilder();
        sb.append(getEffectiveShortName()).append(" - 班別交班對帳單\n");
        sb.append("========================\n");
        sb.append("交班人員：").append(sh.staffName).append("\n");
        sb.append("開班時間：").append(sh.shiftStart).append("\n");
        sb.append("交班時間：").append(sh.shiftEnd).append("\n");
        sb.append("------------------------\n");
        sb.append("本班有效單：").append(sh.orderCount).append(" 筆\n");
        sb.append("本班作廢單：").append(sh.voidCount).append(" 筆\n");
        sb.append("期初備用金：$").append(sh.openingCash).append("\n");
        sb.append("本班現金收入：$").append(sh.cashSales).append("\n");
        sb.append("本班非現金收入：$").append(sh.nonCashSales).append("\n");
        sb.append("========================\n");
        sb.append("錢櫃應有現金：$").append(sh.expectedCash).append("\n");
        sb.append("實點現金金額：$").append(sh.countedCash).append("\n");
        String diffPrefix = sh.diffCash > 0 ? "+" : "";
        sb.append("盤點溢/短差：").append(diffPrefix).append("$").append(sh.diffCash).append("\n");
        if (sh.note != null && !sh.note.trim().isEmpty()) {
            sb.append("備註：").append(sh.note.trim()).append("\n");
        }
        sb.append("========================\n");
        sb.append("交班人簽名：____________\n");
        sb.append("接班人簽名：____________\n");

        TextPaint tp = makeBoldPaint(25f * widthFactor * scale);
        StaticLayout layout = buildLayout(sb.toString(), tp, innerW, Layout.Alignment.ALIGN_NORMAL);
        int h = layout.getHeight() + 50;
        Bitmap bmp = Bitmap.createBitmap(totalDots, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);
        drawStaticLayout(canvas, layout, leftPad, 16);
        return bmp;
    }

    /**
     * 將單色 Bitmap 轉為標準 ESC/POS GS v 0 (0x1D 0x76 0x30 0x00) 光柵位元組 + 自動切刀 GS V 66 0
     */
    public static byte[] encodeEscPosRasterReceipt(Bitmap bmp, boolean cutPaper) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int width = bmp.getWidth();
        int height = bmp.getHeight();
        int bytesPerRow = (width + 7) / 8;

        // ESC @ 初始化印表機
        out.write(0x1B);
        out.write(0x40);

        // 分段傳送避免印表機緩衝區溢位 (每次最多 120 行像素)
        int chunkH = 120;
        for (int startY = 0; startY < height; startY += chunkH) {
            int sliceH = Math.min(chunkH, height - startY);
            out.write(0x1D);
            out.write(0x76);
            out.write(0x30);
            out.write(0x00);
            out.write(bytesPerRow & 0xFF);
            out.write((bytesPerRow >> 8) & 0xFF);
            out.write(sliceH & 0xFF);
            out.write((sliceH >> 8) & 0xFF);

            for (int y = startY; y < startY + sliceH; y++) {
                for (int byteIdx = 0; byteIdx < bytesPerRow; byteIdx++) {
                    int b = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        int x = byteIdx * 8 + bit;
                        if (x < width) {
                            int pixel = bmp.getPixel(x, y);
                            int lum = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3;
                            if (lum < 150) {
                                b |= (1 << (7 - bit)); // ESC/POS: 1 = 黑點, 0 = 白點
                            }
                        }
                    }
                    out.write(b);
                }
            }
        }

        // 走紙 4 行 + 自動切刀 GS V 66 0 (0x1D 0x56 0x42 0x00)
        if (cutPaper) {
            byte[] cutCmd = new byte[]{0x0A, 0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x42, 0x00};
            out.write(cutCmd, 0, cutCmd.length);
        }
        return out.toByteArray();
    }

    // ==================== GoDEX DT2x 40x30mm 貼紙渲染與 EZPL / TSPL 封裝 ====================
    /**
     * 渲染 40x30mm (203 DPI = 320x240 dots) 標籤黑白點陣圖 (支援字體縮放與防裁切邊距)
     */
    public static Bitmap renderLabelBitmap40x30(String callNum, String orderType,
                                                int idx, int total,
                                                String itemName, String modSummary,
                                                String time, int unitPrice) {
        final int W = 320;
        final int H = 240;
        int pad = Math.max(6, labelMarginDots);
        int innerW = W - (pad * 2);
        float scale = Math.max(0.85f, labelFontScalePercent / 100f);

        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);

        TextPaint tpHeader = makeBoldPaint(23f * scale);
        // 1. 頂部列：取餐號 + 用餐別 + (1/N)
        canvas.drawText(callNum + " [" + orderType + "]", pad, 28, tpHeader);
        TextPaint tpIdx = makeBoldPaint(21f * scale);
        tpIdx.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(idx + "/" + total, W - pad, 27, tpIdx);

        Paint divPaint = new Paint();
        divPaint.setColor(Color.BLACK);
        divPaint.setStrokeWidth(2.5f);
        canvas.drawLine(pad, 35, W - pad, 35, divPaint);

        // 2. 品項名稱（粗體大字，過長自動微縮）
        float baseItemSize = (itemName.length() > 8 ? 23f : 27f) * scale;
        TextPaint tpItem = makeBoldPaint(baseItemSize);
        StaticLayout itemLayout = StaticLayout.Builder
            .obtain(itemName, 0, itemName.length(), tpItem, innerW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(1)
            .build();
        drawStaticLayout(canvas, itemLayout, pad, 39);

        // 3. 客製化與附餐/加價明細（自動換行）
        TextPaint tpMod = makeBoldPaint(19f * scale);
        StaticLayout modLayout = StaticLayout.Builder
            .obtain(modSummary, 0, modSummary.length(), tpMod, innerW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(4)
            .build();
        drawStaticLayout(canvas, modLayout, pad, 42 + itemLayout.getHeight());

        // 4. 底部店名、時間與單價
        canvas.drawLine(pad, H - 34, W - pad, H - 34, divPaint);
        TextPaint tpFooter = makeBoldPaint(18f * scale);
        canvas.drawText(getEffectiveShortName() + " " + time, pad, H - 10, tpFooter);
        tpFooter.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("$" + unitPrice, W - pad, H - 10, tpFooter);

        return bmp;
    }

    /**
     * 封裝為 GoDEX DT2x 原生 EZPL 指令 (^Q30,3 / ^W40 / ^L / Q0,0,40,240<CR><LF><raw_bitmap> / E)
     * 修正：GoDEX EZPL Pattern 標準語法為 Qx,y,width_bytes,height_dots (不含 v,)，避免印出空白標籤。
     */
    public static byte[] encodeEzplLabel40x30(Bitmap bmp) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int widthBytes = 40; // 320 / 8 = 40 bytes
        int height = 240;

        String header = "^Q30,3\r\n^W40\r\n^H12\r\n^P1\r\n^S3\r\n^L\r\n"
            + "Q0,0," + widthBytes + "," + height + "\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));

        for (int y = 0; y < height; y++) {
            for (int bx = 0; bx < widthBytes; bx++) {
                int b = 0;
                for (int bit = 0; bit < 8; bit++) {
                    int x = bx * 8 + bit;
                    int pixel = bmp.getPixel(x, y);
                    int lum = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3;
                    if (lum < 150) {
                        b |= (1 << (7 - bit)); // EZPL Q: 1 = 黑點, 0 = 白點
                    }
                }
                out.write(b);
            }
        }
        out.write("\r\nE\r\n".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    /**
     * 封裝為 TSPL 指令 (SIZE 40 mm,30 mm / GAP 3 mm,0 mm / CLS / BITMAP 0,0,40,240,0,<raw> / PRINT 1,1)
     */
    public static byte[] encodeTsplLabel40x30(Bitmap bmp) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int widthBytes = 40;
        int height = 240;

        String header = "SIZE 40 mm,30 mm\r\nGAP 3 mm,0 mm\r\nDIRECTION 1\r\nCLS\r\n"
            + "BITMAP 0,0," + widthBytes + "," + height + ",0,";
        out.write(header.getBytes(StandardCharsets.US_ASCII));

        for (int y = 0; y < height; y++) {
            for (int bx = 0; bx < widthBytes; bx++) {
                int b = 0xFF;
                for (int bit = 0; bit < 8; bit++) {
                    int x = bx * 8 + bit;
                    int pixel = bmp.getPixel(x, y);
                    int lum = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3;
                    if (lum < 150) {
                        b &= ~(1 << (7 - bit)); // TSPL mode 0: 0 = 黑點, 1 = 白點
                    }
                }
                out.write(b);
            }
        }
        out.write("\r\nPRINT 1,1\r\n".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static String joinLogs(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(" ｜ ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    // ==================== 區網印表機狀態偵測 & 自動搜尋 IP (Port 9100) ====================
    public static void probePrintersStatusAsync(final String receiptIp, final int receiptPort,
                                                final String labelIp, final int labelPort,
                                                final StatusCallback cb) {
        EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                final boolean rOk = isPortOpen(receiptIp, receiptPort, 750);
                final boolean lOk = isPortOpen(labelIp, labelPort, 750);
                MAIN_HANDLER.post(new Runnable() {
                    @Override public void run() {
                        if (cb != null) cb.onStatus(rOk, lOk);
                    }
                });
            }
        });
    }

    private static boolean isPortOpen(String ip, int port, int timeoutMs) {
        if (ip == null || ip.trim().isEmpty()) return false;
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(ip.trim(), port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) try { s.close(); } catch (Exception ignored) {}
        }
    }

    public static void discoverPrintersAsync(final DiscoveryCallback cb) {
        EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                final List<String> prefixes = getLocalIpv4Prefixes();
                final List<DiscoveredPrinter> found = java.util.Collections.synchronizedList(new ArrayList<DiscoveredPrinter>());
                if (prefixes.isEmpty()) {
                    addTechLog("區網搜尋失敗：未偵測到區域網路 IPv4 介面");
                    MAIN_HANDLER.post(new Runnable() {
                        @Override public void run() {
                            if (cb != null) cb.onResult("未連上區網 Wi-Fi", found);
                        }
                    });
                    return;
                }
                final String targetPrefix = prefixes.get(0);
                ExecutorService pool = Executors.newFixedThreadPool(32);
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(254);
                for (int i = 1; i <= 254; i++) {
                    final String candidateIp = targetPrefix + i;
                    pool.execute(new Runnable() {
                        @Override public void run() {
                            try {
                                if (isPortOpen(candidateIp, 9100, 350)) {
                                    DiscoveredPrinter dp = probePrinterIdentity(candidateIp);
                                    found.add(dp);
                                }
                            } finally {
                                latch.countDown();
                            }
                        }
                    });
                }
                try { latch.await(8, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
                pool.shutdownNow();
                addTechLog("區網掃描(" + targetPrefix + "0/24) 完成，找到 " + found.size() + " 台 Port 9100 設備");
                MAIN_HANDLER.post(new Runnable() {
                    @Override public void run() {
                        if (cb != null) cb.onResult(targetPrefix + "0/24", new ArrayList<>(found));
                    }
                });
            }
        });
    }

    private static DiscoveredPrinter probePrinterIdentity(String ip) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(ip, 9100), 600);
            s.setSoTimeout(450);
            OutputStream os = s.getOutputStream();
            os.write("~V\r\n".getBytes(StandardCharsets.US_ASCII));
            os.flush();
            byte[] buf = new byte[128];
            int n = s.getInputStream().read(buf);
            if (n > 0) {
                String resp = new String(buf, 0, n, StandardCharsets.US_ASCII).replaceAll("[\\r\\n]+", " ").trim();
                if (resp.toUpperCase(java.util.Locale.US).contains("GODEX")
                    || resp.toUpperCase(java.util.Locale.US).contains("DT2")) {
                    return new DiscoveredPrinter(ip, "GoDEX 貼紙機 (" + resp + ")", true);
                }
                return new DiscoveredPrinter(ip, "Port 9100 印表機 (" + resp + ")", false);
            }
        } catch (Exception ignored) {
        } finally {
            if (s != null) try { s.close(); } catch (Exception ignored) {}
        }
        return new DiscoveredPrinter(ip, "ESC/POS 收據出單機 (Port 9100)", false);
    }

    private static List<String> getLocalIpv4Prefixes() {
        List<String> list = new ArrayList<>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address && addr.isSiteLocalAddress()) {
                        String host = addr.getHostAddress();
                        int lastDot = host != null ? host.lastIndexOf('.') : -1;
                        if (lastDot > 0) {
                            String prefix = host.substring(0, lastDot + 1);
                            if (!list.contains(prefix)) list.add(prefix);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }
}
