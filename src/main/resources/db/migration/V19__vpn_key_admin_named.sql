-- Ключи, создаваемые админом вручную из админ-панели (standalone):
--   name             — человекочитаемое имя ключа (отображается только админу);
--   created_by_admin — маркер: ключ не считается в лимите пользователя, не показывается
--                      в «Мои ключи», без авто-напоминаний и авто-очистки; продлевается вручную по id.
ALTER TABLE vpn_keys ADD COLUMN name TEXT;
ALTER TABLE vpn_keys ADD COLUMN created_by_admin BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_vpn_keys_created_by_admin ON vpn_keys (created_by_admin);
