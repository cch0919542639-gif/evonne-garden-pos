/**
 * 伊凡的花園 Evonne's Garden - 平板 POS 雲端資料庫 & 手機掃碼點餐系統 (Google Apps Script v3)
 *
 * 【功能總覽】：
 * 1. 📱 客人手機掃碼線上點餐系統 (doGet ?page=order&table=1桌~13桌)：
 *    - 客人用手機相機或 LINE 掃描桌牌 QR Code，免裝 App 直接開啟 RWD 手機點餐網頁。
 *    - 完整支援：麻辣燙/藥膳燙選湯頭、主餐升級(焗烤/松露/全素起司)、隨餐附贈冷壓萃茶或補差價換購飲品、飲品甜度/冰量/加料、信用卡/現金/LINE Pay 付款偏好。
 *    - 送出後寫入「📱 線上掃碼待接訂單」工作表，店內平板 POS 自動跳出「🔔 掃碼待接(N)」提示並可一鍵接單印收據與貼紙！
 * 2. 📊 營收與餐點統計看板：
 *    - 支援「近7天」、「前兩週(近14天)」、「本月累計」、「上個月」與「自訂日期區間(B2~D2)」自動統計（含現金、信用卡、LINE Pay、街口、外送平台與餐點銷量排行）。
 */

function doGet(e) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  initAllSheets(ss);

  // 若網址帶有 ?page=order 或 ?table=...，回傳客人手機掃碼點餐 RWD 網頁
  if (e && e.parameter && (e.parameter.page === "order" || e.parameter.table)) {
    var tableParam = String(e.parameter.table || "1桌");
    return renderMobileOrderWebApp(ss, tableParam);
  }

  return ContentService.createTextOutput(
    JSON.stringify({
      status: "ok",
      store: "伊凡的花園 Evonne's Garden",
      message: "POS 雲端試算表與手機掃碼點餐系統連線正常！(欲測試手機點餐請於網址後加上 ?page=order&table=1桌)"
    })
  ).setMimeType(ContentService.MimeType.JSON);
}

