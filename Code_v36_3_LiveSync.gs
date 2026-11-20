/**
 * ============================================================================
 * RE:MK Coaching App & Pixel Watch 4 – Live-Sync & Lizenz-Backend (Code.gs)
 * Version: v36.3.1 Master (100 % passgenau für RE_MK_Athleten_optimiert A–O + v36.3 Schema A–I)
 * ============================================================================
 * Dynamische Spaltenerkennung per Kopfzeilen-Namen:
 *   1) Dein bestehendes Live-Sheet "RE_MK_Athleten_optimiert" (Reiter "Lizenzen" A–O):
 *      A=Name | B=Start-Key | C=Status | D=Rolle / Paket | E=Gerät 1 | F=Gerät 2 | G=Gerät 3
 *      H=Nickname | I=Gültig bis | J=Eigenes Passwort | K=E-Mail / Letzter Login
 *      L=Cloud_Sync_Data | M=Last_Sync | N=Cloud_Backup_JSON | O=Coach_Live_Status
 *   2) Dein bestehendes Live-Sheet "Free_Sync" (4 Spalten A–D):
 *      A=Free_ID | B=Erstellt_am | C=Cloud_Sync_Data | D=Last_Sync
 *   3) Neues v36.3 9-Spalten-Schema (A–I):
 *      A=Key | B=Name | C=Status | D=Rolle | E=Datum | F=Notizen | G=Geräte-Limit | H=Nickname | I=Cloud_Sync_JSON
 *
 * WICHTIGE FIXES GEGENÜBER v36.3.0:
 *   - Schreibt Cloud_Sync_Data exakt in Spalte L (Index 12) statt in Spalte O (Coach_Live_Status)!
 *   - Trennt Live-Sync (Spalte L) sauber von Cloud_Backup_JSON (Spalte N) und aktualisiert
 *     automatisch Last_Sync (Spalte M) & Coach_Live_Status (Spalte O).
 *   - Verhindert Kollisionen bei geteiltem Start-Key "1" (Jochen, Justin, Balthasar) durch
 *     exakten Abgleich von Eigenes Passwort (J) + Nickname (H) + Name (A).
 *   - Unterstützt das 4-Spalten-Layout von "Free_Sync" verlustfrei & ohne doppelte Zeilen (LockService).
 */

var SHEET_PRO = "Lizenzen";
var SHEET_FREE = "Free_Sync";
var CACHE_TTL_SEC = 21600; // 6 Stunden Cache für schnelle 4,5s Live-Polls

function doGet(e) {
  var params = (e && e.parameter) ? e.parameter : {};
  return handleRequest(params, {});
}

function doPost(e) {
  var params = (e && e.parameter) ? e.parameter : {};
  var body = {};
  try {
    if (e && e.postData && e.postData.contents) {
      body = JSON.parse(e.postData.contents);
    }
  } catch (parseErr) {
    return jsonResponse({ valid: false, status: "error", message: "Ungültiges JSON im POST-Body: " + parseErr.toString() });
  }
  return handleRequest(params, body);
}

function handleRequest(params, body) {
  try {
    var action = String(body.action || params.action || "verify").trim();
    var rawKey = String(body.key || params.key || "").trim();
    var deviceId = String(body.deviceId || params.deviceId || "").trim();
    var inputName = String(body.name || body.user || body.nickname || params.name || params.user || params.nickname || "").trim();
    var since = Number(body.since || params.since || 0) || 0;
    var callback = params.callback ? String(params.callback).trim() : "";

    if (!rawKey) {
      return jsonResponse({ valid: false, status: "error", message: "Kein Lizenz-Key angegeben." }, callback);
    }

    var result;
    switch (action) {
      case "verify":
      case "auth":
        result = handleVerifyAuth(rawKey, deviceId, inputName);
        break;

      case "loadSync":
      case "syncPull":
        result = handleLoadSync(rawKey, deviceId, inputName, since);
        break;

      case "saveSync":
      case "syncPush":
        var incoming = body.payload || body.syncData || body.data || {};
        if (typeof incoming === "string") {
          try { incoming = JSON.parse(incoming); } catch (e) { incoming = {}; }
        }
        result = handleSaveSync(rawKey, deviceId, inputName, incoming);
        break;

      case "loadBackup":
        result = handleLoadBackup(rawKey, inputName);
        break;

      case "saveBackup":
        var backupData = body.backupData || body.payload || body.data || {};
        if (typeof backupData === "string") {
          try { backupData = JSON.parse(backupData); } catch (e) { backupData = {}; }
        }
        result = handleSaveBackup(rawKey, inputName, backupData);
        break;

      case "resetDevice":
      case "resetDevices":
        result = handleResetDevice(rawKey, inputName);
        break;

      case "setFirstNickname":
        result = handleSetNickname(rawKey, inputName, String(body.nickname || params.nickname || inputName).trim());
        break;

      case "changePassword":
        result = handleChangePassword(rawKey, inputName, String(body.newPassword || params.newPassword || "").trim());
        break;

      default:
        result = handleVerifyAuth(rawKey, deviceId, inputName);
        break;
    }

    return jsonResponse(result, callback);
  } catch (err) {
    return jsonResponse({ valid: false, status: "error", message: "Serverfehler: " + err.toString() });
  }
}

