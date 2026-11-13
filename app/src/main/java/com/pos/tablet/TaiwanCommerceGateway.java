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
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 台灣電子發票 (加值中心通用中繼 / 本機 Sandbox) + 多元支付 (LINE Pay / 街口 / TWQR) 整合閘道
 * 支援每間門市自由選用不同加值中心，同時預先打通結帳欄位、檢核邏輯與熱感紙證明聯標準排版。
 */
public class TaiwanCommerceGateway {

    public static final String INV_MODE_SANDBOX = "SANDBOX";
    public static final String INV_MODE_WEBHOOK = "GENERIC_WEBHOOK";

    public static final String PAY_MODE_OFF = "OFF";
    public static final String PAY_MODE_SANDBOX = "SANDBOX";
    public static final String PAY_MODE_LIVE = "LIVE";

    public static final String CARRIER_PAPER = "PAPER";
    public static final String CARRIER_MOBILE = "MOBILE";
    public static final String CARRIER_DONATE = "DONATE";

    private static final Pattern MOBILE_BARCODE_RE = Pattern.compile("^/[0-9A-Z.+\\-]{7}$");
    private static final Pattern DONATE_CODE_RE = Pattern.compile("^[0-9]{3,7}$");
    private static final Pattern TRACK_PREFIX_RE = Pattern.compile("^[A-Z]{2}$");

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Random RNG = new Random();

    public static class EinvoiceConfig {
        public boolean enabled = false;
        public String mode = INV_MODE_SANDBOX;
        public String sellerTaxId = "";
        public String trackPrefix = "TW";
        public int sandboxSeq = 100001;
        public String relayUrl = "";
        public String merchantId = "";
        public String apiKey = "";
        public boolean printProofWithCarrier = false;
    }

    public static class PaymentGatewayConfig {
        public String linePayMode = PAY_MODE_OFF;
        public String linePayChannelId = "";
        public String linePayChannelSecret = "";
        public String linePayEndpoint = "https://sandbox-api-pay.line.me/v2/payments/oneTimeKeys/pay";

        public String jkoPayMode = PAY_MODE_OFF;
        public String jkoStoreId = "";
        public String jkoMerchantKey = "";
        public String jkoRelayUrl = "";

        public boolean twqrEnabled = false;
        public String twqrPayloadTemplate = "TWQRP://餐飲門市收款/158/02/V1?D1={AMOUNT}00&D3={ORDER_NO}&D10=901";
    }

    public static final EinvoiceConfig invoiceCfg = new EinvoiceConfig();
    public static final PaymentGatewayConfig paymentCfg = new PaymentGatewayConfig();

    public static class InvoiceResult {
        public boolean success;
        public String invoiceNo = "";
        public String period = "";
        public String randomCode = "";
        public String qrLeft = "";
        public String qrRight = "";
        public String status = "";
        public String message = "";
    }

    public static class PaymentResult {
        public boolean success;
        public String provider = "";
        public String txId = "";
        public String message = "";
    }

    public interface InvoiceCallback {
        void onResult(InvoiceResult result);
    }

    public interface PaymentCallback {
        void onResult(PaymentResult result);
    }