function doPost(e) {
  try {
    var payload = JSON.parse(e.postData.contents);
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    initAllSheets(ss);
    var action = payload.action || "upload_closing";

    // ==================== 1. 客人手機掃碼送出線上點餐 ====================
    if (action === "submit_qr_order") {
      var sheetQr = ss.getSheetByName("線上掃碼待接訂單");
      var now = new Date();
      var timeStr = Utilities.formatDate(now, "GMT+8", "HH:mm");
      var dateStr = Utilities.formatDate(now, "GMT+8", "yyyy-MM-dd HH:mm:ss");
      var orderId = "QR" + Utilities.formatDate(now, "GMT+8", "HHmmss");

      var table = String(payload.table || "1桌");
      var paymentPref = String(payload.paymentPref || "現金");
      var customerNote = String(payload.customerNote || "");
      var total = Number(payload.total || 0);
      var items = payload.items || [];

      sheetQr.appendRow([
        orderId,
        dateStr,
        timeStr,
        table,
        "PENDING",
        paymentPref,
        total,
        customerNote,
        JSON.stringify(items)
      ]);

      return jsonResponse({
        status: "ok",
        orderId: orderId,
        time: timeStr,
        message: "訂單已送達櫃檯 POS！"
      });
    }

    // ==================== 2. 平板 POS 拉取待接掃碼訂單 (PENDING) ====================
    if (action === "fetch_pending_qr_orders") {
      var sq = ss.getSheetByName("線上掃碼待接訂單");
      var pending = [];
      if (sq && sq.getLastRow() > 1) {
        var rows = sq.getRange(2, 1, sq.getLastRow() - 1, 9).getValues();
        for (var r = 0; r < rows.length; r++) {
          var row = rows[r];
          if (String(row[4]) === "PENDING") {
            var parsedItems = [];
            try { parsedItems = JSON.parse(String(row[8] || "[]")); } catch (err) {}
            pending.push({
              orderId: String(row[0]),
              time: String(row[2]),
              table: String(row[3]),
              paymentPref: String(row[5]),
              total: Number(row[6] || 0),
              customerNote: String(row[7] || ""),
              items: parsedItems
            });
          }
        }
      }
      return jsonResponse({ status: "ok", orders: pending });
    }

    // ==================== 3. 平板 POS 確認接單或取消掃碼訂單 ====================
    if (action === "ack_qr_order") {
      var targetId = String(payload.orderId || "");
      var newStatus = String(payload.status || "ACCEPTED");
      var sqAck = ss.getSheetByName("線上掃碼待接訂單");
      if (sqAck && sqAck.getLastRow() > 1) {
        var data = sqAck.getRange(2, 1, sqAck.getLastRow() - 1, 5).getValues();
        for (var idx = 0; idx < data.length; idx++) {
          if (String(data[idx][0]) === targetId) {
            sqAck.getRange(idx + 2, 5).setValue(newStatus);
            break;
          }
        }
      }
      return jsonResponse({ status: "ok", orderId: targetId, updatedStatus: newStatus });
    }

    // ==================== 4. 每日關帳總表 + 品項銷量 + 訂單明細上傳 ====================
    if (action === "upload_closing") {
      var closeTimeStr = String(payload.closeTime || "");
      var dateOnly = closeTimeStr.length >= 10 ? closeTimeStr.substring(0, 10) : closeTimeStr;
      var monthOnly = closeTimeStr.length >= 7 ? closeTimeStr.substring(0, 7) : closeTimeStr;

      var byPay = payload.byPayment || {};
      var payCash = Number(byPay["現金"] || payload.cashSales || 0);
      var payCard = Number(byPay["信用卡"] || 0);
      var payLine = Number(byPay["LINE Pay"] || 0);
      var payJko = Number(byPay["街口支付"] || 0);
      var payUber = Number(byPay["UberEats"] || 0);
      var payPanda = Number(byPay["Foodpanda"] || 0);

      var sheetSummary = ss.getSheetByName("每日關帳總表");
      sheetSummary.appendRow([
        dateOnly,
        monthOnly,
        closeTimeStr,
        Number(payload.validCount || 0),
        Number(payload.voidCount || 0),
        Number(payload.netSales || 0),
        payCash,
        payCard,
        payLine,
        payJko,
        payUber,
        payPanda,
        Number(payload.openingFloat || 0),
        Number(payload.expectedDrawerCash || 0)
      ]);

      var sheetItemSales = ss.getSheetByName("每日品項銷售明細表");
      var itemRank = payload.itemRanking || {};
      var itemKeys = Object.keys(itemRank);
      for (var k = 0; k < itemKeys.length; k++) {
        var iName = itemKeys[k];
        var iQty = Number(itemRank[iName] || 0);
        if (iQty > 0) {
          sheetItemSales.appendRow([dateOnly, monthOnly, closeTimeStr, iName, iQty]);
        }
      }

      var sheetOrders = ss.getSheetByName("訂單明細表");
      var orders = payload.orders || [];
      for (var i = 0; i < orders.length; i++) {
        var o = orders[i];
        sheetOrders.appendRow([
          dateOnly,
          monthOnly,
          closeTimeStr,
          o.callNum || "",
          o.time || "",
          o.orderType || "",
          o.status || "VALID",
          o.paymentMethod || "",
          Number(o.orderDiscount || 0),
          Number(o.total || 0),
          o.itemsSummary || ""
        ]);
      }
      return jsonResponse({ status: "ok", message: "已寫入關帳總表、品項銷量與 " + orders.length + " 筆訂單" });
    }

    // ==================== 5. 平板菜單上傳備份至試算表 ====================
    if (action === "upload_menu") {
      var sheetMenu = ss.getSheetByName("菜單設定表");
      sheetMenu.clearContents();
      sheetMenu.appendRow([
        "ID", "分類", "品項名稱", "副標題說明", "價格",
        "是否飲品", "隨餐附茶", "固定甜度", "僅供冷飲", "是否厚奶", "素別與湯頭選項JSON"
      ]);
      var menu = payload.menu || [];
      for (var j = 0; j < menu.length; j++) {
        var m = menu[j];
        sheetMenu.appendRow([
          m.id, m.cat, m.name, m.sub, m.price,
          m.isDrink, m.hasFreeTea, m.fixedSweet, m.coldOnly, m.isThickMilk,
          JSON.stringify(m.choices || [])
        ]);
      }
      return jsonResponse({ status: "ok", count: menu.length });
    }

    // ==================== 6. 從試算表下載菜單至平板 ====================
    if (action === "download_menu") {
      var outMenu = loadMenuFromSheet(ss);
      if (outMenu.length === 0) return jsonResponse({ status: "empty", menu: [] });
      return jsonResponse({ status: "ok", menu: outMenu });
    }

    return jsonResponse({ status: "error", message: "Unknown action" });
  } catch (err) {
    return jsonResponse({ status: "error", message: String(err) });
  }
}

function loadMenuFromSheet(ss) {
  var sm = ss.getSheetByName("菜單設定表");
  if (!sm || sm.getLastRow() <= 1) return [];
  var rows = sm.getRange(2, 1, sm.getLastRow() - 1, 11).getValues();
  var outMenu = [];
  for (var r = 0; r < rows.length; r++) {
    var row = rows[r];
    var ch = [];
    try { ch = JSON.parse(row[10] || "[]"); } catch (err) {}
    outMenu.push({
      id: Number(row[0]), cat: String(row[1]), name: String(row[2]), sub: String(row[3]),
      price: Number(row[4]), isDrink: Boolean(row[5]), hasFreeTea: Boolean(row[6]),
      fixedSweet: Boolean(row[7]), coldOnly: Boolean(row[8]), isThickMilk: Boolean(row[9]),
      choices: ch
    });
  }
  return outMenu;
}

function initAllSheets(ss) {
  getOrCreateSheet(ss, "每日關帳總表", [
    "日期(YYYY-MM-DD)", "月份(YYYY-MM)", "關帳時間", "有效單數", "作廢單數",
    "今日淨營業額", "現金實收", "信用卡", "LINE Pay", "街口支付", "UberEats", "Foodpanda",
    "期初零用金", "錢櫃應有總現金"
  ]);
  getOrCreateSheet(ss, "每日品項銷售明細表", [
    "日期(YYYY-MM-DD)", "月份(YYYY-MM)", "關帳批次時間", "品項名稱", "銷售數量"
  ]);
  getOrCreateSheet(ss, "訂單明細表", [
    "日期(YYYY-MM-DD)", "月份(YYYY-MM)", "關帳批次時間", "取餐號", "點餐時間",
    "用餐類型", "狀態", "付款方式", "整單折扣", "應收總計", "餐點與客製明細"
  ]);
  getOrCreateSheet(ss, "線上掃碼待接訂單", [
    "線上單號", "送單完整時間", "時間", "桌號/用餐別", "接單狀態(PENDING/ACCEPTED)",
    "預計付款方式", "應收總計", "客人備註", "點餐明細JSON"
  ]);
  getOrCreateSheet(ss, "菜單設定表", [
    "ID", "分類", "品項名稱", "副標題說明", "價格",
    "是否飲品", "隨餐附茶", "固定甜度", "僅供冷飲", "是否厚奶", "素別與湯頭選項JSON"
  ]);
  initDashboardSheet(ss);
}

