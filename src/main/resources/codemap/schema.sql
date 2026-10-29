-- codemap fact database. Natural keys only: class = fqn, method = 'fqn#name(ParamSimpleTypes)'.
-- Booleans are 0/1. Spring fact tables carry source = 'static' | 'runtime'.
-- Statements are separated by lines containing only ';;'.

-- ── scan metadata ────────────────────────────────────────────────
CREATE TABLE repos (
  repo         TEXT PRIMARY KEY,
  app          TEXT NOT NULL,
  path         TEXT,
  git_commit   TEXT,
  git_dirty    INTEGER,
  fingerprint  TEXT,
  scanned_at   TEXT,
  module_count INTEGER,
  class_count  INTEGER
);
;;
CREATE TABLE modules (
  module       TEXT PRIMARY KEY,
  repo         TEXT NOT NULL,
  group_id     TEXT,
  artifact_id  TEXT,
  version      TEXT,
  packaging    TEXT,
  parent       TEXT,
  path         TEXT,
  has_classes  INTEGER,
  is_service   INTEGER
);
;;
CREATE TABLE services (
  service       TEXT PRIMARY KEY,
  repo          TEXT NOT NULL,
  module        TEXT NOT NULL,
  main_class    TEXT,
  scan_packages TEXT
);
;;
CREATE TABLE service_modules (
  service TEXT NOT NULL,
  module  TEXT NOT NULL,
  depth   INTEGER NOT NULL,
  PRIMARY KEY (service, module)
);
;;
CREATE TABLE scan_warnings (
  repo    TEXT,
  module  TEXT,
  kind    TEXT NOT NULL,
  message TEXT NOT NULL
);
;;
-- ── maven ────────────────────────────────────────────────────────
CREATE TABLE maven_dependencies (
  module          TEXT NOT NULL,
  repo            TEXT NOT NULL,
  group_id        TEXT NOT NULL,
  artifact_id     TEXT NOT NULL,
  version         TEXT,
  scope           TEXT,
  optional        INTEGER,
  internal_module TEXT
);
;;
CREATE INDEX ix_maven_dependencies_internal ON maven_dependencies(internal_module);
;;
-- ── code structure ───────────────────────────────────────────────
CREATE TABLE classes (
  fqn          TEXT PRIMARY KEY,
  simple_name  TEXT NOT NULL,
  package      TEXT,
  repo         TEXT,
  module       TEXT,
  kind         TEXT,
  visibility   TEXT,
  is_abstract  INTEGER,
  superclass   TEXT,
  role         TEXT,
  role_reason  TEXT,
  source_file  TEXT,
  first_line   INTEGER,
  last_line    INTEGER,
  is_external  INTEGER NOT NULL DEFAULT 0
);
;;
CREATE INDEX ix_classes_simple_name ON classes(simple_name);
;;
CREATE INDEX ix_classes_module ON classes(module);
;;
CREATE TABLE type_hierarchy (
  class_fqn     TEXT NOT NULL,
  supertype_fqn TEXT NOT NULL,
  relation      TEXT NOT NULL,
  depth         INTEGER NOT NULL,
  PRIMARY KEY (class_fqn, supertype_fqn)
);
;;
CREATE INDEX ix_type_hierarchy_super ON type_hierarchy(supertype_fqn);
;;
CREATE TABLE annotations (
  target_kind    TEXT NOT NULL,
  target         TEXT NOT NULL,
  class_fqn      TEXT NOT NULL,
  annotation_fqn TEXT NOT NULL,
  annotation     TEXT NOT NULL,
  attributes     TEXT
);
;;
CREATE INDEX ix_annotations_target ON annotations(target);
;;
CREATE INDEX ix_annotations_fqn ON annotations(annotation_fqn);
;;
CREATE TABLE annotation_meta (
  annotation_fqn      TEXT NOT NULL,
  meta_annotation_fqn TEXT NOT NULL,
  depth               INTEGER NOT NULL,
  PRIMARY KEY (annotation_fqn, meta_annotation_fqn)
);
;;
CREATE TABLE fields (
  class_fqn TEXT NOT NULL,
  name      TEXT NOT NULL,
  type      TEXT,
  type_fqn  TEXT,
  is_static INTEGER,
  is_final  INTEGER,
  PRIMARY KEY (class_fqn, name)
);
;;
CREATE TABLE methods (
  method_id   TEXT PRIMARY KEY,
  class_fqn   TEXT NOT NULL,
  name        TEXT NOT NULL,
  signature   TEXT,
  return_type TEXT,
  visibility  TEXT,
  is_static   INTEGER,
  is_abstract INTEGER,
  first_line  INTEGER,
  last_line   INTEGER
);
;;
CREATE INDEX ix_methods_class ON methods(class_fqn);
;;
CREATE TABLE calls (
  caller_class  TEXT NOT NULL,
  caller_method TEXT NOT NULL,
  callee_class  TEXT NOT NULL,
  callee_method TEXT NOT NULL,
  line          INTEGER,
  invoke_kind   TEXT
);
;;
CREATE INDEX ix_calls_caller ON calls(caller_method);
;;
CREATE INDEX ix_calls_callee ON calls(callee_method);
;;
CREATE INDEX ix_calls_callee_class ON calls(callee_class);
;;
CREATE TABLE class_dependencies (
  from_class TEXT NOT NULL,
  to_class   TEXT NOT NULL,
  weight     INTEGER NOT NULL,
  PRIMARY KEY (from_class, to_class)
);
;;
-- ── spring ───────────────────────────────────────────────────────
CREATE TABLE beans (
  service        TEXT NOT NULL,
  bean_name      TEXT NOT NULL,
  class_fqn      TEXT,
  repo           TEXT,
  module         TEXT,
  role           TEXT,
  declared_by    TEXT,
  factory_method TEXT,
  scope          TEXT,
  is_primary     INTEGER,
  conditions     TEXT,
  source         TEXT NOT NULL DEFAULT 'static' CHECK (source IN ('static', 'runtime'))
);
;;
CREATE INDEX ix_beans_class ON beans(class_fqn);
;;
CREATE TABLE injections (
  service        TEXT NOT NULL,
  consumer_class TEXT NOT NULL,
  point          TEXT,
  member         TEXT,
  required_type  TEXT,
  qualifier      TEXT,
  resolved_class TEXT,
  candidates     INTEGER,
  source         TEXT NOT NULL DEFAULT 'static' CHECK (source IN ('static', 'runtime'))
);
;;
CREATE INDEX ix_injections_resolved ON injections(resolved_class);
;;
CREATE TABLE endpoints (
  service     TEXT NOT NULL,
  http_method TEXT,
  path        TEXT,
  path_key    TEXT,
  class_fqn   TEXT,
  method_id   TEXT,
  produces    TEXT,
  consumes    TEXT,
  path_expr   TEXT,
  source      TEXT NOT NULL DEFAULT 'static' CHECK (source IN ('static', 'runtime'))
);
;;
CREATE TABLE listeners (
  service          TEXT NOT NULL,
  kind             TEXT NOT NULL,
  destination      TEXT,
  destination_expr TEXT,
  group_id         TEXT,
  class_fqn        TEXT,
  method_id        TEXT,
  payload_type     TEXT,
  schedule         TEXT,
  source           TEXT NOT NULL DEFAULT 'static' CHECK (source IN ('static', 'runtime'))
);
;;
CREATE INDEX ix_listeners_destination ON listeners(destination);
;;
CREATE TABLE outbound (
  repo             TEXT,
  module           TEXT,
  kind             TEXT NOT NULL,
  class_fqn        TEXT,
  method_id        TEXT,
  line             INTEGER,
  http_method      TEXT,
  target           TEXT,
  target_expr      TEXT,
  path_key         TEXT,
  client_interface TEXT
);
;;
CREATE INDEX ix_outbound_target ON outbound(target);
;;
CREATE TABLE config_properties (
  repo    TEXT,
  module  TEXT,
  file    TEXT,
  profile TEXT NOT NULL DEFAULT '(default)',
  key     TEXT NOT NULL,
  value   TEXT,
  source  TEXT NOT NULL DEFAULT 'static' CHECK (source IN ('static', 'runtime'))
);
;;
CREATE INDEX ix_config_module_key ON config_properties(module, key);
;;
CREATE TABLE conditions (
  service          TEXT NOT NULL,
  condition_source TEXT NOT NULL,
  outcome          TEXT NOT NULL,
  message          TEXT,
  source           TEXT NOT NULL DEFAULT 'runtime' CHECK (source IN ('static', 'runtime'))
);
;;
-- ── persistence ──────────────────────────────────────────────────
CREATE TABLE entities (
  class_fqn   TEXT PRIMARY KEY,
  repo        TEXT,
  module      TEXT,
  table_name  TEXT,
  schema_name TEXT
);
;;
CREATE TABLE entity_relations (
  entity_fqn    TEXT NOT NULL,
  field         TEXT NOT NULL,
  relation      TEXT NOT NULL,
  target_entity TEXT,
  mapped_by     TEXT
);
;;
CREATE TABLE data_repositories (
  class_fqn      TEXT PRIMARY KEY,
  repo           TEXT,
  module         TEXT,
  entity_fqn     TEXT,
  id_type        TEXT,
  base_interface TEXT
);
;;
-- ── self-description ─────────────────────────────────────────────
CREATE TABLE schema_doc (
  object        TEXT NOT NULL,
  object_kind   TEXT NOT NULL,
  column        TEXT NOT NULL DEFAULT '',
  description   TEXT NOT NULL,
  example_query TEXT,
  PRIMARY KEY (object, column)
);
;;
-- ── views ────────────────────────────────────────────────────────
CREATE VIEW module_links AS
SELECT d.repo AS from_repo, d.module AS from_module,
       m.repo AS to_repo, m.module AS to_module,
       d.scope, (d.repo <> m.repo) AS cross_repo
