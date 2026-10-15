CREATE SCHEMA search;
CREATE TABLE search.document (
 source_kind varchar(16) NOT NULL CHECK (source_kind IN ('USER','COMMUNITY','POST')),
 source_id uuid NOT NULL, source_version bigint CHECK (source_version>=0), owner_id uuid, scope_id uuid,
 account_version bigint CHECK (account_version>=0), tombstone boolean NOT NULL,
 missing_source boolean NOT NULL,
 source_key varchar(100) NOT NULL, title varchar(200) NOT NULL, body text NOT NULL,
 key_normalized varchar(200) NOT NULL, name_normalized varchar(400) NOT NULL,
 search_vector tsvector GENERATED ALWAYS AS (
   setweight(to_tsvector('simple'::regconfig,source_key),'A') ||
   setweight(to_tsvector('simple'::regconfig,title),'A') ||
   setweight(to_tsvector('simple'::regconfig,body),'B')) STORED,
 updated_at timestamptz NOT NULL,
 PRIMARY KEY(source_kind,source_id),
 CHECK (missing_source OR source_version IS NOT NULL),
 CHECK (source_kind='COMMUNITY' OR missing_source OR (owner_id IS NOT NULL AND account_version IS NOT NULL)),
 CHECK (NOT missing_source OR tombstone),
 CHECK (NOT tombstone OR (source_key='' AND title='' AND body='' AND key_normalized='' AND name_normalized='')),
 CHECK (octet_length(body)<=65536)
);
CREATE INDEX document_fts ON search.document USING gin(search_vector) WHERE NOT tombstone;
CREATE INDEX document_key_prefix ON search.document(source_kind,key_normalized text_pattern_ops) WHERE NOT tombstone;
CREATE INDEX document_name_prefix ON search.document(source_kind,name_normalized text_pattern_ops) WHERE NOT tombstone;
CREATE INDEX document_owner ON search.document(owner_id,source_kind,source_id) WHERE owner_id IS NOT NULL;
CREATE INDEX document_scope ON search.document(source_kind,scope_id) WHERE NOT tombstone AND scope_id IS NOT NULL;
CREATE TABLE search.rebuild (
 id uuid PRIMARY KEY, requested_by uuid NOT NULL REFERENCES account.user_account(id) ON DELETE RESTRICT,
 source_kind varchar(16) NOT NULL CHECK (source_kind IN ('USER','COMMUNITY','POST')),
 stage varchar(16) NOT NULL CHECK (stage IN ('EXPORT','RECONCILE','DONE')),
 after_id uuid, version bigint NOT NULL DEFAULT 0 CHECK (version>=0),
 examined bigint NOT NULL DEFAULT 0 CHECK (examined>=0), changed bigint NOT NULL DEFAULT 0 CHECK (changed>=0),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, completed_at timestamptz,
 CHECK ((stage='DONE')=(completed_at IS NOT NULL))
);
CREATE UNIQUE INDEX rebuild_active_kind ON search.rebuild(source_kind) WHERE stage<>'DONE';
