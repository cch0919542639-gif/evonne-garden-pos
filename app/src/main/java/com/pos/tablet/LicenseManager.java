package com.pos.tablet;

import android.content.Context;
import android.provider.Settings;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 商業化離線授權與功能分級控管模組 (RSA-2048 公開金鑰驗章，免額外授權伺服器即可驗證)
 * 支援商業模式：
 * 1. 創始/內部門市自動豁免 (EVONNE-001)
 * 2. 新店 14 天全功能免費試用
 * 3. 一次性買斷 (LIFETIME) / 年費 (ANNUAL) / 月租 (MONTHLY) + 7 天到期寬限期
 * 4. 功能模組分級解鎖 (core, qr, cloud_backup, staff, cash_drawer)
 */
public class LicenseManager {

    // 僅存放 RSA 公開金鑰 (只可驗章，無法簽發授權碼；私鑰嚴格保存於發行端)
    private static final String VENDOR_PUBLIC_KEY_X509_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA7gpDoyj52510U3JrgwHgkQbzX6RVLbCsJpKxDe5JMpXo"
        + "K0FgjNbfskU/iT0xMUOgEbQY0Kmm/z40qQHB/v5yZUE6cACx4P1qLXxYf5VcEmcxKIJcONkeOJ1g8knK4MOcVA/"
        + "fHh3u6LtbW/vkir8JpYKTgRFOSk4bz/emDWABtK2quHrV+L1FzXq+lY/NKnKwCqSQFxSp8j0L43n7Xu0uLEsBto"
        + "mDfst+KYdYiDeZyEFPIrUOSbrQvJYiAqAtxyW7qydZMEsD+bpm9Hd015IPL16qOS2S9jVVZNwoW8P+cjFQrupj3"
        + "ztOY4zMgXvY1ciJe5bSR1IddfNIWaeIwozbZwIDAQAB";

    public static final int TRIAL_DAYS = 14;
    public static final int GRACE_DAYS = 7;

    public static final String FEATURE_CORE = "core";
    public static final String FEATURE_QR = "qr";
    public static final String FEATURE_CLOUD_BACKUP = "cloud_backup";
    public static final String FEATURE_STAFF = "staff";
    public static final String FEATURE_CASH_DRAWER = "cash_drawer";

    public static class LicenseState {
        public String deviceCode = "DEV-UNKNOWN";
        public String planCode = "TRIAL";       // FOUNDER, LIFETIME, ANNUAL, MONTHLY, TRIAL, EXPIRED
        public String planLabel = "試用版";
        public String expiryDate = "";
        public int daysRemaining = 0;
        public boolean isTrial = true;
        public boolean inGrace = false;
        public boolean canCheckout = true;
        public String enabledFeatures = "core,qr,cloud_backup,staff,cash_drawer";
        public String statusSummary = "";

        public boolean hasFeature(String featureKey) {
            if (!canCheckout) return false;
            if (enabledFeatures == null || enabledFeatures.trim().isEmpty()) return false;
            if ("*".equals(enabledFeatures.trim())) return true;
            String[] parts = enabledFeatures.split(",");
            for (String p : parts) {
                if (featureKey.equalsIgnoreCase(p.trim())) return true;
            }
            return false;
        }
    }