// ============================================================================
// 1. DYNAMISCHE HEADER-ERKENNUNG (15-Spalten Live-Sheet & 9-Spalten v36.3 & 4-Spalten Free_Sync)
// ============================================================================
function normalizeHeader(str) {
  return String(str || "").trim().toLowerCase().replace(/\s+/g, " ");
}

function getColumnMap(headerRow) {
  var map = {
    isFree4Col: false,
    isLive15Col: false,
    nameCol: -1,
    keyCol: -1,
    statusCol: -1,
    roleCol: -1,
    dev1Col: -1,
    dev2Col: -1,
    dev3Col: -1,
    notesCol: -1,
    limitCol: -1,
    nickCol: -1,
    validUntilCol: -1,
    customPwCol: -1,
    emailLoginCol: -1,
    syncCol: -1,
    lastSyncCol: -1,
    backupCol: -1,
    liveStatusCol: -1
  };

  for (var i = 0; i < headerRow.length; i++) {
    var h = normalizeHeader(headerRow[i]);
    var col1 = i + 1;
    if (h === "free_id") { map.isFree4Col = true; map.keyCol = col1; }
    else if (h === "name") map.nameCol = col1;
    else if (h === "start-key" || h === "key" || h === "lizenz-key") map.keyCol = col1;
    else if (h === "status") map.statusCol = col1;
    else if (h.indexOf("rolle") === 0) map.roleCol = col1;
    else if (h === "gerät 1" || h === "geraet 1") map.dev1Col = col1;
    else if (h === "gerät 2" || h === "geraet 2") map.dev2Col = col1;
    else if (h === "gerät 3" || h === "geraet 3") map.dev3Col = col1;
    else if (h === "notizen") map.notesCol = col1;
    else if (h.indexOf("geräte-limit") === 0 || h.indexOf("geraete-limit") === 0) map.limitCol = col1;
    else if (h === "nickname") map.nickCol = col1;
    else if (h.indexOf("gültig bis") === 0 || h.indexOf("gueltig bis") === 0) map.validUntilCol = col1;
    else if (h.indexOf("eigenes passwort") === 0) map.customPwCol = col1;
    else if (h.indexOf("e-mail") === 0 || h.indexOf("letzter login") !== -1) map.emailLoginCol = col1;
    else if (h === "cloud_sync_data" || h === "cloud_sync_json") map.syncCol = col1;
    else if (h === "last_sync") map.lastSyncCol = col1;
    else if (h === "cloud_backup_json") map.backupCol = col1;
    else if (h === "coach_live_status") map.liveStatusCol = col1;
  }

  // Live 15-Spalten-Sheet erkennen (RE_MK_Athleten_optimiert: A=Name, B=Start-Key, L=Cloud_Sync_Data)
  if (map.nameCol === 1 && map.keyCol === 2) {
    map.isLive15Col = true;
    if (map.syncCol === -1) map.syncCol = 12;      // Spalte L: Cloud_Sync_Data
    if (map.lastSyncCol === -1) map.lastSyncCol = 13; // Spalte M: Last_Sync
    if (map.backupCol === -1) map.backupCol = 14;  // Spalte N: Cloud_Backup_JSON
    if (map.liveStatusCol === -1) map.liveStatusCol = 15; // Spalte O: Coach_Live_Status
  } else if (map.isFree4Col) {
    if (map.syncCol === -1) map.syncCol = 3;       // Spalte C: Cloud_Sync_Data
    if (map.lastSyncCol === -1) map.lastSyncCol = 4; // Spalte D: Last_Sync
  } else {
    // 9-Spalten v36.3 Standard-Schema (A–I)
    if (map.keyCol === -1) map.keyCol = 1;
    if (map.nameCol === -1) map.nameCol = 2;
    if (map.statusCol === -1) map.statusCol = 3;
    if (map.roleCol === -1) map.roleCol = 4;
    if (map.notesCol === -1) map.notesCol = 6;
    if (map.limitCol === -1) map.limitCol = 7;
    if (map.nickCol === -1) map.nickCol = 8;
    if (map.syncCol === -1) map.syncCol = 9;
  }
  return map;
}

function getOrCreateProSheet() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName(SHEET_PRO);
  if (!sheet) {
    sheet = ss.insertSheet(SHEET_PRO);
    sheet.getRange(1, 1, 1, 9).setValues([[
      "Key", "Name", "Status", "Rolle", "Datum",
      "Notizen", "Geräte-Limit", "Nickname", "Cloud_Sync_JSON"
    ]]);
    sheet.setFrozenRows(1);
  }
  return sheet;
}