FROM maven_dependencies d
JOIN modules m ON m.module = d.internal_module;
;;
CREATE VIEW beans_effective AS
SELECT * FROM beans b
WHERE b.source = 'runtime'
   OR NOT EXISTS (SELECT 1 FROM beans r WHERE r.service = b.service AND r.source = 'runtime');
;;
CREATE VIEW injections_effective AS
SELECT * FROM injections i
WHERE i.source = 'runtime'
   OR NOT EXISTS (SELECT 1 FROM injections r WHERE r.service = i.service AND r.source = 'runtime');
;;
CREATE VIEW endpoints_effective AS
SELECT * FROM endpoints e
WHERE e.source = 'runtime'
   OR NOT EXISTS (SELECT 1 FROM endpoints r WHERE r.service = e.service AND r.source = 'runtime');
;;
CREATE VIEW listeners_effective AS
SELECT * FROM listeners l
WHERE l.source = 'runtime'
   OR NOT EXISTS (SELECT 1 FROM listeners r WHERE r.service = l.service AND r.source = 'runtime');
;;
CREATE VIEW beans_compare AS
SELECT service, bean_name, MAX(class_fqn) AS class_fqn,
       CASE WHEN SUM(source = 'static') > 0 AND SUM(source = 'runtime') > 0 THEN 'both'
            WHEN SUM(source = 'static') > 0 THEN 'static_only'
            ELSE 'runtime_only' END AS presence