function initDashboardSheet(ss) {
  var name = "📊 營收與餐點統計看板";
  var dash = ss.getSheetByName(name);
  if (dash) return;
  dash = ss.insertSheet(name, 0);

  dash.getRange("A1").setValue("🌿 伊凡的花園 — 區間營收與餐點銷售自動統計看板（修改黃色日期格即可自動查詢任意月份或區間）");
  dash.getRange("A1").setFontWeight("bold").setFontSize(13);

  dash.getRange("A2").setValue("查詢起始日期：");
  dash.getRange("B2").setValue("2026-10-01").setBackground("#FEF08A").setNumberFormat("@");
  dash.getRange("C2").setValue("查詢結束日期：");
  dash.getRange("D2").setValue("2026-12-31").setBackground("#FEF08A").setNumberFormat("@");

  dash.getRange("A4:J4").setValues([[
    "統計區間", "有效單數", "作廢單數", "淨營業額總計",
    "現金總計", "信用卡", "LINE Pay", "街口支付", "UberEats", "Foodpanda"
  ]]).setFontWeight("bold").setBackground("#D1FAE5");

  var cols = ["D", "E", "F", "G", "H", "I", "J", "K", "L"];
  // 1. 自訂區間
  dash.getRange("A5").setValue("🔍 自訂區間 (B2~D2)");
  // 2. 前兩週 (近 14 天)
  dash.getRange("A6").setValue("📅 前兩週 (近 14 天)");
  // 3. 本月累計
  dash.getRange("A7").setValue("📅 本月累計");
  // 4. 上個月累計
  dash.getRange("A8").setValue("📅 上個月累計");

  for (var idx = 0; idx < cols.length; idx++) {
    var sc = cols[idx];
    var targetCol = String.fromCharCode(66 + idx); // B..J
    dash.getRange(targetCol + "5").setFormula('=SUMIFS(\'每日關帳總表\'!' + sc + ':' + sc + ', \'每日關帳總表\'!A:A, ">="&B2, \'每日關帳總表\'!A:A, "<="&D2)');
    dash.getRange(targetCol + "6").setFormula('=SUMIFS(\'每日關帳總表\'!' + sc + ':' + sc + ', \'每日關帳總表\'!A:A, ">="&TEXT(TODAY()-14,"yyyy-mm-dd"))');
    dash.getRange(targetCol + "7").setFormula('=SUMIFS(\'每日關帳總表\'!' + sc + ':' + sc + ', \'每日關帳總表\'!B:B, TEXT(TODAY(),"yyyy-mm"))');
    dash.getRange(targetCol + "8").setFormula('=SUMIFS(\'每日關帳總表\'!' + sc + ':' + sc + ', \'每日關帳總表\'!B:B, TEXT(EDATE(TODAY(),-1),"yyyy-mm"))');
  }

  dash.getRange("A10").setValue("📆 各月份營收自動彙總").setFontWeight("bold");
  dash.getRange("A11").setFormula(
    '=IFERROR(QUERY(\'每日關帳總表\'!A2:L, "SELECT B, SUM(D), SUM(E), SUM(F), SUM(G), SUM(H) WHERE B IS NOT NULL GROUP BY B ORDER BY B DESC LABEL B \'月份\', SUM(D) \'有效單數\', SUM(E) \'作廢單數\', SUM(F) \'月淨營收\', SUM(G) \'現金實收\', SUM(H) \'信用卡\'", 0), "尚無關帳資料")'
  );

  dash.getRange("H10").setValue("🏆 自訂區間 (B2~D2) 餐點銷售數量排行榜").setFontWeight("bold");
  dash.getRange("H11").setFormula(
    '=IFERROR(QUERY(\'每日品項銷售明細表\'!A2:E, "SELECT D, SUM(E) WHERE A >= \'"&B2&"\' AND A <= \'"&D2&"\' GROUP BY D ORDER BY SUM(E) DESC LABEL D \'品項名稱\', SUM(E) \'銷售總數\'", 0), "此區間尚無銷售資料")'
  );
}

function submitQrOrderFromHtml(orderData) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  initAllSheets(ss);
  var sheetQr = ss.getSheetByName("線上掃碼待接訂單");
  var now = new Date();
  var timeStr = Utilities.formatDate(now, "GMT+8", "HH:mm");
  var dateStr = Utilities.formatDate(now, "GMT+8", "yyyy-MM-dd HH:mm:ss");
  var orderId = "QR" + Utilities.formatDate(now, "GMT+8", "HHmmss");

  var table = String((orderData && orderData.table) || "1桌");
  var paymentPref = String((orderData && orderData.paymentPref) || "現金");
  var customerNote = String((orderData && orderData.customerNote) || "");
  var total = Number((orderData && orderData.total) || 0);
  var items = (orderData && orderData.items) || [];

  sheetQr.appendRow([
    orderId,
    dateStr,
    timeStr,
    table,
    "PENDING",
    paymentPref,
    total,
    customerNote,
    JSON.stringify(items)
  ]);

  return {
    status: "ok",
    orderId: orderId,
    time: timeStr,
    message: "訂單已送達櫃檯 POS！"
  };
}

