// Paste this into Extensions > Apps Script on your Google Sheet.
// The Sheet's first row should be: WhatsApp Number | Referral Number | Timestamp
//
// Deploy as a Web App:
//   Deploy > New deployment > type: Web app
//   Execute as: Me
//   Who has access: Anyone
// Copy the resulting /exec URL into SheetSync.kt (ENDPOINT_URL).

function doPost(e) {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getActiveSheet();

  // The Android app sends a raw JSON body (Content-Type: application/json),
  // so the data arrives in e.postData.contents, NOT e.parameter (that's only
  // populated for form-encoded or multipart bodies).
  var data = {};
  if (e && e.postData && e.postData.contents) {
    try {
      data = JSON.parse(e.postData.contents);
    } catch (err) {
      data = {};
    }
  }
  // Fall back to e.parameter too, in case something posts form-encoded instead.
  var whatsapp = data.whatsapp || (e.parameter && e.parameter.whatsapp) || '';
  var referral = data.referral || (e.parameter && e.parameter.referral) || '';
  var timestamp = data.timestamp || (e.parameter && e.parameter.timestamp) || new Date().toISOString();

  // Skip if this WhatsApp number is already in the sheet, so accidental
  // double-taps or retried requests don't create duplicate rows.
  var lastRow = sheet.getLastRow();
  if (lastRow > 1 && whatsapp) {
    var existing = sheet.getRange(2, 1, lastRow - 1, 1).getValues();
    for (var i = 0; i < existing.length; i++) {
      if (String(existing[i][0]) === String(whatsapp)) {
        return ContentService
          .createTextOutput(JSON.stringify({ status: 'ok', duplicate: true }))
          .setMimeType(ContentService.MimeType.JSON);
      }
    }
  }

  sheet.appendRow([whatsapp, referral, timestamp]);

  return ContentService
    .createTextOutput(JSON.stringify({ status: 'ok' }))
    .setMimeType(ContentService.MimeType.JSON);
}

// GET /exec?limit=10            -> preview: first N data rows (omit limit for all rows)
// GET /exec?action=history      -> history: total count + count of contacts added per day
function doGet(e) {
  var action = (e && e.parameter && e.parameter.action) || 'preview';
  if (action === 'history') return getHistory();
  return getPreview(e);
}

function getPreview(e) {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getActiveSheet();
  var lastRow = sheet.getLastRow();
  var hasLimitParam = e.parameter && e.parameter.limit;
  // No limit param -> return everything. Otherwise use the given limit.
  var limit = hasLimitParam ? parseInt(e.parameter.limit, 10) : (lastRow - 1);

  var rowCount = Math.max(0, Math.min(limit, lastRow - 1)); // -1 to skip header

  var rows = [];
  if (rowCount > 0) {
    var values = sheet.getRange(2, 1, rowCount, 3).getValues(); // start after header
    for (var i = 0; i < values.length; i++) {
      rows.push({
        whatsapp: String(values[i][0] || ''),
        referral: String(values[i][1] || ''),
        timestamp: String(values[i][2] || '')
      });
    }
  }

  return ContentService
    .createTextOutput(JSON.stringify(rows))
    .setMimeType(ContentService.MimeType.JSON);
}

// Groups every saved row by the date portion of its timestamp
// ("yyyy-MM-dd HH:mm:ss" -> "yyyy-MM-dd") and counts how many were
// added on each day, plus the all-time total. The app decides which
// dates are "Today" / "Yesterday" using the device's own clock.
function getHistory() {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getActiveSheet();
  var lastRow = sheet.getLastRow();
  var total = Math.max(0, lastRow - 1); // -1 to skip header

  var counts = {};
  if (total > 0) {
    var timestamps = sheet.getRange(2, 3, total, 1).getValues(); // column C
    for (var i = 0; i < timestamps.length; i++) {
      var ts = String(timestamps[i][0] || '');
      var datePart = ts.split(' ')[0]; // "yyyy-MM-dd HH:mm:ss" -> "yyyy-MM-dd"
      if (!datePart) continue;
      counts[datePart] = (counts[datePart] || 0) + 1;
    }
  }

  var days = Object.keys(counts)
    .sort()
    .reverse() // most recent day first
    .map(function (d) {
      return { date: d, count: counts[d] };
    });

  return ContentService
    .createTextOutput(JSON.stringify({ total: total, days: days }))
    .setMimeType(ContentService.MimeType.JSON);
}