FROM beans
GROUP BY service, bean_name;
;;
CREATE VIEW endpoints_compare AS
SELECT service, http_method, path_key, MAX(path) AS path, MAX(method_id) AS method_id,
       CASE WHEN SUM(source = 'static') > 0 AND SUM(source = 'runtime') > 0 THEN 'both'
            WHEN SUM(source = 'static') > 0 THEN 'static_only'
            ELSE 'runtime_only' END AS presence
FROM endpoints
GROUP BY service, http_method, path_key;
;;
CREATE VIEW entry_points AS
SELECT service, 'http' AS kind, http_method || ' ' || path AS trigger,
       class_fqn, method_id, source
FROM endpoints_effective
UNION ALL
SELECT service, kind, COALESCE(destination, schedule, payload_type) AS trigger,
       class_fqn, method_id, source
FROM listeners_effective;
;;
-- Interface/abstract calls expand to every concrete override found via type_hierarchy;
-- abstract targets without a scanned implementation (e.g. HTTP client proxies) stay as they are.
CREATE VIEW calls_resolved AS
SELECT c.caller_class, c.caller_method, c.callee_method,
       c.callee_method AS target_method, 0 AS via_interface, c.line
FROM calls c
WHERE NOT EXISTS (SELECT 1 FROM methods a WHERE a.method_id = c.callee_method AND a.is_abstract = 1)
   OR NOT EXISTS (SELECT 1 FROM type_hierarchy h
                  JOIN methods m ON m.method_id = h.class_fqn || substr(c.callee_method, instr(c.callee_method, '#'))
                  WHERE h.supertype_fqn = c.callee_class AND m.is_abstract = 0)