function getOrCreateFreeSheet() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName(SHEET_FREE);
  if (!sheet) {
    sheet = ss.insertSheet(SHEET_FREE);
    sheet.getRange(1, 1, 1, 4).setValues([["Free_ID", "Erstellt_am", "Cloud_Sync_Data", "Last_Sync"]]);
    sheet.setFrozenRows(1);
  }
  return sheet;
}

function isMasterAdminKey(cleanKey) {
  return (cleanKey === "26011990" || cleanKey === "REMK-VIP-JOCHEN");
}

function isMasterAdminAlias(cleanKey, cleanName) {
  if (isMasterAdminKey(cleanKey)) return true;
  if (cleanKey === "1" && (!cleanName || cleanName === "ADMIN" || cleanName.indexOf("JOCHEN") !== -1)) return true;
  return false;
}

function getCacheBucketKey(rec, cleanKey) {
  if (rec && rec.tier === "pro" && rec.rowIndex > 1) {
    return "REMK_V3631_PRO_R" + rec.rowIndex;
  }
  if (rec && rec.tier === "free") {
    return "REMK_V3631_FREE_" + String(rec.key || cleanKey).replace(/[^A-Z0-9_-]/g, "_");
  }
  return "REMK_V3631_KEY_" + cleanKey.replace(/[^A-Z0-9_-]/g, "_");
}

function findLicenseRecord(rawKey, inputName, createFreeIfMissing) {
  var cleanKey = String(rawKey || "").trim().toUpperCase();
  var cleanName = String(inputName || "").trim().toUpperCase();

  // 1. FREE-* Keys direkt im Free_Sync Blatt suchen (verhindert unnötige Suche in Lizenzen)
  if (cleanKey.indexOf("FREE-") === 0) {
    return findOrCreateFreeRecord(cleanKey, createFreeIfMissing);
  }

  // 2. Suche im Hauptblatt "Lizenzen"
  var proSheet = getOrCreateProSheet();
  var proRec = searchProSheet(proSheet, cleanKey, cleanName);
  if (proRec) return proRec;

  // 3. Fallback: Suche im Free_Sync Blatt
  var freeRec = findOrCreateFreeRecord(cleanKey, false);
  if (freeRec) return freeRec;

  return null;
}

function searchProSheet(sheet, cleanKey, cleanName) {
  var lastRow = sheet.getLastRow();
  if (lastRow < 2) return null;
  var lastCol = Math.max(sheet.getLastColumn(), 9);
  var data = sheet.getRange(1, 1, lastRow, lastCol).getValues();
  var map = getColumnMap(data[0]);

  var fallbackMatch = null;
  for (var i = 1; i < data.length; i++) {
    var row = data[i];
    var rName = map.nameCol > 0 ? String(row[map.nameCol - 1] || "").trim() : "";
    var rKey = map.keyCol > 0 ? String(row[map.keyCol - 1] || "").trim().toUpperCase() : "";
    var rNick = map.nickCol > 0 ? String(row[map.nickCol - 1] || "").trim() : "";
    var rCustomPw = map.customPwCol > 0 ? String(row[map.customPwCol - 1] || "").trim().toUpperCase() : "";
    var rRole = map.roleCol > 0 ? String(row[map.roleCol - 1] || "User").trim() : "User";

    var nameUp = rName.toUpperCase();
    var nickUp = rNick.toUpperCase();
    var keyMatches = (rCustomPw && cleanKey === rCustomPw) || (rKey && cleanKey === rKey);
    if (!keyMatches && isMasterAdminAlias(cleanKey, cleanName) && rRole.toUpperCase() === "ADMIN") {
      keyMatches = true;
    }
    if (!keyMatches) continue;

    // Exakter Match über Eigenes Passwort (eindeutig!) oder passenden Nickname/Name
    var exactPwMatch = (rCustomPw && cleanKey === rCustomPw);
    var nameMatches = cleanName && (cleanName === nickUp || cleanName === nameUp || nameUp.indexOf(cleanName) !== -1);

    var rec = buildRecordFromRow(sheet, i + 1, row, map, "pro");
    if (exactPwMatch || nameMatches) {
      return rec;
    }
    // Wenn mehrere Zeilen Start-Key "1" teilen und Admin-Alias gefragt wird -> Admin-Zeile bevorzugen
    if (!fallbackMatch || rRole.toUpperCase() === "ADMIN") {
      fallbackMatch = rec;
    }
  }
  return fallbackMatch;
}

