package com.pos.tablet;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 純 Java 原生 ISO/IEC 18004 標準 QR Code 點陣圖產生器 (零第三方套件依賴)
 * 支援 UTF-8 Byte Mode、Reed-Solomon GF(256) 糾錯碼 (ECC Level L，Version 1~10，最高可容納 271 bytes 網址)
 * 可直接在平板畫面預覽或透過 HPRT TP805L 出單機印出各桌掃碼點餐立牌。
 */
public class QrCodeGenerator {

    // ECC Level L (Low ~7%) 參數表：Version 1..10
    // 總資料編碼字數 (Total Data Codewords)
    private static final int[] TOTAL_DATA_CODEWORDS_L = {
        0, 19, 34, 55, 80, 108, 136, 156, 194, 232, 274
    };
    // 每區塊 ECC 糾錯碼字數
    private static final int[] EC_CODEWORDS_PER_BLOCK_L = {
        0, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18
    };
    // 區塊數 (Group 1 blocks, Group 1 data cw, Group 2 blocks, Group 2 data cw)
    private static final int[][] BLOCK_STRUCTURE_L = {
        {},
        {1, 19, 0, 0},   // V1
        {1, 34, 0, 0},   // V2
        {1, 55, 0, 0},   // V3
        {1, 80, 0, 0},   // V4
        {1, 108, 0, 0},  // V5
        {2, 68, 0, 0},   // V6
        {2, 78, 0, 0},   // V7
        {2, 97, 0, 0},   // V8
        {2, 116, 0, 0},  // V9
        {2, 68, 2, 69}   // V10
    };

    // 對齊圖案中心座標表 (Alignment Pattern Centers)
    private static final int[][] ALIGNMENT_POS = {
        {},
        {},
        {6, 18},
        {6, 22},
        {6, 26},
        {6, 30},
        {6, 34},
        {6, 22, 38},
        {6, 24, 42},
        {6, 26, 46},
        {6, 28, 50}
    };

