-- Complete the barcode-type catalog against the GS1 General Specifications (r26.0): each type's
-- length and check-digit rule. GTIN-bearing symbols (EAN-13, ITF-14) and the SSCC carry a single
-- mod-10 check digit (GS1 GenSpecs §7.9 "Check digit calculation"); GS1-128 validates a check digit
-- per Application Identifier; plain Code 128 and the 2D carriers have no GS1 structural check digit.
SET search_path TO master_data;

UPDATE barcode_type SET length_rule = '13',       check_digit_rule = 'MOD10'        WHERE name = 'EAN13';
UPDATE barcode_type SET length_rule = '14',       check_digit_rule = 'MOD10'        WHERE name = 'GTIN14';
UPDATE barcode_type SET length_rule = '18',       check_digit_rule = 'MOD10'        WHERE name = 'SSCC';
UPDATE barcode_type SET length_rule = 'VARIABLE', check_digit_rule = 'MOD10_PER_AI' WHERE name = 'GS1-128';
UPDATE barcode_type SET length_rule = 'VARIABLE', check_digit_rule = 'NONE'         WHERE name = 'CODE128';
UPDATE barcode_type SET length_rule = 'VARIABLE', check_digit_rule = 'NONE'         WHERE name = 'QR';
UPDATE barcode_type SET length_rule = 'VARIABLE', check_digit_rule = 'NONE'         WHERE name = 'DATAMATRIX';
