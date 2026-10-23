ALTER TABLE search.document ADD COLUMN erased_by_account boolean NOT NULL DEFAULT false;
ALTER TABLE search.document ADD CONSTRAINT search_erasure_shape CHECK
 (NOT erased_by_account OR (tombstone AND source_key='' AND title='' AND body=''
  AND key_normalized='' AND name_normalized=''));
CREATE FUNCTION search.guard_erasure_tombstone() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.erased_by_account AND NOT NEW.erased_by_account THEN
  RAISE EXCEPTION 'search erasure marker is one-way' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER search_erasure_guard BEFORE UPDATE ON search.document
 FOR EACH ROW EXECUTE FUNCTION search.guard_erasure_tombstone();
