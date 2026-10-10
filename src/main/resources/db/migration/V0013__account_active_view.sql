CREATE SCHEMA account_api;
CREATE VIEW account_api.active_users_v1 AS SELECT id AS user_id FROM account.user_account WHERE status='ACTIVE';
