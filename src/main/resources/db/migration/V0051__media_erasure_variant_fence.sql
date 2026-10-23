CREATE FUNCTION media.guard_variant_erasure() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE erasure uuid;
BEGIN
 SELECT erasure_request_id INTO erasure FROM media.asset WHERE id=NEW.asset_id FOR SHARE;
 IF erasure IS NOT NULL THEN RAISE EXCEPTION 'erasure-claimed asset cannot gain variants' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER variant_erasure_guard BEFORE INSERT ON media.variant
 FOR EACH ROW EXECUTE FUNCTION media.guard_variant_erasure();