function buildRecordFromRow(sheet, rowIndex, row, map, tier) {
  var devices = [];
  if (map.isLive15Col && map.dev1Col > 0) {
    var d1 = String(row[map.dev1Col - 1] || "").trim();
    var d2 = map.dev2Col > 0 ? String(row[map.dev2Col - 1] || "").trim() : "";
    var d3 = map.dev3Col > 0 ? String(row[map.dev3Col - 1] || "").trim() : "";
    if (d1) devices.push(d1);
    if (d2) devices.push(d2);
    if (d3) devices.push(d3);
  } else if (map.notesCol > 0) {
    var rawN = String(row[map.notesCol - 1] || "").trim().replace(/^devices\s*:\s*/i, "");
    if (rawN) {
      devices = rawN.split(",").map(function (s) { return s.trim(); }).filter(function (s) { return s.length > 0; });
    }
  }

  var devLimit = 3;
  if (map.isLive15Col) {
    devLimit = 3;
  } else if (map.limitCol > 0) {
    var parsedLim = parseInt(row[map.limitCol - 1], 10);
    if (!isNaN(parsedLim) && parsedLim > 0) devLimit = parsedLim;
  }

  var rName = map.nameCol > 0 ? String(row[map.nameCol - 1] || "Athlet").trim() : "Athlet";
  var rNick = map.nickCol > 0 ? String(row[map.nickCol - 1] || "").trim() : "";
  var rKey = map.keyCol > 0 ? String(row[map.keyCol - 1] || "").trim().toUpperCase() : "";
  var rCustomPw = map.customPwCol > 0 ? String(row[map.customPwCol - 1] || "").trim().toUpperCase() : "";

  return {
    sheet: sheet,
    rowIndex: rowIndex,
    tier: tier,
    map: map,
    key: rCustomPw || rKey,
    startKey: rKey,
    name: rName,
    nickname: rNick || rName,
    status: map.statusCol > 0 ? String(row[map.statusCol - 1] || "Aktiv").trim() : "Aktiv",
    role: map.roleCol > 0 ? String(row[map.roleCol - 1] || "User").trim() : "User",
    devices: devices,
    deviceLimit: devLimit,
    emailLoginRaw: map.emailLoginCol > 0 ? String(row[map.emailLoginCol - 1] || "").trim() : "",
    syncJsonRaw: (map.syncCol > 0 && row.length >= map.syncCol) ? String(row[map.syncCol - 1] || "").trim() : "",
    backupJsonRaw: (map.backupCol > 0 && row.length >= map.backupCol) ? String(row[map.backupCol - 1] || "").trim() : ""
  };
}

function findOrCreateFreeRecord(cleanKey, createIfMissing) {
  var sheet = getOrCreateFreeSheet();
  var lastRow = sheet.getLastRow();
  var lastCol = Math.max(sheet.getLastColumn(), 4);
  if (lastRow >= 2) {
    var data = sheet.getRange(1, 1, lastRow, lastCol).getValues();
    var map = getColumnMap(data[0]);
    // Von unten nach oben suchen, damit bei historischen Doppelzeilen immer der neueste Stand gewinnt
    for (var i = data.length - 1; i >= 1; i--) {
      var fid = String(data[i][map.keyCol - 1] || "").trim().toUpperCase();
      if (fid === cleanKey) {
        return buildRecordFromRow(sheet, i + 1, data[i], map, "free");
      }
    }
  }

  if (!createIfMissing) return null;

  var todayStr = Utilities.formatDate(new Date(), Session.getScriptTimeZone() || "Europe/Berlin", "yyyy-MM-dd");
  var newRowIdx = sheet.getLastRow() + 1;
  sheet.getRange(newRowIdx, 1, 1, 4).setValues([[cleanKey, todayStr, "{}", new Date().toISOString()]]);
  SpreadsheetApp.flush();
  var freshData = sheet.getRange(1, 1, newRowIdx, 4).getValues();
  return buildRecordFromRow(sheet, newRowIdx, freshData[newRowIdx - 1], getColumnMap(freshData[0]), "free");
}

// ============================================================================
// 2. ACTION: verify / auth (GERÄTE-SLOTS E/F/G ODER F/G + ADMIN-FREIFAHRTSCHEIN)
// ============================================================================
function isStatusAllowed(statusStr) {
  var st = String(statusStr || "").trim().toLowerCase();
  return (st === "aktiv" || st === "active" || st === "pro" || st === "free" || st === "");
}

function isUnlimitedRole(roleStr, cleanKey, cleanName) {
  var r = String(roleStr || "").trim().toLowerCase();
  return (r === "admin" || r === "trainer" || r === "coach" || isMasterAdminAlias(cleanKey, cleanName));
}