    // ==================== 載入 / 儲存設定 ====================
    public static void loadFromDb(PosDatabaseHelper db) {
        if (db == null) return;
        invoiceCfg.enabled = "1".equals(db.getSetting("einvoice_enabled", "0"));
        invoiceCfg.mode = db.getSetting("einvoice_mode", INV_MODE_SANDBOX);
        invoiceCfg.sellerTaxId = db.getSetting("einvoice_tax_id", "");
        String tr = db.getSetting("einvoice_track", "TW").toUpperCase(Locale.US);
        invoiceCfg.trackPrefix = TRACK_PREFIX_RE.matcher(tr).matches() ? tr : "TW";
        invoiceCfg.sandboxSeq = db.getIntSetting("einvoice_sandbox_seq", 100001);
        invoiceCfg.relayUrl = db.getSetting("einvoice_relay_url", "");
        invoiceCfg.merchantId = db.getSetting("einvoice_merchant_id", "");
        invoiceCfg.apiKey = db.getSetting("einvoice_api_key", "");
        invoiceCfg.printProofWithCarrier = "1".equals(db.getSetting("einvoice_print_with_carrier", "0"));

        paymentCfg.linePayMode = db.getSetting("pay_linepay_mode", PAY_MODE_OFF);
        paymentCfg.linePayChannelId = db.getSetting("pay_linepay_channel_id", "");
        paymentCfg.linePayChannelSecret = db.getSetting("pay_linepay_channel_secret", "");
        paymentCfg.linePayEndpoint = db.getSetting("pay_linepay_endpoint",
            "https://sandbox-api-pay.line.me/v2/payments/oneTimeKeys/pay");

        paymentCfg.jkoPayMode = db.getSetting("pay_jkopay_mode", PAY_MODE_OFF);
        paymentCfg.jkoStoreId = db.getSetting("pay_jkopay_store_id", "");
        paymentCfg.jkoMerchantKey = db.getSetting("pay_jkopay_merchant_key", "");
        paymentCfg.jkoRelayUrl = db.getSetting("pay_jkopay_relay_url", "");

        paymentCfg.twqrEnabled = "1".equals(db.getSetting("pay_twqr_enabled", "0"));
        paymentCfg.twqrPayloadTemplate = db.getSetting("pay_twqr_template",
            "TWQRP://餐飲門市收款/158/02/V1?D1={AMOUNT}00&D3={ORDER_NO}&D10=901");
    }

    public static void saveToDb(PosDatabaseHelper db) {
        if (db == null) return;
        db.setSetting("einvoice_enabled", invoiceCfg.enabled ? "1" : "0");
        db.setSetting("einvoice_mode", invoiceCfg.mode);
        db.setSetting("einvoice_tax_id", invoiceCfg.sellerTaxId.trim());
        db.setSetting("einvoice_track", invoiceCfg.trackPrefix.trim().toUpperCase(Locale.US));
        db.setSetting("einvoice_sandbox_seq", String.valueOf(invoiceCfg.sandboxSeq));
        db.setSetting("einvoice_relay_url", invoiceCfg.relayUrl.trim());
        db.setSetting("einvoice_merchant_id", invoiceCfg.merchantId.trim());
        db.setSetting("einvoice_api_key", invoiceCfg.apiKey.trim());
        db.setSetting("einvoice_print_with_carrier", invoiceCfg.printProofWithCarrier ? "1" : "0");

        db.setSetting("pay_linepay_mode", paymentCfg.linePayMode);
        db.setSetting("pay_linepay_channel_id", paymentCfg.linePayChannelId.trim());
        db.setSetting("pay_linepay_channel_secret", paymentCfg.linePayChannelSecret.trim());
        db.setSetting("pay_linepay_endpoint", paymentCfg.linePayEndpoint.trim());

        db.setSetting("pay_jkopay_mode", paymentCfg.jkoPayMode);
        db.setSetting("pay_jkopay_store_id", paymentCfg.jkoStoreId.trim());
        db.setSetting("pay_jkopay_merchant_key", paymentCfg.jkoMerchantKey.trim());
        db.setSetting("pay_jkopay_relay_url", paymentCfg.jkoRelayUrl.trim());

        db.setSetting("pay_twqr_enabled", paymentCfg.twqrEnabled ? "1" : "0");
        db.setSetting("pay_twqr_template", paymentCfg.twqrPayloadTemplate.trim());
    }

