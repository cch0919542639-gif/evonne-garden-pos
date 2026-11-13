package com.pos.tablet;

import android.Manifest;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 雙印表機多通道直連引擎 (Wi-Fi TCP Port 9100 / 藍牙 SPP / USB OTG)：
 * 1. HPRT TP805L / 通用 ESC/POS 收據機 (支援動態調整字體大小倍率、有效列印點寬、左右防裁切安全邊距)
 * 2. GoDEX DT2x / 標籤貼紙機 (40x30mm = 320x240 dots @ 203DPI Canvas 點陣圖封裝 EZPL / TSPL)
 */
public class PosPrintEngine {

    public static final String CONN_WIFI = "WIFI";
    public static final String CONN_BT = "BLUETOOTH";
    public static final String CONN_USB = "USB";

    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String ACTION_USB_PERMISSION = "com.pos.tablet.USB_PERMISSION";
    private static volatile Context appContext;
    private static final Object BT_LOCK = new Object();
    private static final Object USB_LOCK = new Object();

    public static void init(Context ctx) {
        if (ctx != null) appContext = ctx.getApplicationContext();
    }

    public static class BtDeviceItem {
        public final String name;
        public final String mac;
        public BtDeviceItem(String name, String mac) {
            this.name = name;
            this.mac = mac;
        }
    }

    public static class UsbDeviceItem {
        public final String label;
        public final String vidPid;
        public final boolean isPrinterClass;
        public final boolean hasPermission;
        public UsbDeviceItem(String label, String vidPid, boolean isPrinterClass, boolean hasPermission) {
            this.label = label;
            this.vidPid = vidPid;
            this.isPrinterClass = isPrinterClass;
            this.hasPermission = hasPermission;
        }
    }

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

    // 錢櫃 RJ-11 自動彈開控制 (透過出單機 ESC/POS ESC p 脈衝)
    public static volatile boolean autoOpenCashDrawerOnCash = false;
    private static final byte[] ESC_POS_KICK_DRAWER = new byte[]{0x1B, 0x70, 0x00, 0x19, (byte) 0xFA};

    // 連線通道設定 (預設皆為 WIFI Port 9100，可切換為 BLUETOOTH / USB)
    public static volatile String receiptConnMode = CONN_WIFI;
    public static volatile String receiptBtMac = "";
    public static volatile String receiptUsbId = "";
    public static volatile String labelConnMode = CONN_WIFI;
    public static volatile String labelBtMac = "";
    public static volatile String labelUsbId = "";

    public static String formatTarget(String mode, String ip, String btMac, String usbId) {
        if (CONN_BT.equals(mode)) {
            return (btMac != null && !btMac.trim().isEmpty()) ? ("BT " + btMac.trim()) : "BT (未配對)";
        }
        if (CONN_USB.equals(mode)) {
            return (usbId != null && !usbId.trim().isEmpty() && !"AUTO".equalsIgnoreCase(usbId.trim()))
                ? ("USB " + usbId.trim()) : "USB OTG(自動)";
        }
        return ip != null ? ip.trim() : "";
    }

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
                String recTarget = formatTarget(receiptConnMode, receiptIp, receiptBtMac, receiptUsbId);
                String labTarget = formatTarget(labelConnMode, labelIp, labelBtMac, labelUsbId);