function handleVerifyAuth(rawKey, deviceId, inputName) {
  var cleanKey = String(rawKey || "").trim().toUpperCase();
  var cleanName = String(inputName || "").trim().toUpperCase();
  var lock = LockService.getScriptLock();
  lock.tryLock(10000);
  try {
    var rec = findLicenseRecord(cleanKey, cleanName, true);
    if (!rec) {
      return { valid: false, status: "invalid_key", message: "Ungültiger Lizenz-Key oder unbekannter Nickname." };
    }
    if (!isStatusAllowed(rec.status)) {
      return { valid: false, status: "blocked", message: "Zugang gesperrt (Status: " + rec.status + ")." };
    }

    var unlimited = isUnlimitedRole(rec.role, cleanKey, cleanName);
    var devices = rec.devices.slice();

    if (deviceId) {
      var alreadyIn = devices.indexOf(deviceId) !== -1;
      if (!alreadyIn) {
        if (unlimited || devices.length < rec.deviceLimit) {
          if (rec.map.isLive15Col && rec.map.dev1Col > 0) {
            if (devices.length < 3) {
              devices.push(deviceId);
            } else {
              // Admin-Rotation: ältestes Gerät in Slot 1-3 ersetzen
              devices = [devices[1], devices[2], deviceId];
            }
            rec.sheet.getRange(rec.rowIndex, rec.map.dev1Col, 1, 3).setValues([[
              devices[0] || "", devices[1] || "", devices[2] || ""
            ]]);
          } else if (rec.map.notesCol > 0) {
            devices.push(deviceId);
            if (devices.length > 20) devices = devices.slice(-20);
            rec.sheet.getRange(rec.rowIndex, rec.map.notesCol).setValue(devices.join(", "));
          }
        } else {
          return {
            valid: false,
            status: "device_limit_reached",
            deviceCount: devices.length,
            deviceLimit: rec.deviceLimit,
            message: "Geräte-Limit (" + rec.deviceLimit + ") erreicht. Nutze den Geräte-Reset in der App."
          };
        }
      }
    }

    // Letzten Login sauber in Spalte K protokollieren (ohne endlose Wiederholung von "| Login:")
    if (rec.map.emailLoginCol > 0) {
      var todayStr = Utilities.formatDate(new Date(), Session.getScriptTimeZone() || "Europe/Berlin", "yyyy-MM-dd");
      var baseEmail = rec.emailLoginRaw.split("|")[0].trim();
      var newLoginCell = (baseEmail && baseEmail.indexOf("Login:") !== 0)
        ? (baseEmail + " | Login: " + todayStr)
        : ("Login: " + todayStr);
      rec.sheet.getRange(rec.rowIndex, rec.map.emailLoginCol).setValue(newLoginCell);
    }
    SpreadsheetApp.flush();

    var syncObj = readSyncObjectForRecord(rec, cleanKey);
    return {
      valid: true,
      status: "ok",
      key: rec.key,
      user: rec.name,
      name: rec.name,
      nickname: rec.nickname,
      role: rec.role,
      tier: rec.tier,
      deviceLimit: unlimited ? 99 : rec.deviceLimit,
      registeredDevices: devices.length,
      cloudSync: syncObj,
      syncData: syncObj
    };
  } finally {
    try { lock.releaseLock(); } catch (e) {}
  }
}

// ============================================================================
// 3. ACTION: loadSync / syncPull (SPALTE L: Cloud_Sync_Data / SPALTE I + since-CHECK)
// ============================================================================
function createDefaultCloudSyncV363(nickname) {
  return {
    schemaVersion: "36.3",
    updatedAt: 0,
    lastDevice: "server",
    profile: {
      nickname: nickname || "Athlet",
      uiMode: "pro",
      theme: "dark",
      partnerEnabled: false
    },
    activeWorkout: null,
    workoutLogs: [],
    nutrition: {
      date: Utilities.formatDate(new Date(), Session.getScriptTimeZone() || "Europe/Berlin", "yyyy-MM-dd"),
      targetKcal: 2450,
      consumedKcal: 0,
      waterLiters: 0,
      meals: []
    },
    shoppingList: [],
    customLibrary: { recipes: [], exercises: [] },
    eventsQueue: [],
    ackEventIds: []
  };
}

function readSyncObjectForRecord(rec, cleanKey) {
  var cacheKey = getCacheBucketKey(rec, cleanKey);
  var cache = CacheService.getScriptCache();
  var cachedRaw = cache.get(cacheKey);
  if (cachedRaw && cachedRaw.charAt(0) === "{") {
    try { return JSON.parse(cachedRaw); } catch (e) {}
  }

  var raw = rec ? rec.syncJsonRaw : "";
  if (raw && raw.charAt(0) === "{") {
    try {
      var parsed = JSON.parse(raw);
      cache.put(cacheKey, raw, CACHE_TTL_SEC);
      return parsed;
    } catch (e) {}
  }

  return createDefaultCloudSyncV363(rec ? rec.nickname : "Athlet");
}