// ==================== 📱 客人手機掃碼點餐 RWD 網頁渲染 (v4 修正雙引號衝突 + 放大字體卡片 + addMetaTag 真手機適配) ====================
function renderMobileOrderWebApp(ss, defaultTable) {
  var menuList = loadMenuFromSheet(ss);
  var menuJson = JSON.stringify(menuList);
  var safeTable = String(defaultTable || "1桌").replace(/["<>]/g, "");

  var html = '<!DOCTYPE html><html lang="zh-TW"><head><meta charset="UTF-8">'
    + '<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">'
    + '<title>伊凡的花園 - 線上掃碼點餐</title>'
    + '<style>'
    + '*{box-sizing:border-box;-webkit-tap-highlight-color:transparent;}'
    + 'body{margin:0;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Noto Sans TC",sans-serif;background:#0F172A;color:#F8FAFC;padding-bottom:110px;font-size:17px;}'
    + '.header{background:linear-gradient(135deg,#064E3B,#047857);padding:14px 16px;position:sticky;top:0;z-index:20;box-shadow:0 3px 10px rgba(0,0,0,0.45);}'
    + '.brand{font-size:21px;font-weight:900;color:#ECFDF5;display:flex;justify-content:space-between;align-items:center;}'
    + '.sub-banner{font-size:13px;color:#A7F3D0;margin-top:4px;}'
    + '.table-badge{background:#F59E0B;color:#052E16;padding:6px 14px;border-radius:10px;font-size:17px;font-weight:900;box-shadow:0 2px 6px rgba(0,0,0,0.3);}'
    + '.cats{display:flex;overflow-x:auto;gap:10px;padding:12px 14px;background:#1E293B;position:sticky;top:72px;z-index:19;border-bottom:1px solid #334155;}'
    + '.cat-btn{white-space:nowrap;padding:10px 18px;border-radius:24px;border:2px solid #475569;background:#0F172A;color:#E2E8F0;font-size:16px;font-weight:bold;cursor:pointer;}'
    + '.cat-btn.active{background:#10B981;color:#052E16;border-color:#6EE7B7;box-shadow:0 0 10px rgba(16,185,129,0.4);}'
    + '.grid{padding:14px;display:grid;grid-template-columns:1fr;gap:12px;}'
    + '.card{background:#1E293B;border:1.5px solid #334155;border-radius:14px;padding:15px 16px;display:flex;justify-content:space-between;align-items:center;box-shadow:0 2px 6px rgba(0,0,0,0.25);}'
    + '.card-title{font-size:19px;font-weight:800;color:#FFFFFF;line-height:1.3;}'
    + '.card-sub{font-size:14px;color:#94A3B8;margin-top:5px;}'
    + '.card-price{font-size:20px;font-weight:900;color:#FCD34D;margin-top:6px;}'
    + '.add-btn{background:#10B981;color:#052E16;border:none;padding:12px 18px;border-radius:10px;font-weight:900;font-size:16px;white-space:nowrap;margin-left:12px;cursor:pointer;box-shadow:0 2px 6px rgba(16,185,129,0.3);}'
    + '.footer{position:fixed;bottom:0;left:0;right:0;background:#0F172A;border-top:2px solid #10B981;padding:12px 16px;display:flex;justify-content:space-between;align-items:center;z-index:30;box-shadow:0 -4px 14px rgba(0,0,0,0.5);}'
    + '.submit-btn{background:#10B981;color:#052E16;border:none;padding:13px 22px;border-radius:12px;font-size:17px;font-weight:900;cursor:pointer;}'
    + '.modal{position:fixed;inset:0;background:rgba(0,0,0,0.82);display:none;align-items:center;justify-content:center;z-index:50;padding:14px;}'
    + '.modal-box{background:#1E293B;border:2px solid #475569;border-radius:16px;width:100%;max-width:480px;max-height:88vh;overflow-y:auto;padding:18px;}'
    + '.opt-group{margin-top:14px;}'
    + '.opt-title{font-size:15px;color:#A7F3D0;font-weight:bold;margin-bottom:8px;}'
    + '.opt-grid{display:flex;flex-wrap:wrap;gap:8px;}'
    + '.chip{padding:9px 14px;border-radius:8px;border:1.5px solid #475569;background:#0F172A;color:#FFF;font-size:15px;cursor:pointer;}'
    + '.chip.sel{background:#10B981;color:#052E16;font-weight:900;border-color:#6EE7B7;}'
    + '</style></head><body>'
    + '<div class="header">'
    + '  <div class="brand"><span>🌿 伊凡的花園</span><span class="table-badge">🪑 ' + safeTable + '</span></div>'
    + '  <div class="sub-banner">請點選下方分類與餐點，送出後由櫃檯為您確認製作</div>'
    + '</div>'
    + '<div id="catBar" class="cats"></div>'
    + '<div id="menuList" class="grid"></div>'
    + '<div class="footer">'
    + '  <div><div style="font-size:14px;color:#CBD5E1;" id="cartCount">尚未選購餐點</div><div style="font-size:22px;font-weight:900;color:#FCD34D;" id="cartTotal">應收 $0</div></div>'
    + '  <button class="submit-btn" onclick="openCartModal()">🛒 查看購物車 / 送出</button>'
    + '</div>'
    + '<div id="itemModal" class="modal"><div class="modal-box" id="itemModalBody"></div></div>'
    + '<div id="cartModal" class="modal"><div class="modal-box" id="cartModalBody"></div></div>'
    + '<script>'
    + 'var TABLE_NAME = ' + JSON.stringify(safeTable) + ';'
    + 'var MENU = ' + menuJson + ';'
    + 'var CATS = [];'
    + 'MENU.forEach(function(m){ if(CATS.indexOf(m.cat)===-1) CATS.push(m.cat); });'
    + 'var cart = [];'
    + 'var curCatIdx = 0;'
    + 'var SWEETS = ["無糖","1分糖","3分微糖","5分半糖","8分少糖","10分正常","▲甜度固定"];'
    + 'var ICES = ["去冰","3分微冰","5分半冰","8分少冰","10分正常冰","常溫","熱飲(Hot)"];'
    + 'var FREE_TEAS = ["毛尖綠茶","甘露青茶","麥香紅玉","👍凍頂烏龍","英式伯爵","不需附茶"];'
    + 'var DRINK_CATS = ["冷壓萃茶","纖果茶","現萃鮮茶","人氣搭配","獨家厚奶"];'
    + 'var UPGRADES = [{name:"升級焗烤",price:50},{name:"升級松露",price:50},{name:"升級全素起司",price:80}];'
    + 'var SET_ADDONS = [{name:"加爌肉",price:40},{name:"加植蛋",price:40},{name:"加叉燒",price:50},{name:"加棒腿",price:60},{name:"加滷排",price:70},{name:"加和牛排",price:80}];'
    + 'var DRINK_ADDONS = [{name:"寒天愛玉",price:15},{name:"鳳梨椰果",price:10},{name:"厚奶(濃奶)",price:20},{name:"鮮奶",price:30}];'
    + 'var PAY_LIST = ["現金","信用卡","LINE Pay","街口支付"];'
    + 'var MEAL_DRINK_POOL = [{group:"冷壓萃茶",name:"不需附茶",diff:0,fixedSweet:false}];'
    + 'for(var dIdx=0;dIdx<MENU.length;dIdx++){'
    + '  if(MENU[dIdx].isDrink){'
    + '    MEAL_DRINK_POOL.push({group:MENU[dIdx].cat,name:MENU[dIdx].name,diff:Math.max(0,MENU[dIdx].price-30),fixedSweet:!!MENU[dIdx].fixedSweet});'
    + '  }'
    + '}'
    + 'function renderCats(){'
    + '  var h="";'
    + '  for(var i=0;i<CATS.length;i++){'
    + '    h+="<button class=\\"cat-btn "+(i===curCatIdx?"active":"")+"\\" onclick=\\"selCatIdx("+i+")\\">"+CATS[i]+"</button>";'
    + '  }'
    + '  document.getElementById("catBar").innerHTML=h;'
    + '}'
    + 'function selCatIdx(idx){ curCatIdx=idx; renderCats(); renderMenu(); window.scrollTo(0,0); }'
    + 'function renderMenu(){'
    + '  if(!MENU || MENU.length===0){'
    + '    document.getElementById("menuList").innerHTML="<div style=\\"padding:24px;color:#FCD34D;font-size:18px;\\">請先於店內平板 POS 點擊「⚙️設定 ➔ ⬆️上傳菜單備份至試算表」以啟用線上菜單！</div>";'
    + '    return;'
    + '  }'
    + '  var curCat = CATS[curCatIdx] || "";'
    + '  var h="";'
    + '  for(var i=0;i<MENU.length;i++){'
    + '    var m = MENU[i];'
    + '    if(m.cat !== curCat) continue;'
    + '    h+="<div class=\\"card\\" onclick=\\"openItemIdx("+i+")\\">"'
    + '      +"<div style=\\"flex:1;\\"><div class=\\"card-title\\">"+m.name+"</div>"'
    + '      +"<div class=\\"card-sub\\">"+(m.sub||"")+"</div>"'
    + '      +"<div class=\\"card-price\\">$"+m.price+"</div></div>"'
    + '      +"<button class=\\"add-btn\\" onclick=\\"event.stopPropagation();openItemIdx("+i+")\\">＋點選</button></div>";'
    + '  }'
    + '  document.getElementById("menuList").innerHTML=h;'
    + '}'
    + 'var curItem=null, selChoice="", selSweet="5分半糖", selIce="8分少冰", selTea="毛尖綠茶", selTeaDiff=0, selTeaGroup="冷壓萃茶", selTeaSweet="無糖", selTeaIce="3分微冰", selAddons=[];'
    + 'function openItemIdx(idx){'
    + '  curItem = MENU[idx]; if(!curItem) return;'
    + '  selChoice = (curItem.choices && curItem.choices.length>0) ? curItem.choices[0] : "";'
    + '  selSweet = curItem.fixedSweet ? "▲甜度固定" : "5分半糖";'
    + '  selIce = "8分少冰";'
    + '  selTea = "毛尖綠茶"; selTeaDiff = 0; selTeaGroup = "冷壓萃茶"; selTeaSweet = "無糖"; selTeaIce = "3分微冰"; selAddons = [];'
    + '  renderItemModal();'
    + '  document.getElementById("itemModal").style.display="flex";'
    + '}'
    + 'function pickChoice(i){ if(curItem && curItem.choices[i]!==undefined){ selChoice=curItem.choices[i]; renderItemModal(); } }'
    + 'function pickTeaGroup(i){ selTeaGroup=DRINK_CATS[i]; renderItemModal(); }'
    + 'function pickPoolTea(i){'
    + '  var d=MEAL_DRINK_POOL[i]; if(!d) return;'
    + '  selTea=d.name; selTeaDiff=d.diff;'
    + '  if(d.fixedSweet) selTeaSweet="▲甜度固定"; else if(selTeaSweet==="▲甜度固定") selTeaSweet="無糖";'
    + '  renderItemModal();'
    + '}'
    + 'function pickTeaSweet(i){ selTeaSweet=SWEETS[i]; renderItemModal(); }'
    + 'function pickTeaIce(i){ selTeaIce=ICES[i]; renderItemModal(); }'
    + 'function pickSweet(i){ selSweet=SWEETS[i]; renderItemModal(); }'
    + 'function pickIce(i){ selIce=ICES[i]; renderItemModal(); }'
    + 'function toggleUpgrade(i){ toggleAddonObj(UPGRADES[i]); }'
    + 'function toggleSetAddon(i){ toggleAddonObj(SET_ADDONS[i]); }'
    + 'function toggleDrinkAddon(i){ toggleAddonObj(DRINK_ADDONS[i]); }'
    + 'function toggleAddonObj(u){'
    + '  if(!u) return;'
    + '  var idx=-1; for(var k=0;k<selAddons.length;k++){ if(selAddons[k].name===u.name){ idx=k; break; } }'
    + '  if(idx>=0) selAddons.splice(idx,1); else selAddons.push({name:u.name,price:u.price});'
    + '  renderItemModal();'
    + '}'
    + 'function closeItemModal(){ document.getElementById("itemModal").style.display="none"; }'
    + 'function closeCartModal(){ document.getElementById("cartModal").style.display="none"; }'
    + 'function renderItemModal(){'
    + '  var m=curItem; var i;'
    + '  var addSum=0; for(i=0;i<selAddons.length;i++) addSum+=selAddons[i].price;'
    + '  var unitNow = m.price + (m.hasFreeTea ? selTeaDiff : 0) + addSum;'
    + '  var h="<div style=\\"font-size:20px;font-weight:900;color:#A7F3D0;\\">"+m.name+" <span style=\\"color:#FCD34D;\\">($"+unitNow+")</span></div>";'
    + '  if(m.choices && m.choices.length>0){'
    + '    h+="<div class=\\"opt-group\\"><div class=\\"opt-title\\">1. 選擇素別 / 湯頭：</div><div class=\\"opt-grid\\">";'
    + '    for(i=0;i<m.choices.length;i++){'
    + '      var c=m.choices[i];'
    + '      h+="<button class=\\"chip "+(selChoice===c?"sel":"")+"\\" onclick=\\"pickChoice("+i+")\\">"+c+"</button>";'
    + '    }'
    + '    h+="</div></div>";'
    + '  }'
    + '  if(m.cat==="義大利麵&飯食" || m.cat==="個人比薩7.5吋"){'
    + '    h+="<div class=\\"opt-group\\"><div class=\\"opt-title\\">🧀 主餐升級加價：</div><div class=\\"opt-grid\\">";'
    + '    for(i=0;i<UPGRADES.length;i++){'
    + '      var u=UPGRADES[i];'
    + '      var has=false; for(var k=0;k<selAddons.length;k++){ if(selAddons[k].name===u.name) has=true; }'
    + '      h+="<button class=\\"chip "+(has?"sel":"")+"\\" onclick=\\"toggleUpgrade("+i+")\\">+"+u.name+"($"+u.price+")</button>";'
    + '    }'
    + '    h+="</div></div>";'
    + '  } else if(m.cat==="簡餐(五穀飯)"){'
    + '    h+="<div class=\\"opt-group\\"><div class=\\"opt-title\\">🍱 簡餐單點加菜：</div><div class=\\"opt-grid\\">";'
    + '    for(i=0;i<SET_ADDONS.length;i++){'
    + '      var sa=SET_ADDONS[i];'
    + '      var hasSa=false; for(var ks=0;ks<selAddons.length;ks++){ if(selAddons[ks].name===sa.name) hasSa=true; }'
    + '      h+="<button class=\\"chip "+(hasSa?"sel":"")+"\\" onclick=\\"toggleSetAddon("+i+")\\">+"+sa.name+"($"+sa.price+")</button>";'
    + '    }'
    + '    h+="</div></div>";'
    + '  }'
    + '  if(m.hasFreeTea){'
    + '    h+="<div class=\\"opt-group\\"><div class=\\"opt-title\\">🍵 隨餐附飲（免費附冷壓萃茶，可點分類換購其他飲品）：</div><div class=\\"opt-grid\\" style=\\"margin-bottom:8px;\\">";'
    + '    for(i=0;i<DRINK_CATS.length;i++){'
    + '      var dc=DRINK_CATS[i];'
    + '      var lbl = (dc==="冷壓萃茶") ? "免費冷壓萃茶" : ("換購:"+dc);'
    + '      h+="<button class=\\"chip "+(selTeaGroup===dc?"sel":"")+"\\" style=\\"border-color:#38BDF8;\\" onclick=\\"pickTeaGroup("+i+")\\">"+lbl+"</button>";'
    + '    }'
    + '    h+="</div><div class=\\"opt-grid\\">";'
    + '    for(i=0;i<MEAL_DRINK_POOL.length;i++){'
    + '      var md=MEAL_DRINK_POOL[i];'
    + '      if(md.group!==selTeaGroup) continue;'
    + '      var pTag = md.diff>0 ? ("(+$"+md.diff+")") : "(+$0)";'
    + '      h+="<button class=\\"chip "+(selTea===md.name?"sel":"")+"\\" onclick=\\"pickPoolTea("+i+")\\">"+md.name+pTag+"</button>";'
    + '    }'
    + '    h+="</div></div>";'
    + '    if(selTea!=="不需附茶"){'
    + '      h+="<div class=\\"opt-group\\"><div class=\\"opt-title\\">🍬 附飲甜度：</div><div class=\\"opt-grid\\">";'
    + '      for(i=0;i<SWEETS.length;i++){'
    + '        var s=SWEETS[i];'
    + '        h+="<button class=\\"chip "+(selTeaSweet===s?"sel":"")+"\\" onclick=\\"pickTeaSweet("+i+")\\">"+s+"</button>";'
    + '      }'
    + '      h+="</div></div><div class=\\"opt-group\\"><div class=\\"opt-title\\">🧊 附飲冰量：</div><div class=\\"opt-grid\\">";'
    + '      var maxIce = (selTeaGroup==="冷壓萃茶") ? 6 : ICES.length;'
    + '      for(i=0;i<maxIce;i++){'
    + '        var ic=ICES[i];'
    + '        h+="<button class=\\"chip "+(selTeaIce===ic?"sel":"")+"\\" onclick=\\"pickTeaIce("+i+")\\">"+ic+"</button>";'
    + '      }'
    + '      h+="</div></div>";'
    + '    }'
    + '  }'
    + '  if(m.isDrink){'
    + '    h+="<div class=\\"opt-group\\"><div class=\\"opt-title\\">🍬 飲品甜度：</div><div class=\\"opt-grid\\">";'
    + '    for(i=0;i<SWEETS.length;i++){'
    + '      var ds=SWEETS[i];'
    + '      h+="<button class=\\"chip "+(selSweet===ds?"sel":"")+"\\" onclick=\\"pickSweet("+i+")\\">"+ds+"</button>";'
    + '    }'
    + '    h+="</div></div><div class=\\"opt-group\\"><div class=\\"opt-title\\">🧊 飲品冰量：</div><div class=\\"opt-grid\\">";'
    + '    var dMaxIce = m.coldOnly ? 6 : ICES.length;'
    + '    for(i=0;i<dMaxIce;i++){'
    + '      var dic=ICES[i];'
    + '      h+="<button class=\\"chip "+(selIce===dic?"sel":"")+"\\" onclick=\\"pickIce("+i+")\\">"+dic+"</button>";'
    + '    }'
    + '    h+="</div></div><div class=\\"opt-group\\"><div class=\\"opt-title\\">🧋 飲品加料：</div><div class=\\"opt-grid\\">";'
    + '    for(i=0;i<DRINK_ADDONS.length;i++){'
    + '      var da=DRINK_ADDONS[i];'
    + '      var hasDa=false; for(var k2=0;k2<selAddons.length;k2++){ if(selAddons[k2].name===da.name) hasDa=true; }'
    + '      h+="<button class=\\"chip "+(hasDa?"sel":"")+"\\" onclick=\\"toggleDrinkAddon("+i+")\\">+"+da.name+"($"+da.price+")</button>";'
    + '    }'
    + '    h+="</div></div>";'
    + '  }'
    + '  h+="<div style=\\"display:flex;gap:10px;margin-top:20px;\\">"'
    + '    +"<button class=\\"chip\\" style=\\"flex:1;padding:12px;\\" onclick=\\"closeItemModal()\\">取消</button>"'
    + '    +"<button class=\\"add-btn\\" style=\\"flex:2;padding:12px;margin-left:0;\\" onclick=\\"confirmAddItem()\\">✅ 加入點餐單 ($"+unitNow+")</button></div>";'
    + '  document.getElementById("itemModalBody").innerHTML=h;'
    + '}'
    + 'function confirmAddItem(){'
    + '  var addSum=0; for(var i=0;i<selAddons.length;i++) addSum+=selAddons[i].price;'
    + '  var tDiff = curItem.hasFreeTea ? selTeaDiff : 0;'
    + '  cart.push({name:curItem.name,qty:1,unitPrice:curItem.price+tDiff+addSum,choice:selChoice,sweet:curItem.isDrink?selSweet:"",ice:curItem.isDrink?selIce:"",mealDrinkName:curItem.hasFreeTea?selTea:"",mealDrinkDiff:tDiff,mealDrinkSweet:selTeaSweet,mealDrinkIce:selTeaIce,addons:selAddons.slice()});'
    + '  closeItemModal(); updateFooter();'
    + '}'
    + 'function updateFooter(){'
    + '  var q=0,t=0; for(var i=0;i<cart.length;i++){ q+=cart[i].qty; t+=cart[i].unitPrice*cart[i].qty; }'
    + '  document.getElementById("cartCount").innerText = q>0 ? ("已選 "+q+" 份餐點") : "尚未選購餐點";'
    + '  document.getElementById("cartTotal").innerText = "應收 $"+t;'
    + '}'
    + 'var payPref="現金";'
    + 'function pickPay(i){ payPref=PAY_LIST[i]; openCartModal(); }'
    + 'function removeCartItem(i){ cart.splice(i,1); updateFooter(); if(cart.length===0) closeCartModal(); else openCartModal(); }'
    + 'function openCartModal(){'
    + '  if(cart.length===0){ alert("請先點選餐點加入購物車！"); return; }'
    + '  var t=0; var h="<div style=\\"font-size:20px;font-weight:900;color:#A7F3D0;margin-bottom:12px;\\">🛒 點餐確認 ("+TABLE_NAME+")</div>";'
    + '  for(var i=0;i<cart.length;i++){'
    + '    var c=cart[i]; t+=c.unitPrice*c.qty;'
    + '    h+="<div style=\\"border-bottom:1px solid #334155;padding:10px 0;display:flex;justify-content:space-between;align-items:center;\\">"'
    + '      +"<div><b style=\\"font-size:17px;\\">"+(i+1)+". "+c.name+"</b> x"+c.qty+" <span style=\\"color:#FCD34D;font-weight:bold;\\">($"+(c.unitPrice*c.qty)+")</span>"'
    + '      +"<div style=\\"font-size:14px;color:#6EE7B7;margin-top:3px;\\">"+(c.choice||"")+" "+(c.mealDrinkName?"附:"+c.mealDrinkName+"("+c.mealDrinkSweet+"/"+c.mealDrinkIce+")":"")+" "+(c.sweet?c.sweet+"/"+c.ice:"")+"</div></div>"'
    + '      +"<button class=\\"chip\\" onclick=\\"removeCartItem("+i+")\\">刪除</button></div>";'
    + '  }'
    + '  h+="<div class=\\"opt-group\\"><div class=\\"opt-title\\">💳 預計結帳方式 (請於送出後至櫃檯結帳)：</div><div class=\\"opt-grid\\">";'
    + '  for(var p=0;p<PAY_LIST.length;p++){'
    + '    var pm=PAY_LIST[p];'
    + '    h+="<button class=\\"chip "+(payPref===pm?"sel":"")+"\\" onclick=\\"pickPay("+p+")\\">"+pm+"</button>";'
    + '  }'
    + '  h+="</div></div>";'
    + '  h+="<div class=\\"opt-group\\"><div class=\\"opt-title\\">📝 備註需求：</div><input id=\\"custNote\\" style=\\"width:100%;padding:10px;font-size:16px;border-radius:8px;border:1px solid #475569;background:#0F172A;color:#FFF;\\" placeholder=\\"例如：少鹽、餐具需求...\\"></div>";'
    + '  h+="<div style=\\"display:flex;gap:10px;margin-top:18px;\\">"'
    + '    +"<button class=\\"chip\\" style=\\"flex:1;padding:12px;\\" onclick=\\"closeCartModal()\\">繼續加點</button>"'
    + '    +"<button id=\\"btnSend\\" class=\\"submit-btn\\" style=\\"flex:2;\\" onclick=\\"submitOrder("+t+")\\">🚀 確認送出 ($"+t+")</button></div>";'
    + '  document.getElementById("cartModalBody").innerHTML=h;'
    + '  document.getElementById("cartModal").style.display="flex";'
    + '}'
    + 'function submitOrder(totalAmt){'
    + '  var btn=document.getElementById("btnSend"); btn.disabled=true; btn.innerText="⏳ 傳送至櫃檯中...";'
    + '  var note=document.getElementById("custNote")?document.getElementById("custNote").value:"";'
    + '  var payloadObj={action:"submit_qr_order",table:TABLE_NAME,paymentPref:payPref,customerNote:note,total:totalAmt,items:cart};'
    + '  function onSuccess(res){'
    + '    document.getElementById("cartModalBody").innerHTML="<div style=\\"text-align:center;padding:26px;\\"><div style=\\"font-size:48px;\\">✅</div><div style=\\"font-size:22px;font-weight:900;color:#A7F3D0;margin-top:10px;\\">點餐已送達櫃檯！</div><div style=\\"color:#FCD34D;font-size:18px;font-weight:bold;margin:12px 0;\\">桌號："+TABLE_NAME+" ｜ 單號："+res.orderId+"</div><div style=\\"color:#E2E8F0;font-size:16px;line-height:1.5;\\">應收總計：$"+totalAmt+" ("+payPref+")<br>請至櫃檯確認結帳，我們將立即為您製作餐點！</div></div>";'
    + '    cart=[]; updateFooter();'
    + '  }'
    + '  function onErr(e){ alert("傳送失敗，請洽櫃檯人員："+e); btn.disabled=false; btn.innerText="🚀 確認送出 ($"+totalAmt+")"; }'
    + '  if(typeof google!=="undefined" && google.script && google.script.run){'
    + '    google.script.run.withSuccessHandler(onSuccess).withFailureHandler(onErr).submitQrOrderFromHtml(payloadObj);'
    + '  } else {'
    + '    fetch(' + JSON.stringify(ScriptApp.getService().getUrl()) + ', {'
    + '      method:"POST", headers:{"Content-Type":"text/plain;charset=utf-8"}, body:JSON.stringify(payloadObj)'
    + '    }).then(function(r){return r.json();}).then(onSuccess).catch(onErr);'
    + '  }'
    + '}'
    + 'renderCats(); renderMenu();'
    + '</script></body></html>';

  return HtmlService.createHtmlOutput(html)
    .setTitle("伊凡的花園 - 線上掃碼點餐 (" + safeTable + ")")
    .addMetaTag("viewport", "width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no")
    .setXFrameOptionsMode(HtmlService.XFrameOptionsMode.ALLOWALL);
}

function getOrCreateSheet(ss, name, headers) {
  var sheet = ss.getSheetByName(name);
  if (!sheet) {
    sheet = ss.insertSheet(name);
    sheet.appendRow(headers);
  }
  return sheet;
}

function jsonResponse(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
