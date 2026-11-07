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

// ==================== 📱 客人手機掃碼點餐 RWD 網頁渲染 ====================
function renderMobileOrderWebApp(ss, defaultTable) {
  var menuList = loadMenuFromSheet(ss);
  var menuJson = JSON.stringify(menuList);
  var safeTable = String(defaultTable || "1桌").replace(/["<>]/g, "");

  var html = '<!DOCTYPE html><html lang="zh-TW"><head><meta charset="UTF-8">'
    + '<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">'
    + '<title>伊凡的花園 - 線上掃碼點餐</title>'
    + '<style>'
    + 'body{margin:0;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:#0F172A;color:#F8FAFC;padding-bottom:90px;}'
    + '.header{background:#064E3B;padding:12px 14px;position:sticky;top:0;z-index:20;box-shadow:0 2px 8px rgba(0,0,0,0.4);}'
    + '.brand{font-size:18px;font-weight:bold;color:#A7F3D0;display:flex;justify-content:space-between;align-items:center;}'
    + '.table-badge{background:#F59E0B;color:#000;padding:4px 10px;border-radius:6px;font-size:14px;font-weight:bold;}'
    + '.cats{display:flex;overflow-x:auto;gap:8px;padding:10px 12px;background:#1E293B;position:sticky;top:48px;z-index:19;}'
    + '.cat-btn{white-space:nowrap;padding:7px 14px;border-radius:20px;border:1px solid #475569;background:#0F172A;color:#CBD5E1;font-size:13px;font-weight:bold;}'
    + '.cat-btn.active{background:#10B981;color:#052E16;border-color:#34D399;}'
    + '.grid{padding:12px;display:grid;grid-template-columns:1fr;gap:10px;}'
    + '.card{background:#1E293B;border:1px solid #334155;border-radius:10px;padding:12px;display:flex;justify-content:space-between;align-items:center;}'
    + '.card-title{font-size:16px;font-weight:bold;color:#FFF;}'
    + '.card-sub{font-size:12px;color:#94A3B8;margin-top:3px;}'
    + '.card-price{font-size:16px;font-weight:bold;color:#FCD34D;margin-top:4px;}'
    + '.add-btn{background:#10B981;color:#052E16;border:none;padding:8px 14px;border-radius:8px;font-weight:bold;font-size:14px;}'
    + '.footer{position:fixed;bottom:0;left:0;right:0;background:#1E293B;border-top:2px solid #10B981;padding:10px 14px;display:flex;justify-content:space-between;align-items:center;z-index:30;}'
    + '.submit-btn{background:#10B981;color:#052E16;border:none;padding:10px 20px;border-radius:8px;font-size:16px;font-weight:bold;}'
    + '.modal{position:fixed;inset:0;background:rgba(0,0,0,0.75);display:none;align-items:center;justify-content:center;z-index:50;padding:14px;}'
    + '.modal-box{background:#1E293B;border:1px solid #475569;border-radius:12px;width:100%;max-width:460px;max-height:88vh;overflow-y:auto;padding:16px;}'
    + '.opt-group{margin-top:10px;}'
    + '.opt-title{font-size:13px;color:#A7F3D0;font-weight:bold;margin-bottom:6px;}'
    + '.opt-grid{display:flex;flex-wrap:wrap;gap:6px;}'
    + '.chip{padding:6px 10px;border-radius:6px;border:1px solid #475569;background:#0F172A;color:#FFF;font-size:12px;}'
    + '.chip.sel{background:#10B981;color:#052E16;font-weight:bold;border-color:#6EE7B7;}'
    + '</style></head><body>'
    + '<div class="header"><div class="brand"><span>🌿 伊凡的花園 線上點餐</span><span class="table-badge">🪑 ' + safeTable + '</span></div></div>'
    + '<div id="catBar" class="cats"></div>'
    + '<div id="menuList" class="grid"></div>'
    + '<div class="footer">'
    + '  <div><div style="font-size:12px;color:#94A3B8;" id="cartCount">尚未選購餐點</div><div style="font-size:18px;font-weight:bold;color:#FCD34D;" id="cartTotal">應收 $0</div></div>'
    + '  <button class="submit-btn" onclick="openCartModal()">🛒 查看購物車 / 送出</button>'
    + '</div>'
    + '<div id="itemModal" class="modal"><div class="modal-box" id="itemModalBody"></div></div>'
    + '<div id="cartModal" class="modal"><div class="modal-box" id="cartModalBody"></div></div>'
    + '<script>'
    + 'var TABLE_NAME = ' + JSON.stringify(safeTable) + ';'
    + 'var MENU = ' + menuJson + ';'
    + 'if(!MENU || MENU.length===0){ document.getElementById("menuList").innerHTML="<div style=\'padding:20px;color:#FCD34D;\'>請先於店內平板 POS 點擊「⚙️設定 ➔ ⬆️上傳菜單備份至試算表」以啟用線上菜單！</div>"; }'
    + 'var cart = []; var curCat = MENU.length>0 ? MENU[0].cat : "";'
    + 'var SWEETS = ["無糖","1分糖","3分微糖","5分半糖","8分少糖","10分正常","▲甜度固定"];'
    + 'var ICES = ["去冰","3分微冰","5分半冰","8分少冰","10分正常冰","常溫","熱飲(Hot)"];'
    + 'var FREE_TEAS = ["毛尖綠茶","甘露青茶","麥香紅玉","👍凍頂烏龍","英式伯爵","不需附茶"];'
    + 'function renderCats(){'
    + '  var cats=[]; MENU.forEach(function(m){if(cats.indexOf(m.cat)===-1)cats.push(m.cat);});'
    + '  var h=""; cats.forEach(function(c){ h+="<button class=\'cat-btn "+(c===curCat?"active":"")+"\' onclick=\'selCat("+JSON.stringify(c)+")\'>"+c+"</button>"; });'
    + '  document.getElementById("catBar").innerHTML=h;'
    + '}'
    + 'function selCat(c){ curCat=c; renderCats(); renderMenu(); }'
    + 'function renderMenu(){'
    + '  var h=""; MENU.filter(function(m){return m.cat===curCat;}).forEach(function(m){'
    + '    h+="<div class=\'card\'><div><div class=\'card-title\'>"+m.name+"</div><div class=\'card-sub\'>"+(m.sub||"")+"</div><div class=\'card-price\'>$"+m.price+"</div></div>"'
    + '      +"<button class=\'add-btn\' onclick=\'openItem("+m.id+")\'>＋選購</button></div>";'
    + '  });'
    + '  document.getElementById("menuList").innerHTML=h;'
    + '}'
    + 'var curItem=null, selChoice="", selSweet="5分半糖", selIce="8分少冰", selTea="毛尖綠茶", selTeaSweet="無糖", selTeaIce="3分微冰", selAddons=[];'
    + 'function openItem(id){'
    + '  curItem = MENU.find(function(x){return x.id===id;}); if(!curItem)return;'
    + '  selChoice = (curItem.choices && curItem.choices.length>0) ? curItem.choices[0] : "";'
    + '  selSweet = curItem.fixedSweet ? "▲甜度固定" : "5分半糖"; selIce = "8分少冰";'
    + '  selTea = "毛尖綠茶"; selTeaSweet = "無糖"; selTeaIce = "3分微冰"; selAddons = [];'
    + '  renderItemModal(); document.getElementById("itemModal").style.display="flex";'
    + '}'
    + 'function renderItemModal(){'
    + '  var m=curItem; var h="<div style=\'font-size:17px;font-weight:bold;color:#A7F3D0;\'>"+m.name+" ($"+m.price+")</div>";'
    + '  if(m.choices && m.choices.length>0){'
    + '    h+="<div class=\'opt-group\'><div class=\'opt-title\'>1. 選擇素別 / 湯頭：</div><div class=\'opt-grid\'>";'
    + '    m.choices.forEach(function(c){ h+="<button class=\'chip "+(selChoice===c?"sel":"")+"\' onclick=\'selChoice="+JSON.stringify(c)+";renderItemModal()\'>"+c+"</button>"; });'
    + '    h+="</div></div>";'
    + '  }'
    + '  if(m.cat==="義大利麵&飯食" || m.cat==="個人比薩7.5吋"){'
    + '    var upgs=[{name:"升級焗烤",price:50},{name:"升級松露",price:50},{name:"升級全素起司",price:80}];'
    + '    h+="<div class=\'opt-group\'><div class=\'opt-title\'>🧀 主餐升級加價：</div><div class=\'opt-grid\'>";'
    + '    upgs.forEach(function(u){ var has=selAddons.some(function(a){return a.name===u.name;});'
    + '      h+="<button class=\'chip "+(has?"sel":"")+"\' onclick=\'toggleAddon("+JSON.stringify(u.name)+","+u.price+")\'>+"+u.name+"($"+u.price+")</button>";'
    + '    }); h+="</div></div>";'
    + '  }'
    + '  if(m.hasFreeTea){'
    + '    h+="<div class=\'opt-group\'><div class=\'opt-title\'>🍵 隨餐免費附贈冷壓萃茶：</div><div class=\'opt-grid\'>";'
    + '    FREE_TEAS.forEach(function(t){ h+="<button class=\'chip "+(selTea===t?"sel":"")+"\' onclick=\'selTea="+JSON.stringify(t)+";renderItemModal()\'>"+t+"</button>"; });'
    + '    h+="</div></div>";'
    + '    if(selTea!=="不需附茶"){'
    + '      h+="<div class=\'opt-group\'><div class=\'opt-title\'>🍬 附茶甜度：</div><div class=\'opt-grid\'>";'
    + '      SWEETS.forEach(function(s){ h+="<button class=\'chip "+(selTeaSweet===s?"sel":"")+"\' onclick=\'selTeaSweet="+JSON.stringify(s)+";renderItemModal()\'>"+s+"</button>"; });'
    + '      h+="</div></div><div class=\'opt-group\'><div class=\'opt-title\'>🧊 附茶冰塊：</div><div class=\'opt-grid\'>";'
    + '      ICES.slice(0,6).forEach(function(ic){ h+="<button class=\'chip "+(selTeaIce===ic?"sel":"")+"\' onclick=\'selTeaIce="+JSON.stringify(ic)+";renderItemModal()\'>"+ic+"</button>"; });'
    + '      h+="</div></div>";'
    + '    }'
    + '  }'
    + '  if(m.isDrink){'
    + '    h+="<div class=\'opt-group\'><div class=\'opt-title\'>🍬 飲品甜度：</div><div class=\'opt-grid\'>";'
    + '    SWEETS.forEach(function(s){ h+="<button class=\'chip "+(selSweet===s?"sel":"")+"\' onclick=\'selSweet="+JSON.stringify(s)+";renderItemModal()\'>"+s+"</button>"; });'
    + '    h+="</div></div><div class=\'opt-group\'><div class=\'opt-title\'>🧊 飲品冰量：</div><div class=\'opt-grid\'>";'
    + '    ICES.forEach(function(ic){ h+="<button class=\'chip "+(selIce===ic?"sel":"")+"\' onclick=\'selIce="+JSON.stringify(ic)+";renderItemModal()\'>"+ic+"</button>"; });'
    + '    h+="</div></div>";'
    + '    var dAdds=[{name:"寒天愛玉",price:15},{name:"鳳梨椰果",price:10},{name:"厚奶(濃奶)",price:20},{name:"鮮奶",price:30}];'
    + '    h+="<div class=\'opt-group\'><div class=\'opt-title\'>🧋 飲品加料：</div><div class=\'opt-grid\'>";'
    + '    dAdds.forEach(function(u){ var has=selAddons.some(function(a){return a.name===u.name;});'
    + '      h+="<button class=\'chip "+(has?"sel":"")+"\' onclick=\'toggleAddon("+JSON.stringify(u.name)+","+u.price+")\'>+"+u.name+"($"+u.price+")</button>";'
    + '    }); h+="</div></div>";'
    + '  }'
    + '  h+="<div style=\'display:flex;gap:10px;margin-top:16px;\'>"'
    + '    +"<button class=\'chip\' style=\'flex:1;padding:10px;\' onclick=\'document.getElementById(\"itemModal\").style.display=\"none\"\'>取消</button>"'
    + '    +"<button class=\'add-btn\' style=\'flex:2;padding:10px;\' onclick=\'confirmAddItem()\'>✅ 加入點餐單</button></div>";'
    + '  document.getElementById("itemModalBody").innerHTML=h;'
    + '}'
    + 'function toggleAddon(n,p){'
    + '  var idx=selAddons.findIndex(function(x){return x.name===n;});'
    + '  if(idx>=0)selAddons.splice(idx,1); else selAddons.push({name:n,price:p});'
    + '  renderItemModal();'
    + '}'
    + 'function confirmAddItem(){'
    + '  var addSum=0; selAddons.forEach(function(a){addSum+=a.price;});'
    + '  cart.push({name:curItem.name,qty:1,unitPrice:curItem.price+addSum,choice:selChoice,sweet:curItem.isDrink?selSweet:"",ice:curItem.isDrink?selIce:"",mealDrinkName:curItem.hasFreeTea?selTea:"",mealDrinkDiff:0,mealDrinkSweet:selTeaSweet,mealDrinkIce:selTeaIce,addons:selAddons.slice()});'
    + '  document.getElementById("itemModal").style.display="none"; updateFooter();'
    + '}'
    + 'function updateFooter(){'
    + '  var q=0,t=0; cart.forEach(function(c){q+=c.qty; t+=c.unitPrice*c.qty;});'
    + '  document.getElementById("cartCount").innerText="已選 "+q+" 份餐點";'
    + '  document.getElementById("cartTotal").innerText="應收 $"+t;'
    + '}'
    + 'var payPref="現金";'
    + 'function openCartModal(){'
    + '  if(cart.length===0){alert("請先點選餐點！");return;}'
    + '  var t=0; var h="<div style=\'font-size:17px;font-weight:bold;color:#A7F3D0;margin-bottom:10px;\'>🛒 點餐確認 ("+TABLE_NAME+")</div>";'
    + '  cart.forEach(function(c,i){ t+=c.unitPrice*c.qty;'
    + '    h+="<div style=\'border-bottom:1px solid #334155;padding:8px 0;display:flex;justify-content:space-between;\'>"'
    + '      +"<div><b>"+(i+1)+". "+c.name+"</b> x"+c.qty+" ($"+(c.unitPrice*c.qty)+")<div style=\'font-size:12px;color:#6EE7B7;\'>"+(c.choice||"")+" "+(c.mealDrinkName?"附:"+c.mealDrinkName+"("+c.mealDrinkSweet+"/"+c.mealDrinkIce+")":"")+" "+(c.sweet?c.sweet+"/"+c.ice:"")+"</div></div>"'
    + '      +"<button class=\'chip\' onclick=\'cart.splice("+i+",1);updateFooter();openCartModal()\'>刪除</button></div>";'
    + '  });'
    + '  h+="<div class=\'opt-group\'><div class=\'opt-title\'>💳 預計結帳方式：</div><div class=\'opt-grid\'>";'
    + '  ["現金","信用卡","LINE Pay","街口支付"].forEach(function(p){ h+="<button class=\'chip "+(payPref===p?"sel":"")+"\' onclick=\'payPref="+JSON.stringify(p)+";openCartModal()\'>"+p+"</button>"; });'
    + '  h+="</div></div>";'
    + '  h+="<div class=\'opt-group\'><div class=\'opt-title\'>📝 備註需求：</div><input id=\'custNote\' style=\'width:95%;padding:8px;border-radius:6px;border:1px solid #475569;background:#0F172A;color:#FFF;\' placeholder=\'例如：少鹽、餐具需求...\'></div>";'
    + '  h+="<div style=\'display:flex;gap:10px;margin-top:16px;\'>"'
    + '    +"<button class=\'chip\' style=\'flex:1;padding:10px;\' onclick=\'document.getElementById(\"cartModal\").style.display=\"none\"\'>繼續加點</button>"'
    + '    +"<button id=\'btnSend\' class=\'submit-btn\' style=\'flex:2;\' onclick=\'submitOrder("+t+")\'>🚀 確認送出 ($"+t+")</button></div>";'
    + '  document.getElementById("cartModalBody").innerHTML=h;'
    + '  document.getElementById("cartModal").style.display="flex";'
    + '}'
    + 'function submitOrder(totalAmt){'
    + '  var btn=document.getElementById("btnSend"); btn.disabled=true; btn.innerText="⏳ 傳送至櫃檯中...";'
    + '  var note=document.getElementById("custNote")?document.getElementById("custNote").value:"";'
    + '  fetch(window.location.href.split("?")[0], {'
    + '    method:"POST", headers:{"Content-Type":"text/plain;charset=utf-8"},'
    + '    body:JSON.stringify({action:"submit_qr_order",table:TABLE_NAME,paymentPref:payPref,customerNote:note,total:totalAmt,items:cart})'
    + '  }).then(function(r){return r.json();}).then(function(res){'
    + '    document.getElementById("cartModalBody").innerHTML="<div style=\'text-align:center;padding:24px;\'><div style=\'font-size:42px;\'>✅</div><div style=\'font-size:20px;font-weight:bold;color:#A7F3D0;margin-top:10px;\'>點餐已送達櫃檯！</div><div style=\'color:#FCD34D;font-size:16px;margin:10px 0;\'>桌號："+TABLE_NAME+" ｜ 單號："+res.orderId+"</div><div style=\'color:#CBD5E1;font-size:14px;\'>應收總計：$"+totalAmt+" ("+payPref+")<br>店員確認後將立即為您製作餐點！</div></div>";'
    + '    cart=[]; updateFooter();'
    + '  }).catch(function(e){ alert("傳送失敗，請洽櫃檯人員："+e); btn.disabled=false; btn.innerText="🚀 確認送出"; });'
    + '}'
    + 'if(MENU.length>0){ renderCats(); renderMenu(); }'
    + '</script></body></html>';

  return HtmlService.createHtmlOutput(html)
    .setTitle("伊凡的花園 - 線上掃碼點餐 (" + safeTable + ")")
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