function handleLoadSync(rawKey, deviceId, inputName, since) {
  var cleanKey = String(rawKey || "").trim().toUpperCase();
  var cleanName = String(inputName || "").trim().toUpperCase();
  var rec = findLicenseRecord(cleanKey, cleanName, true);
  if (!rec) {
    return { valid: false, status: "invalid_key", message: "Lizenz-Key nicht gefunden." };
  }
  if (!isStatusAllowed(rec.status)) {
    return { valid: false, status: "blocked", message: "Zugang gesperrt." };
  }

  var syncObj = readSyncObjectForRecord(rec, cleanKey);
  var currentTs = Number(syncObj.updatedAt || syncObj.ts || 0) || 0;
  if (since > 0 && currentTs > 0 && currentTs <= since) {
    return { valid: true, status: "unchanged", updatedAt: currentTs };
  }

  return {
    valid: true,
    status: "ok",
    updatedAt: currentTs,
    nickname: rec.nickname,
    payload: syncObj,
    cloudSync: syncObj,
    syncData: syncObj
  };
}

// ============================================================================
// 4. ACTION: saveSync / syncPush (LOCKSERVICE + MERGE + SPALTE L/M/O SCHREIBEN)
// ============================================================================
function handleSaveSync(rawKey, deviceId, inputName, incoming) {
  var cleanKey = String(rawKey || "").trim().toUpperCase();
  var cleanName = String(inputName || "").trim().toUpperCase();
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) {
    return { valid: false, status: "busy", message: "Sync-Lock belegt, bitte erneut versuchen." };
  }

  try {
    var rec = findLicenseRecord(cleanKey, cleanName, true);
    if (!rec) {
      return { valid: false, status: "invalid_key", message: "Lizenz-Key nicht gefunden." };
    }
    if (!isStatusAllowed(rec.status)) {
      return { valid: false, status: "blocked", message: "Zugang gesperrt." };
    }

    var existingObj = readSyncObjectForRecord(rec, cleanKey);
    var merged = mergeCloudSyncV363(existingObj, incoming || {}, deviceId, rec.nickname);

    var serialized = JSON.stringify(merged);
    if (serialized.length > 48000) {
      if (Array.isArray(merged.eventsQueue) && merged.eventsQueue.length > 30) {
        merged.eventsQueue = merged.eventsQueue.slice(-30);
      }
      if (Array.isArray(merged.workoutLogs) && merged.workoutLogs.length > 50) {
        merged.workoutLogs = merged.workoutLogs.slice(-50);
      }
      serialized = JSON.stringify(merged);
    }

    // 1. Dauerhaft in Cloud_Sync_Data (Spalte L im Live-Sheet / Spalte C in Free_Sync / Spalte I in 9-Col)
    if (rec.map.syncCol > 0 && serialized.length <= 49500) {
      rec.sheet.getRange(rec.rowIndex, rec.map.syncCol).setValue(serialized);
    }
    // 2. Last_Sync ISO-Zeitstempel (Spalte M im Live-Sheet / Spalte D in Free_Sync)
    if (rec.map.lastSyncCol > 0) {
      rec.sheet.getRange(rec.rowIndex, rec.map.lastSyncCol).setValue(new Date().toISOString());
    }
    // 3. Coach_Live_Status (Spalte O im Live-Sheet — bereinigt gegen 9833-kcal-Familientopf!)
    if (rec.map.liveStatusCol > 0) {
      var tz = Session.getScriptTimeZone() || "Europe/Berlin";
      var timeLabel = Utilities.formatDate(new Date(), tz, "dd.MM. HH:mm");
      var setsToday = Number(merged.doneSetsToday || 0) || 0;
      var safeOpenKcal = Number(merged.openKcal || 0);
      if (safeOpenKcal > 4800 && merged.nutrition && merged.nutrition.targetKcal) {
        safeOpenKcal = Math.max(0, Number(merged.nutrition.targetKcal) - Number(merged.nutrition.consumedKcal || 0));
      }
      var waterStr = Number(merged.drankLiters || 0).toFixed(2).replace(".", ",");
      var liveSummary = "🟢 Aktiv (" + timeLabel + ") | ✓ " + setsToday + " Sätze heute | 🍎 " + Math.round(safeOpenKcal) + " kcal offen | 💧 " + waterStr + " L";
      rec.sheet.getRange(rec.rowIndex, rec.map.liveStatusCol).setValue(liveSummary);
    }
    SpreadsheetApp.flush();

    // 4. Cache aktualisieren
    var cacheKey = getCacheBucketKey(rec, cleanKey);
    try { CacheService.getScriptCache().put(cacheKey, serialized, CACHE_TTL_SEC); } catch (ce) {}

    return {
      valid: true,
      status: "synced",
      updatedAt: merged.updatedAt,
      payload: merged,
      cloudSync: merged,
      syncData: merged
    };
  } finally {
    try { lock.releaseLock(); } catch (e) {}
  }
}

