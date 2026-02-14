-- Allow multiple active keys per user (max enforced in application)
DROP INDEX IF EXISTS ux_vpn_keys_one_active_per_user;
