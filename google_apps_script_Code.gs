/**
 * 伊凡的花園 Evonne's Garden - 平板 POS 雲端資料庫 Google Apps Script (升級版 v2：含月份/前兩週/自訂區間自動統計看板)
 *
 * 【升級重點】：
 * 1. 自動建立「📊 營收與餐點統計看板」工作表：
 *    - 內建【近 7 天】、【前兩週 (近 14 天)】、【本月累計】、【上個月】自動統計
 *    - 只要在 B2、D2 輸入任意起始與結束日期（例如 2026-10-01 ~ 2026-10-31），下方會自動算出該區間的總營收、各付款別金額，以及【餐點銷售數量排行榜】！
 * 2. 「每日關帳總表」自動拆出「日期(YYYY-MM-DD)」、「月份(YYYY-MM)」與「現金 / LINE Pay / 街口 / UberEats / Foodpanda」獨立金額欄位。
 * 3. 新增「🍱 每日品項銷售明細表」：自動將每次關帳的餐點銷量展開為獨立資料列，支援試算表自動加總與篩選。
 */

function doGet(e) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  initAllSheets(ss);
  return ContentService.createTextOutput(
    JSON.stringify({
      status: "ok",
      store: "伊凡的花園 Evonne's Garden",
      message: "POS 雲端試算表連線正常，已備妥「📊 營收與餐點統計看板」等 5 個工作表！"
    })
  ).setMimeType(ContentService.MimeType.JSON);
}

function doPost(e) {
  try {
    var payload = JSON.parse(e.postData.contents);
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    initAllSheets(ss);
    var action = payload.action || "upload_closing";

    // 1. 每日關帳總表 + 每日品項銷售明細表 + 訂單明細表上傳
    if (action === "upload_closing") {
      var closeTimeStr = String(payload.closeTime || "");
      var dateOnly = closeTimeStr.length >= 10 ? closeTimeStr.substring(0, 10) : closeTimeStr;
      var monthOnly = closeTimeStr.length >= 7 ? closeTimeStr.substring(0, 7) : closeTimeStr;

      var byPay = payload.byPayment || {};
      var payCash = Number(byPay["現金"] || payload.cashSales || 0);
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
        payLine,
        payJko,
        payUber,
        payPanda,
        Number(payload.openingFloat || 0),
        Number(payload.expectedDrawerCash || 0)
      ]);

      // 將當日每個品項的銷售數量寫入「🍱 每日品項銷售明細表」以利區間/月份自動統計
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

    // 2. 平板菜單上傳備份至試算表
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

    // 3. 從試算表下載菜單至平板
    if (action === "download_menu") {
      var sm = ss.getSheetByName("菜單設定表");
      if (!sm || sm.getLastRow() <= 1) return jsonResponse({ status: "empty", menu: [] });
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
      return jsonResponse({ status: "ok", menu: outMenu });
    }
    return jsonResponse({ status: "error", message: "Unknown action" });
  } catch (err) {
    return jsonResponse({ status: "error", message: String(err) });
  }
}