function mergeCloudSyncV363(existingObj, incomingObj, deviceId, fallbackNick) {
  var merged = createDefaultCloudSyncV363(fallbackNick);
  var k;
  for (k in existingObj) {
    if (existingObj.hasOwnProperty(k)) merged[k] = existingObj[k];
  }
  for (k in incomingObj) {
    if (incomingObj.hasOwnProperty(k) && incomingObj[k] !== undefined && incomingObj[k] !== null) {
      merged[k] = incomingObj[k];
    }
  }

  var nowMs = Date.now();
  var prevTs = Number(existingObj.updatedAt || existingObj.ts || 0) || 0;
  var incTs = Number(incomingObj.updatedAt || incomingObj.ts || 0) || 0;
  merged.schemaVersion = "36.3";
  merged.updatedAt = Math.max(nowMs, prevTs + 1, incTs);
  merged.ts = merged.updatedAt;
  merged.lastDevice = String(incomingObj.lastDevice || deviceId || existingObj.lastDevice || "unknown");

  // Profil erhalten
  merged.profile = merged.profile || {};
  if (existingObj.profile) {
    for (var pk in existingObj.profile) {
      if (merged.profile[pk] === undefined) merged.profile[pk] = existingObj.profile[pk];
    }
  }
  if (!merged.profile.nickname) merged.profile.nickname = fallbackNick || "Athlet";

  // watchPlanCompactJson der Haupt-App für die Uhr bewahren
  if (incomingObj.watchPlanCompactJson) {
    merged.watchPlanCompactJson = incomingObj.watchPlanCompactJson;
  } else if (existingObj.watchPlanCompactJson) {
    merged.watchPlanCompactJson = existingObj.watchPlanCompactJson;
  }

  // Ernährung & Wasser (höchster Wasserstand am selben Tag gewinnt verlustfrei)
  var oldNut = existingObj.nutrition || {};
  var newNut = incomingObj.nutrition || {};
  var mergedNut = {};
  for (var nk in oldNut) mergedNut[nk] = oldNut[nk];
  for (var nk2 in newNut) {
    if (newNut[nk2] !== undefined && newNut[nk2] !== null) mergedNut[nk2] = newNut[nk2];
  }
  var todayStr = Utilities.formatDate(new Date(), Session.getScriptTimeZone() || "Europe/Berlin", "yyyy-MM-dd");
  var sameDay = (!oldNut.date || !newNut.date || oldNut.date === newNut.date);
  var oldWater = parseFloat(existingObj.drankLiters || oldNut.waterLiters || 0) || 0;
  var newWater = parseFloat(incomingObj.drankLiters || newNut.waterLiters || 0) || 0;
  var bestWater = sameDay ? Math.max(oldWater, newWater) : newWater;
  merged.drankLiters = Math.round(bestWater * 100) / 100;
  merged.hydrationMl = Math.round(merged.drankLiters * 1000);
  mergedNut.waterLiters = merged.drankLiters;
  mergedNut.date = newNut.date || oldNut.date || todayStr;

  var tKcal = Number(mergedNut.targetKcal || 2450) || 2450;
  if (tKcal < 1100 || tKcal > 4800) tKcal = 2450;
  mergedNut.targetKcal = tKcal;
  merged.nutrition = mergedNut;

  // Sätze & Satz-Matrix mergen
  var oldSets = parseInt(existingObj.doneSetsToday || 0, 10) || 0;
  var newSets = parseInt(incomingObj.doneSetsToday || 0, 10) || 0;
  merged.doneSetsToday = sameDay ? Math.max(oldSets, newSets) : newSets;

  try {
    var oldMap = (typeof existingObj.completedSetsMap === "string")
      ? JSON.parse(existingObj.completedSetsMap || "{}")
      : (existingObj.completedSetsMap || {});
    var newMap = (typeof incomingObj.completedSetsMap === "string")
      ? JSON.parse(incomingObj.completedSetsMap || "{}")
      : (incomingObj.completedSetsMap || {});
    for (var mk in newMap) {
      oldMap[mk] = Math.max(parseInt(oldMap[mk] || 0, 10) || 0, parseInt(newMap[mk] || 0, 10) || 0);
    }
    merged.completedSetsMap = JSON.stringify(oldMap);
  } catch (e) {}

  if (!incomingObj.activeWorkout && existingObj.activeWorkout) merged.activeWorkout = existingObj.activeWorkout;
  if (!incomingObj.shoppingList && existingObj.shoppingList) merged.shoppingList = existingObj.shoppingList;
  if (!incomingObj.customLibrary && existingObj.customLibrary) merged.customLibrary = existingObj.customLibrary;

  // Rolling Event-Queue & ackEventIds zusammenführen
  var ackList = Array.isArray(incomingObj.ackEventIds)
    ? incomingObj.ackEventIds
    : (Array.isArray(existingObj.ackEventIds) ? existingObj.ackEventIds : []);
  var ackMap = {};
  for (var a = 0; a < ackList.length; a++) ackMap[String(ackList[a])] = true;
  merged.ackEventIds = ackList.slice(-80);

  var combined = [];
  var seen = {};
  var both = (Array.isArray(existingObj.eventsQueue) ? existingObj.eventsQueue : [])
    .concat(Array.isArray(incomingObj.eventsQueue) ? incomingObj.eventsQueue : []);
  for (var i = 0; i < both.length; i++) {
    var ev = both[i];
    if (!ev || !ev.id) continue;
    var eid = String(ev.id);
    if (ackMap[eid] || seen[eid]) continue;
    seen[eid] = true;
    combined.push(ev);
  }
  merged.eventsQueue = combined.slice(-80);
  return merged;
}

