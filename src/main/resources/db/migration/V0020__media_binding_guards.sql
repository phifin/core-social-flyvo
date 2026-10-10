CREATE FUNCTION media.check_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a media.asset%ROWTYPE;
BEGIN
 SELECT * INTO a FROM media.asset WHERE id=NEW.asset_id FOR UPDATE;
 IF a.state<>'READY' OR a.purpose<>NEW.context_type THEN
   RAISE EXCEPTION 'media is not bindable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER binding_guard BEFORE INSERT ON media.binding
 FOR EACH ROW EXECUTE FUNCTION media.check_binding();
ALTER TABLE media.asset ADD CONSTRAINT asset_pinned_version CHECK
 (uploaded_version_id IS NULL OR (length(uploaded_version_id)>0 AND uploaded_version_id<>'null'));
ALTER TABLE media.asset ADD CONSTRAINT asset_checksum CHECK (sha256 ~ '^[A-Za-z0-9+/]{43}=$');
