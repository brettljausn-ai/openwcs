-- Per-user UI language preference (frontend only): en|de|fr|es|zh. Default English.
-- Backend output, logs and the login screen stay English regardless.
ALTER TABLE app_user ADD COLUMN language varchar(8) NOT NULL DEFAULT 'en';