UNION ALL
SELECT c.caller_class, c.caller_method, c.callee_method,
       m.method_id AS target_method, 1 AS via_interface, c.line
FROM calls c
JOIN type_hierarchy h ON h.supertype_fqn = c.callee_class
JOIN methods m ON m.method_id = h.class_fqn || substr(c.callee_method, instr(c.callee_method, '#'))
WHERE m.is_abstract = 0
  AND EXISTS (SELECT 1 FROM methods a WHERE a.method_id = c.callee_method AND a.is_abstract = 1);
;;
CREATE VIEW message_flows AS
SELECT COALESCE(o.target, l.destination) AS destination,
       COALESCE(o.kind, l.kind) AS kind,
       o.method_id AS producer_method, sm.service AS producer_service,
       l.method_id AS consumer_method, l.service AS consumer_service
FROM (SELECT * FROM outbound WHERE kind IN ('kafka', 'jms', 'rabbit')) o
LEFT JOIN service_modules sm ON sm.module = o.module
FULL OUTER JOIN (SELECT * FROM listeners_effective WHERE kind IN ('kafka', 'jms', 'rabbit')) l
  ON l.destination = o.target AND l.kind = o.kind;
;;
CREATE VIEW http_flows AS
SELECT o.module AS client_module, sm.service AS client_service,
       o.method_id AS client_method, o.http_method, o.target AS client_path,
       e.service AS server_service, e.method_id AS server_method, e.path AS server_path,
       CASE WHEN e.method_id IS NULL THEN 'none'
            WHEN o.http_method = e.http_method THEN 'path+method'
            ELSE 'path' END AS match_quality
FROM outbound o
LEFT JOIN service_modules sm ON sm.module = o.module
LEFT JOIN endpoints_effective e
  ON e.path_key = o.path_key AND (o.http_method IS NULL OR e.http_method IS NULL OR e.http_method = o.http_method)
WHERE o.kind IN ('http_exchange', 'feign', 'rest_client', 'web_client', 'rest_template');
;;
-- One row per module x profile x key: the profile's value, else the '(default)' value.
CREATE VIEW config_effective AS
WITH profs AS (
  SELECT DISTINCT module, profile FROM config_properties WHERE source = 'static'
),
candidates AS (
  SELECT c.repo, c.module, p.profile, c.key, c.value, c.file,
         (c.profile = p.profile) AS specific
  FROM config_properties c
  JOIN profs p ON p.module = c.module AND (c.profile = p.profile OR c.profile = '(default)')
  WHERE c.source = 'static'
),
ranked AS (
  SELECT *, ROW_NUMBER() OVER (PARTITION BY module, profile, key ORDER BY specific DESC, file) AS rn
  FROM candidates
)
SELECT repo, module, profile, key, value, file, 'static' AS source FROM ranked WHERE rn = 1
UNION ALL
SELECT repo, module, profile, key, value, file, source FROM config_properties WHERE source = 'runtime';
;;
CREATE VIEW class_summary AS
SELECT c.fqn, c.simple_name, c.repo, c.module, c.kind, c.role,
       (SELECT COUNT(*) FROM methods m WHERE m.class_fqn = c.fqn) AS methods,
       (SELECT COUNT(*) FROM calls k WHERE k.callee_class = c.fqn AND k.caller_class <> c.fqn) AS calls_in,
       (SELECT COUNT(*) FROM calls k WHERE k.caller_class = c.fqn AND k.callee_class <> c.fqn) AS calls_out,
       (SELECT COUNT(DISTINCT i.consumer_class) FROM injections i WHERE i.resolved_class = c.fqn) AS injected_into
FROM classes c
WHERE c.is_external = 0;
