CREATE TABLE customer (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  code        TEXT    NOT NULL UNIQUE,
  name        TEXT    NOT NULL,
  phone       TEXT,
  document    TEXT,
  notes       TEXT,
  search_text TEXT    NOT NULL,
  active      INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
  created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  version     INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_customer_document ON customer(document) WHERE document IS NOT NULL;
CREATE INDEX ix_customer_phone ON customer(phone);
CREATE INDEX ix_customer_active_name ON customer(active, name);

CREATE VIRTUAL TABLE customer_fts USING fts5(
  search_text, content='customer', content_rowid='id', tokenize='trigram');
CREATE TRIGGER customer_fts_ai AFTER INSERT ON customer BEGIN
  INSERT INTO customer_fts(rowid, search_text) VALUES (new.id, new.search_text);
END;
CREATE TRIGGER customer_fts_ad AFTER DELETE ON customer BEGIN
  INSERT INTO customer_fts(customer_fts, rowid, search_text) VALUES ('delete', old.id, old.search_text);
END;
CREATE TRIGGER customer_fts_au AFTER UPDATE OF search_text ON customer BEGIN
  INSERT INTO customer_fts(customer_fts, rowid, search_text) VALUES ('delete', old.id, old.search_text);
  INSERT INTO customer_fts(rowid, search_text) VALUES (new.id, new.search_text);
END;

CREATE TABLE customer_balance (
  customer_id   INTEGER PRIMARY KEY REFERENCES customer(id),
  balance_cents INTEGER NOT NULL DEFAULT 0 CHECK (balance_cents >= 0),
  version       INTEGER NOT NULL DEFAULT 0,
  last_entry_at TEXT,
  updated_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE TRIGGER customer_balance_init AFTER INSERT ON customer BEGIN
  INSERT INTO customer_balance(customer_id) VALUES (new.id);
END;

CREATE TABLE ledger_entry (
  id                  INTEGER PRIMARY KEY AUTOINCREMENT,
  customer_id         INTEGER NOT NULL REFERENCES customer(id),
  type                TEXT NOT NULL CHECK (type IN ('TOPUP','SPEND','REFUND','ADJUST','REVERSAL')),
  amount_cents        INTEGER NOT NULL CHECK (amount_cents <> 0),
  balance_after_cents INTEGER NOT NULL CHECK (balance_after_cents >= 0),
  idempotency_key     TEXT NOT NULL UNIQUE,
  reverses_entry_id   INTEGER UNIQUE REFERENCES ledger_entry(id),
  note                TEXT,
  import_job_id       TEXT,
  source              TEXT NOT NULL DEFAULT 'UI' CHECK (source IN ('UI','IMPORT','SYSTEM')),
  actor_login         TEXT NOT NULL,
  actor_name          TEXT NOT NULL,
  created_at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  CHECK ( (type = 'TOPUP' AND amount_cents > 0)
       OR (type IN ('SPEND','REFUND') AND amount_cents < 0)
       OR  type IN ('ADJUST','REVERSAL') ),
  CHECK ( type NOT IN ('ADJUST','REVERSAL','REFUND') OR length(trim(coalesce(note,''))) > 0 ),
  CHECK ( (type = 'REVERSAL') = (reverses_entry_id IS NOT NULL) )
);
CREATE INDEX ix_ledger_customer_date ON ledger_entry(customer_id, created_at DESC);
CREATE INDEX ix_ledger_date_type     ON ledger_entry(created_at, type);
CREATE INDEX ix_ledger_amount        ON ledger_entry(amount_cents);

CREATE TRIGGER ledger_no_update BEFORE UPDATE ON ledger_entry
BEGIN SELECT RAISE(ABORT, 'ledger_entry es append-only'); END;
CREATE TRIGGER ledger_no_delete BEFORE DELETE ON ledger_entry
BEGIN SELECT RAISE(ABORT, 'ledger_entry es append-only'); END;

CREATE TRIGGER ledger_reversal_rules BEFORE INSERT ON ledger_entry
WHEN new.reverses_entry_id IS NOT NULL
BEGIN
  SELECT RAISE(ABORT, 'no se puede anular una anulación')
   WHERE (SELECT type FROM ledger_entry WHERE id = new.reverses_entry_id) = 'REVERSAL';
  SELECT RAISE(ABORT, 'la anulación debe ser del mismo cliente')
   WHERE (SELECT customer_id FROM ledger_entry WHERE id = new.reverses_entry_id) <> new.customer_id;
END;

CREATE VIEW v_ledger_effective AS
SELECT e.*, CASE WHEN e.type = 'REVERSAL' THEN o.type ELSE e.type END AS effective_type
  FROM ledger_entry e LEFT JOIN ledger_entry o ON o.id = e.reverses_entry_id;

CREATE TABLE audit_log (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  action      TEXT NOT NULL,
  entity      TEXT NOT NULL,
  entity_id   TEXT,
  old_value   TEXT,
  new_value   TEXT,
  actor_login TEXT NOT NULL,
  actor_name  TEXT NOT NULL,
  host        TEXT
);
CREATE INDEX ix_audit_entity ON audit_log(entity, entity_id, at DESC);
CREATE INDEX ix_audit_at     ON audit_log(at DESC);
CREATE TRIGGER audit_no_update BEFORE UPDATE ON audit_log
BEGIN SELECT RAISE(ABORT, 'audit_log es append-only'); END;
CREATE TRIGGER audit_no_delete BEFORE DELETE ON audit_log
BEGIN SELECT RAISE(ABORT, 'audit_log es append-only'); END;

CREATE TABLE app_setting (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