    /**
     * 將網址或文字編碼為 QR Code Bitmap
     */
    public static Bitmap generateQrBitmap(String content, int sizePx) {
        byte[] dataBytes = content.getBytes(StandardCharsets.UTF_8);
        int len = dataBytes.length;

        // 選擇最小可容納之 Version (1~10)
        int version = 1;
        while (version <= 10) {
            int headerBits = (version <= 9) ? 12 : 20; // Mode(4) + Count(8 or 16)
            int maxBytes = (TOTAL_DATA_CODEWORDS_L[version] * 8 - headerBits) / 8;
            if (len <= maxBytes) break;
            version++;
        }
        if (version > 10) version = 10;

        int totalDataCw = TOTAL_DATA_CODEWORDS_L[version];
        byte[] dataCw = buildDataCodewords(dataBytes, version, totalDataCw);
        byte[] allCw = interleaveWithEcc(dataCw, version);

        int dim = 17 + 4 * version;
        boolean[][] modules = new boolean[dim][dim];
        boolean[][] isFunction = new boolean[dim][dim];

        drawFunctionPatterns(modules, isFunction, version, dim);
        drawCodewords(modules, isFunction, allCw, dim);

        // 選擇最佳遮罩 (0~7)
        int bestMask = 0;
        int minPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] masked = copyGrid(modules);
            applyMask(masked, isFunction, mask, dim);
            drawFormatBits(masked, mask, dim);
            if (version >= 7) drawVersionBits(masked, version, dim);
            int pen = calculatePenalty(masked, dim);
            if (pen < minPenalty) {
                minPenalty = pen;
                bestMask = mask;
            }
        }

        applyMask(modules, isFunction, bestMask, dim);
        drawFormatBits(modules, bestMask, dim);
        if (version >= 7) drawVersionBits(modules, version, dim);

        int quietZone = 4;
        int totalModules = dim + quietZone * 2;
        int scale = Math.max(4, sizePx / totalModules);
        int actualSize = totalModules * scale;

        Bitmap bmp = Bitmap.createBitmap(actualSize, actualSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);

        Paint blackPaint = new Paint();
        blackPaint.setColor(Color.BLACK);
        blackPaint.setStyle(Paint.Style.FILL);

        for (int y = 0; y < dim; y++) {
            for (int x = 0; x < dim; x++) {
                if (modules[y][x]) {
                    int left = (x + quietZone) * scale;
                    int top = (y + quietZone) * scale;
                    canvas.drawRect(left, top, left + scale, top + scale, blackPaint);
                }
            }
        }
        return bmp;
    }

    /**
     * 渲染可由 HPRT TP805L 出單機直接列印之「桌牌掃碼點餐單」點陣圖
     */
    public static Bitmap renderTableQrStandeeBitmap(String tableName, String orderUrl, int printableDots) {
        int totalW = Math.max(384, (printableDots / 8) * 8);
        int leftPad = Math.max(16, PosPrintEngine.receiptLeftMarginDots);
        int rightPad = Math.max(16, PosPrintEngine.receiptRightMarginDots);
        int innerW = Math.max(260, totalW - leftPad - rightPad);

        TextPaint tpStore = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tpStore.setColor(Color.BLACK);
        tpStore.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        tpStore.setTextSize(32f);

        TextPaint tpTable = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tpTable.setColor(Color.BLACK);
        tpTable.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        tpTable.setTextSize(44f);

        TextPaint tpHint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tpHint.setColor(Color.BLACK);
        tpHint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        tpHint.setTextSize(24f);

        StaticLayout slStore = StaticLayout.Builder
            .obtain("🌿 伊凡的花園 Evonne's Garden", 0, "🌿 伊凡的花園 Evonne's Garden".length(), tpStore, innerW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build();

        String tblTitle = "【 " + tableName + " 】手機掃碼點餐";
        StaticLayout slTable = StaticLayout.Builder
            .obtain(tblTitle, 0, tblTitle.length(), tpTable, innerW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build();

        String guide = "請用手機相機或 LINE 掃描下方 QR Code\n即可免下載 App 直接瀏覽菜單與線上點餐！";
        StaticLayout slGuide = StaticLayout.Builder
            .obtain(guide, 0, guide.length(), tpHint, innerW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(4f, 1.1f)
            .build();

        int qrTargetSize = Math.min(innerW - 20, 340);
        Bitmap qrBmp = generateQrBitmap(orderUrl, qrTargetSize);

        int totalH = 30 + slStore.getHeight() + 14 + slTable.getHeight() + 16
            + qrBmp.getHeight() + 16 + slGuide.getHeight() + 40;

        Bitmap out = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.WHITE);

        Paint borderPaint = new Paint();
        borderPaint.setColor(Color.BLACK);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(4f);
        canvas.drawRect(leftPad + 2, 10, leftPad + innerW - 2, totalH - 14, borderPaint);

        int y = 24;
        canvas.save();
        canvas.translate(leftPad, y);
        slStore.draw(canvas);
        canvas.restore();
        y += slStore.getHeight() + 10;

        canvas.save();
        canvas.translate(leftPad, y);
        slTable.draw(canvas);
        canvas.restore();
        y += slTable.getHeight() + 12;

        int qrX = leftPad + (innerW - qrBmp.getWidth()) / 2;
        canvas.drawBitmap(qrBmp, qrX, y, null);
        y += qrBmp.getHeight() + 12;

        canvas.save();
        canvas.translate(leftPad, y);
        slGuide.draw(canvas);
        canvas.restore();

        return out;
    }

    private static byte[] buildDataCodewords(byte[] data, int version, int totalDataCw) {
        int maxBits = totalDataCw * 8;
        int[] bits = new int[maxBits];
        int bitLen = 0;

        // Mode indicator: Byte mode = 0100 (4 bits)
        bitLen = appendBits(bits, bitLen, 0b0100, 4);
        // Character count indicator: 8 bits for V1..9, 16 bits for V10..26
        int countBits = (version <= 9) ? 8 : 16;
        bitLen = appendBits(bits, bitLen, data.length, countBits);

        for (byte b : data) {
            if (bitLen + 8 > maxBits) break;
            bitLen = appendBits(bits, bitLen, b & 0xFF, 8);
        }

        // Terminator (up to 4 zero bits)
        int term = Math.min(4, maxBits - bitLen);
        bitLen = appendBits(bits, bitLen, 0, term);

        // Pad to byte boundary
        while (bitLen % 8 != 0 && bitLen < maxBits) {
            bits[bitLen++] = 0;
        }

        // Pad bytes 0xEC, 0x11
        int padIdx = 0;
        while (bitLen + 8 <= maxBits) {
            int padByte = (padIdx % 2 == 0) ? 0xEC : 0x11;
            bitLen = appendBits(bits, bitLen, padByte, 8);
            padIdx++;
        }

        byte[] cw = new byte[totalDataCw];
        for (int i = 0; i < totalDataCw; i++) {
            int val = 0;
            for (int b = 0; b < 8; b++) {
                val = (val << 1) | bits[i * 8 + b];
            }
            cw[i] = (byte) val;
        }
        return cw;
    }

    private static int appendBits(int[] bits, int pos, int val, int numBits) {
        for (int i = numBits - 1; i >= 0; i--) {
            bits[pos++] = (val >>> i) & 1;
        }
        return pos;
    }

    private static byte[] interleaveWithEcc(byte[] dataCw, int version) {
        int[] st = BLOCK_STRUCTURE_L[version];
        int g1Blocks = st[0], g1DataCw = st[1], g2Blocks = st[2], g2DataCw = st[3];
        int totalBlocks = g1Blocks + g2Blocks;
        int ecLen = EC_CODEWORDS_PER_BLOCK_L[version];

        byte[][] dataBlocks = new byte[totalBlocks][];
        byte[][] ecBlocks = new byte[totalBlocks][];

        int offset = 0;
        for (int i = 0; i < totalBlocks; i++) {
            int len = (i < g1Blocks) ? g1DataCw : g2DataCw;
            dataBlocks[i] = Arrays.copyOfRange(dataCw, offset, offset + len);
            offset += len;
            ecBlocks[i] = computeReedSolomon(dataBlocks[i], ecLen);
        }

        int maxDataLen = Math.max(g1DataCw, g2DataCw);
        byte[] result = new byte[dataCw.length + totalBlocks * ecLen];
        int pos = 0;
        for (int c = 0; c < maxDataLen; c++) {
            for (int b = 0; b < totalBlocks; b++) {
                if (c < dataBlocks[b].length) {
                    result[pos++] = dataBlocks[b][c];
                }
            }
        }
        for (int c = 0; c < ecLen; c++) {
            for (int b = 0; b < totalBlocks; b++) {
                result[pos++] = ecBlocks[b][c];
            }
        }
        return result;
    }

    // GF(256) Reed-Solomon Generator (primitive polynomial 0x11D)
    private static byte[] computeReedSolomon(byte[] data, int degree) {
        byte[] gen = new byte[degree];
        gen[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                gen[j] = (byte) gfMultiply(gen[j] & 0xFF, root);
                if (j + 1 < degree) gen[j] ^= gen[j + 1];
            }
            root = gfMultiply(root, 0x02);
        }

        byte[] rem = new byte[degree];
        for (byte b : data) {
            int factor = (b ^ rem[0]) & 0xFF;
            System.arraycopy(rem, 1, rem, 0, degree - 1);
            rem[degree - 1] = 0;
            for (int j = 0; j < degree; j++) {
                rem[j] ^= (byte) gfMultiply(gen[j] & 0xFF, factor);
            }
        }
        return rem;
    }

    private static int gfMultiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static void drawFunctionPatterns(boolean[][] modules, boolean[][] isFunc, int version, int dim) {
        // Timing patterns
        for (int i = 0; i < dim; i++) {
            setFunctionModule(modules, isFunc, 6, i, i % 2 == 0);
            setFunctionModule(modules, isFunc, i, 6, i % 2 == 0);
        }

        // Finder patterns (3 corners)
        drawFinderPattern(modules, isFunc, 3, 3, dim);
        drawFinderPattern(modules, isFunc, dim - 4, 3, dim);
        drawFinderPattern(modules, isFunc, 3, dim - 4, dim);

        // Alignment patterns
        int[] align = ALIGNMENT_POS[version];
        for (int i = 0; i < align.length; i++) {
            for (int j = 0; j < align.length; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == align.length - 1) || (i == align.length - 1 && j == 0)) {
                    continue;
                }
                drawAlignmentPattern(modules, isFunc, align[i], align[j]);
            }
        }

        // Reserve Format Info areas
        for (int i = 0; i <= 8; i++) {
            if (i != 6) {
                setFunctionModule(modules, isFunc, 8, i, false);
                setFunctionModule(modules, isFunc, i, 8, false);
            }
        }
        for (int i = 0; i < 8; i++) {
            setFunctionModule(modules, isFunc, dim - 1 - i, 8, false);
            setFunctionModule(modules, isFunc, 8, dim - 1 - i, false);
        }
        // Dark module
        setFunctionModule(modules, isFunc, 8, dim - 8, true);

        // Reserve Version Info areas (V7+)
        if (version >= 7) {
            for (int i = 0; i < 6; i++) {
                for (int j = 0; j < 3; j++) {
                    setFunctionModule(modules, isFunc, dim - 11 + j, i, false);
                    setFunctionModule(modules, isFunc, i, dim - 11 + j, false);
                }
            }
        }
    }

    private static void drawFinderPattern(boolean[][] modules, boolean[][] isFunc, int cx, int cy, int dim) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && x < dim && y >= 0 && y < dim) {
                    int dist = Math.max(Math.abs(dx), Math.abs(dy));
                    boolean dark = (dist != 2 && dist != 4);
                    setFunctionModule(modules, isFunc, x, y, dark);
                }
            }
        }
    }

    private static void drawAlignmentPattern(boolean[][] modules, boolean[][] isFunc, int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                setFunctionModule(modules, isFunc, cx + dx, cy + dy, dist != 1);
            }
        }
    }

    private static void setFunctionModule(boolean[][] modules, boolean[][] isFunc, int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunc[y][x] = true;
    }

    private static void drawCodewords(boolean[][] modules, boolean[][] isFunc, byte[] allCw, int dim) {
        int bitIdx = 0;
        int totalBits = allCw.length * 8;
        for (int right = dim - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < dim; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? (dim - 1 - vert) : vert;
                    if (!isFunc[y][x] && bitIdx < totalBits) {
                        boolean dark = ((allCw[bitIdx >>> 3] >>> (7 - (bitIdx & 7))) & 1) != 0;
                        modules[y][x] = dark;
                        bitIdx++;
                    }
                }
            }
        }
    }

    private static void applyMask(boolean[][] modules, boolean[][] isFunc, int mask, int dim) {
        for (int y = 0; y < dim; y++) {
            for (int x = 0; x < dim; x++) {
                if (isFunc[y][x]) continue;
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = (x * y) % 2 + (x * y) % 3 == 0; break;
                    case 6: invert = ((x * y) % 2 + (x * y) % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + (x * y) % 3) % 2 == 0; break;
                }
                if (invert) modules[y][x] = !modules[y][x];
            }
        }
    }

    // Format Info: ECC Level L = 01 (binary)
    private static void drawFormatBits(boolean[][] modules, int mask, int dim) {
        int data = (0b01 << 3) | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        int bits = ((data << 10) | rem) ^ 0x5412;

        for (int i = 0; i <= 5; i++) modules[i][8] = getBit(bits, i);
        modules[7][8] = getBit(bits, 6);
        modules[8][8] = getBit(bits, 7);
        modules[8][7] = getBit(bits, 8);
        for (int i = 9; i < 15; i++) modules[8][14 - i] = getBit(bits, i);

        for (int i = 0; i < 8; i++) modules[8][dim - 1 - i] = getBit(bits, i);
        for (int i = 8; i < 15; i++) modules[dim - 15 + i][8] = getBit(bits, i);
        modules[dim - 8][8] = true;
    }

    private static void drawVersionBits(boolean[][] modules, int version, int dim) {
        int rem = version;
        for (int i = 0; i < 12; i++) {
            rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        }
        int bits = (version << 12) | rem;
        for (int i = 0; i < 18; i++) {
            boolean bit = getBit(bits, i);
            int a = dim - 11 + (i % 3);
            int b = i / 3;
            modules[b][a] = bit;
            modules[a][b] = bit;
        }
    }

    private static boolean getBit(int val, int idx) {
        return ((val >>> idx) & 1) != 0;
    }

    private static boolean[][] copyGrid(boolean[][] src) {
        boolean[][] dst = new boolean[src.length][src.length];
        for (int i = 0; i < src.length; i++) {
            System.arraycopy(src[i], 0, dst[i], 0, src[i].length);
        }
        return dst;
    }

    private static int calculatePenalty(boolean[][] m, int dim) {
        int penalty = 0;
        for (int y = 0; y < dim; y++) {
            int run = 1;
            for (int x = 1; x < dim; x++) {
                if (m[y][x] == m[y][x - 1]) {
                    run++;
                    if (run == 5) penalty += 3;
                    else if (run > 5) penalty += 1;
                } else {
                    run = 1;
                }
            }
        }
        for (int x = 0; x < dim; x++) {
            int run = 1;
            for (int y = 1; y < dim; y++) {
                if (m[y][x] == m[y - 1][x]) {
                    run++;
                    if (run == 5) penalty += 3;
                    else if (run > 5) penalty += 1;
                } else {
                    run = 1;
                }
            }
        }
        return penalty;
    }
}
