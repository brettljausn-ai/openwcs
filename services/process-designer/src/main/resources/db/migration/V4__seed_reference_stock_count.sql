-- ---------------------------------------------------------------------------
-- Seed a richer ACTIVE reference process that exercises the designer end to end:
-- scan + VERIFY a location and a SKU against master data, LOOK UP the expected
-- on-hand, capture a count, COMPUTE a match flag (with a recount tolerance), and
-- a DECISION step that either posts the count or loops back for a recount.
--
-- Kept as its own process key ('stock-count-ref') so it never clashes with the
-- simpler seeded 'stock-check' or any draft a user has created on it. Idempotent:
-- re-running (or running on a DB that already has it) is a no-op.
-- ---------------------------------------------------------------------------
INSERT INTO process_definition (process_key, version, status, title, icon, json, published_at, published_by)
VALUES (
    'stock-count-ref', 1, 'ACTIVE', 'Stock Count (reference)', 'inventory',
    $json$
    {
      "processKey": "stock-count-ref",
      "version": 1,
      "status": "ACTIVE",
      "title": "Stock Count (reference)",
      "icon": "inventory",
      "dataSchema": [
        { "name": "locationScan", "type": "string" },
        { "name": "locationId",   "type": "location" },
        { "name": "locationCode", "type": "string" },
        { "name": "skuScan",      "type": "string" },
        { "name": "skuId",        "type": "sku" },
        { "name": "uom",          "type": "string" },
        { "name": "expectedQty",  "type": "number" },
        { "name": "qty",          "type": "number" },
        { "name": "prevCount",    "type": "number" },
        { "name": "match",        "type": "boolean" },
        { "name": "eventId",      "type": "string" }
      ],
      "start": "scanLocation",
      "steps": {
        "scanLocation": {
          "type": "screen", "screen": "textInput",
          "config": {
            "header": "Scan location",
            "detail": "Scan the bin location to count.",
            "scanBinding": true,
            "writeTo": "locationScan",
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
            "writeTo": "skuScan",
            "validation": { "required": true },
            "verify": {
              "kind": "skuScan",
              "write": { "id": "skuId", "uomCode": "uom" },
              "onNotFound": { "mode": "reprompt" }
            }
          },
          "next": "lookupExpected"
        },
        "lookupExpected": {
          "type": "task", "task": "inventory.lookup",
          "input": { "skuId": "skuId", "locationId": "locationId" },
          "output": { "expectedQty": "onHand" },
          "next": "enterQty"
        },
        "enterQty": {
          "type": "screen", "screen": "numberInput",
          "config": {
            "header": "Count {{skuScan}}",
            "detail": "Enter the counted quantity (expected {{expectedQty}} {{uom}}).",
            "writeTo": "qty",
            "validation": { "required": true, "min": 0, "integerOnly": true }
          },
          "next": "computeMatch"
        },
        "computeMatch": {
          "type": "compute",
          "set": [
            { "var": "match",     "expr": "qty == expectedQty or qty == prevCount" },
            { "var": "prevCount", "expr": "qty" }
          ],
          "next": "decideCount"
        },
        "decideCount": {
          "type": "decision",
          "transitions": [ { "when": "match == true", "to": "postCount" } ],
          "next": "enterQty"
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
          "next": "done"
        },
        "done": {
          "type": "screen", "screen": "acknowledge",
          "config": {
            "header": "Count recorded",
            "detail": "Counted {{qty}} of {{skuScan}} at {{locationCode}}.",
            "confirmLabel": "Done"
          }
        }
      }
    }
    $json$::jsonb,
    now(), 'system'
)
ON CONFLICT (process_key, version) DO NOTHING;
