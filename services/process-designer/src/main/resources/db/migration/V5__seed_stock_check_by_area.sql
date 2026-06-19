-- ---------------------------------------------------------------------------
-- Seed an ACTIVE reference process that exercises the new Master Data Areas
-- pieces end to end: VERIFY an area against master data (kind 'area'), pull the
-- next open stock-check line in that area (stockcheck.next, with the running
-- instanceId auto-injected so the claim is tagged), count each line in a loop,
-- and RELEASE the instance's reserved work (work.release) when the area runs
-- dry, so abandoning the flow frees its reservations.
--
-- Kept as its own process key ('stock-check-by-area') so it never clashes with
-- the simpler seeded flows or any draft a user has created. Idempotent:
-- re-running (or running on a DB that already has it) is a no-op.
-- ---------------------------------------------------------------------------
INSERT INTO process_definition (process_key, version, status, title, icon, json, published_at, published_by)
VALUES (
    'stock-check-by-area', 1, 'ACTIVE', 'Stock Check by Area (reference)', 'inventory',
    $json$
    {
      "processKey": "stock-check-by-area",
      "version": 1,
      "status": "ACTIVE",
      "title": "Stock Check by Area (reference)",
      "icon": "inventory",
      "dataSchema": [
        { "name": "areaScan",     "type": "string" },
        { "name": "areaId",       "type": "string" },
        { "name": "areaCode",     "type": "string" },
        { "name": "found",        "type": "boolean" },
        { "name": "countTaskId",  "type": "string" },
        { "name": "lineId",       "type": "string" },
        { "name": "locationId",   "type": "location" },
        { "name": "locationCode", "type": "string" },
        { "name": "skuId",        "type": "sku" },
        { "name": "uom",          "type": "string" },
        { "name": "expectedQty",  "type": "number" },
        { "name": "qty",          "type": "number" },
        { "name": "eventId",      "type": "string" }
      ],
      "start": "pickArea",
      "steps": {
        "pickArea": {
          "type": "screen", "screen": "textInput",
          "config": {
            "header": "Scan area",
            "detail": "Scan or type the area to stock-check.",
            "scanBinding": true,
            "writeTo": "areaScan",
            "validation": { "required": true },
            "verify": {
              "kind": "area",
              "write": { "areaId": "areaId", "areaCode": "areaCode" },
              "onNotFound": { "mode": "reprompt" }
            }
          },
          "next": "getNext"
        },
        "getNext": {
          "type": "task", "task": "stockcheck.next",
          "input": { "areaId": "areaId" },
          "output": {
            "found": "found",
            "countTaskId": "countTaskId",
            "lineId": "lineId",
            "locationId": "locationId",
            "skuId": "skuId",
            "expectedQty": "expectedQty",
            "uom": "uomCode"
          },
          "next": "decideFound"
        },
        "decideFound": {
          "type": "decision",
          "transitions": [ { "when": "found == true", "to": "scanLocation" } ],
          "next": "releaseWork"
        },
        "scanLocation": {
          "type": "screen", "screen": "textInput",
          "config": {
            "header": "Scan location",
            "detail": "Scan the bin location {{locationId}} to count.",
            "scanBinding": true,
            "writeTo": "areaScan",
            "validation": { "required": true },
            "verify": {
              "kind": "location",
              "write": { "id": "locationId", "code": "locationCode" },
              "onNotFound": { "mode": "reprompt" }
            }
          },
          "next": "scanSku"
        },
        "scanSku": {
          "type": "screen", "screen": "textInput",
          "config": {
            "header": "Scan SKU at {{locationCode}}",
            "detail": "Scan the SKU code or its barcode.",
            "scanBinding": true,
            "writeTo": "areaScan",
            "validation": { "required": true },
            "verify": {
              "kind": "skuScan",
              "write": { "id": "skuId", "uomCode": "uom" },
              "onNotFound": { "mode": "reprompt" }
            }
          },
          "next": "enterQty"
        },
        "enterQty": {
          "type": "screen", "screen": "numberInput",
          "config": {
            "header": "Count SKU",
            "detail": "Enter the counted quantity (expected {{expectedQty}} {{uom}}).",
            "writeTo": "qty",
            "validation": { "required": true, "min": 0, "integerOnly": true }
          },
          "next": "postCount"
        },
        "postCount": {
          "type": "task", "task": "txlog.post",
          "input": {
            "streamId": "locationId",
            "eventType": "literal:StockCounted",
            "qty": "qty",
            "sku": "skuId",
            "location": "locationId"
          },
          "output": { "eventId": "txEventId" },
          "next": "getNext"
        },
        "releaseWork": {
          "type": "task", "task": "work.release",
          "input": {},
          "output": {},
          "next": "exit"
        },
        "exit": {
          "type": "screen", "screen": "acknowledge",
          "config": {
            "header": "No more work in this area",
            "detail": "All stock-check lines in {{areaCode}} are done. Your reservations were released.",
            "confirmLabel": "Done"
          }
        }
      }
    }
    $json$::jsonb,
    now(), 'system'
)
ON CONFLICT (process_key, version) DO NOTHING;