    public static String getDeviceCode(Context ctx) {
        try {
            String aid = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (aid == null || aid.trim().isEmpty()) aid = "FALLBACK-POS";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(("POS_DEV_" + aid).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format(Locale.US, "%02X", dig[i] & 0xFF));
            }
            return "DEV-" + hex.toString();
        } catch (Exception e) {
            return "DEV-00000000";
        }
    }

    public static LicenseState evaluate(Context ctx, PosDatabaseHelper db, String storeCode) {
        LicenseState st = new LicenseState();
        st.deviceCode = getDeviceCode(ctx);

        // 1. 創始/內部直營門市保留完全豁免權
        if ("EVONNE-001".equalsIgnoreCase(storeCode != null ? storeCode.trim() : "")) {
            st.planCode = "FOUNDER";
            st.planLabel = "創始門市永久授權";
            st.expiryDate = "2099-12-31";
            st.daysRemaining = 9999;
            st.isTrial = false;
            st.inGrace = false;
            st.canCheckout = true;
            st.enabledFeatures = "core,qr,cloud_backup,staff,cash_drawer";
            st.statusSummary = "👑 " + st.planLabel + " (全功能永久開通)";
            return st;
        }

        // 2. 反時鐘回撥基準日期追蹤
        String sysToday = new SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN).format(new Date());
        String lastSeen = db.getSetting("license_last_seen_date", "");
        String effectiveToday = sysToday;
        if (!lastSeen.isEmpty() && lastSeen.compareTo(sysToday) > 0) {
            effectiveToday = lastSeen;
        } else if (!sysToday.equals(lastSeen)) {
            db.setSetting("license_last_seen_date", sysToday);
        }

        // 3. 若已寫入商業授權碼，優先進行 RSA 公開金鑰驗章
        String token = db.getSetting("license_token", "").trim();
        if (!token.isEmpty()) {
            try {
                JSONObject payload = verifyTokenPayload(token, st.deviceCode);
                String plan = payload.optString("plan", "LIFETIME").toUpperCase(Locale.US);
                String exp = payload.optString("exp", "2099-12-31");
                String features = payload.optString("features", "core,qr,cloud_backup,staff,cash_drawer");

                int diffDays = daysBetween(effectiveToday, exp);
                st.planCode = plan;
                st.planLabel = formatPlanLabel(plan);
                st.expiryDate = exp;
                st.daysRemaining = diffDays;
                st.isTrial = false;
                st.enabledFeatures = features;

                if (diffDays >= 0) {
                    st.inGrace = false;
                    st.canCheckout = true;
                    st.statusSummary = "✅ " + st.planLabel + " 授權有效 (到期：" + exp + ")";
                    return st;
                } else if (diffDays >= -GRACE_DAYS) {
                    st.inGrace = true;
                    st.canCheckout = true;
                    int graceLeft = GRACE_DAYS + diffDays;
                    st.statusSummary = "⚠️ 授權已於 " + exp + " 到期（寬限期剩 " + Math.max(0, graceLeft) + " 天，請盡快續約）";
                    return st;
                } else {
                    st.planCode = "EXPIRED";
                    st.inGrace = false;
                    st.canCheckout = false;
                    st.statusSummary = "🔒 授權已到期 (" + exp + ")，請輸入新授權碼開通結帳功能（歷史資料可正常匯出）";
                    return st;
                }
            } catch (Exception e) {
                st.planCode = "INVALID";
                st.canCheckout = false;
                st.isTrial = false;
                st.statusSummary = "❌ 授權碼無效或與此裝置不符：" + e.getMessage();
                return st;
            }
        }

        // 4. 尚未輸入授權碼：使用新店 14 天全功能免費試用期
        String trialStart = db.getSetting("trial_start_date", "");
        if (trialStart.isEmpty()) {
            trialStart = sysToday;
            db.setSetting("trial_start_date", trialStart);
        }
        int usedDays = Math.max(0, daysBetween(trialStart, effectiveToday));
        int leftDays = TRIAL_DAYS - usedDays;
        st.planCode = "TRIAL";
        st.planLabel = "14 天免費試用版";
        st.expiryDate = addDays(trialStart, TRIAL_DAYS);
        st.daysRemaining = Math.max(0, leftDays);
        st.isTrial = true;
        st.enabledFeatures = "core,qr,cloud_backup,staff,cash_drawer";

        if (leftDays >= 0) {
            st.canCheckout = true;
            st.statusSummary = "🆓 商業試用期剩餘 " + leftDays + " 天 (到期：" + st.expiryDate + ")";
        } else {
            st.planCode = "EXPIRED";
            st.canCheckout = false;
            st.statusSummary = "🔒 14 天試用期已結束，請聯繫供應商取得授權碼開通（歷史資料與快照匯出不受影響）";
        }
        return st;
    }

    public static LicenseState verifyAndSaveToken(Context ctx, PosDatabaseHelper db, String rawToken, String storeCode) throws Exception {
        if (rawToken == null || rawToken.trim().isEmpty()) {
            throw new IllegalArgumentException("請貼上有效的 POS1... 授權開通碼");
        }
        String dev = getDeviceCode(ctx);
        verifyTokenPayload(rawToken.trim(), dev);
        db.setSetting("license_token", rawToken.trim());
        return evaluate(ctx, db, storeCode);
    }

    private static JSONObject verifyTokenPayload(String token, String currentDeviceCode) throws Exception {
        String[] parts = token.trim().split("\\.");
        if (parts.length != 3 || !"POS1".equals(parts[0])) {
            throw new IllegalArgumentException("授權碼格式錯誤 (須以 POS1. 開頭)");
        }
        byte[] payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        byte[] sigBytes = Base64.decode(parts[2], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);

        byte[] pubBytes = Base64.decode(VENDOR_PUBLIC_KEY_X509_B64, Base64.DEFAULT);
        PublicKey pubKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pubBytes));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pubKey);
        verifier.update(payloadBytes);
        if (!verifier.verify(sigBytes)) {
            throw new SecurityException("數位簽章驗證失敗（授權碼遭竄改或非官方簽發）");
        }

        JSONObject payload = new JSONObject(new String(payloadBytes, StandardCharsets.UTF_8));
        String targetDev = payload.optString("device", "").trim().toUpperCase(Locale.US);
        if (!"*".equals(targetDev) && !currentDeviceCode.equalsIgnoreCase(targetDev)) {
            throw new IllegalArgumentException("此授權碼綁定設備 [" + targetDev + "]，與本機 [" + currentDeviceCode + "] 不符");
        }
        return payload;
    }

    private static String formatPlanLabel(String plan) {
        if ("LIFETIME".equals(plan)) return "買斷永久版";
        if ("ANNUAL".equals(plan)) return "年費授權版";
        if ("MONTHLY".equals(plan)) return "月租訂閱版";
        if ("FOUNDER".equals(plan)) return "創始門市永久授權";
        return plan;
    }

    private static int daysBetween(String startYmd, String endYmd) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date d1 = sdf.parse(startYmd);
            Date d2 = sdf.parse(endYmd);
            if (d1 == null || d2 == null) return 0;
            return (int) ((d2.getTime() - d1.getTime()) / (24L * 60L * 60L * 1000L));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String addDays(String ymd, int days) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date d = sdf.parse(ymd);
            if (d == null) return ymd;
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTime(d);
            cal.add(java.util.Calendar.DAY_OF_YEAR, days);
            return sdf.format(cal.getTime());
        } catch (Exception e) {
            return ymd;
        }
    }
}