                // A. 列印收據/廚房總單 (含選擇性錢櫃彈開脈衝)
                if (order.printReceipt) {
                    try {
                        Bitmap recBmp = renderReceiptBitmap(order, receiptWidthMm);
                        byte[] escPosBytes = encodeEscPosRasterReceipt(recBmp, true);
                        if (order.triggerCashDrawer) {
                            ByteArrayOutputStream combined = new ByteArrayOutputStream(escPosBytes.length + ESC_POS_KICK_DRAWER.length);
                            combined.write(escPosBytes);
                            combined.write(ESC_POS_KICK_DRAWER);
                            escPosBytes = combined.toByteArray();
                        }
                        if (shouldPrintEinvoiceProof(order)) {
                            Bitmap proofBmp = renderEinvoiceProofBitmap(order, receiptWidthMm);
                            byte[] proofBytes = encodeEscPosRasterReceipt(proofBmp, true);
                            ByteArrayOutputStream combined = new ByteArrayOutputStream(escPosBytes.length + proofBytes.length);
                            combined.write(escPosBytes);
                            combined.write(proofBytes);
                            escPosBytes = combined.toByteArray();
                        }
                        sendBytes(receiptConnMode, receiptIp, receiptPort, receiptBtMac, receiptUsbId, escPosBytes);
                        logs.add("🧾 出單機(" + recTarget + ")：列印成功"
                            + (shouldPrintEinvoiceProof(order) ? " + 電子發票證明聯(" + order.invoiceNo + ")" : "")
                            + (order.triggerCashDrawer ? " (已開錢箱)" : ""));
                    } catch (Exception e) {
                        ok = false;
                        addTechLog("出單機連線失敗(" + recTarget + "): " + e.getMessage());
                        logs.add("⚠️ 出單機(" + recTarget + ")未連線：" + e.getMessage());
                    }
                } else if (order.triggerCashDrawer) {
                    try {
                        sendBytes(receiptConnMode, receiptIp, receiptPort, receiptBtMac, receiptUsbId, ESC_POS_KICK_DRAWER);
                        logs.add("💰 已透過出單機彈開錢櫃");
                    } catch (Exception e) {
                        ok = false;
                        addTechLog("錢櫃開啟失敗(" + recTarget + "): " + e.getMessage());
                        logs.add("⚠️ 錢櫃開啟失敗：" + e.getMessage());
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
                            sendBytes(labelConnMode, labelIp, labelPort, labelBtMac, labelUsbId, allLabels.toByteArray());
                            logs.add("🏷️ 貼紙機(" + labTarget + ")：已印 " + (cur - 1) + " 張");
                        }
                    } catch (Exception e) {
                        ok = false;
                        addTechLog("貼紙機連線失敗(" + labTarget + "): " + e.getMessage());
                        logs.add("⚠️ 貼紙機(" + labTarget + ")未連線：" + e.getMessage());
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

    // ==================== 2. 每日關帳日結報表列印 (出單機) ====================
    public static void printClosingReportAsync(final MainActivity.ClosingRecord cr,
                                               final Map<String, Integer> byPay,
                                               final Map<String, Integer> itemRank,
                                               final String receiptIp, final int receiptPort,
                                               final int receiptWidthMm,
                                               final PrintCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                String recTarget = formatTarget(receiptConnMode, receiptIp, receiptBtMac, receiptUsbId);
                try {
                    Bitmap bmp = renderClosingReportBitmap(cr, byPay, itemRank, receiptWidthMm);
                    byte[] payload = encodeEscPosRasterReceipt(bmp, true);
                    sendBytes(receiptConnMode, receiptIp, receiptPort, receiptBtMac, receiptUsbId, payload);
                    postResult(callback, true, "🧾 日結報表列印成功！(" + recTarget + ")");
                } catch (Exception e) {
                    postResult(callback, false, "⚠️ 日結單列印失敗 (" + recTarget + ")：" + e.getMessage());
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
                String recTarget = formatTarget(receiptConnMode, receiptIp, receiptBtMac, receiptUsbId);
                try {
                    Bitmap bmp = renderShiftHandoverBitmap(sh, receiptWidthMm);
                    byte[] payload = encodeEscPosRasterReceipt(bmp, true);
                    sendBytes(receiptConnMode, receiptIp, receiptPort, receiptBtMac, receiptUsbId, payload);
                    postResult(callback, true, "🤝 交班對帳單列印成功！(" + recTarget + ")");
                } catch (Exception e) {
                    postResult(callback, false, "⚠️ 交班單列印失敗 (" + recTarget + ")：" + e.getMessage());
                }
            }
        });
    }

    public static void openCashDrawerAsync(final String receiptIp, final int receiptPort,
                                           final PrintCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                String recTarget = formatTarget(receiptConnMode, receiptIp, receiptBtMac, receiptUsbId);
                try {
                    sendBytes(receiptConnMode, receiptIp, receiptPort, receiptBtMac, receiptUsbId, ESC_POS_KICK_DRAWER);
                    postResult(callback, true, "💰 錢櫃已彈開！(" + recTarget + ")");
                } catch (Exception e) {
                    addTechLog("手動開錢箱失敗(" + recTarget + "): " + e.getMessage());
                    postResult(callback, false, "❌ 開錢箱失敗：" + e.getMessage());
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
                String target = formatTarget(receiptConnMode, ip, receiptBtMac, receiptUsbId);
                try {
                    MainActivity.OrderRecord demo = createDemoOrder();
                    Bitmap bmp = renderReceiptBitmap(demo, widthMm);
                    byte[] payload = encodeEscPosRasterReceipt(bmp, true);
                    sendBytes(receiptConnMode, ip, port, receiptBtMac, receiptUsbId, payload);
                    postResult(callback, true, "✅ 出單機 (" + target + ") 測試收據列印成功！");
                } catch (Exception e) {
                    postResult(callback, false, "❌ 出單機 (" + target + ") 連線失敗：" + e.getMessage());
                }
            }
        });
    }

    public static void printTestLabelAsync(final String ip, final int port, final String protocol,
                                           final PrintCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                String target = formatTarget(labelConnMode, ip, labelBtMac, labelUsbId);
                try {
                    Bitmap bmp = renderLabelBitmap40x30(
                        "#001", "外帶", 1, 1,
                        "▲檸檬冬瓜", "▲甜度固定 / 3分微冰 / +寒天愛玉($15)", "12:30", 65);
                    byte[] payload = "TSPL".equalsIgnoreCase(protocol)
                        ? encodeTsplLabel40x30(bmp)
                        : encodeEzplLabel40x30(bmp);
                    sendBytes(labelConnMode, ip, port, labelBtMac, labelUsbId, payload);
                    postResult(callback, true, "✅ 貼紙機 (" + target + ") 測試貼紙列印成功！");
                } catch (Exception e) {
                    postResult(callback, false, "❌ 貼紙機 (" + target + ") 連線失敗：" + e.getMessage());
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
                String target = formatTarget(receiptConnMode, receiptIp, receiptBtMac, receiptUsbId);
                try {
                    Bitmap bmp = QrCodeGenerator.renderTableQrStandeeBitmap(
                        tableName, orderUrl, receiptPrintableDots);
                    byte[] payload = encodeEscPosRasterReceipt(bmp, true);
                    sendBytes(receiptConnMode, receiptIp, receiptPort, receiptBtMac, receiptUsbId, payload);
                    postResult(callback, true, "✅ 已從出單機 (" + target + ") 印出【" + tableName + "】掃碼點餐立牌！");
                } catch (Exception e) {
                    postResult(callback, false, "❌ 列印桌牌失敗 (" + target + ")：" + e.getMessage());
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

    // ==================== 多通道統一傳送入口 (Wi-Fi TCP / Bluetooth SPP / USB OTG) ====================
    private static void sendBytes(String mode, String ip, int port, String btMac, String usbId, byte[] data) throws Exception {
        if (data == null || data.length == 0) return;
        if (CONN_BT.equals(mode)) {
            sendBluetoothSpp(btMac, data);
        } else if (CONN_USB.equals(mode)) {
            sendUsbOtg(usbId, data);
        } else {
            sendTcpWithRetry(ip, port, data);
        }
    }

    // ==================== 1. Wi-Fi TCP Socket 傳送 (含逾時保護與自動重試) ====================
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

    // ==================== 2. 藍牙 SPP RFCOMM 傳送 ====================
    private static void sendBluetoothSpp(String mac, byte[] data) throws Exception {
        if (mac == null || mac.trim().isEmpty()) {
            throw new IllegalArgumentException("尚未指定藍牙印表機 MAC 位址，請先於設定頁配對選取");
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) throw new IllegalStateException("此平板不支援藍牙");
        if (!adapter.isEnabled()) throw new IllegalStateException("藍牙未開啟，請先於平板系統開啟藍牙");
        if (Build.VERSION.SDK_INT >= 31 && (appContext == null
            || appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
            throw new SecurityException("缺少藍牙「鄰近裝置」連線權限，請於設定頁點擊選取藍牙以授權");
        }

        synchronized (BT_LOCK) {
            try { adapter.cancelDiscovery(); } catch (Exception ignored) {}
            BluetoothDevice device = adapter.getRemoteDevice(mac.trim().toUpperCase(Locale.US));
            BluetoothSocket socket = null;
            try {
                try {
                    socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                    socket.connect();
                } catch (Exception primaryErr) {
                    if (socket != null) try { socket.close(); } catch (Exception ignored) {}
                    socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                    socket.connect();
                }
                OutputStream os = socket.getOutputStream();
                int offset = 0;
                final int chunk = 1024;
                while (offset < data.length) {
                    int len = Math.min(chunk, data.length - offset);
                    os.write(data, offset, len);
                    os.flush();
                    offset += len;
                }
                // 預留短暫緩衝讓印表機收齊尾端指令後再關閉 RFCOMM
                try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            } finally {
                if (socket != null) try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ==================== 3. USB OTG Host Bulk-Out 傳送 ====================
    private static class UsbPrinterEndpoint {
        final UsbInterface intf;
        final UsbEndpoint epOut;
        UsbPrinterEndpoint(UsbInterface intf, UsbEndpoint epOut) {
            this.intf = intf;
            this.epOut = epOut;
        }
    }

    private static String formatUsbVidPid(UsbDevice d) {
        return String.format(Locale.US, "%04X:%04X", d.getVendorId(), d.getProductId());
    }

    private static UsbPrinterEndpoint findPrinterBulkOutEndpoint(UsbDevice dev) {
        if (dev == null) return null;
        UsbPrinterEndpoint fallback = null;
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface intf = dev.getInterface(i);
            int cls = intf.getInterfaceClass();
            UsbEndpoint out = null;
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    out = ep;
                    break;
                }
            }
            if (out == null) continue;
            if (cls == UsbConstants.USB_CLASS_PRINTER) {
                return new UsbPrinterEndpoint(intf, out);
            }
            // 跳過讀卡機/隨身碟/鍵盤滑鼠/集線器類別，保留轉接晶片 (如 CDC/Vendor) 作為備選
            if (fallback == null
                && cls != UsbConstants.USB_CLASS_MASS_STORAGE
                && cls != UsbConstants.USB_CLASS_HID
                && cls != UsbConstants.USB_CLASS_HUB
                && cls != UsbConstants.USB_CLASS_AUDIO) {
                fallback = new UsbPrinterEndpoint(intf, out);
            }
        }
        return fallback;
    }

    private static UsbDevice findTargetUsbDevice(UsbManager um, String usbId) {
        if (um == null) return null;
        String want = usbId != null ? usbId.trim().toUpperCase(Locale.US) : "";
        boolean autoMode = want.isEmpty() || "AUTO".equals(want);
        UsbDevice autoCandidate = null;
        for (UsbDevice dev : um.getDeviceList().values()) {
            UsbPrinterEndpoint ep = findPrinterBulkOutEndpoint(dev);
            if (ep == null) continue;
            if (!autoMode) {
                if (want.equals(formatUsbVidPid(dev))) return dev;
            } else {
                if (ep.intf.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) return dev;
                if (autoCandidate == null) autoCandidate = dev;
            }
        }
        return autoCandidate;
    }

    private static void ensureUsbPermission(UsbManager um, UsbDevice dev) throws Exception {
        if (um.hasPermission(dev)) return;
        if (appContext == null) throw new IllegalStateException("POS 列印引擎尚未初始化 Context");

        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] grantedHolder = new boolean[]{false};
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
                UsbDevice extraDev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (extraDev == null || extraDev.getDeviceId() == dev.getDeviceId()) {
                    grantedHolder[0] = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    latch.countDown();
                }
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(receiver, filter);
        }
        try {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            Intent permIntent = new Intent(ACTION_USB_PERMISSION).setPackage(appContext.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(appContext, dev.getDeviceId(), permIntent, flags);
            um.requestPermission(dev, pi);
            boolean completed = latch.await(15, TimeUnit.SECONDS);
            if (!completed || (!grantedHolder[0] && !um.hasPermission(dev))) {
                throw new SecurityException("未取得 USB OTG 裝置存取權限，請插拔轉接線後點擊「允許」");
            }
        } finally {
            try { appContext.unregisterReceiver(receiver); } catch (Exception ignored) {}
        }
    }

    private static void sendUsbOtg(String usbId, byte[] data) throws Exception {
        if (appContext == null) throw new IllegalStateException("POS 列印引擎尚未初始化 Context");
        UsbManager um = (UsbManager) appContext.getSystemService(Context.USB_SERVICE);
        if (um == null) throw new IllegalStateException("此平板不支援 USB OTG Host");

        synchronized (USB_LOCK) {
            UsbDevice dev = findTargetUsbDevice(um, usbId);
            if (dev == null) {
                throw new IllegalStateException("未偵測到 USB 出單機/貼紙機，請確認 OTG 轉接線與電源已插妥");
            }
            UsbPrinterEndpoint ep = findPrinterBulkOutEndpoint(dev);
            if (ep == null) {
                throw new IllegalStateException("USB 裝置 (" + formatUsbVidPid(dev) + ") 無可用列印端點");
            }
            ensureUsbPermission(um, dev);

            UsbDeviceConnection conn = um.openDevice(dev);
            if (conn == null) throw new IllegalStateException("無法開啟 USB 裝置連線 (" + formatUsbVidPid(dev) + ")");
            boolean claimed = false;
            try {
                claimed = conn.claimInterface(ep.intf, true);
                if (!claimed) throw new IllegalStateException("無法佔用 USB 列印介面");
                int offset = 0;
                final int chunkMax = 16384;
                while (offset < data.length) {
                    int len = Math.min(chunkMax, data.length - offset);
                    byte[] slice = new byte[len];
                    System.arraycopy(data, offset, slice, 0, len);
                    int written = conn.bulkTransfer(ep.epOut, slice, len, SO_TIMEOUT_MS);
                    if (written < 0) {
                        throw new RuntimeException("USB bulkTransfer 寫入失敗 (code=" + written + ")");
                    }
                    offset += written > 0 ? written : len;
                }
            } finally {
                if (claimed) try { conn.releaseInterface(ep.intf); } catch (Exception ignored) {}
                try { conn.close(); } catch (Exception ignored) {}
            }
        }
    }

    public static List<BtDeviceItem> listBondedBluetoothDevices(Context ctx) throws Exception {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) throw new IllegalStateException("此平板不支援藍牙");
        if (!adapter.isEnabled()) throw new IllegalStateException("平板藍牙尚未開啟");
        if (Build.VERSION.SDK_INT >= 31 && ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("NEED_BLUETOOTH_CONNECT");
        }
        List<BtDeviceItem> list = new ArrayList<>();
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice d : bonded) {
                String name = d.getName();
                if (name == null || name.trim().isEmpty()) name = "藍牙裝置";
                list.add(new BtDeviceItem(name, d.getAddress()));
            }
        }
        return list;
    }

    public static List<UsbDeviceItem> listUsbPrinterCandidates(Context ctx) {
        List<UsbDeviceItem> list = new ArrayList<>();
        UsbManager um = ctx != null ? (UsbManager) ctx.getSystemService(Context.USB_SERVICE) : null;
        if (um == null) return list;
        for (UsbDevice dev : um.getDeviceList().values()) {
            UsbPrinterEndpoint ep = findPrinterBulkOutEndpoint(dev);
            if (ep == null) continue;
            String prod = Build.VERSION.SDK_INT >= 21 ? dev.getProductName() : null;
            if (prod == null || prod.trim().isEmpty()) prod = "USB 列印/串列裝置";
            boolean isPrn = ep.intf.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER;
            String vidPid = formatUsbVidPid(dev);
            String label = (isPrn ? "🖨️ " : "🔌 ") + prod + " [" + vidPid + "]"
                + (um.hasPermission(dev) ? " (已授權)" : " (點擊授權)");
            list.add(new UsbDeviceItem(label, vidPid, isPrn, um.hasPermission(dev)));
        }
        return list;
    }

    public static void requestUsbPermissionAsync(final Context ctx, final String vidPid, final PrintCallback cb) {
        init(ctx);
        EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                try {
                    UsbManager um = (UsbManager) appContext.getSystemService(Context.USB_SERVICE);
                    UsbDevice dev = findTargetUsbDevice(um, vidPid);
                    if (dev == null) {
                        postResult(cb, false, "未找到對應的 USB OTG 裝置");
                        return;
                    }
                    ensureUsbPermission(um, dev);
                    postResult(cb, true, "✅ 已取得 USB OTG 裝置 (" + formatUsbVidPid(dev) + ") 授權！");
                } catch (Exception e) {
                    postResult(cb, false, "⚠️ " + e.getMessage());
                }
            }
        });
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
        StringBuilder metaSb = new StringBuilder();
        if ("現金".equals(order.paymentMethod)) {
            metaSb.append("實收：$").append(order.cashReceived).append("   找零：$").append(order.cashChange);
        }
        if (order.paymentTxId != null && !order.paymentTxId.trim().isEmpty()) {
            if (metaSb.length() > 0) metaSb.append("\n");
            metaSb.append("交易序號：").append(order.paymentTxId.trim());
        }
        if (order.invoiceNo != null && !order.invoiceNo.trim().isEmpty() && !"VOIDED".equals(order.invoiceStatus)) {
            if (metaSb.length() > 0) metaSb.append("\n");
            metaSb.append("發票號碼：").append(order.invoiceNo.trim());
            if (TaiwanCommerceGateway.CARRIER_MOBILE.equals(order.carrierType)) {
                metaSb.append(" (手機條碼 ").append(order.carrierCode).append(")");
            } else if (TaiwanCommerceGateway.CARRIER_DONATE.equals(order.carrierType)) {
                metaSb.append(" (愛心碼 ").append(order.carrierCode).append(")");
            }
        }
        if (metaSb.length() > 0) {
            slCash = buildLayout(metaSb.toString(), tpItem, innerW, Layout.Alignment.ALIGN_NORMAL);
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

    public static boolean shouldPrintEinvoiceProof(MainActivity.OrderRecord order) {
        if (order == null || order.voided || "VOIDED".equals(order.invoiceStatus)) return false;
        if (order.invoiceNo == null || order.invoiceNo.trim().isEmpty()) return false;
        boolean hasBuyer = order.buyerTaxId != null && !order.buyerTaxId.trim().isEmpty();
        boolean isPaper = order.carrierType == null || order.carrierType.isEmpty()
            || TaiwanCommerceGateway.CARRIER_PAPER.equals(order.carrierType);
        return isPaper || hasBuyer || TaiwanCommerceGateway.invoiceCfg.printProofWithCarrier;
    }

    public static void printEinvoiceProofAsync(final MainActivity.OrderRecord order,
                                               final String receiptIp, final int receiptPort,
                                               final int widthMm, final PrintCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                String target = formatTarget(receiptConnMode, receiptIp, receiptBtMac, receiptUsbId);
                try {
                    if (order == null || order.invoiceNo == null || order.invoiceNo.trim().isEmpty()) {
                        throw new IllegalArgumentException("此訂單尚未開立電子發票");
                    }
                    Bitmap bmp = renderEinvoiceProofBitmap(order, widthMm);
                    byte[] payload = encodeEscPosRasterReceipt(bmp, true);
                    sendBytes(receiptConnMode, receiptIp, receiptPort, receiptBtMac, receiptUsbId, payload);
                    postResult(callback, true, "🧾 已列印電子發票證明聯 (" + order.invoiceNo + ")");
                } catch (Exception e) {
                    postResult(callback, false, "⚠️ 證明聯列印失敗 (" + target + ")：" + e.getMessage());
                }
            }
        });
    }

    /**
     * 台灣財政部規範風格：電子發票證明聯點陣排版 (57/80mm 相容，含雙 QR Code)
     */
    public static Bitmap renderEinvoiceProofBitmap(MainActivity.OrderRecord order, int widthMm) {
        int totalDots = Math.max(320, (receiptPrintableDots / 8) * 8);
        int leftPad = Math.max(0, receiptLeftMarginDots);
        int rightPad = Math.max(8, receiptRightMarginDots);
        int innerW = Math.max(200, totalDots - leftPad - rightPad);

        float wf = (totalDots <= 400) ? 0.80f : 1.0f;
        TextPaint tpBrand = makeBoldPaint(28f * wf);
        TextPaint tpTitle = makeBoldPaint(30f * wf);
        TextPaint tpBigNo = makeBoldPaint(34f * wf);
        TextPaint tpInfo = makeBoldPaint(20f * wf);

        String brand = getEffectiveShortName();
        if ("SANDBOX".equals(order.invoiceStatus)) {
            brand += " [測試模擬]";
        }
        String period = (order.invoicePeriod != null && !order.invoicePeriod.isEmpty())
            ? order.invoicePeriod : TaiwanCommerceGateway.formatRocPeriod(new java.util.Date());
        String invNo = order.invoiceNo != null ? order.invoiceNo : "TW-00000000";
        String rnd = (order.invoiceRandom != null && !order.invoiceRandom.isEmpty()) ? order.invoiceRandom : "0000";
        String seller = TaiwanCommerceGateway.isValidTaiwanTaxId(TaiwanCommerceGateway.invoiceCfg.sellerTaxId)
            ? TaiwanCommerceGateway.invoiceCfg.sellerTaxId : "00000000";
        String dtStr = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.TAIWAN).format(new java.util.Date())
            + " " + (order.time != null ? order.time : "");

        StringBuilder meta = new StringBuilder();
        meta.append(dtStr).append("\n");
        meta.append("隨機碼：").append(rnd).append("   總計：").append(order.total).append("\n");
        meta.append("賣方：").append(seller);
        if (order.buyerTaxId != null && !order.buyerTaxId.trim().isEmpty()) {
            meta.append("   買方：").append(order.buyerTaxId.trim());
        }

        StaticLayout slBrand = buildLayout(brand, tpBrand, innerW, Layout.Alignment.ALIGN_CENTER);
        StaticLayout slTitle = buildLayout("電子發票證明聯", tpTitle, innerW, Layout.Alignment.ALIGN_CENTER);
        StaticLayout slPeriod = buildLayout(period + "\n" + invNo, tpBigNo, innerW, Layout.Alignment.ALIGN_CENTER);
        StaticLayout slMeta = buildLayout(meta.toString(), tpInfo, innerW, Layout.Alignment.ALIGN_NORMAL);

        int qrSize = Math.min(180, Math.max(120, (innerW - 24) / 2));
        String qlStr = (order.invoiceQrLeft != null && !order.invoiceQrLeft.isEmpty()) ? order.invoiceQrLeft : invNo;
        String qrStr = (order.invoiceQrRight != null && !order.invoiceQrRight.isEmpty()) ? order.invoiceQrRight : "**";
        Bitmap bmpQrL = QrCodeGenerator.generateQrBitmap(qlStr, qrSize);
        Bitmap bmpQrR = QrCodeGenerator.generateQrBitmap(qrStr, qrSize);

        int qrRealSize = Math.max(bmpQrL.getHeight(), bmpQrR.getHeight());
        int totalH = 20 + slBrand.getHeight() + 6 + slTitle.getHeight() + 6
            + slPeriod.getHeight() + 12 + slMeta.getHeight() + 14 + qrRealSize + 34;

        Bitmap bmp = Bitmap.createBitmap(totalDots, totalH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);

        int y = 14;
        drawStaticLayout(canvas, slBrand, leftPad, y);
        y += slBrand.getHeight() + 6;
        drawStaticLayout(canvas, slTitle, leftPad, y);
        y += slTitle.getHeight() + 6;
        drawStaticLayout(canvas, slPeriod, leftPad, y);
        y += slPeriod.getHeight() + 10;
        drawStaticLayout(canvas, slMeta, leftPad, y);
        y += slMeta.getHeight() + 12;

        int gap = 16;
        int pairW = bmpQrL.getWidth() + gap + bmpQrR.getWidth();
        int startX = leftPad + Math.max(0, (innerW - pairW) / 2);
        canvas.drawBitmap(bmpQrL, startX, y, null);
        canvas.drawBitmap(bmpQrR, startX + bmpQrL.getWidth() + gap, y, null);
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

    // ==================== 印表機連線狀態偵測 (Wi-Fi / 藍牙 / USB OTG) ====================
    public static void probePrintersStatusAsync(final String receiptIp, final int receiptPort,
                                                final String labelIp, final int labelPort,
                                                final StatusCallback cb) {
        EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                final boolean rOk = probeSinglePrinter(receiptConnMode, receiptIp, receiptPort, receiptBtMac, receiptUsbId);
                final boolean lOk = probeSinglePrinter(labelConnMode, labelIp, labelPort, labelBtMac, labelUsbId);
                MAIN_HANDLER.post(new Runnable() {
                    @Override public void run() {
                        if (cb != null) cb.onStatus(rOk, lOk);
                    }
                });
            }
        });
    }

    private static boolean probeSinglePrinter(String mode, String ip, int port, String btMac, String usbId) {
        if (CONN_BT.equals(mode)) {
            if (btMac == null || btMac.trim().isEmpty()) return false;
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return false;
            if (Build.VERSION.SDK_INT >= 31 && (appContext == null
                || appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
                return false;
            }
            try {
                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                if (bonded == null) return false;
                String target = btMac.trim().toUpperCase(Locale.US);
                for (BluetoothDevice d : bonded) {
                    if (target.equals(d.getAddress())) return true;
                }
            } catch (Exception ignored) {}
            return false;
        }
        if (CONN_USB.equals(mode)) {
            if (appContext == null) return false;
            UsbManager um = (UsbManager) appContext.getSystemService(Context.USB_SERVICE);
            return findTargetUsbDevice(um, usbId) != null;
        }
        return isPortOpen(ip, port, 750);
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
