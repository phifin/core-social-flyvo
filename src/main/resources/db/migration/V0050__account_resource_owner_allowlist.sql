ALTER TABLE account.erasure_export ADD CONSTRAINT erasure_resource_owner_kind CHECK
 (operation<>'RESOURCE' OR (owner IS NOT NULL AND resource_kind IS NOT NULL AND
   ((owner='identity' AND resource_kind='MAIL_PROOF') OR
    (owner IN ('profile','content','media') AND resource_kind='MEDIA_ASSET'))));