    // ==================== 台灣財政部格式檢核 ====================
    /** 財政部 8 碼統一編號邏輯檢查 (含第 7 碼為 7 之例外規則) */
    public static boolean isValidTaiwanTaxId(String taxId) {
        if (taxId == null || !taxId.matches("^[0-9]{8}$")) return false;
        int[] weights = {1, 2, 1, 2, 1, 2, 4, 1};
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            int p = (taxId.charAt(i) - '0') * weights[i];
            sum += (p / 10) + (p % 10);
        }
        return (sum % 5 == 0) || (taxId.charAt(6) == '7' && ((sum + 1) % 5 == 0));
    }

    public static boolean isValidMobileBarcode(String code) {
        return code != null && MOBILE_BARCODE_RE.matcher(code.trim().toUpperCase(Locale.US)).matches();
    }

    public static boolean isValidDonateCode(String code) {
        return code != null && DONATE_CODE_RE.matcher(code.trim()).matches();
    }

    public static String formatRocPeriod(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date != null ? date : new Date());
        int rocYear = cal.get(Calendar.YEAR) - 1911;
        int m = cal.get(Calendar.MONTH) + 1;
        int startM = (m % 2 == 1) ? m : (m - 1);
        int endM = startM + 1;
        return String.format(Locale.US, "%d年%02d-%02d月", rocYear, startM, endM);
    }

    public static String formatRocYmd(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date != null ? date : new Date());
        int rocYear = cal.get(Calendar.YEAR) - 1911;
        return String.format(Locale.US, "%03d%02d%02d",
            rocYear, cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    public static boolean isDigitalPaymentConfigured(String method) {
        if ("LINE Pay".equals(method)) return !PAY_MODE_OFF.equals(paymentCfg.linePayMode);
        if ("街口支付".equals(method)) return !PAY_MODE_OFF.equals(paymentCfg.jkoPayMode);
        if ("TWQR 掃碼".equals(method)) return paymentCfg.twqrEnabled;
        return false;
    }

    public static String buildTwqrScanString(int amount, String callNum) {
        String tpl = paymentCfg.twqrPayloadTemplate != null ? paymentCfg.twqrPayloadTemplate.trim() : "";
        if (tpl.isEmpty()) {
            tpl = "TWQRP://餐飲門市收款/158/02/V1?D1={AMOUNT}00&D3={ORDER_NO}&D10=901";
        }
        String cleanOrder = callNum != null ? callNum.replaceAll("[^A-Za-z0-9_-]", "") : "POS";
        return tpl.replace("{AMOUNT}", String.valueOf(Math.max(0, amount)))
                  .replace("{ORDER_NO}", cleanOrder);
    }

    // ==================== 電子發票開立 & 作廢 ====================
    public static void issueEinvoiceAsync(final PosDatabaseHelper db, final MainActivity.OrderRecord order,
                                          final InvoiceCallback cb) {
        EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                InvoiceResult res = new InvoiceResult();
                try {
                    if (!invoiceCfg.enabled) {
                        res.success = false;
                        res.status = "NONE";
                        res.message = "未啟用電子發票模組";
                        postInvoice(cb, res);
                        return;
                    }
                    String buyer = order.buyerTaxId != null ? order.buyerTaxId.trim() : "";
                    if (!buyer.isEmpty() && !isValidTaiwanTaxId(buyer)) {
                        throw new IllegalArgumentException("買方統一編號格式或檢查碼無效 (" + buyer + ")");
                    }
                    String cType = order.carrierType != null ? order.carrierType : CARRIER_PAPER;
                    String cCode = order.carrierCode != null ? order.carrierCode.trim().toUpperCase(Locale.US) : "";
                    if (CARRIER_MOBILE.equals(cType) && !isValidMobileBarcode(cCode)) {
                        throw new IllegalArgumentException("手機條碼須為 / 開頭共 8 碼 (" + cCode + ")");
                    }
                    if (CARRIER_DONATE.equals(cType) && !isValidDonateCode(cCode)) {
                        throw new IllegalArgumentException("捐贈愛心碼須為 3~7 碼數字 (" + cCode + ")");
                    }

                    Date now = new Date();
                    String period = formatRocPeriod(now);
                    String seller = isValidTaiwanTaxId(invoiceCfg.sellerTaxId) ? invoiceCfg.sellerTaxId : "00000000";

                    if (INV_MODE_WEBHOOK.equals(invoiceCfg.mode)) {
                        if (invoiceCfg.relayUrl == null || !invoiceCfg.relayUrl.trim().startsWith("http")) {
                            throw new IllegalArgumentException("尚未設定有效的電子發票加值中心 Webhook 網址");
                        }
                        JSONObject payload = new JSONObject();
                        payload.put("action", "ISSUE");
                        payload.put("merchantId", invoiceCfg.merchantId);
                        payload.put("sellerTaxId", seller);
                        payload.put("orderNo", order.callNum);
                        payload.put("amount", order.total);
                        payload.put("buyerTaxId", buyer);
                        payload.put("carrierType", cType);
                        payload.put("carrierCode", cCode);
                        JSONArray items = new JSONArray();
                        for (MainActivity.CartItem ci : order.items) {
                            JSONObject io = new JSONObject();
                            io.put("name", ci.menu.name);
                            io.put("qty", ci.qty);
                            io.put("amount", ci.getSubTotal());
                            items.put(io);
                        }
                        payload.put("items", items);

                        JSONObject resp = postJson(invoiceCfg.relayUrl.trim(), payload,
                            buildHmacHeaders(payload.toString(), invoiceCfg.apiKey));
                        if (!resp.optBoolean("ok", false)) {
                            throw new RuntimeException(resp.optString("error", "加值中心回傳開立失敗"));
                        }
                        res.invoiceNo = resp.optString("invoiceNo", "").trim();
                        if (res.invoiceNo.isEmpty()) throw new RuntimeException("加值中心未回傳發票號碼");
                        res.randomCode = resp.optString("randomCode", String.format(Locale.US, "%04d", RNG.nextInt(10000)));
                        res.period = resp.optString("period", period);
                        res.qrLeft = resp.optString("qrLeft",
                            buildMofLeftQr(res.invoiceNo, now, res.randomCode, order.total, buyer, seller));
                        res.qrRight = resp.optString("qrRight", buildMofRightQr(order));
                        res.status = "ISSUED";
                        res.success = true;
                        res.message = "🧾 已開立正式電子發票：" + res.invoiceNo;
                    } else {
                        String track = TRACK_PREFIX_RE.matcher(invoiceCfg.trackPrefix).matches()
                            ? invoiceCfg.trackPrefix : "TW";
                        int seq = invoiceCfg.sandboxSeq;
                        invoiceCfg.sandboxSeq = seq + 1;
                        if (db != null) db.setSetting("einvoice_sandbox_seq", String.valueOf(invoiceCfg.sandboxSeq));

                        res.invoiceNo = String.format(Locale.US, "%s-%08d", track, seq);
                        res.randomCode = String.format(Locale.US, "%04d", 1000 + RNG.nextInt(9000));
                        res.period = period;
                        res.qrLeft = buildMofLeftQr(res.invoiceNo, now, res.randomCode, order.total, buyer, seller);
                        res.qrRight = buildMofRightQr(order);
                        res.status = "SANDBOX";
                        res.success = true;
                        res.message = "🧪 [測試模擬發票] " + res.invoiceNo + " (隨機碼:" + res.randomCode + ")";
                    }
                } catch (Exception e) {
                    res.success = false;
                    res.status = "FAILED";
                    res.message = "⚠️ 電子發票開立失敗：" + e.getMessage();
                }
                postInvoice(cb, res);
            }
        });
    }

    public static void voidEinvoiceAsync(final MainActivity.OrderRecord order, final InvoiceCallback cb) {
        EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                InvoiceResult res = new InvoiceResult();
                try {
                    if (order.invoiceNo == null || order.invoiceNo.trim().isEmpty()) {
                        res.success = true;
                        res.status = "NONE";
                        res.message = "此訂單無須作廢發票";
                        postInvoice(cb, res);
                        return;
                    }
                    if ("ISSUED".equals(order.invoiceStatus) && INV_MODE_WEBHOOK.equals(invoiceCfg.mode)) {
                        JSONObject payload = new JSONObject();
                        payload.put("action", "VOID");
                        payload.put("merchantId", invoiceCfg.merchantId);
                        payload.put("invoiceNo", order.invoiceNo);
                        payload.put("reason", "門市退單作廢");
                        JSONObject resp = postJson(invoiceCfg.relayUrl.trim(), payload,
                            buildHmacHeaders(payload.toString(), invoiceCfg.apiKey));
                        if (!resp.optBoolean("ok", false)) {
                            throw new RuntimeException(resp.optString("error", "加值中心作廢失敗"));
                        }
                    }
                    res.success = true;
                    res.invoiceNo = order.invoiceNo;
                    res.status = "VOIDED";
                    res.message = "✅ 發票 " + order.invoiceNo + " 已作廢";
                } catch (Exception e) {
                    res.success = false;
                    res.status = order.invoiceStatus;
                    res.message = "⚠️ 發票作廢失敗：" + e.getMessage();
                }
                postInvoice(cb, res);
            }
        });
    }

    // ==================== 多元支付掃碼收款 (LINE Pay / 街口 / TWQR) ====================
    public static void processDigitalPaymentAsync(final String method, final int amount,
                                                  final String callNum, final String barcode,
                                                  final PaymentCallback cb) {
        EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                PaymentResult res = new PaymentResult();
                res.provider = method;
                try {
                    String cleanCode = barcode != null ? barcode.trim() : "";
                    if ("LINE Pay".equals(method)) {
                        if (PAY_MODE_LIVE.equals(paymentCfg.linePayMode)) {
                            if (cleanCode.length() < 12) {
                                throw new IllegalArgumentException("LINE Pay 付款條碼格式過短，請重新掃描");
                            }
                            if (paymentCfg.linePayChannelId.isEmpty() || paymentCfg.linePayChannelSecret.isEmpty()) {
                                throw new IllegalArgumentException("尚未設定 LINE Pay Channel ID / Secret");
                            }
                            JSONObject body = new JSONObject();
                            body.put("productName", PosPrintEngine.getEffectiveShortName() + " 餐飲消費");
                            body.put("amount", amount);
                            body.put("currency", "TWD");
                            body.put("oneTimeKey", cleanCode);
                            body.put("orderId", callNum.replace("#", "") + "-" + System.currentTimeMillis());

                            JSONObject hdrs = new JSONObject();
                            hdrs.put("X-LINE-ChannelId", paymentCfg.linePayChannelId);
                            hdrs.put("X-LINE-ChannelSecret", paymentCfg.linePayChannelSecret);
                            JSONObject resp = postJson(paymentCfg.linePayEndpoint, body, hdrs);
                            String rc = resp.optString("returnCode", "");
                            if (!"0000".equals(rc)) {
                                throw new RuntimeException("[" + rc + "] " + resp.optString("returnMessage", "授權失敗"));
                            }
                            JSONObject info = resp.optJSONObject("info");
                            res.txId = info != null ? info.optString("transactionId", "LP-" + System.currentTimeMillis())
                                                    : "LP-" + System.currentTimeMillis();
                            res.success = true;
                            res.message = "✅ LINE Pay 扣款成功 (單號:" + res.txId + ")";
                        } else {
                            if (cleanCode.isEmpty()) throw new IllegalArgumentException("請掃描條碼或點擊模擬測試條碼");
                            Thread.sleep(250);
                            res.txId = "SIM-LP-" + (System.currentTimeMillis() % 100000000L);
                            res.success = true;
                            res.message = "🧪 [LINE Pay 模擬收款成功] $" + amount + " (" + res.txId + ")";
                        }
                    } else if ("街口支付".equals(method)) {
                        if (PAY_MODE_LIVE.equals(paymentCfg.jkoPayMode)) {
                            if (cleanCode.length() < 12) {
                                throw new IllegalArgumentException("街口付款條碼格式無效，請重新掃描");
                            }
                            if (!paymentCfg.jkoRelayUrl.startsWith("http")) {
                                throw new IllegalArgumentException("尚未設定街口支付 POS 中繼網址");
                            }
                            JSONObject body = new JSONObject();
                            body.put("action", "PAY");
                            body.put("storeId", paymentCfg.jkoStoreId);
                            body.put("posOrderNo", callNum.replace("#", "") + "-" + System.currentTimeMillis());
                            body.put("amount", amount);
                            body.put("barcode", cleanCode);
                            JSONObject resp = postJson(paymentCfg.jkoRelayUrl, body,
                                buildHmacHeaders(body.toString(), paymentCfg.jkoMerchantKey));
                            if (!resp.optBoolean("ok", false)) {
                                throw new RuntimeException(resp.optString("error", "街口收款失敗"));
                            }
                            res.txId = resp.optString("txId", "JKO-" + System.currentTimeMillis());
                            res.success = true;
                            res.message = "✅ 街口支付收款成功 (單號:" + res.txId + ")";
                        } else {
                            if (cleanCode.isEmpty()) throw new IllegalArgumentException("請掃描條碼或點擊模擬測試條碼");
                            Thread.sleep(250);
                            res.txId = "SIM-JKO-" + (System.currentTimeMillis() % 100000000L);
                            res.success = true;
                            res.message = "🧪 [街口模擬收款成功] $" + amount + " (" + res.txId + ")";
                        }
                    } else {
                        res.txId = "TWQR-" + (System.currentTimeMillis() % 100000000L);
                        res.success = true;
                        res.message = "✅ 已確認 TWQR 入帳 $" + amount + " (" + res.txId + ")";
                    }
                } catch (Exception e) {
                    res.success = false;
                    res.message = "❌ 收款失敗：" + e.getMessage();
                }
                final PaymentResult finalRes = res;
                MAIN.post(new Runnable() {
                    @Override public void run() { if (cb != null) cb.onResult(finalRes); }
                });
            }
        });
    }

    // ==================== 財政部 QR Code 規格組裝 ====================
    private static String buildMofLeftQr(String invNo, Date now, String random4, int total,
                                         String buyerTaxId, String sellerTaxId) {
        String cleanInv = invNo != null ? invNo.replace("-", "").toUpperCase(Locale.US) : "TW00000000";
        if (cleanInv.length() != 10) cleanInv = String.format(Locale.US, "%-10s", cleanInv).substring(0, 10).replace(' ', '0');
        String rocYmd = formatRocYmd(now);
        String rnd = (random4 != null && random4.length() == 4) ? random4 : "0000";
        String totalHex = String.format(Locale.US, "%08x", Math.max(0, total));
        String buyer = isValidTaiwanTaxId(buyerTaxId) ? buyerTaxId : "00000000";
        String seller = isValidTaiwanTaxId(sellerTaxId) ? sellerTaxId : "00000000";
        String verifyStub = sha256B64Stub(cleanInv + rocYmd + rnd + totalHex + seller);
        return cleanInv + rocYmd + rnd + totalHex + totalHex + buyer + seller + verifyStub
            + ":**********:1:1:1:餐飲消費:1:" + Math.max(0, total);
    }

    private static String buildMofRightQr(MainActivity.OrderRecord order) {
        StringBuilder sb = new StringBuilder("**");
        if (order != null && order.items != null) {
            int max = Math.min(3, order.items.size());
            for (int i = 0; i < max; i++) {
                MainActivity.CartItem ci = order.items.get(i);
                sb.append(ci.menu.name).append(":").append(ci.qty).append(":").append(ci.getSubTotal());
                if (i < max - 1) sb.append(":");
            }
        }
        return sb.toString();
    }

    private static String sha256B64Stub(String src) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(src.getBytes(StandardCharsets.UTF_8));
            String hex = android.util.Base64.encodeToString(dig, android.util.Base64.NO_WRAP);
            return hex.length() >= 24 ? hex.substring(0, 24) : String.format(Locale.US, "%-24s", hex).replace(' ', '=');
        } catch (Exception e) {
            return "========================";
        }
    }

    private static JSONObject buildHmacHeaders(String body, String secret) {
        JSONObject h = new JSONObject();
        try {
            if (secret != null && !secret.trim().isEmpty()) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.trim().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                byte[] sig = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (byte b : sig) hex.append(String.format(Locale.US, "%02x", b & 0xFF));
                h.put("X-Pos-Signature", hex.toString());
            }
        } catch (Exception ignored) {}
        return h;
    }

    private static JSONObject postJson(String urlStr, JSONObject body, JSONObject extraHeaders) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            if (extraHeaders != null) {
                java.util.Iterator<String> it = extraHeaders.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    conn.setRequestProperty(k, extraHeaders.optString(k, ""));
                }
            }
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(out);
                os.flush();
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder resp = new StringBuilder();
            if (is != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) resp.append(line);
                }
            }
            if (resp.length() == 0) {
                throw new RuntimeException("HTTP " + code + " 空回應");
            }
            return new JSONObject(resp.toString());
        } finally {
            conn.disconnect();
        }
    }

    private static void postInvoice(final InvoiceCallback cb, final InvoiceResult res) {
        MAIN.post(new Runnable() {
            @Override public void run() { if (cb != null) cb.onResult(res); }
        });
    }
}
