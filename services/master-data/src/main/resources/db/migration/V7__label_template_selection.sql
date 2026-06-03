-- Label-template selection inputs (ADR 0002): a shipping service can name its dispatch-label
-- template, and a warehouse a fallback default. Effective template = order override → service
-- → warehouse default (resolved by order-management at release). Codes reference label_template.
SET search_path TO master_data;

ALTER TABLE shipping_service ADD COLUMN label_template_code text;
ALTER TABLE warehouse_fulfillment_config ADD COLUMN default_label_template_code text;
