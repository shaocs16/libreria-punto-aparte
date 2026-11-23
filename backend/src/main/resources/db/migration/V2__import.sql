CREATE TABLE import_job (
  id          TEXT PRIMARY KEY,
  kind        TEXT NOT NULL CHECK (kind IN ('CUSTOMERS','MOVEMENTS')),
  file_name   TEXT NOT NULL,
  file_sha256 TEXT NOT NULL,
  status      TEXT NOT NULL CHECK (status IN ('VALIDATED','EXECUTED','FAILED','DISCARDED')),
  total_rows  INTEGER, ok_rows INTEGER, error_rows INTEGER,
  actor_login TEXT NOT NULL,
  created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  executed_at TEXT
);
CREATE UNIQUE INDEX ux_import_executed ON import_job(kind, file_sha256) WHERE status = 'EXECUTED';

CREATE TABLE import_row (
  job_id  TEXT NOT NULL REFERENCES import_job(id) ON DELETE CASCADE,
  row_num INTEGER NOT NULL,
  raw     TEXT NOT NULL,
  error   TEXT,
  PRIMARY KEY (job_id, row_num)
);