function initAllSheets(ss) {
  getOrCreateSheet(ss, "每日關帳總表", [
    "日期(YYYY-MM-DD)", "月份(YYYY-MM)", "關帳時間", "有效單數", "作廢單數",
    "今日淨營業額", "現金實收", "LINE Pay", "街口支付", "UberEats", "Foodpanda",
    "期初零用金", "錢櫃應有總現金"
  ]);
  getOrCreateSheet(ss, "每日品項銷售明細表", [
    "日期(YYYY-MM-DD)", "月份(YYYY-MM)", "關帳批次時間", "品項名稱", "銷售數量"
  ]);
  getOrCreateSheet(ss, "訂單明細表", [
    "日期(YYYY-MM-DD)", "月份(YYYY-MM)", "關帳批次時間", "取餐號", "點餐時間",
    "用餐類型", "狀態", "付款方式", "整單折扣", "應收總計", "餐點與客製明細"
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

  // 一、常用區間快速總覽
  dash.getRange("A4:I4").setValues([[
    "統計區間", "有效單數", "作廢單數", "淨營業額總計",
    "現金總計", "LINE Pay", "街口支付", "UberEats", "Foodpanda"
  ]]).setFontWeight("bold").setBackground("#D1FAE5");

  // 1. 自訂日期區間 (讀取 B2 ~ D2)
  dash.getRange("A5").setValue("🔍 自訂區間 (B2~D2)");
  dash.getRange("B5").setFormula('=SUMIFS(\'每日關帳總表\'!D:D, \'每日關帳總表\'!A:A, ">="&B2, \'每日關帳總表\'!A:A, "<="&D2)');
  dash.getRange("C5").setFormula('=SUMIFS(\'每日關帳總表\'!E:E, \'每日關帳總表\'!A:A, ">="&B2, \'每日關帳總表\'!A:A, "<="&D2)');
  dash.getRange("D5").setFormula('=SUMIFS(\'每日關帳總表\'!F:F, \'每日關帳總表\'!A:A, ">="&B2, \'每日關帳總表\'!A:A, "<="&D2)');
  dash.getRange("E5").setFormula('=SUMIFS(\'每日關帳總表\'!G:G, \'每日關帳總表\'!A:A, ">="&B2, \'每日關帳總表\'!A:A, "<="&D2)');
  dash.getRange("F5").setFormula('=SUMIFS(\'每日關帳總表\'!H:H, \'每日關帳總表\'!A:A, ">="&B2, \'每日關帳總表\'!A:A, "<="&D2)');
  dash.getRange("G5").setFormula('=SUMIFS(\'每日關帳總表\'!I:I, \'每日關帳總表\'!A:A, ">="&B2, \'每日關帳總表\'!A:A, "<="&D2)');
  dash.getRange("H5").setFormula('=SUMIFS(\'每日關帳總表\'!J:J, \'每日關帳總表\'!A:A, ">="&B2, \'每日關帳總表\'!A:A, "<="&D2)');
  dash.getRange("I5").setFormula('=SUMIFS(\'每日關帳總表\'!K:K, \'每日關帳總表\'!A:A, ">="&B2, \'每日關帳總表\'!A:A, "<="&D2)');

  // 2. 前兩週 (近 14 天)
  dash.getRange("A6").setValue("📅 前兩週 (近 14 天)");
  dash.getRange("B6").setFormula('=SUMIFS(\'每日關帳總表\'!D:D, \'每日關帳總表\'!A:A, ">="&TEXT(TODAY()-14,"yyyy-mm-dd"))');
  dash.getRange("C6").setFormula('=SUMIFS(\'每日關帳總表\'!E:E, \'每日關帳總表\'!A:A, ">="&TEXT(TODAY()-14,"yyyy-mm-dd"))');
  dash.getRange("D6").setFormula('=SUMIFS(\'每日關帳總表\'!F:F, \'每日關帳總表\'!A:A, ">="&TEXT(TODAY()-14,"yyyy-mm-dd"))');
  dash.getRange("E6").setFormula('=SUMIFS(\'每日關帳總表\'!G:G, \'每日關帳總表\'!A:A, ">="&TEXT(TODAY()-14,"yyyy-mm-dd"))');
  dash.getRange("F6").setFormula('=SUMIFS(\'每日關帳總表\'!H:H, \'每日關帳總表\'!A:A, ">="&TEXT(TODAY()-14,"yyyy-mm-dd"))');
  dash.getRange("G6").setFormula('=SUMIFS(\'每日關帳總表\'!I:I, \'每日關帳總表\'!A:A, ">="&TEXT(TODAY()-14,"yyyy-mm-dd"))');
  dash.getRange("H6").setFormula('=SUMIFS(\'每日關帳總表\'!J:J, \'每日關帳總表\'!A:A, ">="&TEXT(TODAY()-14,"yyyy-mm-dd"))');
  dash.getRange("I6").setFormula('=SUMIFS(\'每日關帳總表\'!K:K, \'每日關帳總表\'!A:A, ">="&TEXT(TODAY()-14,"yyyy-mm-dd"))');

  // 3. 本月累計
  dash.getRange("A7").setValue("📅 本月累計");
  dash.getRange("B7").setFormula('=SUMIFS(\'每日關帳總表\'!D:D, \'每日關帳總表\'!B:B, TEXT(TODAY(),"yyyy-mm"))');
  dash.getRange("C7").setFormula('=SUMIFS(\'每日關帳總表\'!E:E, \'每日關帳總表\'!B:B, TEXT(TODAY(),"yyyy-mm"))');
  dash.getRange("D7").setFormula('=SUMIFS(\'每日關帳總表\'!F:F, \'每日關帳總表\'!B:B, TEXT(TODAY(),"yyyy-mm"))');
  dash.getRange("E7").setFormula('=SUMIFS(\'每日關帳總表\'!G:G, \'每日關帳總表\'!B:B, TEXT(TODAY(),"yyyy-mm"))');
  dash.getRange("F7").setFormula('=SUMIFS(\'每日關帳總表\'!H:H, \'每日關帳總表\'!B:B, TEXT(TODAY(),"yyyy-mm"))');
  dash.getRange("G7").setFormula('=SUMIFS(\'每日關帳總表\'!I:I, \'每日關帳總表\'!B:B, TEXT(TODAY(),"yyyy-mm"))');
  dash.getRange("H7").setFormula('=SUMIFS(\'每日關帳總表\'!J:J, \'每日關帳總表\'!B:B, TEXT(TODAY(),"yyyy-mm"))');
  dash.getRange("I7").setFormula('=SUMIFS(\'每日關帳總表\'!K:K, \'每日關帳總表\'!B:B, TEXT(TODAY(),"yyyy-mm"))');

  // 4. 上個月累計
  dash.getRange("A8").setValue("📅 上個月累計");
  dash.getRange("B8").setFormula('=SUMIFS(\'每日關帳總表\'!D:D, \'每日關帳總表\'!B:B, TEXT(EDATE(TODAY(),-1),"yyyy-mm"))');
  dash.getRange("C8").setFormula('=SUMIFS(\'每日關帳總表\'!E:E, \'每日關帳總表\'!B:B, TEXT(EDATE(TODAY(),-1),"yyyy-mm"))');
  dash.getRange("D8").setFormula('=SUMIFS(\'每日關帳總表\'!F:F, \'每日關帳總表\'!B:B, TEXT(EDATE(TODAY(),-1),"yyyy-mm"))');
  dash.getRange("E8").setFormula('=SUMIFS(\'每日關帳總表\'!G:G, \'每日關帳總表\'!B:B, TEXT(EDATE(TODAY(),-1),"yyyy-mm"))');
  dash.getRange("F8").setFormula('=SUMIFS(\'每日關帳總表\'!H:H, \'每日關帳總表\'!B:B, TEXT(EDATE(TODAY(),-1),"yyyy-mm"))');
  dash.getRange("G8").setFormula('=SUMIFS(\'每日關帳總表\'!I:I, \'每日關帳總表\'!B:B, TEXT(EDATE(TODAY(),-1),"yyyy-mm"))');
  dash.getRange("H8").setFormula('=SUMIFS(\'每日關帳總表\'!J:J, \'每日關帳總表\'!B:B, TEXT(EDATE(TODAY(),-1),"yyyy-mm"))');
  dash.getRange("I8").setFormula('=SUMIFS(\'每日關帳總表\'!K:K, \'每日關帳總表\'!B:B, TEXT(EDATE(TODAY(),-1),"yyyy-mm"))');

  // 二、各月份營收自動彙總表 (左下方 A10:E) 與 自訂區間餐點銷售排行 (右下方 G10:H)
  dash.getRange("A10").setValue("📆 各月份營收自動彙總").setFontWeight("bold");
  dash.getRange("A11").setFormula(
    '=IFERROR(QUERY(\'每日關帳總表\'!A2:K, "SELECT B, SUM(D), SUM(E), SUM(F), SUM(G) WHERE B IS NOT NULL GROUP BY B ORDER BY B DESC LABEL B \'月份\', SUM(D) \'有效單數\', SUM(E) \'作廢單數\', SUM(F) \'月淨營收\', SUM(G) \'現金實收\'", 0), "尚無關帳資料")'
  );

  dash.getRange("G10").setValue("🏆 自訂區間 (B2~D2) 餐點銷售數量排行榜").setFontWeight("bold");
  dash.getRange("G11").setFormula(
    '=IFERROR(QUERY(\'每日品項銷售明細表\'!A2:E, "SELECT D, SUM(E) WHERE A >= \'"&B2&"\' AND A <= \'"&D2&"\' GROUP BY D ORDER BY SUM(E) DESC LABEL D \'品項名稱\', SUM(E) \'銷售總數\'", 0), "此區間尚無銷售資料")'
  );
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