// ============================================================================
// 5. SEPARATE BACKUP-SPALTE (Spalte N: Cloud_Backup_JSON) & SELF-SERVICE ACTIONS
// ============================================================================
function handleLoadBackup(rawKey, inputName) {
  var rec = findLicenseRecord(rawKey, inputName, false);
  if (!rec) return { valid: false, status: "invalid_key", message: "Lizenz-Key nicht gefunden." };
  var obj = {};
  if (rec.backupJsonRaw && rec.backupJsonRaw.charAt(0) === "{") {
    try { obj = JSON.parse(rec.backupJsonRaw); } catch (e) {}
  }
  return { valid: true, status: "ok", backupData: obj };
}

function handleSaveBackup(rawKey, inputName, backupData) {
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) return { valid: false, status: "busy", message: "Lock belegt." };
  try {
    var rec = findLicenseRecord(rawKey, inputName, false);
    if (!rec) return { valid: false, status: "invalid_key", message: "Lizenz-Key nicht gefunden." };
    var targetCol = rec.map.backupCol > 0 ? rec.map.backupCol : rec.map.syncCol;
    var str = JSON.stringify(backupData || {});
    if (targetCol > 0 && str.length <= 49500) {
      rec.sheet.getRange(rec.rowIndex, targetCol).setValue(str);
      SpreadsheetApp.flush();
    }
    return { valid: true, status: "backup_saved" };
  } finally {
    try { lock.releaseLock(); } catch (e) {}
  }
}

function handleResetDevice(rawKey, inputName) {
  var lock = LockService.getScriptLock();
  lock.tryLock(10000);
  try {
    var rec = findLicenseRecord(rawKey, inputName, false);
    if (!rec) return { valid: false, status: "invalid_key", message: "Lizenz-Key nicht gefunden." };
    if (rec.map.isLive15Col && rec.map.dev1Col > 0) {
      rec.sheet.getRange(rec.rowIndex, rec.map.dev1Col, 1, 3).setValues([["", "", ""]]);
    } else if (rec.map.notesCol > 0) {
      rec.sheet.getRange(rec.rowIndex, rec.map.notesCol).setValue("");
    }
    SpreadsheetApp.flush();
    return { valid: true, status: "reset_ok", message: "Alle registrierten Geräte wurden zurückgesetzt." };
  } finally {
    try { lock.releaseLock(); } catch (e) {}
  }
}

function handleSetNickname(rawKey, inputName, nickname) {
  if (!nickname) return { valid: false, status: "error", message: "Kein Nickname angegeben." };
  var lock = LockService.getScriptLock();
  lock.tryLock(10000);
  try {
    var rec = findLicenseRecord(rawKey, inputName, false);
    if (!rec) return { valid: false, status: "invalid_key", message: "Lizenz-Key nicht gefunden." };
    if (rec.map.nickCol > 0) {
      rec.sheet.getRange(rec.rowIndex, rec.map.nickCol).setValue(nickname.slice(0, 40));
      SpreadsheetApp.flush();
    }
    return { valid: true, status: "ok", nickname: nickname.slice(0, 40) };
  } finally {
    try { lock.releaseLock(); } catch (e) {}
  }
}

function handleChangePassword(rawKey, inputName, newPassword) {
  if (!newPassword || newPassword.length < 4) {
    return { valid: false, status: "error", message: "Passwort muss mindestens 4 Zeichen lang sein." };
  }
  var lock = LockService.getScriptLock();
  lock.tryLock(10000);
  try {
    var rec = findLicenseRecord(rawKey, inputName, false);
    if (!rec) return { valid: false, status: "invalid_key", message: "Lizenz-Key nicht gefunden." };
    var pwCol = rec.map.customPwCol > 0 ? rec.map.customPwCol : rec.map.keyCol;
    rec.sheet.getRange(rec.rowIndex, pwCol).setValue(newPassword);
    SpreadsheetApp.flush();
    return { valid: true, status: "password_updated", message: "Passwort erfolgreich aktualisiert." };
  } finally {
    try { lock.releaseLock(); } catch (e) {}
  }
}

function jsonResponse(obj, callback) {
  var jsonStr = JSON.stringify(obj);
  if (callback) {
    return ContentService.createTextOutput(callback + "(" + jsonStr + ")")
      .setMimeType(ContentService.MimeType.JAVASCRIPT);
  }
  return ContentService.createTextOutput(jsonStr)
    .setMimeType(ContentService.MimeType.JSON);
}
