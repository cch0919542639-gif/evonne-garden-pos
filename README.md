# 🌿 伊凡的花園 (Evonne's Garden) — Android 平板獨立點餐與雙機列印 POS 系統

專為餐飲門市平板（如 `Lenovo Tab M8 TB300FU`）打造的 **100% 原生 Android 點餐與 Wi-Fi 雙印表機出單系統**。採用輕量化原生架構（零第三方肥大套件依賴），不需連接電腦主機，單靠一台平板連線店內 Wi-Fi 即可獨立點餐、控制收據機與標籤貼紙機、離線儲存帳務、匯出 Excel CSV 報表、同步上傳至 Google 試算表，並支援客人手機掃描 QR Code 線上點餐。

---

## ✨ 核心功能特色

1. **完整點餐與客製化系統（`MainActivity.java`）**
   - 支援 **🏠 內用（🗺️ 1~13桌 + 吧台 視覺化桌位平面圖點選與自由排桌）／🛍️ 外帶／🛵 外送／🛵 UberEats／🐼 Foodpanda** 五種點餐模式。
   - **結帳多元收款**：支援 **現金（含快速面額試算找零）、信用卡、LINE Pay、街口支付、UberEats、Foodpanda**。
   - **智慧貼紙勾選與隨餐附飲獨立印單規則**：
     - 單點飲品預設常態印貼紙。
     - 主餐（義大利麵/燉飯/煎餃/簡餐/比薩）所含之**隨餐附贈冷壓萃茶或補差價換購飲品**，無論內用、外帶或外送，皆**自動獨立印出一張 `[附飲]` 飲料杯貼紙**（外帶/外送時自動印「餐盒貼紙 + 附餐飲料貼紙」共 2 張）。
   - **麻辣燙／藥膳燙一鍵秒換湯頭**、**主餐升級加價（焗烤／松露／全素起司）**、**隨餐附贈冷壓萃茶與全店飲品補差價換購**、**飲品甜度／冰量／加料**、**單品與整單折扣／招待**。
2. **📱 客人掃碼線上點餐 & 桌牌 QR Code 列印系統（`QrCodeGenerator.java` & `google_apps_script_Code.gs`）**
   - **零月租費雲端手機點餐網頁**：客人使用手機相機或 LINE 掃描桌牌 QR Code（`?page=order&table=1桌`），免裝 App 直接開啟 RWD 手機點餐頁，選完送出後直達 Google 試算表雲端佇列。
   - **平板 POS 自動輪詢與一鍵接單出單**：頂部導覽列內建「📱 掃碼點餐/桌牌」中心，收到新單時自動亮起「🔔 掃碼待接(N)」，可一鍵載入購物車或直接結帳並觸發雙機出單。
   - **收據機直接列印各桌 QR Code 立牌**：內建純 Java ISO/IEC 18004 QR Code 產生器，可直接透過 `HPRT TP805L` 出單機印出 1~13 桌及外帶的紙本掃碼立牌。
3. **Wi-Fi 雙印表機 Port 9100 直連列印引擎（`PosPrintEngine.java`）**
   - **🧾 收據出單機（`HPRT TP805L`，80mm/58mm ESC/POS）**：
     - 全圖形光柵點陣渲染（`GS v 0` + 自動切刀 `GS V 66 0`），100% 繁體中文清晰不亂碼。
     - 內建 **6 段字體大小縮放（100% ～ 210%）**、**6 種紙張可印點寬（352 ～ 576 dots）** 與 **左右邊緣防裁切留白微調**。
   - **🏷️ 標籤貼紙機（`GoDEX DT2x`，`40 × 30 mm`）**：
     - Canvas 渲染 `320 × 240 dots (203 DPI)` 黑白點陣圖，封裝為標準 GoDEX 原生 **`EZPL` (`Q0,0,40,240`)** 與 **`TSPL` (`BITMAP`)** 指令，單品多件與隨餐附飲自動拆張標註 `1/N`。
4. **本地 SQLite 參數化資料庫與區間營收統計（`PosDatabaseHelper.java`）**
   - 100% 參數化查詢（`?`）防止 SQL Injection，離線完整保存菜單、當日訂單、作廢退單（`VOID`）與歷史關帳紀錄。
   - 內建 **「📈 區間營收 & 餐點統計」**：平板上可直接一鍵查詢 **近 7 天、前兩週（近 14 天）、本月累計、上個月、指定月份（1~12月）或自訂日期區間** 的總營收、各付款別金額與 **🏆 餐點銷售數量排行榜**，並可用出單機直接印出區間統計報表。
   - 每日關帳自動於平板 `Download/POS_Reports/` 匯出含 UTF-8 BOM 的 Excel CSV 報表。
5. **Google 試算表雲端雙向同步（`CloudSyncManager.java` & `google_apps_script_Code.gs`）**
   - 程式碼中**不寫死任何 API 金鑰或敏感網址**（使用者於平板設定頁輸入自建之 Google Apps Script 網頁應用程式網址）。
   - 關帳時自動上傳至 Google 試算表，並自動建立 **「📊 營收與餐點統計看板」**、**「每日關帳總表」**、**「每日品項銷售明細表」**、**「訂單明細表」**、**「線上掃碼待接訂單」** 與 **「菜單設定表」**。

---

## 📂 專案結構

```text
pos_tablet_app/
├── app/src/main/
│   ├── AndroidManifest.xml
│   └── java/com/pos/tablet/
│       ├── MainActivity.java        # 點餐、視覺化排桌、掃碼接單、客製化彈窗、日結與統計 UI
│       ├── PosDatabaseHelper.java   # SQLite 離線資料庫 (參數化查詢) 與 UTF-8 BOM CSV 匯出
│       ├── PosPrintEngine.java      # HPRT TP805L (ESC/POS) 與 GoDEX DT2x (EZPL/TSPL) 列印引擎
│       ├── QrCodeGenerator.java     # 純 Java 原生 ISO/IEC 18004 QR Code 與桌牌立牌產生器
│       └── CloudSyncManager.java    # Google 試算表雲端同步、掃碼訂單拉取與菜單備份/下載
├── google_apps_script_Code.gs       # 部署於 Google 試算表「擴充功能 -> Apps Script」之雲端與手機點餐腳本
├── build_and_install.ps1            # 一鍵編譯打包 APK 並透過 ADB 安裝至平板腳本
└── .gitignore                       # 排除私密簽章金鑰 (*.keystore) 與編譯暫存檔
```

---

## 🛠️ 編譯與安裝方式

於 Windows PowerShell 執行：

```powershell
powershell -ExecutionPolicy Bypass -File .\build_and_install.ps1
```
> 腳本會自動使用 Android SDK `aapt2`、`javac`、`d8`、`zipalign` 與本地自動產生的測試簽章打包出 `pos_tablet_app.apk`（`debug.keystore` 已由 `.gitignore` 排除不上傳版本庫）。
